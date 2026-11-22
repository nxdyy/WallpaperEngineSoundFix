package io.wallpaper.soundfix

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * 网页壁纸渲染器（系统 WebView 版）。
 *
 * 结构：
 *   WallpaperService Surface  ←  VirtualDisplay  ←  Presentation(WebView)
 *                                              ↑
 *                              http(s)://<随机主机>/index.html
 *                                              ↑
 *                              WebViewClient.shouldInterceptRequest 直接从壁纸目录读文件
 *
 * 不使用本地 HTTP 服务器：目标应用 targetSdk 35 默认禁止明文 HTTP，
 * 且 shouldInterceptRequest 的性能和安全性都更好。
 *
 * 壁纸目录约定：
 *   files/webwallpapers/<id>/project.json 等文件
 *   files/webwallpapers/current 内容为 <id>（存在时强制使用该网页壁纸）
 *
 * 模块卸载后这些文件只是普通数据，原版不会读取，不影响壁纸引擎正常运行。
 */
internal object WebWallpaper {

    private const val TAG = "WESoundFix"
    private const val ROOT_DIR = "webwallpapers"
    private const val CURRENT_FILE = "current"
    /** 设置项：网页壁纸帧率上限（默认 20fps） */
    const val KEY_FPS = "web_wallpaper_fps"

    /** 设置项：网页壁纸渲染倍率，百分比字符串（默认 100） */
    const val KEY_RENDER_SCALE = "web_wallpaper_render_scale"

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 每个 WallpaperService Engine（桌面壁纸、系统预览等）各自一个渲染器。
     * 不能用单例：预览 Engine 销毁时不能影响仍在运行的桌面 Engine。
     */
    private val renderers = java.util.WeakHashMap<Any, Renderer>()

    /** 帧率上限，来自壁纸引擎设置（滑动条，10~60）。 */
    fun fpsLimit(ctx: Context): Int = prefInt(ctx, KEY_FPS, 20).coerceIn(10, 60)

    /** 渲染倍率，1.0 = 原生分辨率（滑动条 50~100）。 */
    fun renderScale(ctx: Context): Float = prefInt(ctx, KEY_RENDER_SCALE, 100).coerceIn(25, 100) / 100f

    /** 兼容旧版本保存的字符串类型值。 */
    private fun prefInt(ctx: Context, key: String, def: Int): Int {
        val sp = defaultPrefs(ctx) ?: return def
        return when (val v = sp.all[key]) {
            is Int -> v
            is Long -> v.toInt()
            is String -> v.toIntOrNull() ?: def
            else -> def
        }
    }

