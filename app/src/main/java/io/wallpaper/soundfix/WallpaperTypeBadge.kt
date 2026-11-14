package io.wallpaper.soundfix

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 壁纸类型角标：
 * - 在壁纸封面左上角显示类型（视频 / 场景 / 图片 / 网页）
 * - 传输完成后自动判定类型并写入缓存
 * - 打开软件时扫描列表中尚未判定类型的壁纸，后台逐个判定
 *
 * 判定优先级：
 * 1) content:// 文件用 ContentResolver MIME
 * 2) 应用自带的 SceneLib.getWallpaperType(path)（返回 "Scene"/"Video"）
 * 3) 文件扩展名
 * 4) 文件头魔数（mp4 的 ftyp / zip 的 PK / JPEG / PNG）
 *
 * 结果按 filePath 写入 SharedPreferences，避免重复判定。
 */
internal object WallpaperTypeBadge {

    private const val TAG = "WESoundFix"
    private const val PREFS_NAME = "we_soundfix_wallpaper_types"
    private const val WORKSHOP_PREFS_NAME = "we_soundfix_workshop_ids"
    private const val BADGE_TAG = "we_soundfix_type_badge"

    private const val TYPE_VIDEO = "video"
    private const val TYPE_SCENE = "scene"
    private const val TYPE_IMAGE = "image"
    private const val TYPE_WEB = "web"

