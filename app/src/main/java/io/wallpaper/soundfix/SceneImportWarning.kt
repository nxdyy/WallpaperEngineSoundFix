package io.wallpaper.soundfix

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * mpkg 场景壁纸导入校验。
 *
 * 触发时机（两者最终都会调用 WallpaperInfoSparse.setFilePath）：
 * - 从电脑传输完成：BrowseActivity 在 bytesRemaining == 0 时写入 filePath
 * - 本地导入完成：文件复制到 downloads/ 后写入 filePath
 *
 * 校验流程：
 * 1) 文件扩展名必须是 .mpkg 且文件已完整落盘（progress == 100）
 * 2) 判定为场景类型（Scene）
 * 3) 分析并缓存创意工坊 ID
 * 4) 弹出声音风险提示
 */
internal object SceneImportWarning {

    private const val TAG = "WESoundFix"
    private const val OPEN_URL = "https://project.nxdyy.cn/WallpaperEngineSoundFix/"
    private const val PROGRESS_DONE = 100

    /** setFilePath 之后等待进度/文件落盘更新 */
    private const val CHECK_DELAY_MS = 250L

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WESoundFix-MpkgCheck").apply { isDaemon = true }
    }
    private val checked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** WallpaperInfoSparse.setFilePath 回调（主线程）。 */
    fun onFilePathSet(item: Any?, path: String?) {
        if (item == null || path.isNullOrBlank()) return
        if (!path.endsWith(".mpkg", ignoreCase = true)) return

        // 电脑端已经做过声音修复的包带 WEP_ 前缀，不再提示可能的声音问题
        if (path.substringAfterLast('/').startsWith("WEP_", ignoreCase = true)) return

        // 传输完成时先 setFilePath 再 setProgress(100)；刚上传排队时 progress 为 0，
        // 稍等片刻再检查可避免把“等待下载”的条目误判为完成。
        handler.postDelayed({
            try {
                if (callInt(item, "getProgress") < PROGRESS_DONE) return@postDelayed
                submit(path)
            } catch (t: Throwable) {
                log("check mpkg import failed", t)
            }
        }, CHECK_DELAY_MS)
    }

    private fun submit(path: String) {
        val ctx = appContext() ?: return
        val file = resolveFile(ctx, path)
        if (file == null || !file.isFile || file.length() <= 0L) return
        if (!checked.add(path)) return

        executor.execute {
            try {
                if (!isScene(path)) return@execute
                val workshopId = WallpaperTypeBadge.workshopId(path)
                log("mpkg scene detected: $path, workshopId=${workshopId ?: "unknown"}")
                handler.post { showWarning() }
            } catch (t: Throwable) {
                log("analyze mpkg failed", t)
            }
        }
    }

    /** 是否为场景类型；原生不可用时 .mpkg 按场景处理。 */
    private fun isScene(path: String): Boolean {
        val native = WallpaperTypeBadge.nativeWallpaperType(path)
        if (native != null) return native.equals("Scene", ignoreCase = true)
        return path.endsWith(".mpkg", ignoreCase = true)
    }

    // ---------------------------------------------------------------- 弹窗

    private fun showWarning() {
        val activity = currentActivity() ?: return
        try {
            AlertDialog.Builder(activity)
                .setTitle("可能的声音问题")
                .setMessage("你正在导入一张mpkg格式的场景壁纸，" +
                    "如果你的壁纸没有经过特殊处理可能会无法播放声音！")
                .setNegativeButton("我已处理") { dialog, _ -> dialog.dismiss() }
                .setPositiveButton("我有原始pkg格式的电脑版壁纸") { dialog, _ ->
                    dialog.dismiss()
                    showOpenDialog(activity)
                }
                .show()
        } catch (t: Throwable) {
            log("show mpkg warning failed", t)
        }
    }

    private fun showOpenDialog(activity: Activity) {
        try {
            val dialog = AlertDialog.Builder(activity)
                .setTitle("请选择打开方式")
                .setMessage("即将打开网页版壁纸转换工具")
                .setNegativeButton("关闭", null)
                .setNeutralButton("直接打开", null)
                .setPositiveButton("复制链接", null)
                .create()

            // 后两个按钮点击后不关闭弹窗
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setOnClickListener { dialog.dismiss() }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener { openInBrowser(activity) }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener { copyLink(activity) }
            }
            dialog.show()
        } catch (t: Throwable) {
            log("show open dialog failed", t)
        }
    }

    private fun openInBrowser(activity: Activity) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OPEN_URL)))
        } catch (t: Throwable) {
            log("open url failed", t)
            toast(activity, "无法打开链接")
        }
    }

    private fun copyLink(activity: Activity) {
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("url", OPEN_URL))
            toast(activity, "复制成功")
        } catch (t: Throwable) {
            log("copy url failed", t)
        }
    }

    private fun toast(activity: Activity, text: String) {
        try {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun currentActivity(): Activity? {
        DownloadProgressUi.currentActivity()?.let { activity ->
            if (!activity.isFinishing) return activity
        }
        // 兜底：从 ActivityThread 的活动记录中取最后一个
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null) ?: return null
            val field = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val map = field.get(at) as? Map<*, *> ?: return null
            var found: Activity? = null
            for (value in map.values) {
                val record = value ?: continue
                val af = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                val activity = af.get(record) as? Activity
                if (activity != null && !activity.isFinishing) found = activity
            }
            found
        } catch (_: Throwable) {
            null
        }
    }

    private fun appContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }

    private fun resolveFile(ctx: Context, path: String): File? {
        if (path.startsWith("content://")) return null
        val p = if (path.startsWith("file://")) path.removePrefix("file://") else path
        if (p.isBlank()) return null
        val f = File(p)
        return if (f.isAbsolute) f else File(ctx.filesDir, p)
    }

    private fun callInt(target: Any, method: String): Int = try {
        (target.javaClass.getMethod(method).invoke(target) as? Number)?.toInt() ?: 0
    } catch (_: Throwable) {
        0
    }

    private fun log(message: String, t: Throwable? = null) {
        Log.w(TAG, message, t)
    }
}