    /** 读取壁纸引擎的默认 SharedPreferences（androidx.preference 的默认文件名）。 */
    private fun defaultPrefs(ctx: Context): android.content.SharedPreferences? {
        return try {
            val pm = Class.forName("androidx.preference.PreferenceManager")
            pm.getMethod("getDefaultSharedPreferences", Context::class.java)
                .invoke(null, ctx) as? android.content.SharedPreferences
        } catch (_: Throwable) {
            try {
                ctx.getSharedPreferences(
                    ctx.packageName + "_preferences", Context.MODE_PRIVATE)
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** 选择当前要渲染的网页壁纸目录；不是网页壁纸返回 null。 */
    fun pickWallpaper(ctx: Context, path: String?): File? {
        // 用户从列表选中的网页壁纸优先
        val selected = resolveDir(ctx, path)
        if (selected != null && isWebProject(selected)) return selected
        return overrideDir(ctx)
    }

    /** 解析并确认某个 filePath 是网页壁纸目录。 */
    fun webDir(ctx: Context, filePath: String?): File? {
        val dir = resolveDir(ctx, filePath) ?: return null
        return if (isWebProject(dir)) dir else null
    }

    /** 扫描 files/webwallpapers 下的所有网页壁纸。 */
    fun entries(ctx: Context): List<Entry> {
        return try {
            val base = File(ctx.filesDir, ROOT_DIR)
            if (!base.isDirectory) return emptyList()
            base.listFiles()
                ?.filter { it.isDirectory && isWebProject(it) }
                ?.map { dir ->
                    val project = File(dir, "project.json")
                    val title = try {
                        JSONObject(project.readText()).optString("title", "").ifBlank { dir.name }
                    } catch (_: Throwable) {
                        dir.name
                    }
                    Entry(dir.name, title, project.lastModified())
                }
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 从 ZIP 导入网页壁纸（根目录包含 project.json / index.html / 预览图）。
     * 成功返回新目录名，失败抛出带中文提示的异常。
     */
    fun importZip(ctx: Context, uri: android.net.Uri): String {
        val base = File(ctx.filesDir, ROOT_DIR)
        if (!base.isDirectory && !base.mkdirs()) throw IllegalStateException("无法创建壁纸目录")
        val temp = File(ctx.cacheDir, "webimport-${System.currentTimeMillis()}")
        if (!temp.mkdirs()) throw IllegalStateException("无法创建临时目录")
        try {
            var total = 0L
            var count = 0
            val input = ctx.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            input.use { raw ->
                java.util.zip.ZipInputStream(java.io.BufferedInputStream(raw)).use { zip ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        count++
                        if (count > 3000) throw IllegalStateException("压缩包内文件过多")
                        val name = entry.name.replace('\\', '/')
                        if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                            throw IllegalStateException("压缩包路径不合法")
                        }
                        if (entry.isDirectory) continue
                        val out = File(temp, name)
                        val rootPath = temp.canonicalPath + File.separator
                        if (!out.canonicalPath.startsWith(rootPath)) {
                            throw IllegalStateException("压缩包路径不合法")
                        }
                        out.parentFile?.mkdirs()
                        java.io.FileOutputStream(out).use { fos ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n <= 0) break
                                total += n
                                if (total > 200L * 1024 * 1024) {
                                    throw IllegalStateException("压缩包内容过大")
                                }
                                fos.write(buffer, 0, n)
                            }
                        }
                    }
                }
            }

            validateWebProject(temp)

            val rawName = displayName(ctx, uri)?.substringBeforeLast('.')?.trim().orEmpty()
            val name = uniqueDirName(base, rawName.ifBlank { "webwallpaper" })
            val dest = File(base, name)
            if (!temp.renameTo(dest)) {
                temp.copyRecursively(dest, overwrite = true)
            }
            return name
        } finally {
            temp.deleteRecursively()
        }
    }

    /** 检查解压后的目录是否是合法的网页壁纸。 */
    private fun validateWebProject(dir: File) {
        val project = File(dir, "project.json")
        if (!project.isFile) throw IllegalStateException("缺少 project.json")
        val json = try {
            JSONObject(project.readText())
        } catch (_: Throwable) {
            throw IllegalStateException("project.json 解析失败")
        }
        val type = json.optString("type")
        val entry = json.optString("file")
        if (!type.equals("web", ignoreCase = true) &&
            !entry.endsWith(".html", ignoreCase = true)
        ) {
            throw IllegalStateException("不是网页壁纸（project.json 中 type 不是 web）")
        }
        val html = entry.ifBlank { "index.html" }
        if (html.contains("..")) throw IllegalStateException("project.json 中文件路径不合法")
        if (!File(dir, html).isFile) throw IllegalStateException("缺少 $html")
        val previews = arrayOf(
            "preview.gif", "preview.png", "preview.jpg", "preview.jpeg",
            "preview.webp", "preview.tga", "default.webp"
        )
        if (previews.none { File(dir, it).isFile }) {
            throw IllegalStateException("缺少预览图（preview.gif / default.webp）")
        }
    }

    private fun displayName(ctx: Context, uri: android.net.Uri): String? {
        return try {
            ctx.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun uniqueDirName(base: File, raw: String): String {
        val safe = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "webwallpaper" }
        var candidate = safe
        var index = 2
        while (File(base, candidate).exists()) {
            candidate = "$safe ($index)"
            index++
        }
        return candidate
    }

    /** 列表缩略图：从壁纸目录里的 preview/default 图片解码。 */
    fun previewBitmap(ctx: Context, filePath: String): Bitmap? {
        if (!filePath.startsWith("$ROOT_DIR/")) return null
        return try {
            val dir = File(ctx.filesDir, filePath)
            val names = arrayOf(
                "preview.gif", "preview.png", "preview.jpg", "preview.jpeg",
                "preview.webp", "default.webp", "preview.tga"
            )
            for (name in names) {
                val f = File(dir, name)
                if (f.isFile) {
                    val bmp = BitmapFactory.decodeFile(f.absolutePath)
                    if (bmp != null) return bmp
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** 在 Activity 的容器里放一个网页预览（普通 WebView，性能优于 VirtualDisplay）。 */
    fun addPreview(activity: Activity, container: android.view.ViewGroup, dir: File) {
        try {
            stopPreview()
            val host = "we-${randomToken()}.local"
            val wv = createWebView(activity, dir, host)
            previewWebView = wv
            container.removeAllViews()
            container.addView(wv, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT))
            val entry = projectEntry(dir)
            loadWhenLaidOut(wv, "https://$host/$entry")
            Log.i(TAG, "web preview started: ${dir.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "web preview failed", t)
        }
    }

    fun stopPreview() {
        val wv = previewWebView ?: return
        previewWebView = null
        try {
            wv.onPause()
            wv.stopLoading()
            wv.destroy()
        } catch (_: Throwable) {
        }
    }

    private var previewWebView: WebView? = null

    /**
     * 等 WebView 完成布局再加载：不少壁纸脚本在加载时读取 innerWidth/innerHeight
     * 来确定 canvas 尺寸，过早加载会得到 0x0 导致画面全黑。
     */
    private fun loadWhenLaidOut(wv: WebView, url: String) {
        val loader = object : Runnable {
            private var tries = 0
            override fun run() {
                if (wv.width > 0 && wv.height > 0) {
                    wv.loadUrl(url)
                } else if (tries++ < 40) {
                    wv.postDelayed(this, 50)
                } else {
                    wv.loadUrl(url)
                }
            }
        }
        wv.post(loader)
    }

    data class Entry(val name: String, val title: String, val lastModified: Long)

    /** 在壁纸 Surface 上启动网页渲染（同一 Engine 的旧渲染器会被替换）。 */
    fun start(
        ctx: Context,
        dir: File,
        surface: android.view.Surface,
        width: Int,
        height: Int,
        owner: Any
    ): Boolean {
        val existing = renderers[owner]
        if (existing != null && existing.matches(dir, surface, width, height)) {
            return true
        }
        existing?.stop()
        renderers.remove(owner)
        if (!surface.isValid) return false
        val densityDpi = ctx.resources.displayMetrics.densityDpi
        return try {
            val r = Renderer(ctx, dir, surface, width, height, densityDpi)
            r.start()
            renderers[owner] = r
            Log.i(TAG, "web wallpaper started: ${dir.name}, ${width}x${height}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start web wallpaper failed", t)
            false
        }
    }

    /** 停止指定 Engine 的渲染器；不传参数时停止全部。 */
    fun stop(owner: Any? = null) {
        if (owner != null) {
            renderers.remove(owner)?.let { r ->
                try {
                    r.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "stop web wallpaper failed", t)
                }
            }
            return
        }
        for (r in renderers.values) {
            try {
                r.stop()
            } catch (_: Throwable) {
            }
        }
        renderers.clear()
    }

    fun setPaused(owner: Any?, p: Boolean) {
        renderers[owner]?.setPaused(p)
    }

    /** 把壁纸 Engine 收到的触摸事件转发给对应 WebView。 */
    fun dispatchTouch(owner: Any?, event: android.view.MotionEvent): Boolean {
        return renderers[owner]?.dispatchTouch(event) ?: false
    }

    fun isRunning(): Boolean = renderers.isNotEmpty()

    private val settingsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingApply: Runnable? = null

    /** 设置变化后延迟重建现有渲染器，避免拖动滑动条过程中反复重建。 */
    fun scheduleApplySettings() {
        pendingApply?.let { settingsHandler.removeCallbacks(it) }
        val r = Runnable {
            pendingApply = null
            applySettings()
        }
        pendingApply = r
        settingsHandler.postDelayed(r, 400)
    }

    /** 用当前设置重新创建所有正在运行的桌面渲染器。 */
    private fun applySettings() {
        val owners = renderers.keys.toList()
        if (owners.isEmpty()) return
        for (owner in owners) {
            val old = renderers[owner] ?: continue
            try {
                val surface = old.surface
                if (!surface.isValid) {
                    renderers.remove(owner)
                    old.stop()
                    continue
                }
                val dir = old.dir
                val w = old.width
                val h = old.height
                val dpi = old.densityDpi
                val wasPaused = old.isPaused()
                old.stop()
                renderers.remove(owner)
                val fresh = Renderer(old.ctx, dir, surface, w, h, dpi)
                fresh.start()
                fresh.setPaused(wasPaused)
                renderers[owner] = fresh
                Log.i(TAG, "web wallpaper restarted for new settings: ${dir.name}")
            } catch (t: Throwable) {
                Log.w(TAG, "restart web wallpaper failed", t)
            }
        }
    }

    // ------------------------------------------------------------ 路径/项目

    private fun overrideDir(ctx: Context): File? {
        return try {
            val base = File(ctx.filesDir, ROOT_DIR)
            val marker = File(base, CURRENT_FILE)
            if (!marker.isFile) return null
            val name = marker.readText().trim().ifBlank { return null }
            val dir = File(base, name)
            if (isWebProject(dir)) dir else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveDir(ctx: Context, path: String?): File? {
        if (path.isNullOrBlank()) return null
        if (path.endsWith(".mpkg", true) || path.endsWith(".pkg", true)) return null
        val f = File(if (path.startsWith("/")) path else File(ctx.filesDir, path).absolutePath)
        return if (f.isDirectory) f else null
    }

    private fun isWebProject(dir: File): Boolean {
        return try {
            val project = File(dir, "project.json")
            if (!project.isFile) return false
            val json = JSONObject(project.readText())
            val type = json.optString("type", "")
            val entry = json.optString("file", "")
            type.equals("web", ignoreCase = true) || entry.endsWith(".html", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun projectEntry(dir: File): String {
        return try {
            val json = JSONObject(File(dir, "project.json").readText())
            json.optString("file", "").ifBlank { "index.html" }
        } catch (_: Throwable) {
            "index.html"
        }
    }

    private fun defaultPropertiesJs(dir: File): String? {
        return try {
            val json = JSONObject(File(dir, "project.json").readText())
            val props = json.optJSONObject("general")?.optJSONObject("properties")
                ?: return null
            val out = JSONObject()
            val keys = props.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = props.optJSONObject(key) ?: continue
                val obj = JSONObject()
                obj.put("value", item.opt("value"))
                out.put(key, obj)
            }
            if (out.length() == 0) null else
                "if(window.wallpaperPropertyListener&&window.wallpaperPropertyListener.applyUserProperties)" +
                    "{window.wallpaperPropertyListener.applyUserProperties(${out})}"
        } catch (_: Throwable) {
            null
        }
    }

    private fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            else -> "application/octet-stream"
        }
    }

    private fun randomToken(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder()
        repeat(16) { sb.append(chars[rnd.nextInt(chars.length)]) }
        return sb.toString()
    }

    /** 创建一个从 [dir] 加载资源的 WebView（桌面壁纸与预览页共用）。 */
    private fun createWebView(context: Context, dir: File, host: String): WebView {
        val propsJs = defaultPropertiesJs(dir)
        val wv = WebView(context)
        wv.setBackgroundColor(Color.BLACK)
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        try {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        } catch (_: Throwable) {
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val uri = request.url
                if (!uri.host.equals(host, ignoreCase = true)) return null
                val rel = uri.path?.trimStart('/')?.ifBlank { "index.html" } ?: return null
                val file = resolveSafe(dir, rel)
                if (file == null) {
                    Log.w(TAG, "web asset 404: $rel")
                    return WebResourceResponse(
                        "text/plain", "UTF-8",
                        ByteArrayInputStream("Not Found".toByteArray()))
                }
                Log.d(TAG, "web asset: $rel (${file.length()} bytes)")
                return try {
                    WebResourceResponse(mimeType(file.name), null, FileInputStream(file))
                } catch (t: Throwable) {
                    Log.w(TAG, "open web asset failed: $rel", t)
                    null
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = !request.url.host.equals(host, ignoreCase = true)

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                Log.i(TAG, "web page started: $url")
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "web page finished: $url")
                try {
                    val js = StringBuilder()
                    // 限制 rAF 到设置中的帧率上限，降低壁纸持续渲染的 CPU/GPU 占用
                    val maxFps = fpsLimit(view.context)
                    js.append(
                        "(function(){var raf=window.requestAnimationFrame;" +
                            "if(!raf)return;var last=0,min=${1000 / maxFps};" +
                            "window.requestAnimationFrame=function(cb){return raf(function(t){" +
                            "if(t-last<min){return window.requestAnimationFrame(cb);}" +
                            "last=t;cb(t);});};})();")
                    if (!propsJs.isNullOrBlank()) js.append(propsJs)
                    // 触摸 → mousemove 桥接：WebView 默认把滑动当作滚动手势，
                    // 不会派生 mousemove，网页壁纸的鼠标跟随会失效。
                    js.append(
                        "(function(){" +
                            "function send(e){var t=(e.changedTouches&&e.changedTouches[0])||" +
                            "(e.touches&&e.touches[0]);if(!t)return;" +
                            "var el=document.elementFromPoint(t.clientX,t.clientY)||document.body||" +
                            "document.documentElement;if(!el)return;" +
                            "var ev;try{ev=new MouseEvent('mousemove',{clientX:t.clientX," +
                            "clientY:t.clientY,bubbles:true,cancelable:true});}catch(_){" +
                            "ev=document.createEvent('MouseEvents');" +
                            "ev.initMouseEvent('mousemove',true,true,window,0,t.clientX,t.clientY," +
                            "t.clientX,t.clientY,false,false,false,false,0,null);}" +
                            "el.dispatchEvent(ev);}" +
                            "document.addEventListener('touchstart',send,{passive:true});" +
                            "document.addEventListener('touchmove',send,{passive:true});" +
                            "var s=document.createElement('style');" +
                            "s.textContent='html,body{touch-action:none !important;" +
                            "overscroll-behavior:none !important;}';" +
                            "(document.head||document.documentElement).appendChild(s);" +
                            "})();")
                    view.evaluateJavascript(js.toString(), null)
                } catch (t: Throwable) {
                    Log.w(TAG, "inject web wallpaper script failed", t)
                }
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError
            ) {
                Log.e(TAG, "web error: ${error.errorCode} ${error.description} ${request.url}")
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d(TAG, "[web] ${msg.message()} (${msg.lineNumber()})")
                return true
            }
        }
        return wv
    }

    /** 把 URL 路径安全地映射到壁纸目录内的文件。 */
    private fun resolveSafe(root: File, rel: String): File? {
        return try {
            val rootPath = root.canonicalFile
            var f = File(rootPath, rel).canonicalFile
            if (!(f.path == rootPath.path || f.path.startsWith(rootPath.path + File.separator))) {
                return null
            }
            if (f.isDirectory) f = File(f, "index.html")
            if (f.isFile) f else null
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------ 渲染器

    private class Renderer(
        val ctx: Context,
        val dir: File,
        val surface: android.view.Surface,
        val width: Int,
        val height: Int,
        val densityDpi: Int
    ) {
        /** 随机主机名，避免与真实域名冲突，也避免其他页面引用本地资源 */
        private val host = "we-${randomToken()}.local"

        /** WebView 内部渲染倍率，低于 1 时按比例缩小布局再放大显示 */
        private val viewScale: Float = renderScale(ctx)

        private var display: VirtualDisplay? = null
        private var presentation: Presentation? = null
        private var webView: WebView? = null
        private var paused = false

        fun start() {
            val entry = projectEntry(dir)

            mainHandler.post {
                try {
                    val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    var vd: VirtualDisplay? = null
                    // 必须与壁纸 Surface 的缓冲区尺寸一致：SurfaceFlinger 不会缩放
                    // 虚拟显示内容，尺寸不一致会导致画面只占左上角、其余区域黑屏。
                    val renderW = width.coerceAtLeast(1)
                    val renderH = height.coerceAtLeast(1)
                    val renderDpi = densityDpi
                    val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    try {
                        vd = dm.createVirtualDisplay(
                            "WESoundFixWeb", renderW, renderH, renderDpi, surface, flags)
                    } catch (t: Throwable) {
                        Log.w(TAG, "createVirtualDisplay(presentation) failed", t)
                    }
                    if (vd == null) {
                        try {
                            vd = dm.createVirtualDisplay(
                                "WESoundFixWeb", renderW, renderH, renderDpi, surface,
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC)
                        } catch (t: Throwable) {
                            Log.e(TAG, "createVirtualDisplay(public) failed", t)
                        }
                    }
                    if (vd == null) {
                        Log.e(TAG, "virtual display unavailable")
                        stop()
                        return@post
                    }
                    display = vd
                    Log.i(TAG, "virtual display created: id=${vd.display.displayId}, " +
                        "${renderW}x${renderH}, dpi=$renderDpi (surface ${width}x${height})")

                    val themeCtx = ContextThemeWrapper(
                        ctx, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
                    val p = Presentation(themeCtx, vd.display)
                    p.setCancelable(false)

                    val wv = createWebView(p.context, dir, host)
                    webView = wv
                    // 降低渲染倍率：虚拟屏仍是全屏尺寸，WebView 以较小尺寸布局后放大，
                    // 这样画面仍铺满屏幕，但内核只按缩小后的分辨率光栅化。
                    val root = android.widget.FrameLayout(p.context)
                    root.setBackgroundColor(Color.BLACK)
                    if (viewScale < 0.999f) {
                        wv.scaleX = 1f / viewScale
                        wv.scaleY = 1f / viewScale
                        wv.pivotX = 0f
                        wv.pivotY = 0f
                    }
                    val viewW = (width * viewScale).toInt().coerceAtLeast(1)
                    val viewH = (height * viewScale).toInt().coerceAtLeast(1)
                    root.addView(wv, android.widget.FrameLayout.LayoutParams(viewW, viewH))
                    Log.i(TAG, "web view render size: ${viewW}x$viewH (scale=$viewScale)")
                    p.setContentView(root, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT))
                    presentation = p
                    p.show()
                    Log.i(TAG, "presentation shown on virtual display")

                    val url = "https://$host/$entry"
                    Log.i(TAG, "loading web wallpaper: $url")
                    loadWhenLaidOut(wv, url)
                } catch (t: Throwable) {
                    Log.e(TAG, "show presentation failed", t)
                    stop()
                }
            }
        }


        /** 同一个壁纸目录、同一个输出 Surface、同样尺寸时无需重建。 */
        fun matches(target: File, targetSurface: android.view.Surface,
                    w: Int, h: Int): Boolean {
            return target.absolutePath == dir.absolutePath &&
                targetSurface === surface && w == width && h == height &&
                viewScale == renderScale(ctx)
        }

        /** 事件坐标是屏幕坐标，需要缩放到 WebView 的本地布局坐标。 */
        fun dispatchTouch(event: android.view.MotionEvent): Boolean {
            val wv = webView ?: return false
            return try {
                val copy = android.view.MotionEvent.obtain(event)
                try {
                    if (viewScale < 0.999f) {
                        val m = android.graphics.Matrix()
                        m.setScale(viewScale, viewScale)
                        copy.transform(m)
                    }
                    wv.dispatchTouchEvent(copy)
                } finally {
                    copy.recycle()
                }
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun isPaused(): Boolean = paused

        fun setPaused(p: Boolean) {
            if (paused == p) return
            paused = p
            mainHandler.post {
                val wv = webView ?: return@post
                try {
                    // 注意：不能用 pauseTimers()/resumeTimers()，它们会影响进程内
                    // 所有 WebView（包括应用内预览页），导致预览一直加载不出来。
                    if (p) {
                        wv.onPause()
                        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
                    } else {
                        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                        wv.onResume()
                    }
                    Log.i(TAG, "web wallpaper ${if (p) "paused" else "resumed"}")
                } catch (t: Throwable) {
                    Log.w(TAG, "webview pause/resume failed", t)
                }
            }
        }

        fun stop() {
            val p = presentation
            val wv = webView
            val vd = display
            presentation = null
            webView = null
            display = null
            mainHandler.post {
                try {
                    wv?.stopLoading()
                    wv?.loadUrl("about:blank")
                    p?.dismiss()
                } catch (_: Throwable) {
                }
                try {
                    vd?.release()
                } catch (_: Throwable) {
                }
                try {
                    wv?.destroy()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