    /** 与壁纸进度一致：达到 100 才视为已完成下载，可判定类型 */
    private const val PROGRESS_DONE = 100

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WESoundFix-TypeDetect").apply { isDaemon = true }
    }
    private val pending = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var adapterRef: WeakReference<Any>? = null
    private var sceneLibRef: WeakReference<Any>? = null
    private val badgePaths = WeakHashMap<TextView, String>()

    /** 适配器创建：保存 SceneLib，并在后台扫描所有尚未判定类型的壁纸。 */
    fun onAdapterCreated(adapter: Any?, sceneLib: Any?, data: Any?) {
        if (adapter == null) return
        adapterRef = WeakReference(adapter)
        if (sceneLib != null) sceneLibRef = WeakReference(sceneLib)
        val ctx = appContext() ?: return
        val list = data as? List<*> ?: return
        // 主线程做快照：该列表随后会被主线程排序/增删，不能在后台直接遍历
        val snapshot = try {
            ArrayList(list)
        } catch (t: Throwable) {
            log("snapshot wallpaper list failed", t)
            return
        }
        executor.execute {
            try {
                var detectedAny = false
                for (item in snapshot) {
                    if (item == null) continue
                    try {
                        if (callInt(item, "getProgress") < PROGRESS_DONE) continue
                        val path = callString(item, "getFilePath").orEmpty()
                        if (path.isBlank()) continue
                        if (readCache(ctx, path) != null) continue
                        if (detectAndCache(ctx, path, notifyAdapter = false)) detectedAny = true
                    } catch (t: Throwable) {
                        log("scan wallpaper type failed", t)
                    }
                }
                // 扫描完成后刷新一次列表，让角标立即显示
                if (detectedAny) notifyAdapter()
            } catch (t: Throwable) {
                // 后台线程未捕获异常会导致整个目标进程崩溃，这里统一兜底
                log("scan wallpaper types failed", t)
            }
        }
    }

    /** 列表项绑定：在封面左上角显示类型角标。 */
    fun onBind(adapter: Any?, holder: Any?, position: Int) {
        val itemView = getItemView(holder) ?: return
        val item = getItem(adapter, position)
        if (item == null) {
            hideBadge(itemView)
            return
        }
        val path = callString(item, "getFilePath").orEmpty()
        val progress = callInt(item, "getProgress")
        val badge = ensureBadge(itemView) ?: return
        if (path.isBlank() || progress < PROGRESS_DONE) {
            badgePaths.remove(badge)
            badge.visibility = View.GONE
            return
        }

        val ctx = itemView.context.applicationContext ?: return
        val cached = readCache(ctx, path)
        if (cached != null) {
            showBadge(badge, path, cached)
            return
        }

        badge.visibility = View.GONE
        requestDetect(ctx, path, notifyAdapter = true) { type ->
            if (badgePaths[badge] == path) showBadge(badge, path, type)
        }
    }

    /** 传输事件：文件接收完成时立即判定并缓存类型。 */
    fun onTransferInfo(info: Any?) {
        if (info == null) return
        try {
            if (callString(info, "getCommand") != "transmissionContinue") return
            if (callLong(info, "getBytesRemaining") != 0L) return
            val file = callString(info, "getFile").orEmpty()
            if (file.isBlank()) return
            val ctx = appContext() ?: return
            requestDetect(ctx, "downloads/$file", notifyAdapter = true, callback = null)
        } catch (t: Throwable) {
            log("transfer type detect failed", t)
        }
    }

    // ---------------------------------------------------------------- 判定

    private fun requestDetect(
        ctx: Context,
        path: String,
        notifyAdapter: Boolean,
        callback: ((String) -> Unit)?
    ) {
        readCache(ctx, path)?.let { cached ->
            if (callback != null) handler.post { callback(cached) }
            return
        }
        if (!pending.add(path)) return
        executor.execute {
            try {
                val type = detect(ctx, path)
                if (type != null) {
                    writeCache(ctx, path, type)
                    if (notifyAdapter) notifyAdapter()
                    if (callback != null) handler.post { callback(type) }
                }
            } catch (t: Throwable) {
                log("detect wallpaper type failed: $path", t)
            } finally {
                pending.remove(path)
            }
        }
    }

    private fun detectAndCache(ctx: Context, path: String, notifyAdapter: Boolean): Boolean {
        val type = detect(ctx, path) ?: return false
        writeCache(ctx, path, type)
        if (notifyAdapter) notifyAdapter()
        return true
    }

    private fun detect(ctx: Context, path: String): String? {
        if (path.startsWith("content://")) {
            val mime = try {
                ctx.contentResolver.getType(Uri.parse(path))
            } catch (_: Throwable) {
                null
            }
            return when {
                mime == null -> null
                mime.startsWith("video/") -> TYPE_VIDEO
                mime.startsWith("image/") -> TYPE_IMAGE
                else -> null
            }
        }

        // 应用自带的原生判定（与播放器选择渲染器的逻辑一致）
        when (nativeType(path)?.lowercase()) {
            "scene" -> return TYPE_SCENE
            "video" -> return TYPE_VIDEO
        }

        val file = resolveFile(ctx, path)
        val name = (file?.name ?: path).lowercase()
        when (name.substringAfterLast('.', "")) {
            "mp4", "webm", "mkv", "avi", "mov", "m4v", "3gp", "ts" -> return TYPE_VIDEO
            "mpkg", "pkg" -> return TYPE_SCENE
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> return TYPE_IMAGE
            "html", "htm" -> return TYPE_WEB
        }

        // 文件头兜底
        if (file != null && file.isFile) {
            try {
                val header = ByteArray(12)
                FileInputStream(file).use { it.read(header) }
                if (header.size >= 8 && header[4] == 'f'.code.toByte() &&
                    header[5] == 't'.code.toByte() && header[6] == 'y'.code.toByte() &&
                    header[7] == 'p'.code.toByte()) {
                    return TYPE_VIDEO
                }
                if (header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
                    return TYPE_SCENE
                }
                if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) return TYPE_IMAGE
                if (header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte()) return TYPE_IMAGE
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun nativeType(path: String): String? = try {
        val lib = sceneLibRef?.get() ?: return null
        lib.javaClass.getMethod("getWallpaperType", String::class.java)
            .invoke(lib, path) as? String
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------ 供 mpkg 导入校验使用

    /** 应用原生判定类型，返回 "Scene"/"Video"/null。 */
    fun nativeWallpaperType(path: String): String? = nativeType(path)

    /**
     * 分析创意工坊 ID 并缓存：
     * 1) project.json 的 workshopid
     * 2) project.json 的 workshopurl 中解析
     * 3) 纯数字文件名（PC 传输时以工坊 ID 命名）
     */
    fun workshopId(path: String): String? {
        if (path.isBlank()) return null
        val ctx = appContext() ?: return null
        readWorkshopCache(ctx, path)?.let { return it }

        var id: String? = null
        val lib = sceneLibRef?.get()
        if (lib != null) {
            id = extractWorkshopId(projectString(lib, path, "workshopid"))
                ?: extractWorkshopId(projectString(lib, path, "workshopurl"))
        }
        if (id == null) {
            val base = path.substringAfterLast('/').substringBeforeLast('.')
            if (base.length in 5..20 && base.all { it.isDigit() }) id = base
        }
        if (id != null) writeWorkshopCache(ctx, path, id)
        return id
    }

    private fun projectString(lib: Any, path: String, key: String): String? = try {
        lib.javaClass.getMethod(
            "getWallpaperProjectString", String::class.java, String::class.java)
            .invoke(lib, path, key) as? String
    } catch (_: Throwable) {
        null
    }

    private fun extractWorkshopId(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.length in 5..20 && value.all { it.isDigit() }) return value

        val normalized = value.replace('\\', '/').replace("id=", "/")
        val candidate = normalized.split('/').lastOrNull { it.isNotBlank() }?.trim() ?: return null
        return if (candidate.length in 5..20 && candidate.all { it.isDigit() }) candidate else null
    }

    private fun readWorkshopCache(ctx: Context, path: String): String? = try {
        ctx.getSharedPreferences(WORKSHOP_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(path, null)
    } catch (_: Throwable) {
        null
    }

    private fun writeWorkshopCache(ctx: Context, path: String, id: String) {
        try {
            ctx.getSharedPreferences(WORKSHOP_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(path, id).apply()
        } catch (t: Throwable) {
            log("write workshop cache failed", t)
        }
    }

    private fun resolveFile(ctx: Context, path: String): File? {
        val p = if (path.startsWith("file://")) path.removePrefix("file://") else path
        if (p.isBlank()) return null
        val f = File(p)
        return if (f.isAbsolute) f else File(ctx.filesDir, p)
    }

    // ---------------------------------------------------------------- 缓存

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readCache(ctx: Context, path: String): String? =
        try {
            prefs(ctx).getString(path, null)
        } catch (_: Throwable) {
            null
        }

    private fun writeCache(ctx: Context, path: String, type: String) {
        try {
            prefs(ctx).edit().putString(path, type).apply()
        } catch (t: Throwable) {
            log("write type cache failed", t)
        }
    }

    // ---------------------------------------------------------------- 角标 UI

    private fun getItemView(holder: Any?): ViewGroup? = try {
        val field = holder?.javaClass?.getField("itemView")
        field?.get(holder) as? ViewGroup
    } catch (_: Throwable) {
        null
    }

    private fun getItem(adapter: Any?, position: Int): Any? = try {
        val m = adapter?.javaClass?.getDeclaredMethod("getItem", Int::class.javaPrimitiveType)
        m?.isAccessible = true
        m?.invoke(adapter, position)
    } catch (_: Throwable) {
        null
    }

    private fun ensureBadge(itemView: ViewGroup): TextView? = try {
        itemView.findViewWithTag<TextView>(BADGE_TAG) ?: TextView(itemView.context).also { tv ->
            val density = itemView.resources.displayMetrics.density
            val padH = (6 * density).toInt()
            val padV = (2 * density).toInt()
            tv.tag = BADGE_TAG
            tv.textSize = 10f
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setTextColor(Color.WHITE)
            tv.setPadding(padH, padV, padH, padV)
            tv.background = GradientDrawable().apply {
                cornerRadius = 4 * density
                setColor(Color.parseColor("#CC000000"))
            }
            // 角标贴左上角（ConstraintLayout 中未加约束的子视图默认位于 0,0）
            tv.translationX = 5 * density
            tv.translationY = 5 * density
            tv.visibility = View.GONE
            itemView.addView(tv, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    } catch (_: Throwable) {
        null
    }

    private fun showBadge(badge: TextView, path: String, type: String) {
        badgePaths[badge] = path
        badge.text = label(type)
        badge.visibility = View.VISIBLE
    }

    private fun hideBadge(itemView: ViewGroup) {
        try {
            val badge = itemView.findViewWithTag<TextView>(BADGE_TAG)
            badge?.visibility = View.GONE
            if (badge != null) badgePaths.remove(badge)
        } catch (_: Throwable) {
        }
    }

    private fun label(type: String): String = when (type) {
        TYPE_VIDEO -> "视频"
        TYPE_SCENE -> "场景"
        TYPE_IMAGE -> "图片"
        TYPE_WEB -> "网页"
        else -> type
    }

    private fun notifyAdapter() {
        handler.post {
            try {
                val adapter = adapterRef?.get() ?: return@post
                adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- 反射工具

    private fun appContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }

    private fun callString(target: Any, method: String): String? = try {
        target.javaClass.getMethod(method).invoke(target) as? String
    } catch (_: Throwable) {
        null
    }

    private fun callInt(target: Any, method: String): Int = try {
        (target.javaClass.getMethod(method).invoke(target) as? Number)?.toInt() ?: 0
    } catch (_: Throwable) {
        0
    }

    private fun callLong(target: Any, method: String): Long = try {
        (target.javaClass.getMethod(method).invoke(target) as? Number)?.toLong() ?: 0L
    } catch (_: Throwable) {
        0L
    }

    private fun log(message: String, t: Throwable? = null) {
        Log.w(TAG, message, t)
    }
}
