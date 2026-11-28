package io.wallpaper.soundfix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * 将壁纸引擎 BrowseActivity 顶部的连接状态条（“已连接到 …”）变为下载进度显示。
 *
 * 数据来源：TransferService.updateNotification(DownloadInfo)，该方法在主线程被调用，
 * 每个传输事件（transmissionStart / transmissionContinue / transmissionCancel）都会经过。
 * 事件字段：
 *  - transmissionStart:    size = 壁纸总字节数，title = 壁纸名称，file = 文件名
 *  - transmissionContinue: bytesRemaining = 该文件剩余字节数，file = 文件名
 *
 * 下载期间状态条文本替换为四行进度信息，同时状态条背景改为按下载比例填充的进度条。
 */
internal object DownloadProgressUi {

    private const val TAG = "WESoundFix"
    private const val RESTORE_DELAY_MS = 1200L
    private const val RENDER_INTERVAL_MS = 100L

    private val handler = Handler(Looper.getMainLooper())

    private var activityRef: WeakReference<Any>? = null
    private var statusViewRef: WeakReference<TextView>? = null

    private var connectedBg = Color.parseColor("#00AA00")
    private var connectedFg = Color.WHITE

    private var progressDrawable: ProgressDrawable? = null

    /** 下载状态 */
    private var active = false
    private var computerName = ""
    private var fileName = ""
    private var wallpaperTitle = ""
    private var totalBytes = 0L
    private var downloadedBytes = 0L
    private var speedBytesPerSec = 0.0
    private var lastSampleTime = 0L
    private var lastSampleBytes = 0L
    private var restorePending = false

    /** file -> 总字节数 / 剩余字节数（多文件并发时区分） */
    private val totals = HashMap<String, Long>()
    private val remaining = HashMap<String, Long>()
    private val titles = HashMap<String, String>()

    private var renderScheduled = false
    private var lastRenderTime = 0L

    /** 当前绑定的 BrowseActivity（供其他模块复用，如弹窗）。 */
    fun currentActivity(): android.app.Activity? =
        activityRef?.get() as? android.app.Activity

    /** 绑定当前 BrowseActivity（onCreate 之后视图已就绪）。 */
    fun attach(activity: Any?) {
        if (activity == null) return
        activityRef = WeakReference(activity)
        val ctx = activity as? Context ?: return
        try {
            connectedBg = resolveColor(ctx, "connection_status_connected_bg", connectedBg)
            connectedFg = resolveColor(ctx, "connection_status_connected_fg", connectedFg)

            val tv = findStatusView(activity, ctx) ?: return
            statusViewRef = WeakReference(tv)
            if (active) renderNow()
        } catch (t: Throwable) {
            log("attach failed", t)
        }
    }

    /**
     * 原应用刷新连接状态后会重置文本和背景。
     * 在 showConnectionInfo 执行完之后重新覆盖为进度显示。
     */
    fun onConnectionInfoShown(activity: Any?) {
        attach(activity)
        if (active) renderNow()
    }

    /** 处理一次传输事件（主线程）。 */
    fun onDownloadInfo(info: Any?) {
        if (info == null) return
        try {
            val command = invokeString(info, "getCommand")
            val file = invokeString(info, "getFile")
            when (command) {
                "transmissionStart" -> onStart(info, file)
                "transmissionContinue" -> onContinue(info, file)
                "transmissionCancel" -> onCancel(info, file)
            }
        } catch (t: Throwable) {
            log("handle download info failed", t)
        }
    }

    /**
     * 电脑端可能以空标题、"Unknown wallpaper" 或 "未知" 发送任务，
     * 此时退回文件名（去掉扩展名）作为标题。
     */
    private fun resolveTitle(rawTitle: String, file: String): String {
        val title = rawTitle.trim()
        val unknown = title.isEmpty() ||
            title.equals("Unknown wallpaper", ignoreCase = true) ||
            title.equals("unknown", ignoreCase = true) ||
            title == "未知"
        return if (unknown) {
            file.substringAfterLast('/').substringBeforeLast('.')
        } else {
            title
        }
    }

