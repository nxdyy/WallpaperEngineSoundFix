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

    /** 暂停页面内所有 audio/video 与 Web Audio，避免退出后声音继续播放。 */
    private val PAUSE_MEDIA_JS = """
        (function(){
          var n = 0;
          try { n = document.querySelectorAll('audio,video').length } catch (e) {}
          try {
            if (window.__wePauseAll) { window.__wePauseAll(); return 'guard,' + n }
          } catch (e) {}
          try {
            var m = document.querySelectorAll('audio,video');
            for (var i = 0; i < m.length; i++) { try { m[i].pause() } catch (e) {} }
          } catch (e) {}
          return 'plain,' + n
        })()
    """.trimIndent()

    private val RESUME_MEDIA_JS = """
        (function(){
          try { if (window.__weResumeAll) window.__weResumeAll() } catch (e) {}
        })()
    """.trimIndent()

    /** 通知网页壁纸自身进入暂停状态（wallpaperPropertyListener.setPaused）。 */
    private const val PROPERTY_PAUSED_JS =
        "(function(){try{var l=window.wallpaperPropertyListener;" +
            "if(l&&l.setPaused)l.setPaused(true)}catch(e){}})()"

    private const val PROPERTY_RESUMED_JS =
        "(function(){try{var l=window.wallpaperPropertyListener;" +
            "if(l&&l.setPaused)l.setPaused(false)}catch(e){}})()"

    /**
     * 在页面脚本执行前注入媒体守卫：
     * - 记录所有 AudioContext（页面后续创建的也能暂停）
     * - 暂停期间拦截 HTMLMediaElement.play()，防止页面自动重试播放
     * - 同时处理同源 iframe
     */
    private val MEDIA_GUARD_JS = """
        <script>
        (function () {
          var cs = [];
          var AC = window.AudioContext || window.webkitAudioContext;
          if (AC) {
            var W = function () {
              var c = new AC(...arguments);
              cs.push(c);
              if (window.__wePaused) { try { c.suspend() } catch (e) {} }
              return c;
            };
            W.prototype = AC.prototype;
            window.AudioContext = W;
            window.webkitAudioContext = W;
          }
          window.__wePaused = __WE_PAUSED__;
          var savedMedia = [];
          var blockedMedia = [];
          var origPlay = HTMLMediaElement.prototype.play;
          HTMLMediaElement.prototype.play = function () {
            if (window.__wePaused) {
              try { if (blockedMedia.indexOf(this) < 0) blockedMedia.push(this); } catch (e) {}
              try { this.pause() } catch (e) {}
              return Promise.resolve();
            }
            return origPlay.apply(this, arguments);
          };
          document.addEventListener('play', function (e) {
            if (window.__wePaused) { try { e.target.pause() } catch (e2) {} }
          }, true);
          window.__wePauseMedia = function () {
            window.__wePaused = true;
            var m = document.querySelectorAll('audio,video');
            for (var i = 0; i < m.length; i++) {
              try {
                // 记录暂停前正在播放的媒体，恢复时需要主动重新播放
                if (!m[i].paused && !m[i].ended) savedMedia.push(m[i]);
                m[i].pause();
              } catch (e) {}
            }
            for (var j = 0; j < cs.length; j++) { try { cs[j].suspend() } catch (e) {} }
          };
          window.__weResumeMedia = function () {
            window.__wePaused = false;
            var list = savedMedia.concat(blockedMedia);
            savedMedia = [];
            blockedMedia = [];
            var seen = [];
            for (var i = 0; i < list.length; i++) {
              try {
                if (seen.indexOf(list[i]) >= 0) continue;
                seen.push(list[i]);
                if (list[i].paused) {
                  var pr = origPlay.call(list[i]);
                  if (pr && pr.catch) pr.catch(function () {});
                }
              } catch (e) {}
            }
            for (var j = 0; j < cs.length; j++) { try { cs[j].resume() } catch (e) {} }
          };
          function eachFrame(fn) {
            for (var i = 0; i < window.frames.length; i++) {
              try { fn(window.frames[i]) } catch (e) {}
            }
          }
          window.__wePauseAll = function () {
            window.__wePauseMedia();
            eachFrame(function (f) { if (f.__wePauseMedia) f.__wePauseMedia(); });
          };
          window.__weResumeAll = function () {
            window.__weResumeMedia();
            eachFrame(function (f) { if (f.__weResumeMedia) f.__weResumeMedia(); });
          };
        })();
        </script>
    """.trimIndent()
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
        val selected = resolveDir(ctx, path) ?: return null
        return if (isWebProject(selected)) selected else null
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
            ensurePropertyListener(activity)
            val host = hostFor(dir)
            // 渲染进程被回收时重建预览，避免应用被系统终止
            val wv = createWebView(activity, dir, host,
                onRendererGone = { addPreview(activity, container, dir) })
            previewWebView = wv
            previewDir = dir
            previewContainer = container
            val metrics = activity.resources.displayMetrics
            previewScreenW = metrics.widthPixels
            previewScreenH = metrics.heightPixels
            val geo = geometryFor(activity, dir, previewScreenW, previewScreenH)
            previewOffsetX = geo.offsetX.toFloat()
            previewOffsetY = geo.offsetY.toFloat()
            val scale = renderScale(activity)
            val layoutW = (geo.contentW * scale).toInt().coerceAtLeast(1)
            val layoutH = (geo.contentH * scale).toInt().coerceAtLeast(1)
            container.removeAllViews()
            container.addView(wv, android.view.ViewGroup.LayoutParams(layoutW, layoutH))
            if (scale < 0.999f) {
                // 与桌面渲染保持一致：按渲染精度缩小布局再放大
                wv.scaleX = 1f / scale
                wv.scaleY = 1f / scale
                wv.pivotX = 0f
                wv.pivotY = 0f
            }
            wv.x = -previewOffsetX
            wv.y = -previewOffsetY
            val entry = projectEntry(dir)
            loadWhenLaidOut(wv, "https://$host/$entry")
            Log.i(TAG, "web preview started: ${dir.name}, " +
                "${geo.contentW}x${geo.contentH}, offset=${geo.offsetX},${geo.offsetY}")
        } catch (t: Throwable) {
            Log.e(TAG, "web preview failed", t)
        }
    }

    fun stopPreview() {
        val wv = previewWebView ?: return
        previewWebView = null
        previewDir = null
        previewContainer = null
        // 渲染进程可能已经死亡，逐步 try 确保 destroy 一定执行
        try { wv.evaluateJavascript(PAUSE_MEDIA_JS, null) } catch (_: Throwable) {}
        try { wv.onPause() } catch (_: Throwable) {}
        try { wv.loadUrl("about:blank") } catch (_: Throwable) {}
        try { wv.stopLoading() } catch (_: Throwable) {}
        try { wv.destroy() } catch (_: Throwable) {}
    }

    /** 预览页不可见时暂停 WebView 并停止页面里的音频。 */
    fun pausePreview() {
        val wv = previewWebView ?: return
        try {
            wv.evaluateJavascript(PAUSE_MEDIA_JS) { r ->
                Log.i(TAG, "preview media pause result: $r")
            }
            wv.evaluateJavascript(PROPERTY_PAUSED_JS, null)
            wv.onPause()
        } catch (_: Throwable) {
        }
    }

    fun resumePreview() {
        try {
            previewWebView?.evaluateJavascript(RESUME_MEDIA_JS, null)
            previewWebView?.evaluateJavascript(PROPERTY_RESUMED_JS, null)
            previewWebView?.onResume()
        } catch (_: Throwable) {
        }
    }

    private var previewWebView: WebView? = null
    private var previewDir: File? = null
    private var previewContainer: android.view.ViewGroup? = null
    private var previewScreenW = 0
    private var previewScreenH = 0
    private var previewOffsetX = 0f
    private var previewOffsetY = 0f

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

    // ------------------------------------------------------------ 自定义选项 UI

    /** 把网页大小与自定义选项填充到预览页自带的属性抽屉（propertiesContainer）。 */
    fun addPreviewProperties(activity: Activity, dir: File) {
        try {
            val id = activity.resources.getIdentifier(
                "propertiesContainer", "id", activity.packageName)
            if (id == 0) return
            val container = activity.findViewById<ViewGroup>(id) ?: return
            // 原版属性 Fragment 对网页壁纸是空的，替换成网页大小与自定义选项
            container.removeAllViews()
            val view = buildPropertiesView(activity, dir)
            container.addView(view, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            Log.i(TAG, "web wallpaper properties embedded: ${dir.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "embed web properties failed", t)
        }
    }

    /** 构建属性设置视图（嵌入预览页属性抽屉），第一项是网页大小。 */
    private fun buildPropertiesView(ctx: Context, dir: File): View {
        val scroll = android.widget.ScrollView(ctx)
        scroll.isFillViewport = true
        val box = android.widget.LinearLayout(ctx)
        box.orientation = android.widget.LinearLayout.VERTICAL
        box.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 24))
        scroll.addView(box)

        addSizeRows(ctx, box, dir)

        val defs = projectProperties(dir)
        if (defs != null && defs.length() > 0) {
            val filePath = filePathFor(ctx, dir)
            val saved = savedProperties(ctx, filePath)
            val items = ArrayList<Pair<String, JSONObject>>()
            val keys = defs.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = defs.optJSONObject(key) ?: continue
                items.add(key to item)
            }
            items.sortBy { it.second.optInt("order", Int.MAX_VALUE) }
            for ((key, def) in items) {
                addPropertyRow(ctx, box, dir, key, def, saved)
            }
        }
        return scroll
    }

    /** 抽屉第一项：网页大小选择 + 溢出方向的位置调节。 */
    private fun addSizeRows(ctx: Context, box: android.widget.LinearLayout, dir: File) {
        val title = android.widget.TextView(ctx)
        title.text = htmlText("网页大小")
        box.addView(title)

        val spinner = android.widget.Spinner(ctx)
        val names = WEB_SIZE_OPTIONS.map { it.second }
        val values = WEB_SIZE_OPTIONS.map { it.first }
        spinner.adapter = android.widget.ArrayAdapter(
            ctx, android.R.layout.simple_spinner_dropdown_item, names)
        spinner.setSelection(values.indexOf(webSize(ctx, dir)).coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                val value = values.getOrNull(position) ?: return
                if (value == webSize(ctx, dir)) return
                saveWebSize(ctx, dir, value)
                // 比例变化后网页需要按新视口重新排版
                if (ctx is Activity) {
                    val activity = ctx
                    val container = previewContainer
                    if (container != null) {
                        addPreview(activity, container, dir)
                        box.post { addPreviewProperties(activity, dir) }
                    }
                }
                scheduleApplySettings()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        box.addView(spinner)

        val metrics = ctx.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val geo = geometryFor(ctx, dir, screenW, screenH)
        if (geo.contentW > screenW) {
            addOffsetRow(ctx, box, dir, true, "水平位置")
        }
        if (geo.contentH > screenH) {
            addOffsetRow(ctx, box, dir, false, "垂直位置")
        }

        val divider = View(ctx)
        val lp = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1))
        lp.topMargin = dp(ctx, 12)
        lp.bottomMargin = dp(ctx, 4)
        divider.layoutParams = lp
        divider.setBackgroundColor(0x33FFFFFF)
        box.addView(divider)
    }

    private fun addOffsetRow(
        ctx: Context,
        box: android.widget.LinearLayout,
        dir: File,
        horizontal: Boolean,
        labelText: String
    ) {
        val label = android.widget.TextView(ctx)
        val current = webOffset(ctx, dir, horizontal)
        label.text = htmlText("$labelText（${current}%）")
        val sb = android.widget.SeekBar(ctx)
        sb.max = 100
        sb.progress = current
        sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean
            ) {
                label.text = htmlText("$labelText（${progress}%）")
                if (fromUser) {
                    saveWebOffset(ctx, dir, horizontal, progress)
                    applyGeometryToDir(ctx, dir)
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        box.addView(label)
        box.addView(sb)
    }

    private fun addPropertyRow(
        ctx: Context,
        box: android.widget.LinearLayout,
        dir: File,
        key: String,
        def: JSONObject,
        saved: JSONObject?
    ) {
        val type = def.optString("type", "")
        val title = localizedPropertyText(dir, def.optString("text", key))
        val current: Any? = saved?.optJSONObject(key)?.opt("value") ?: def.opt("value")
        when (type) {
            "bool" -> {
                val cb = android.widget.CheckBox(ctx)
                cb.text = htmlText(title)
                cb.isChecked = current as? Boolean ?: false
                cb.setOnCheckedChangeListener { _, v -> savePropertyValue(ctx, dir, key, v) }
                box.addView(cb)
            }
            "slider" -> {
                val label = android.widget.TextView(ctx)
                val min = def.optDouble("min", 0.0)
                val max = def.optDouble("max", 100.0)
                val step = def.optDouble("step", 1.0).coerceAtLeast(0.0001)
                val fraction = def.optBoolean("fraction", false)
                val cur = (current as? Number)?.toDouble() ?: min
                label.text = htmlLabel(title, formatPropertyNumber(cur, fraction))
                val sb = android.widget.SeekBar(ctx)
                sb.max = (((max - min) / step).toInt()).coerceAtLeast(1)
                sb.progress = (((cur - min) / step).toInt()).coerceIn(0, sb.max)
                sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean
                    ) {
                        val v = min + progress * step
                        val value: Number = if (fraction) {
                            String.format(Locale.US, "%.2f", v).toDouble()
                        } else {
                            Math.round(v)
                        }
                        label.text =
                            htmlLabel(title, formatPropertyNumber(value.toDouble(), fraction))
                        if (fromUser) savePropertyValue(ctx, dir, key, value)
                    }

                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                })
                box.addView(label)
                box.addView(sb)
            }
            "combo" -> {
                val label = android.widget.TextView(ctx)
                label.text = htmlText(title)
                val spinner = android.widget.Spinner(ctx)
                val options = def.optJSONArray("options") ?: org.json.JSONArray()
                val labels = ArrayList<CharSequence>()
                val values = ArrayList<String>()
                for (i in 0 until options.length()) {
                    val opt = options.optJSONObject(i) ?: continue
                    labels.add(htmlText(localizedPropertyText(dir, opt.optString("label", ""))))
                    values.add(opt.optString("value", ""))
                }
                val adapter = android.widget.ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item, labels)
                spinner.adapter = adapter
                val cur = current?.toString() ?: ""
                spinner.setSelection(values.indexOf(cur).coerceAtLeast(0), false)
                spinner.onItemSelectedListener = object :
                    android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
                    ) {
                        values.getOrNull(position)?.let { savePropertyValue(ctx, dir, key, it) }
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
                box.addView(label)
                box.addView(spinner)
            }
            "textinput" -> {
                val label = android.widget.TextView(ctx)
                label.text = htmlText(title)
                val et = android.widget.EditText(ctx)
                et.setText(current?.toString() ?: "")
                et.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?, start: Int, count: Int, after: Int
                    ) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        savePropertyValue(ctx, dir, key, s?.toString() ?: "")
                    }
                })
                box.addView(label)
                box.addView(et)
            }
            "text" -> {
                val tv = android.widget.TextView(ctx)
                tv.text = htmlText(localizedPropertyText(dir, def.optString("text", "")))
                box.addView(tv)
            }
            "color" -> {
                val tv = android.widget.TextView(ctx)
                tv.text = htmlLabel(title, current?.toString() ?: "")
                tv.alpha = 0.8f
                box.addView(tv)
            }
            "file" -> {
                val tv = android.widget.TextView(ctx)
                val value = current?.toString() ?: ""
                tv.text = htmlLabel(title, if (value.isBlank()) "未选择" else value)
                tv.alpha = 0.6f
                box.addView(tv)
            }
            else -> {
                val tv = android.widget.TextView(ctx)
                tv.text = htmlText(title)
                box.addView(tv)
            }
        }
    }

    /** project.json 的属性 text/label 允许写 HTML，统一按 HTML 解析。 */
    private fun htmlText(text: String): CharSequence {
        return try {
            android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY)
        } catch (_: Throwable) {
            text
        }
    }

    /** 标题（可含 HTML）+ “：值”的拼接，保留标题格式。 */
    private fun htmlLabel(title: String, value: String): CharSequence {
        return try {
            android.text.TextUtils.concat(htmlText(title), "：", value)
        } catch (_: Throwable) {
            "$title：$value"
        }
    }

    /** 读取属性标题的多语言文本（project.json 的 general.localization）。 */
    private fun localizedPropertyText(dir: File, text: String): String {
        if (text.isBlank()) return ""
        return try {
            val loc = JSONObject(File(dir, "project.json").readText())
                .optJSONObject("general")?.optJSONObject("localization") ?: return text
            for (lang in propertyLanguageCandidates()) {
                val v = loc.optJSONObject(lang)?.optString(text, "") ?: ""
                if (v.isNotBlank()) return v
            }
            text
        } catch (_: Throwable) {
            text
        }
    }

    private fun propertyLanguageCandidates(): List<String> {
        val locale = Locale.getDefault()
        val tag = locale.toString()
        val traditional = tag.contains("Hant") || tag.contains("TW") ||
            tag.contains("HK") || tag.contains("MO")
        return when {
            locale.language == "zh" && traditional ->
                listOf("zh-cht", "zh-chs", "en-us")
            locale.language == "zh" ->
                listOf("zh-chs", "zh-cht", "en-us")
            else ->
                listOf("en-us")
        }
    }

    private fun formatPropertyNumber(v: Double, fraction: Boolean): String {
        return if (fraction || v % 1.0 != 0.0) {
            String.format(Locale.US, "%.2f", v)
        } else {
            v.toInt().toString()
        }
    }

    /** 保存单个属性值到壁纸引擎偏好，并立即推送给网页。 */
    private fun savePropertyValue(ctx: Context, dir: File, key: String, value: Any?) {
        val filePath = filePathFor(ctx, dir)
        try {
            val prefs = defaultPrefs(ctx) ?: return
            val raw = prefs.getString("wp_properties_$filePath", null)
            val root = try {
                raw?.let { JSONObject(it) } ?: JSONObject()
            } catch (_: Throwable) {
                JSONObject()
            }
            val entry = root.optJSONObject(key) ?: JSONObject()
            entry.put("value", value ?: JSONObject.NULL)
            root.put(key, entry)
            prefs.edit().putString("wp_properties_$filePath", root.toString()).apply()
        } catch (_: Throwable) {
        }
        pushPropertiesToDir(ctx, dir)
    }

    private fun dp(ctx: Context, value: Int): Int {
        return (value * ctx.resources.displayMetrics.density).toInt()
    }

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
        // 同一个 Surface 只能有一个生产者，否则两个 WebView 交替出帧会表现为画面跳动
        val duplicate = renderers.entries.firstOrNull {
            it.key !== owner && it.value.surface === surface
        }
        if (duplicate != null) {
            duplicate.value.stop()
            renderers.remove(duplicate.key)
            Log.i(TAG, "stopped duplicate renderer on same surface: " +
                "engine=${duplicate.key.hashCode()}")
        }
        if (!surface.isValid) return false
        val densityDpi = ctx.resources.displayMetrics.densityDpi
        return try {
            val r = Renderer(ctx, dir, surface, width, height, densityDpi)
            r.start()
            renderers[owner] = r
            Log.i(TAG, "web wallpaper started: ${dir.name}, ${width}x${height}, " +
                "engine=${owner.hashCode()}, surface=${System.identityHashCode(surface)}, " +
                "total=${renderers.size}")
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
                    Log.i(TAG, "web wallpaper stopped: engine=${owner.hashCode()}, " +
                        "surface=${System.identityHashCode(r.surface)}, total=${renderers.size}")
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
        Log.i(TAG, "web wallpaper setPaused=$p, engine=${owner?.hashCode()}, " +
            "renderer=${renderers[owner] != null}")
        renderers[owner]?.setPaused(p)
    }

    /** 把壁纸 Engine 收到的触摸事件转发给对应 WebView。 */
    fun dispatchTouch(owner: Any?, event: android.view.MotionEvent): Boolean {
        return renderers[owner]?.dispatchTouch(event) ?: false
    }

    fun isRunning(): Boolean = renderers.isNotEmpty()

    /**
     * 清除网页壁纸在 WebView 中留下的数据。
     *
     * 旧版本每次加载都使用随机虚拟域名，浏览器按 origin 保存的 localStorage、
     * IndexedDB 与 HTTP 缓存会随每次重载不断累积，而且永远不会再被访问。
     */
    fun clearWebData(ctx: Context) {
        try {
            // localStorage / sessionStorage
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (_: Throwable) {
        }
        try {
            val cookies = android.webkit.CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
        } catch (_: Throwable) {
        }
        try {
            val db = android.webkit.WebViewDatabase.getInstance(ctx)
            db.clearFormData()
            db.clearHttpAuthUsernamePassword()
            @Suppress("DEPRECATION")
            db.clearUsernamePassword()
        } catch (_: Throwable) {
        }
        try {
            // 清除 HTTP 缓存（含磁盘缓存），必须在主线程调用
            WebView(ctx).clearCache(true)
        } catch (_: Throwable) {
        }
        // IndexedDB / Service Worker 缓存没有公开清理接口，删除磁盘目录（Chromium 会重建）
        Thread {
            try {
                val root = File(ctx.filesDir.parentFile, "app_webview/Default")
                File(root, "IndexedDB").deleteRecursively()
                File(root, "Local Storage").deleteRecursively()
                File(root, "Service Worker/CacheStorage").deleteRecursively()
            } catch (_: Throwable) {
            }
        }.start()
        // 重新加载当前壁纸，让页面读到清空后的数据
        scheduleApplySettings()
    }

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

    /** 读取 project.json 中定义的用户属性（键 → 属性定义）。 */
    private fun projectProperties(dir: File): JSONObject? {
        return try {
            JSONObject(File(dir, "project.json").readText())
                .optJSONObject("general")?.optJSONObject("properties")
        } catch (_: Throwable) {
            null
        }
    }

    /** 壁纸在壁纸引擎中的相对路径，也就是 wp_properties_ 偏好键的后缀。 */
    private fun filePathFor(ctx: Context, dir: File): String {
        return try {
            val base = File(ctx.filesDir, ROOT_DIR)
            if (dir.parentFile?.canonicalPath == base.canonicalPath) {
                "$ROOT_DIR/${dir.name}"
            } else {
                dir.absolutePath
            }
        } catch (_: Throwable) {
            "$ROOT_DIR/${dir.name}"
        }
    }

    // ------------------------------------------------------------ 网页大小与位置

    /** 网页大小选项：值（宽:高）→ 显示名称，screen 表示跟随屏幕。 */
    private val WEB_SIZE_OPTIONS = listOf(
        "screen" to "跟随屏幕",
        "16:9" to "16 : 9",
        "16:10" to "16 : 10",
        "4:3" to "4 : 3",
        "3:2" to "3 : 2",
        "21:9" to "21 : 9",
        "1:1" to "1 : 1",
        "9:16" to "9 : 16"
    )

    private fun sizePrefKey(ctx: Context, dir: File): String =
        "web_wallpaper_size_${filePathFor(ctx, dir)}"

    private fun offsetPrefKey(ctx: Context, dir: File, horizontal: Boolean): String =
        "web_wallpaper_offset_${if (horizontal) "x" else "y"}_${filePathFor(ctx, dir)}"

    fun webSize(ctx: Context, dir: File): String {
        return try {
            defaultPrefs(ctx)?.getString(sizePrefKey(ctx, dir), "screen") ?: "screen"
        } catch (_: Throwable) {
            "screen"
        }
    }

    private fun saveWebSize(ctx: Context, dir: File, value: String) {
        try {
            defaultPrefs(ctx)?.edit()?.putString(sizePrefKey(ctx, dir), value)?.apply()
        } catch (_: Throwable) {
        }
    }

    /** 位置百分比 0~100，50 为居中。 */
    private fun webOffset(ctx: Context, dir: File, horizontal: Boolean): Int {
        return try {
            (defaultPrefs(ctx)?.getInt(offsetPrefKey(ctx, dir, horizontal), 50) ?: 50)
                .coerceIn(0, 100)
        } catch (_: Throwable) {
            50
        }
    }

    private fun saveWebOffset(ctx: Context, dir: File, horizontal: Boolean, value: Int) {
        try {
            defaultPrefs(ctx)?.edit()
                ?.putInt(offsetPrefKey(ctx, dir, horizontal), value.coerceIn(0, 100))?.apply()
        } catch (_: Throwable) {
        }
    }

    /**
     * 按所选网页大小计算实际渲染尺寸与位置。
     * 非屏幕比例时按 cover 铺满屏幕，溢出的方向可以通过位置百分比平移。
     */
    private data class Geometry(
        val contentW: Int,
        val contentH: Int,
        val offsetX: Int,
        val offsetY: Int
    )

    private fun geometryFor(ctx: Context, dir: File, screenW: Int, screenH: Int): Geometry {
        val w = screenW.coerceAtLeast(1)
        val h = screenH.coerceAtLeast(1)
        var contentW = w
        var contentH = h
        val size = webSize(ctx, dir)
        if (size != "screen") {
            val parts = size.split(":")
            val rw = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val rh = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            if (rw > 0 && rh > 0) {
                val ratio = rw / rh
                if (ratio >= w.toDouble() / h) {
                    contentH = h
                    contentW = Math.round(h * ratio).toInt()
                } else {
                    contentW = w
                    contentH = Math.round(w / ratio).toInt()
                }
            }
        }
        contentW = contentW.coerceAtLeast(1)
        contentH = contentH.coerceAtLeast(1)
        val rangeX = (contentW - w).coerceAtLeast(0)
        val rangeY = (contentH - h).coerceAtLeast(0)
        val offsetX = if (rangeX > 0) {
            Math.round(rangeX * webOffset(ctx, dir, true) / 100f).toInt()
        } else {
            0
        }
        val offsetY = if (rangeY > 0) {
            Math.round(rangeY * webOffset(ctx, dir, false) / 100f).toInt()
        } else {
            0
        }
        return Geometry(contentW, contentH, offsetX, offsetY)
    }

    /** 位置改变后只平移网页，不重新加载页面。 */
    private fun applyGeometryToDir(ctx: Context, dir: File) {
        for (r in renderers.values) {
            if (r.dir.absolutePath == dir.absolutePath) r.applyGeometry()
        }
        val wv = previewWebView
        if (wv != null && previewDir?.absolutePath == dir.absolutePath) {
            updatePreviewGeometry(ctx, dir, wv)
        }
    }

    private fun updatePreviewGeometry(ctx: Context, dir: File, wv: WebView) {
        try {
            val metrics = ctx.resources.displayMetrics
            val screenW = if (previewScreenW > 0) previewScreenW else metrics.widthPixels
            val screenH = if (previewScreenH > 0) previewScreenH else metrics.heightPixels
            val geo = geometryFor(ctx, dir, screenW, screenH)
            previewOffsetX = geo.offsetX.toFloat()
            previewOffsetY = geo.offsetY.toFloat()
            val scale = renderScale(ctx)
            val layoutW = (geo.contentW * scale).toInt().coerceAtLeast(1)
            val layoutH = (geo.contentH * scale).toInt().coerceAtLeast(1)
            wv.layoutParams = android.widget.FrameLayout.LayoutParams(layoutW, layoutH)
            if (scale < 0.999f) {
                wv.scaleX = 1f / scale
                wv.scaleY = 1f / scale
                wv.pivotX = 0f
                wv.pivotY = 0f
            }
            wv.x = -previewOffsetX
            wv.y = -previewOffsetY
        } catch (t: Throwable) {
            Log.w(TAG, "update preview geometry failed", t)
        }
    }

    /** 壁纸引擎已保存的用户属性（JSON 对象，值形如 {"value": ...}）。 */
    private fun savedProperties(ctx: Context, filePath: String): JSONObject? {
        val raw = defaultPrefs(ctx)?.getString("wp_properties_$filePath", null) ?: return null
        return try {
            JSONObject(raw)
        } catch (_: Throwable) {
            null
        }
    }

    /** 合并 project.json 默认值与用户保存值。 */
    private fun mergedProperties(ctx: Context, dir: File): JSONObject? {
        val defs = projectProperties(dir) ?: return null
        val out = JSONObject()
        val keys = defs.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = defs.optJSONObject(key) ?: continue
            out.put(key, JSONObject().put("value", item.opt("value") ?: JSONObject.NULL))
        }
        val saved = savedProperties(ctx, filePathFor(ctx, dir))
        if (saved != null) {
            val savedKeys = saved.keys()
            while (savedKeys.hasNext()) {
                val key = savedKeys.next()
                val value = saved.optJSONObject(key)?.opt("value") ?: continue
                val target = out.optJSONObject(key) ?: JSONObject().also { out.put(key, it) }
                target.put("value", value)
            }
        }
        return if (out.length() == 0) null else out
    }

    /** 生成把用户属性推送给页面的脚本。 */
    private fun propertiesJs(ctx: Context, dir: File): String? {
        val merged = mergedProperties(ctx, dir) ?: return null
        return "(function(){try{var l=window.wallpaperPropertyListener;" +
            "if(l&&l.applyUserProperties){l.applyUserProperties($merged)}}catch(e){}})()"
    }

    /** 把指定壁纸的最新属性推送给桌面渲染器与预览页。 */
    private fun pushPropertiesToDir(ctx: Context, dir: File) {
        for (r in renderers.values) {
            if (r.dir.absolutePath == dir.absolutePath) r.pushProperties()
        }
        val wv = previewWebView
        if (wv != null && previewDir?.absolutePath == dir.absolutePath) {
            val js = propertiesJs(ctx, dir) ?: return
            try {
                wv.evaluateJavascript(js, null)
            } catch (_: Throwable) {
            }
        }
    }

    private var propertyListenerRegistered = false
    private var propertyPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** 监听壁纸引擎保存的属性，修改后实时推送到正在显示的网页。 */
    private fun ensurePropertyListener(ctx: Context) {
        if (propertyListenerRegistered) return
        val prefs = defaultPrefs(ctx) ?: return
        propertyListenerRegistered = true
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || !key.startsWith("wp_properties_")) return@OnSharedPreferenceChangeListener
            val filePath = key.removePrefix("wp_properties_")
            mainHandler.post {
                for (r in renderers.values) {
                    if (filePathFor(r.ctx, r.dir) == filePath) r.pushProperties()
                }
                val wv = previewWebView
                val dir = previewDir
                if (wv != null && dir != null && filePathFor(wv.context, dir) == filePath) {
                    propertiesJs(wv.context, dir)?.let { js ->
                        try {
                            wv.evaluateJavascript(js, null)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
        propertyPrefsListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
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

    /**
     * 同一壁纸目录固定使用同一个虚拟主机名。
     * localStorage/IndexedDB 按 origin 隔离，随机主机名会让每次重载变成新的 origin，
     * 导致壁纸保存的设置丢失。
     */
    private fun hostFor(dir: File): String {
        return try {
            val hex = java.security.MessageDigest.getInstance("MD5")
                .digest(dir.name.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            "we-${hex.substring(0, 16)}.local"
        } catch (_: Throwable) {
            "we-${Integer.toHexString(dir.name.hashCode())}.local"
        }
    }

    /**
     * 创建一个从 [dir] 加载资源的 WebView（桌面壁纸与预览页共用）。
     *
     * [onRendererGone] 在 WebView 渲染进程被系统回收时回调；必须处理该事件并
     * 返回 true，否则系统会终止整个应用（AwBrowserTerminator）。
     */
    private fun createWebView(
        context: Context,
        dir: File,
        host: String,
        isPaused: () -> Boolean = { false },
        onRendererGone: (() -> Unit)? = null
    ): WebView {
        ensurePropertyListener(context)
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
                    if (file.name.endsWith(".html", true) || file.name.endsWith(".htm", true)) {
                        // 在页面脚本执行前注入媒体守卫
                        val html = injectMediaGuard(file.readText(Charsets.UTF_8), isPaused())
                        Log.i(TAG, "media guard injected into $rel")
                        WebResourceResponse(
                            "text/html", "UTF-8",
                            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                    } else {
                        WebResourceResponse(mimeType(file.name), null, FileInputStream(file))
                    }
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
                    // 推送用户属性（project.json 默认值 + 壁纸引擎保存值）
                    propertiesJs(view.context, dir)?.let { js.append(it) }
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
                    view.evaluateJavascript(
                        "(function(){return typeof window.__wePauseAll})()"
                    ) { r -> Log.i(TAG, "media guard state: $r") }
                    // 加载期间壁纸可能已被隐藏，此时页面刚解析完还可能自动重试播放
                    if (isPaused()) {
                        view.evaluateJavascript(PAUSE_MEDIA_JS, null)
                        view.onPause()
                    }
                    // 部分壁纸在异步初始化完成后才注册属性监听，延迟再推一次
                    view.postDelayed({
                        propertiesJs(view.context, dir)?.let {
                            view.evaluateJavascript(it, null)
                        }
                    }, 800L)
                } catch (t: Throwable) {
                    Log.w(TAG, "inject web wallpaper script failed", t)
                }
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError
            ) {
                Log.e(TAG, "web error: ${error.errorCode} ${error.description} ${request.url}")
            }

            override fun onRenderProcessGone(
                view: WebView, detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                // 返回 true 表示应用已处理，系统不再终止进程；
                // 此后该 WebView 不能继续使用，由调用方销毁并重建。
                Log.w(TAG, "web renderer process gone (didCrash=${detail.didCrash()})")
                try {
                    onRendererGone?.invoke()
                } catch (t: Throwable) {
                    Log.w(TAG, "handle renderer gone failed", t)
                }
                return true
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

    /** 把媒体守卫脚本插到页面最前面（doctype 之后），保证早于页面脚本执行。 */
    private fun injectMediaGuard(html: String, paused: Boolean = false): String {
        // 页面在壁纸不可见时加载的话，从第一行脚本起就禁止自动播放
        val guard = MEDIA_GUARD_JS.replace("__WE_PAUSED__", if (paused) "true" else "false")
        val trimmed = html.trimStart()
        if (trimmed.startsWith("<!doctype", ignoreCase = true)) {
            val end = html.indexOf('>')
            if (end >= 0) {
                return html.substring(0, end + 1) + guard + html.substring(end + 1)
            }
        }
        return guard + html
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
        /** 固定主机名，同一壁纸的 localStorage 等设置才能在重载后保留 */
        private val host = hostFor(dir)

        /** WebView 内部渲染倍率，低于 1 时按比例缩小布局再放大显示 */
        private val viewScale: Float = renderScale(ctx)

        /** 创建时的网页大小设置，用于判断是否需要重建渲染器。 */
        private val sizeOption: String = webSize(ctx, dir)

        private var display: VirtualDisplay? = null
        private var presentation: Presentation? = null
        private var webView: WebView? = null
        private var paused = false

        /** 网页左上角相对屏幕左上角的负向偏移（像素），用于非屏幕比例时的位置调节。 */
        private var offsetX = 0f
        private var offsetY = 0f

        /** 渲染进程在壁纸不可见时死亡，等恢复可见再重建，避免后台加载页面并出声。 */
        private var needsRecreate = false

        /**
         * 已停止标记。创建任务通过 post 异步执行，如果 stop() 在任务执行前发生，
         * 此时字段全为 null，销毁任务什么也做不了；若不拦截，创建任务随后仍会
         * 建出 VirtualDisplay + WebView 并持续向 Surface 输出，导致多个 WebView
         * 同时渲染（画面跳动）且退出后音频继续播放。
         */
        @Volatile
        private var cancelled = false

        fun start() {
            val entry = projectEntry(dir)

            mainHandler.post {
                if (cancelled) {
                    Log.i(TAG, "skip cancelled renderer creation: ${dir.name}")
                    return@post
                }
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

                    val wv = createWebView(p.context, dir, host,
                        isPaused = { paused },
                        onRendererGone = { onRendererGone() })
                    webView = wv
                    if (paused) {
                        // 壁纸当前不可见：新 WebView 直接进入暂停状态，避免后台出声
                        try {
                            wv.onPause()
                            wv.setRendererPriorityPolicy(
                                WebView.RENDERER_PRIORITY_WAIVED, true)
                        } catch (_: Throwable) {
                        }
                    }
                    // 按“网页大小”设置计算渲染尺寸；非屏幕比例时按 cover 铺满并可平移
                    val geo = geometryFor(ctx, dir, width, height)
                    offsetX = geo.offsetX.toFloat()
                    offsetY = geo.offsetY.toFloat()
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
                    val viewW = (geo.contentW * viewScale).toInt().coerceAtLeast(1)
                    val viewH = (geo.contentH * viewScale).toInt().coerceAtLeast(1)
                    root.addView(wv, android.widget.FrameLayout.LayoutParams(viewW, viewH))
                    wv.x = -offsetX
                    wv.y = -offsetY
                    Log.i(TAG, "web view render size: ${viewW}x$viewH (scale=$viewScale), " +
                        "content=${geo.contentW}x${geo.contentH}, " +
                        "offset=${geo.offsetX},${geo.offsetY}")
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


        /** 同一个壁纸目录、同一个输出 Surface、同样尺寸与设置时无需重建。 */
        fun matches(target: File, targetSurface: android.view.Surface,
                    w: Int, h: Int): Boolean {
            return target.absolutePath == dir.absolutePath &&
                targetSurface === surface && w == width && h == height &&
                viewScale == renderScale(ctx) && sizeOption == webSize(ctx, dir)
        }

        /** 事件坐标是屏幕坐标，需要先加上平移再缩放到 WebView 的本地布局坐标。 */
        fun dispatchTouch(event: android.view.MotionEvent): Boolean {
            val wv = webView ?: return false
            return try {
                val copy = android.view.MotionEvent.obtain(event)
                try {
                    if (viewScale < 0.999f || offsetX != 0f || offsetY != 0f) {
                        val m = android.graphics.Matrix()
                        m.setTranslate(offsetX, offsetY)
                        if (viewScale < 0.999f) m.postScale(viewScale, viewScale)
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

        /** 位置设置变化时平移网页，不重新加载页面。 */
        fun applyGeometry() {
            mainHandler.post {
                val wv = webView ?: return@post
                try {
                    val geo = geometryFor(ctx, dir, width, height)
                    offsetX = geo.offsetX.toFloat()
                    offsetY = geo.offsetY.toFloat()
                    val viewW = (geo.contentW * viewScale).toInt().coerceAtLeast(1)
                    val viewH = (geo.contentH * viewScale).toInt().coerceAtLeast(1)
                    val lp = wv.layoutParams
                    if (lp != null && (lp.width != viewW || lp.height != viewH)) {
                        lp.width = viewW
                        lp.height = viewH
                        wv.layoutParams = lp
                    }
                    wv.x = -offsetX
                    wv.y = -offsetY
                } catch (t: Throwable) {
                    Log.w(TAG, "apply web geometry failed", t)
                }
            }
        }

        fun isPaused(): Boolean = paused

        fun setPaused(p: Boolean) {
            if (paused == p) return
            paused = p
            mainHandler.post {
                if (needsRecreate) {
                    // 渲染进程是在壁纸隐藏期间死的，恢复可见时再重建
                    if (!p) {
                        needsRecreate = false
                        start()
                    }
                    return@post
                }
                val wv = webView ?: return@post
                try {
                    // 注意：不能用 pauseTimers()/resumeTimers()，它们会影响进程内
                    // 所有 WebView（包括应用内预览页），导致预览一直加载不出来。
                    if (p) {
                        // 页面里的 audio/video 不受 WebView.onPause 控制，需显式暂停
                        wv.evaluateJavascript(PAUSE_MEDIA_JS, null)
                        wv.evaluateJavascript(PROPERTY_PAUSED_JS, null)
                        wv.onPause()
                        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
                    } else {
                        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                        wv.evaluateJavascript(RESUME_MEDIA_JS, null)
                        wv.evaluateJavascript(PROPERTY_RESUMED_JS, null)
                        wv.onResume()
                    }
                    Log.i(TAG, "web wallpaper ${if (p) "paused" else "resumed"}")
                } catch (t: Throwable) {
                    Log.w(TAG, "webview pause/resume failed", t)
                }
            }
        }

        /** 把最新的用户属性推送给页面（属性修改或 SharedPreferences 变化后调用）。 */
        fun pushProperties() {
            mainHandler.post {
                val wv = webView ?: return@post
                val js = propertiesJs(ctx, dir) ?: return@post
                try {
                    wv.evaluateJavascript(js, null)
                } catch (t: Throwable) {
                    Log.w(TAG, "push web properties failed", t)
                }
            }
        }

        /** 渲染进程被系统回收（OOM 等）后清理旧 WebView，并在同一 Surface 上重建。 */
        private fun onRendererGone() {
            if (cancelled) return
            Log.w(TAG, "recreate web renderer after process gone: ${dir.name}")
            val p = presentation
            val wv = webView
            val vd = display
            presentation = null
            webView = null
            display = null
            try { wv?.stopLoading() } catch (_: Throwable) {}
            try { p?.dismiss() } catch (_: Throwable) {}
            try { vd?.release() } catch (_: Throwable) {}
            try { wv?.destroy() } catch (_: Throwable) {}
            if (paused) {
                // 壁纸不可见，先不重建；等 setPaused(false) 时再创建，避免后台出声
                needsRecreate = true
                return
            }
            if (surface.isValid) start() else stop()
        }

        fun stop() {
            cancelled = true
            needsRecreate = false
            val p = presentation
            val wv = webView
            val vd = display
            presentation = null
            webView = null
            display = null
            mainHandler.post {
                try {
                    wv?.evaluateJavascript(PAUSE_MEDIA_JS, null)
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