    private fun onStart(info: Any, file: String) {
        val size = invokeLong(info, "getSize")
        val title = resolveTitle(invokeString(info, "getTitle"), file)
        totals[file] = size
        remaining[file] = size
        if (title.isNotBlank()) titles[file] = title

        fileName = file.substringAfterLast('/')
        wallpaperTitle = title.ifBlank { titles[file] ?: "" }
        totalBytes = size
        downloadedBytes = 0L
        speedBytesPerSec = 0.0
        lastSampleTime = 0L
        lastSampleBytes = 0L
        active = true
        restorePending = false
        updateComputerName()
        renderNow()
    }

    private fun onContinue(info: Any, file: String) {
        val bytesRemaining = invokeLong(info, "getBytesRemaining")
        remaining[file] = bytesRemaining

        // 未收到 transmissionStart 时无法得知总大小，退化为按已接收量估算
        val total = totals[file] ?: (bytesRemaining + downloadedBytes).coerceAtLeast(bytesRemaining)
        totalBytes = total
        fileName = file.substringAfterLast('/')
        wallpaperTitle = titles[file] ?: wallpaperTitle
        downloadedBytes = (total - bytesRemaining).coerceIn(0L, total)

        updateSpeed(downloadedBytes)
        updateComputerName()
        active = true
        restorePending = false

        if (bytesRemaining <= 0L) {
            renderNow()
            remaining.remove(file)
            scheduleRestore()
        } else {
            requestRender()
        }
    }

    private fun onCancel(info: Any, file: String) {
        if (invokeLong(info, "getSize") != 0L) return
        remaining.remove(file)
        totals.remove(file)
        titles.remove(file)
        if (remaining.values.none { it > 0L }) {
            renderNow()
            scheduleRestore()
        }
    }

    /** 速率估算：指数平滑，避免单块抖动。 */
    private fun updateSpeed(downloaded: Long) {
        val now = SystemClock.elapsedRealtime()
        if (lastSampleTime == 0L) {
            lastSampleTime = now
            lastSampleBytes = downloaded
            return
        }
        val dtSec = (now - lastSampleTime) / 1000.0
        if (dtSec < 0.2) return
        val instant = (downloaded - lastSampleBytes) / dtSec
        if (instant >= 0) {
            speedBytesPerSec = if (speedBytesPerSec <= 0.0) instant
            else speedBytesPerSec * 0.7 + instant * 0.3
        }
        lastSampleTime = now
        lastSampleBytes = downloaded
    }

    private fun requestRender() {
        if (renderScheduled) return
        val now = SystemClock.elapsedRealtime()
        val delay = (lastRenderTime + RENDER_INTERVAL_MS - now).coerceAtLeast(0L)
        renderScheduled = true
        handler.postDelayed({
            renderScheduled = false
            renderNow()
        }, delay)
    }

    private fun renderNow() {
        if (!active) return
        val tv = statusViewRef?.get() ?: return
        lastRenderTime = SystemClock.elapsedRealtime()

        val totalKb = (totalBytes / 1024).coerceAtLeast(0L)
        val downloadedKb = (downloadedBytes / 1024).coerceIn(0L, totalKb.coerceAtLeast(0L))
        val percent = if (totalBytes > 0L) (downloadedBytes * 100 / totalBytes).coerceIn(0L, 100L) else 0L
        val speedKb = (speedBytesPerSec / 1024.0).coerceAtLeast(0.0).toLong()

        val name = computerName.ifBlank { "PC" }
        val displayName = fileName.ifBlank { "-" }

        tv.text = buildString {
            append("正在从")
            append(name)
            append("下载")
            append(displayName)
            append("\n标题：")
            append(wallpaperTitle)
            append("\n进度：")
            append(downloadedKb)
            append("kb/")
            append(totalKb)
            append("kb")
            append("\n")
            append(percent)
            append("% | ")
            append(speedKb)
            append("kb/s")
        }
        tv.setTextColor(connectedFg)

        val trackColor = Color.argb(
            0x66, Color.red(connectedBg), Color.green(connectedBg), Color.blue(connectedBg))
        val drawable = progressDrawable
            ?: ProgressDrawable(trackColor, connectedBg).also { progressDrawable = it }
        drawable.fraction = if (totalBytes > 0L) downloadedBytes.toFloat() / totalBytes else 0f
        if (tv.background !== drawable) tv.background = drawable
    }

    private fun scheduleRestore() {
        if (restorePending) return
        restorePending = true
        handler.postDelayed({
            restorePending = false
            if (remaining.values.any { it > 0L }) return@postDelayed
            restoreConnectionInfo()
        }, RESTORE_DELAY_MS)
    }

    /** 恢复原应用的“已连接到 …”状态条。 */
    private fun restoreConnectionInfo() {
        active = false
        val activity = activityRef?.get()
        if (activity != null) {
            try {
                activity.javaClass.getDeclaredMethod("showConnectionInfo").apply {
                    isAccessible = true
                }.invoke(activity)
                return
            } catch (_: Throwable) {
                // 回退到手动恢复
            }
        }
        val tv = statusViewRef?.get() ?: return
        tv.text = if (computerName.isBlank()) "已连接" else "已连接到 $computerName"
        tv.setTextColor(connectedFg)
        tv.setBackgroundColor(connectedBg)
    }

    private fun updateComputerName() {
        val activity = activityRef?.get() ?: return
        var clazz: Class<*>? = activity.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField("connectedComputerName").apply { isAccessible = true }
                val value = f.get(activity) as? String
                if (!value.isNullOrBlank()) {
                    computerName = value
                    return
                }
            } catch (_: NoSuchFieldException) {
            }
            clazz = clazz.superclass
        }
    }

    private fun findStatusView(activity: Any, ctx: Context): TextView? {
        // 优先使用 BrowseActivity.connectionStatus 字段
        var clazz: Class<*>? = activity.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField("connectionStatus").apply { isAccessible = true }
                (field.get(activity) as? TextView)?.let { return it }
            } catch (_: NoSuchFieldException) {
            }
            clazz = clazz.superclass
        }
        // 回退到资源 id tvConnectionStatus
        val id = ctx.resources.getIdentifier("tvConnectionStatus", "id", ctx.packageName)
        if (id == 0) return null
        return activity.javaClass.getMethod("findViewById", Int::class.javaPrimitiveType)
            .invoke(activity, id) as? TextView
    }

    private fun resolveColor(ctx: Context, name: String, fallback: Int): Int {
        val id = ctx.resources.getIdentifier(name, "color", ctx.packageName)
        return if (id != 0) ctx.getColor(id) else fallback
    }

    private fun invokeString(target: Any, method: String): String =
        try {
            target.javaClass.getMethod(method).invoke(target) as? String ?: ""
        } catch (_: Throwable) {
            ""
        }

    private fun invokeLong(target: Any, method: String): Long =
        try {
            (target.javaClass.getMethod(method).invoke(target) as? Number)?.toLong() ?: 0L
        } catch (_: Throwable) {
            0L
        }

    private fun log(message: String, t: Throwable? = null) {
        Log.w(TAG, message, t)
    }

    /** 背景进度条：暗色轨道 + 按比例填充的绿色。 */
    private class ProgressDrawable(
        private val trackColor: Int,
        private val fillColor: Int
    ) : Drawable() {

        private val paint = Paint()

        var fraction: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            paint.color = trackColor
            canvas.drawRect(b, paint)
            if (fraction > 0f) {
                paint.color = fillColor
                canvas.drawRect(
                    b.left.toFloat(), b.top.toFloat(),
                    b.left + b.width() * fraction, b.bottom.toFloat(), paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
