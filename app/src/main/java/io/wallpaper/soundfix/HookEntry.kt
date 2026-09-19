package io.wallpaper.soundfix

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * Wallpaper Engine (io.wallpaperengine.weclient) 声音修复模块。
 *
 * 修复两条路径：
 *
 * A. 视频路径（视频壁纸 + 场景壁纸中的视频元素）：
 *    应用硬编码 MediaPlayer.setVolume(0, 0) 静音，hook 后替换为用户音量。
 *
 * B. 场景 Sound 对象路径（纯音频文件）：
 *    libscenejni.so 的 AndroidMediaExtensions 音频函数全部是空壳 stub，
 *    声音从未创建。通过替换其导出虚表 _ZTV22AndroidMediaExtensions 的音频槽位，
 *    桥接到 Java SoundBridge（MediaPlayer 播放）。
 *
 * C. 暂停同步路径：原应用暂停壁纸时只停渲染，AudioRecorder/场景 Sound/场景视频元素
 *    全部继续运行。hook updatePausedState 按 shouldBePaused() 同步启停所有音频源。
 */
class HookEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        log(Log.INFO, TAG, "loaded in process ${param.processName}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName !in TARGET_PACKAGES) return
        // 向模块自身上报激活状态（MainActivity 据此显示是否已激活）
        reportActivation()
        if (!param.isFirstPackage) return
        log(Log.INFO, TAG, "loaded into ${param.packageName}, installing hooks")

        // 初始音量系数
        SoundBridge.volumeFactor = targetVolume()

        // 路径 A：拦截视频静音调用，替换为用户音量
        try {
            hookMediaPlayerVolume()
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook MediaPlayer.setVolume failed", t)
        }

        val cl = param.classLoader

        // 防止 setVolume 被内联进调用方导致 hook 不生效
        try {
            deoptimize(cl.loadClass("io.wallpaperengine.weutil.SupportVideoPlayer")
                .getDeclaredMethod("startPlayback", Context::class.java, String::class.java,
                    Long::class.javaPrimitiveType, Long::class.javaPrimitiveType))
            deoptimize(cl.loadClass("io.wallpaperengine.weviews.VideoWallpaperView\$VideoDrawable")
                .getDeclaredMethod("load", String::class.java))
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "deoptimize callers failed (non-fatal)", t)
        }

        // 防止 updatePausedState 被内联进调用方导致暂停同步 hook 不生效
        try {
            val engineClass = cl.loadClass("io.wallpaperengine.weclient.WEWallpaperService\$GLWallpaperEngine")
            deoptimize(engineClass.getDeclaredMethod("onVisibilityChanged\$lambda\$12",
                engineClass, Boolean::class.javaPrimitiveType))
            deoptimize(engineClass.getDeclaredMethod("powerSavingReceiver\$lambda\$0",
                engineClass, Boolean::class.javaPrimitiveType))
            deoptimize(engineClass.getDeclaredMethod("loadWallpaper\$lambda\$9\$lambda\$8",
                engineClass, cl.loadClass("io.wallpaperengine.weclient.WEWallpaperService")))
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "deoptimize updatePausedState callers failed (non-fatal)", t)
        }

        // 在设置界面注入音量滑条
        try {
            hookSettingsFragment(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook settings fragment failed", t)
        }

        // 设置重载时重新应用音量
        try {
            hookSettingsReload(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook settings reload failed", t)
        }

        // 路径 B：场景 Sound 对象的 native 音频引擎
        try {
            hookSceneLibInit(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook SceneLib.initLibrary failed", t)
        }

        // 路径 C：壁纸暂停/恢复时同步 AudioRecorder、场景 Sound 与场景视频元素
        try {
            hookSupportVideoPlayers(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook SupportVideoPlayer failed", t)
        }
        try {
            hookWallpaperPauseSync(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook wallpaper pause sync failed", t)
        }

        // 首次运行弹窗（仅壁纸引擎内，只弹一次）
        try {
            hookFirstRunDialog(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook first-run dialog failed", t)
        }

        // 版本更新检查（每次打开壁纸引擎）
        try {
            hookUpdateCheck(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook update check failed", t)
        }
    }

    /**
     * 跟踪 SupportVideoPlayer 实例（场景壁纸的视频元素）。
     * 该类由 native 层经 JNI 创建，Java 层无调用点，只能 hook 构造函数收集。
     * 原应用靠 setVolume(0,0) 全局静音掩盖了暂停未接线的问题；模块解除静音后，
     * 壁纸暂停只停 GLSurfaceView 渲染，这些 MediaPlayer 会继续出声，需同步暂停。
     */
    private fun hookSupportVideoPlayers(cl: ClassLoader) {
        val playerClass = cl.loadClass("io.wallpaperengine.weutil.SupportVideoPlayer")
        videoIsPlayingMethod = playerClass.getDeclaredMethod("isPlaying")
        videoPauseMethod = playerClass.getDeclaredMethod("pause")
        videoPlayMethod = playerClass.getDeclaredMethod("play")
        hook(playerClass.getDeclaredConstructor()).intercept { chain ->
            val result = chain.proceed()
            chain.thisObject?.let { trackedVideoPlayers.add(it) }
            result
        }
    }

    /** 暂停所有播放中的场景视频元素，并记录本次由模块暂停的实例（弱引用）。幂等。 */
    private fun pauseTrackedVideos() {
        for (p in trackedVideoPlayers.toTypedArray()) {
            try {
                val playing = videoIsPlayingMethod?.invoke(p) as? Boolean ?: continue
                if (playing) {
                    videoPauseMethod?.invoke(p)
                    globallyPausedVideos.add(p)
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** 恢复仅由 pauseTrackedVideos 暂停的视频元素，不触碰引擎主动暂停的。幂等。 */
    private fun resumeTrackedVideos() {
        if (globallyPausedVideos.isEmpty()) return
        for (p in globallyPausedVideos.toTypedArray()) {
            try {
                videoPlayMethod?.invoke(p)
            } catch (_: Throwable) {
            }
            globallyPausedVideos.remove(p)
        }
    }

    /**
     * Hook GLWallpaperEngine.updatePausedState：壁纸暂停/恢复时同步所有音频源。
     *
     * 原应用 bug：updatePausedState 只暂停渲染（GLSurfaceView）和 ParallaxController，
     * 音频相关路径全部未接线。此处按 shouldBePaused() 结果同步启停：
     *   暂停（离开桌面/省电）→ AudioRecorder.stopAudioListener + SoundBridge.pauseAll
     *                          + 暂停场景视频元素
     *   恢复（回到桌面）     → 对应恢复
     *
     * 注意：audioRecorder 仅在壁纸启用 audioprocessing（FFT 可视化）时非 null；
     * 场景 Sound 和视频元素的同步与 audioprocessing 无关，对所有壁纸都必须执行。
     * 各启停操作内部有状态检查，重复调用幂等安全。
     */
    private fun hookWallpaperPauseSync(cl: ClassLoader) {
        val engineClass = cl.loadClass("io.wallpaperengine.weclient.WEWallpaperService\$GLWallpaperEngine")
        val updateMethod = engineClass.getDeclaredMethod("updatePausedState")
        val shouldBePausedMethod = engineClass.getDeclaredMethod("shouldBePaused")
            .apply { isAccessible = true }
        val recorderField = engineClass.getDeclaredField("audioRecorder")
            .apply { isAccessible = true }

        val recorderClass = cl.loadClass("io.wallpaperengine.weutil.AudioRecorder")
        val stateField = recorderClass.getDeclaredField("mAudioRecordState")
            .apply { isAccessible = true }
        val startMethod = recorderClass.getDeclaredMethod("startAudioListener")
        val stopMethod = recorderClass.getDeclaredMethod("stopAudioListener")

        hook(updateMethod).intercept { chain ->
            val result = chain.proceed()
            try {
                val engine = chain.thisObject ?: return@intercept result
                val shouldPause = shouldBePausedMethod.invoke(engine) as Boolean
                // 1) AudioRecorder（FFT 采集），仅当壁纸启用了 audioprocessing
                val recorder = recorderField.get(engine)
                if (recorder != null) {
                    val running = stateField.get(recorder) as Boolean
                    if (shouldPause && running) {
                        stopMethod.invoke(recorder)
                    } else if (!shouldPause && !running) {
                        startMethod.invoke(recorder)
                    }
                }
                // 2) 场景 Sound（SoundBridge）与场景视频元素，所有壁纸类型都同步
                if (shouldPause) {
                    SoundBridge.pauseAll()
                    pauseTrackedVideos()
                    log(Log.INFO, TAG, "wallpaper paused: sounds & videos stopped")
                } else {
                    SoundBridge.resumeAll()
                    resumeTrackedVideos()
                    log(Log.INFO, TAG, "wallpaper resumed: sounds & videos restarted")
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "sync pause state failed", t)
            }
            result
        }
    }

    /**
     * 首次运行弹窗（壁纸引擎内，只弹一次）。
     * 检测下载来源，提醒被收费的用户举报。
     */
    private fun hookFirstRunDialog(cl: ClassLoader) {
        val browseClass = cl.loadClass("io.wallpaperengine.weclient.BrowseActivity")
        val onCreateMethod = browseClass.getDeclaredMethod("onCreate", Bundle::class.java)
            .apply { isAccessible = true }
        val shownKey = "we_soundfix_dialog_shown"

        hook(onCreateMethod).intercept { chain ->
            val result = chain.proceed()
            try {
                val activity = chain.thisObject as? Context ?: return@intercept result
                val prefs = obtainPrefs() ?: return@intercept result
                if (prefs.getBoolean(shownKey, false)) return@intercept result
                prefs.edit().putBoolean(shownKey, true).apply()

                Handler(Looper.getMainLooper()).postDelayed({
                    showSourceDialog(activity)
                }, 500)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "first-run dialog failed", t)
            }
            result
        }
    }

    private fun showSourceDialog(ctx: Context) {
        val sources = arrayOf("迅雷网盘", "夸克网盘", "123云盘", "GitHub", "QQ群")
        AlertDialog.Builder(ctx)
            .setTitle("你从哪里下载的模块")
            .setSingleChoiceItems(sources, -1) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0, 1, 4 -> showPaidResultDialog(ctx)   // 迅雷/夸克/QQ群 → 问是否付费
                    else -> return@setSingleChoiceItems     // 123云盘/GitHub → 关闭
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun showPaidResultDialog(ctx: Context) {
        val items = arrayOf("付费了😡😡", "没有😍😍")
        AlertDialog.Builder(ctx)
            .setTitle("你付费了吗")
            .setSingleChoiceItems(items, -1) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> showScammedDialog(ctx)     // 付费了
                    1 -> showMockDialog(ctx)        // 没有
                }
            }
            .setCancelable(false)
            .show()
    }

    /** 付费了 → 提醒举报 */
    private fun showScammedDialog(ctx: Context) {
        val isZhCN = ctx.resources.configuration.locales[0].let {
            it.language == "zh" && it.country == "CN"
        }
        val msg = if (isZhCN) {
            "这个老牧师的商家，太可恶了，火速举报！！😡😡😡😡\n\n（123云盘关掉付费弹窗可以不付费下载😍）"
        } else {
            "这个商家太可恶了，火速举报！！😡😡😡😡\n\n（123云盘关掉付费弹窗可以不付费下载😍）"
        }
        AlertDialog.Builder(ctx)
            .setTitle("😡😡😡")
            .setMessage(msg)
            .setPositiveButton("我马上去") { _, _ -> }
            .setNeutralButton("打开真正作者主页") { _, _ ->
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://space.bilibili.com/660595349")))
            }
            .setCancelable(false)
            .show()
    }

    /** 没有 → 调侃 */
    private fun showMockDialog(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle("🤣🤣🤣")
            .setMessage("你知道吗：你看到的那个视频的作者已经捞到了5块钱了🤣🤣🤣")
            .setPositiveButton("我超级生气😡") { _, _ -> }
            .setNeutralButton("打开真正作者主页") { _, _ ->
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://space.bilibili.com/660595349")))
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 向模块自身的 StatusProvider 上报激活状态。
     * 在目标应用进程中运行（说明模块已被 LSPosed 启用），Provider 会拉起
     * 模块进程并写入时间戳标记；模块未启用时不会上报，标记过期后主页显示未激活。
     * 异步执行，避免拉起模块进程阻塞目标应用主线程。
     */
    private fun reportActivation() {
        Thread {
            try {
                val app = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication").invoke(null) as? Context
                    ?: return@Thread
                app.contentResolver.call(
                    Uri.parse("content://${StatusProvider.AUTHORITY}"), "report", null, null)
                log(Log.INFO, TAG, "activation reported to module provider")
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "report activation failed", t)
            }
        }.start()
    }

    /**
     * 版本更新检查：每次打开壁纸引擎时异步请求服务器。
     * 流程：version → log/logen → url → wever → must → 弹窗（一并展示模块与 WE 更新）。
     */
    private fun hookUpdateCheck(cl: ClassLoader) {
        val browseClass = cl.loadClass("io.wallpaperengine.weclient.BrowseActivity")
        val onCreateMethod = browseClass.getDeclaredMethod("onCreate", Bundle::class.java)
            .apply { isAccessible = true }
        val ignoredKey = "we_soundfix_update_ignored_version"

        hook(onCreateMethod).intercept { chain ->
            val result = chain.proceed()
            try {
                val activity = chain.thisObject as? Context ?: return@intercept result
                val prefs = obtainPrefs() ?: return@intercept result
                Thread {
                    try {
                        val currentVersion = 4 // versionCode，与 build.gradle.kts 一致
                        val BASE = "https://project.nxdyy.cn/WallpaperEngineSoundFix/"

                        // 1) 获取服务器模块版本，已安装 >= 服务器版本则不检查
                        val serverVersion = httpGet(BASE + "version")?.toIntOrNull() ?: return@Thread
                        if (serverVersion <= currentVersion) return@Thread
                        val ignoredVersion = prefs.getInt(ignoredKey, 0)
                        if (serverVersion <= ignoredVersion) return@Thread

                        // 2) 更新日志（按语言选择端点：简体中文用 /log，其他用 /logen）
                        val isZhCN = activity.resources.configuration.locales[0].let {
                            it.language == "zh" && it.country == "CN"
                        }
                        val changelog = httpGet(BASE + if (isZhCN) "log" else "logen") ?: ""

                        // 3) 更新地址
                        val updateUrl = httpGet(BASE + "url")

                        // 4) Wallpaper Engine 版本检查
                        var weOutdated = false
                        try {
                            val serverWeVer = httpGet(BASE + "wever")?.toIntOrNull()
                            if (serverWeVer != null) {
                                val pkgInfo = activity.packageManager.getPackageInfo(
                                    "io.wallpaperengine.weclient", 0)
                                val installedWeVer = pkgInfo.longVersionCode.toInt()
                                weOutdated = installedWeVer < serverWeVer
                            }
                        } catch (_: Throwable) {}

                        // 5) 必要/非必要更新标记（0 = 非必要，1 = 必要/默认）
                        val mustStr = httpGet(BASE + "must")
                        val nonEssential = mustStr == "0"

                        // 6) 弹窗（集成模式下可能存在独立+内嵌两个模块实例重复 hook，按时间戳去重）
                        if (!markUpdateDialogShown(prefs)) return@Thread
                        Handler(Looper.getMainLooper()).post {
                            try {
                                showUpdateDialog(
                                    activity, serverVersion, changelog, updateUrl,
                                    weOutdated, nonEssential, prefs, ignoredKey)
                            } catch (t: Throwable) {
                                log(Log.WARN, TAG, "show update dialog failed", t)
                            }
                        }
                    } catch (t: Throwable) {
                        log(Log.WARN, TAG, "update check failed", t)
                    }
                }.start()
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "update check hook failed", t)
            }
            result
        }
    }

    /** GET 请求，返回 body 字符串（trimmed），失败返回 null。 */
    private fun httpGet(url: String): String? = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText().trim() else null
    } catch (_: Throwable) {
        null
    }

    /**
     * 更新弹窗去重标记。
     * 集成模式（npatch）下同一进程可能同时加载独立模块与内嵌模块两个实例，
     * 各自 hook 同一方法并异步弹窗。两实例共享同一应用 SharedPreferences，
     * 用时间戳在去重窗口内只允许弹一次。
     */
    private fun markUpdateDialogShown(prefs: SharedPreferences): Boolean =
        synchronized(prefs) {
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(UPDATE_DIALOG_TS_KEY, 0L) < UPDATE_DIALOG_DEDUP_MS) {
                false
            } else {
                prefs.edit().putLong(UPDATE_DIALOG_TS_KEY, now).apply()
                true
            }
        }

    /** 根据设备语言获取更新弹窗 i18n 文本。 */
    private fun getUpdateI18N(ctx: Context): Map<String, String> {
        val lang = ctx.resources.configuration.locales[0].language
        val region = ctx.resources.configuration.locales[0].country
        val key = if (lang == "zh" && region == "TW") "zh-rTW" else lang
        return UPDATE_I18N[key] ?: UPDATE_I18N["en"]!!
    }

    private fun showUpdateDialog(
        ctx: Context, serverVersion: Int, changelog: String, updateUrl: String?,
        weOutdated: Boolean, nonEssential: Boolean,
        prefs: SharedPreferences, ignoredKey: String
    ) {
        val i18n = getUpdateI18N(ctx)
        val versionStr = if (serverVersion > 100) "${serverVersion / 100}.${serverVersion % 100}" else "v$serverVersion"
        val msg = buildString {
            append(i18n["version"]!!.format(versionStr))
            if (changelog.isNotBlank()) {
                append("\n\n")
                append(i18n["changelog_header"])
                append("\n")
                append(changelog)
            }
            if (weOutdated) {
                append("\n\n")
                append(i18n["we_outdated"])
            }
            if (nonEssential) {
                append("\n\n")
                append(i18n["non_essential"])
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle(i18n["title"])
            .setMessage(msg)
            .setPositiveButton(i18n["update"]) { _, _ ->
                val url = updateUrl ?: "https://github.com/nxdyy/WallpaperEngineSoundFix/releases"
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .setNegativeButton(i18n["close"]) { _, _ -> }
            .setNeutralButton(i18n["ignore"]) { _, _ ->
                prefs.edit().putInt(ignoredKey, serverVersion).apply()
            }
            .setCancelable(false)
            .show()
    }

    /** Hook MediaPlayer.setVolume：静音调用 (0,0) → 用户音量；并跟踪实例。 */
    private fun hookMediaPlayerVolume() {
        val m = MediaPlayer::class.java.getDeclaredMethod("setVolume",
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        hook(m).intercept { chain ->
            val mp = chain.thisObject as? MediaPlayer
            // native Bridge 的播放器直接放行（音量由 native SetVolume 指定）
            if (mp != null && SoundBridge.isBridgePlayer(mp)) {
                return@intercept chain.proceed()
            }
            mp?.let { tracked.add(it) }
            val left = chain.getArg(0) as? Float ?: 0f
            val right = chain.getArg(1) as? Float ?: 0f
            if (left == 0f && right == 0f) {
                val v = targetVolume()
                if (v > 0f) {
                    return@intercept chain.proceed(arrayOf(v, v))
                }
            }
            chain.proceed()
        }
    }

    /**
     * Hook SceneLib.initLibrary：在原始方法 **之前** 安装 native 虚表补丁。
     *
     * 关键：必须先 patch 再 proceed，否则 initLibrary 加载 libscenejni.so 后
     * 立即创建 Main/RenderContext/Sound 对象，此时虚表还是空壳 stub。
     */
    private fun hookSceneLibInit(cl: ClassLoader) {
        val sceneLibClass = Class.forName("io.wallpaperengine.wrapper.SceneLib", false, cl)
        val m = sceneLibClass.getDeclaredMethod("initLibrary", Context::class.java)
        hook(m).intercept { chain ->
            // 先加载模块 native 库并安装虚表补丁（native 内部会解析内存中的 libscenejni.so）
            if (!nativeInstalled) {
                try {
                    val ctx = chain.getArg(0) as? Context
                    System.load(resolveModuleNativeLib(ctx))
                    val ok = SoundFixNative.install(SoundBridge)
                    nativeInstalled = ok
                    log(Log.INFO, TAG, "native sound engine install: $ok")
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "native install failed", t)
                }
            }
            // 再调用原方法（加载 libscenejni.so + 初始化场景，此时虚表已补丁）
            chain.proceed()
        }
    }

    /**
     * 定位模块的 libsoundfix.so：
     * 1) 常规 LSPosed：模块独立安装，nativeLibraryDir 有效
     * 2) npatch 等集成模式：模块 APK 未解压安装，nativeLibraryDir 为 null，
     *    从模块 APK (sourceDir) 内提取 lib/arm64-v8a/libsoundfix.so 到目标应用缓存目录
     */
    private fun resolveModuleNativeLib(ctx: Context?): String {
        val modInfo = moduleApplicationInfo
        // 1) 直接已解压的路径
        modInfo.nativeLibraryDir?.let { dir ->
            val f = java.io.File(dir, "libsoundfix.so")
            if (f.exists()) return f.absolutePath
        }
        // 2) 从模块 APK 提取
        val apkPath = modInfo.sourceDir
            ?: throw IllegalStateException("module sourceDir unavailable")
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val appCtx = ctx ?: run {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as Context
        }
        val outFile = java.io.File(appCtx.cacheDir, "libsoundfix.so")
        java.util.zip.ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry("lib/$abi/libsoundfix.so")
                ?: zip.getEntry("lib/arm64-v8a/libsoundfix.so")
                ?: throw IllegalStateException("libsoundfix.so not found in module APK ($apkPath)")
            zip.getInputStream(entry).use { input ->
                java.io.FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        log(Log.INFO, TAG, "extracted libsoundfix.so to ${outFile.absolutePath}")
        return outFile.absolutePath
    }

    /** Hook 设置页 onCreatePreferences：向 General 分类注入音量滑条。 */
    private fun hookSettingsFragment(cl: ClassLoader) {
        val fragClass = cl.loadClass("io.wallpaperengine.weclient.GeneralSettingsActivity\$SettingsFragment")
        val m = fragClass.getDeclaredMethod("onCreatePreferences", Bundle::class.java, String::class.java)
        hook(m).intercept { chain ->
            val result = chain.proceed()
            try {
                injectVolumePreference(chain.thisObject, cl)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "inject volume preference failed", t)
            }
            result
        }
    }

    /** Hook GLWallpaperEngine.reloadApplyGeneralSettings：设置应用后刷新音量。 */
    private fun hookSettingsReload(cl: ClassLoader) {
        val engineClass = cl.loadClass("io.wallpaperengine.weclient.WEWallpaperService\$GLWallpaperEngine")
        val m = engineClass.getDeclaredMethod("reloadApplyGeneralSettings")
        hook(m).intercept { chain ->
            val result = chain.proceed()
            try {
                applyVolumeToTracked()
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "reapply volume failed", t)
            }
            result
        }
    }

    /** 程序化创建 SeekBarPreference 并加入第一个分类（General）。 */
    private fun injectVolumePreference(fragment: Any?, cl: ClassLoader) {
        if (fragment == null) return
        // 防止重复注入
        val findPreference = fragment.javaClass.getMethod("findPreference", CharSequence::class.java)
        if (findPreference.invoke(fragment, PREF_KEY) != null) return

        val screen = fragment.javaClass.getMethod("getPreferenceScreen").invoke(fragment) ?: return
        val count = screen.javaClass.getMethod("getPreferenceCount").invoke(screen) as Int
        if (count < 1) return
        val generalCategory = screen.javaClass.getMethod("getPreference", Int::class.javaPrimitiveType).invoke(screen, 0) ?: return

        val context = fragment.javaClass.getMethod("requireContext").invoke(fragment) as Context
        val prefClass = cl.loadClass("androidx.preference.SeekBarPreference")
        val pref = prefClass.getConstructor(Context::class.java).newInstance(context)

        val lang = context.resources.configuration.locales[0].language
        val region = context.resources.configuration.locales[0].country
        val key = if (lang == "zh" && region == "TW") "zh-rTW" else lang
        val (title, summary) = PREF_I18N[key] ?: PREF_I18N["en"]!!

        pref.javaClass.getMethod("setKey", String::class.java).invoke(pref, PREF_KEY)
        pref.javaClass.getMethod("setTitle", CharSequence::class.java).invoke(pref, title)
        pref.javaClass.getMethod("setSummary", CharSequence::class.java).invoke(pref, summary)
        pref.javaClass.getMethod("setIconSpaceReserved", Boolean::class.javaPrimitiveType).invoke(pref, false)
        pref.javaClass.getMethod("setMin", Int::class.javaPrimitiveType).invoke(pref, 0)
        pref.javaClass.getMethod("setMax", Int::class.javaPrimitiveType).invoke(pref, 100)
        pref.javaClass.getMethod("setShowSeekBarValue", Boolean::class.javaPrimitiveType).invoke(pref, true)
        pref.javaClass.getMethod("setDefaultValue", Any::class.java).invoke(pref, DEFAULT_VOLUME)

        val prefBaseClass = cl.loadClass("androidx.preference.Preference")
        generalCategory.javaClass.getMethod("addPreference", prefBaseClass).invoke(generalCategory, pref)
        log(Log.INFO, TAG, "volume preference injected")
    }

    /** 用户音量 (0.0 ~ 1.0)。 */
    private fun targetVolume(): Float =
        (readVolumePercent() / 100f)

    private fun readVolumePercent(): Int =
        ((obtainPrefs()?.getInt(PREF_KEY, DEFAULT_VOLUME) ?: DEFAULT_VOLUME).coerceIn(0, 100))

    /** 对所有被跟踪的 MediaPlayer 重新应用音量。 */
    private fun applyVolumeToTracked() {
        val v = targetVolume()
        for (mp in tracked.toTypedArray()) {
            try {
                mp.setVolume(v, v)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "apply volume to player failed", t)
            }
        }
        log(Log.INFO, TAG, "volume reapplied: $v")
    }

    /** 懒加载应用默认 SharedPreferences 并注册变化监听（修改即时生效）。 */
    private fun obtainPrefs(): SharedPreferences? {
        prefs?.let { return it }
        return try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? Context
            app?.getSharedPreferences("${app.packageName}_preferences", Context.MODE_PRIVATE)?.also { p ->
                p.registerOnSharedPreferenceChangeListener { _, key ->
                    if (key == PREF_KEY) {
                        SoundBridge.volumeFactor = targetVolume()
                        applyVolumeToTracked()
                    }
                }
                prefs = p
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "obtain prefs failed", t)
            null
        }
    }

    companion object {
        private const val TAG = "WESoundFix"
        private const val TARGET_PACKAGE = "io.wallpaperengine.weclient"
        private val TARGET_PACKAGES = setOf(TARGET_PACKAGE, "io.wallpaperengine.nxdyy")
        private const val PREF_KEY = "general_volume"
        private const val DEFAULT_VOLUME = 100
        private const val UPDATE_DIALOG_TS_KEY = "we_soundfix_update_dialog_ts"
        private const val UPDATE_DIALOG_DEDUP_MS = 30_000L

        /** 注入壁纸引擎设置的音量滑条标题和摘要，按设备语言匹配。 */
        private val PREF_I18N = mapOf(
            "zh-rTW" to ("桌布音量" to "修復桌布無聲：控制影片桌布播放音量"),
            "zh"     to ("壁纸音量" to "修复壁纸无声：控制视频壁纸播放音量"),
            "ja"     to ("壁紙音量" to "壁紙の音声を修正：動画壁紙の音量を制御"),
            "ko"     to ("배경화면 음량" to "무음 배경화면 수정: 비디오 배경화면 음량 제어"),
            "fr"     to ("Volume du fond d'écran" to "Corriger les fonds d'écran muets : contrôler le volume vidéo"),
            "de"     to ("Hintergrund-Lautstärke" to "Stumme Hintergründe beheben: Video-Hintergrund-Lautstärke steuern"),
            "es"     to ("Volumen de fondo" to "Corregir fondos silenciosos: controlar el volumen de vídeo"),
            "pt"     to ("Volume do papel de parede" to "Corrigir papéis de parede mudos: controlar o volume de vídeo"),
            "ru"     to ("Громкость обоев" to "Исправить бесшумные обои: управление громкостью видео"),
            "it"     to ("Volume sfondo" to "Correggi sfondi silenziosi: controlla il volume video"),
            "pl"     to ("Głośność tapety" to "Napraw ciche tapety: kontroluj głośność wideo"),
            "nl"     to ("Achtergrondvolume" to "Stille achtergronden oplossen: video-achtergrondvolume regelen"),
            "sv"     to ("Bakgrundsvolym" to "Fixa tysta bakgrunder: kontrollera videobakgrundsvolym"),
            "da"     to ("Baggrundsvolumen" to "Fix stumme baggrunde: kontroller video-baggrundsvolumen"),
            "nb"     to ("Bakgrunnsvolum" to "Fiks stille bakgrunner: kontroller videobakgrunnsvolum"),
            "fi"     to ("Taustan äänenvoimakkuus" to "Korjaa hiljaiset taustat: hallitse videotakustan äänenvoimakkuutta"),
            "cs"     to ("Hlasitost pozadí" to "Opravit tichá pozadí: ovládat hlasitost videa"),
            "sk"     to ("Hlasitosť pozadia" to "Opraviť tiché pozadia: ovládať hlasitosť videa"),
            "hu"     to ("Háttér hangereje" to "Némított háttérképek javítása: videó-háttér hangerejének vezérlése"),
            "ro"     to ("Volum fundal" to "Repară fundaluri silențioase: controlează volumul video"),
            "tr"     to ("Arka plan ses seviyesi" to "Sessiz duvar kağıtlarını düzelt: video ses seviyesini kontrol et"),
            "el"     to ("Ένταση φόντου" to "Διόρθωση σιωπηρών φόντων: έλεγχος έντασης βίντεο"),
            "bg"     to ("Сила на звука на тапета" to "Поправка на безшумни фонове: контрол на силата на видеото"),
            "uk"     to ("Гучність шпалер" to "Виправити безшумні шпалери: керування гучністю відео"),
            "ar"     to ("صورة الخلفية" to "إصلاح خلفيات صامتة: التحكم في مستوى صوت الفيديو"),
            "he"     to ("עוצמת שמע רקע" to "תקן רקעים שקטים: שליטה בעוצמת וידאו"),
            "fa"     to ("بلندی صدای پس‌زمینه" to "اصلاح پس‌زمینه‌های بی‌صدا: کنترل بلندی صدای ویدیو"),
            "id"     to ("Volume latar belakang" to "Perbaiki wallpaper sunyi: kontrol volume video"),
            "th"     to ("ระดับเสียงวอลเปเปอร์" to "แก้ไขวอลเปเปอร์เงียบ: ควบคุมระดับเสียงวิดีโอ"),
            "vi"     to ("Âm lượng hình nền" to "Sửa hình nền im lặng: điều khiển âm lượng video"),
            "eu"     to ("Hondoaren bolumena" to "Konpondu isil-hondoak: kontrolatu bideoaren bolumena"),
            "sl"     to ("Glasnost ozadja" to "Popravi tiha ozadja: upravljaj glasnost videa"),
            "lt"     to ("Fono garsumas" to "Pataisyti tylius fonus: valdyti vaizdo garsumą"),
            "be"     to ("Гучнасць шпалер" to "Выпраўці бясшумныя шпалеры: кіраванне гучнасцю відэа"),
            "en"     to ("Wallpaper volume" to "Fix silent wallpapers: control video wallpaper volume"),
        )

        /** 更新弹窗 i18n，keys: title, version, changelog_header, update, close, ignore, we_outdated, non_essential */
        private val UPDATE_I18N: Map<String, Map<String, String>> = mapOf(
            "zh" to mapOf(
                "title" to "存在新版本！",
                "version" to "版本：%s",
                "changelog_header" to "更新日志：",
                "update" to "更新",
                "close" to "关闭",
                "ignore" to "忽略此版本",
                "we_outdated" to "Wallpaper Engine 不是最新版本，建议同步更新",
                "non_essential" to "非必要更新",
            ),
            "zh-rTW" to mapOf(
                "title" to "存在新版本！",
                "version" to "版本：%s",
                "changelog_header" to "更新日誌：",
                "update" to "更新",
                "close" to "關閉",
                "ignore" to "忽略此版本",
                "we_outdated" to "Wallpaper Engine 不是最新版本，建議同步更新",
                "non_essential" to "非必要更新",
            ),
            "en" to mapOf(
                "title" to "New version available!",
                "version" to "Version: %s",
                "changelog_header" to "Changelog:",
                "update" to "Update",
                "close" to "Close",
                "ignore" to "Ignore this version",
                "we_outdated" to "Wallpaper Engine is not up to date, update recommended",
                "non_essential" to "Non-essential update",
            ),
            "ja" to mapOf(
                "title" to "新しいバージョンがあります！",
                "version" to "バージョン：%s",
                "changelog_header" to "変更履歴：",
                "update" to "更新",
                "close" to "閉じる",
                "ignore" to "このバージョンを無視",
                "we_outdated" to "Wallpaper Engine が最新ではありません。更新を推奨します",
                "non_essential" to "必須ではない更新",
            ),
            "ko" to mapOf(
                "title" to "새 버전이 있습니다!",
                "version" to "버전: %s",
                "changelog_header" to "변경 로그:",
                "update" to "업데이트",
                "close" to "닫기",
                "ignore" to "이 버전 무시",
                "we_outdated" to "Wallpaper Engine이 최신 버전이 아닙니다. 업데이트 권장",
                "non_essential" to "필수 업데이트 아님",
            ),
            "fr" to mapOf(
                "title" to "Nouvelle version disponible !",
                "version" to "Version : %s",
                "changelog_header" to "Journal des modifications :",
                "update" to "Mettre à jour",
                "close" to "Fermer",
                "ignore" to "Ignorer cette version",
                "we_outdated" to "Wallpaper Engine n'est pas à jour, mise à jour recommandée",
                "non_essential" to "Mise à jour non essentielle",
            ),
            "de" to mapOf(
                "title" to "Neue Version verfügbar!",
                "version" to "Version: %s",
                "changelog_header" to "Änderungsprotokoll:",
                "update" to "Aktualisieren",
                "close" to "Schließen",
                "ignore" to "Diese Version ignorieren",
                "we_outdated" to "Wallpaper Engine ist nicht aktuell, Aktualisierung empfohlen",
                "non_essential" to "Nicht erforderliches Update",
            ),
            "es" to mapOf(
                "title" to "¡Nueva versión disponible!",
                "version" to "Versión: %s",
                "changelog_header" to "Registro de cambios:",
                "update" to "Actualizar",
                "close" to "Cerrar",
                "ignore" to "Ignorar esta versión",
                "we_outdated" to "Wallpaper Engine no está actualizado, se recomienda actualizar",
                "non_essential" to "Actualización no esencial",
            ),
            "pt" to mapOf(
                "title" to "Nova versão disponível!",
                "version" to "Versão: %s",
                "changelog_header" to "Registro de alterações:",
                "update" to "Atualizar",
                "close" to "Fechar",
                "ignore" to "Ignorar esta versão",
                "we_outdated" to "Wallpaper Engine não está atualizado, atualização recomendada",
                "non_essential" to "Atualização não essencial",
            ),
            "ru" to mapOf(
                "title" to "Доступна новая версия!",
                "version" to "Версия: %s",
                "changelog_header" to "Журнал изменений:",
                "update" to "Обновить",
                "close" to "Закрыть",
                "ignore" to "Игнорировать эту версию",
                "we_outdated" to "Wallpaper Engine не обновлён, рекомендуется обновить",
                "non_essential" to "Необязательное обновление",
            ),
            "it" to mapOf(
                "title" to "Nuova versione disponibile!",
                "version" to "Versione: %s",
                "changelog_header" to "Registro modifiche:",
                "update" to "Aggiorna",
                "close" to "Chiudi",
                "ignore" to "Ignora questa versione",
                "we_outdated" to "Wallpaper Engine non è aggiornato, aggiornamento consigliato",
                "non_essential" to "Aggiornamento non essenziale",
            ),
            "pl" to mapOf(
                "title" to "Dostępna nowa wersja!",
                "version" to "Wersja: %s",
                "changelog_header" to "Dziennik zmian:",
                "update" to "Aktualizuj",
                "close" to "Zamknij",
                "ignore" to "Zignoruj tę wersję",
                "we_outdated" to "Wallpaper Engine nie jest aktualny, zalecana aktualizacja",
                "non_essential" to "Aktualizacja nie jest wymagana",
            ),
            "nl" to mapOf(
                "title" to "Nieuwe versie beschikbaar!",
                "version" to "Versie: %s",
                "changelog_header" to "Wijzigingslogboek:",
                "update" to "Bijwerken",
                "close" to "Sluiten",
                "ignore" to "Negeer deze versie",
                "we_outdated" to "Wallpaper Engine is niet actueel, update aanbevolen",
                "non_essential" to "Niet-essentiële update",
            ),
            "sv" to mapOf(
                "title" to "Ny version tillgänglig!",
                "version" to "Version: %s",
                "changelog_header" to "Ändringslogg:",
                "update" to "Uppdatera",
                "close" to "Stäng",
                "ignore" to "Ignorera denna version",
                "we_outdated" to "Wallpaper Engine är inte uppdaterad, uppdatering rekommenderas",
                "non_essential" to "Icke-väsentlig uppdatering",
            ),
            "da" to mapOf(
                "title" to "Ny version tilgængelig!",
                "version" to "Version: %s",
                "changelog_header" to "Ændringslog:",
                "update" to "Opdater",
                "close" to "Luk",
                "ignore" to "Ignorer denne version",
                "we_outdated" to "Wallpaper Engine er ikke opdateret, opdatering anbefales",
                "non_essential" to "Ikke-væsentlig opdatering",
            ),
            "nb" to mapOf(
                "title" to "Ny versjon tilgjengelig!",
                "version" to "Versjon: %s",
                "changelog_header" to "Endringslogg:",
                "update" to "Oppdater",
                "close" to "Lukk",
                "ignore" to "Ignorer denne versjonen",
                "we_outdated" to "Wallpaper Engine er ikke oppdatert, oppdatering anbefales",
                "non_essential" to "Ikke-nødvendig oppdatering",
            ),
            "fi" to mapOf(
                "title" to "Uusi versio saatavilla!",
                "version" to "Versio: %s",
                "changelog_header" to "Muutosloki:",
                "update" to "Päivitä",
                "close" to "Sulje",
                "ignore" to "Ohita tämä versio",
                "we_outdated" to "Wallpaper Engine ei ole ajan tasalla, päivitys suositellaan",
                "non_essential" to "Ei-välttämätön päivitys",
            ),
            "cs" to mapOf(
                "title" to "K dispozici nová verze!",
                "version" to "Verze: %s",
                "changelog_header" to "Protokol změn:",
                "update" to "Aktualizovat",
                "close" to "Zavřít",
                "ignore" to "Ignorovat tuto verzi",
                "we_outdated" to "Wallpaper Engine není aktuální, doporučujeme aktualizovat",
                "non_essential" to "Nevyžadovaná aktualizace",
            ),
            "sk" to mapOf(
                "title" to "K dispozícii nová verzia!",
                "version" to "Verzia: %s",
                "changelog_header" to "Protokol zmien:",
                "update" to "Aktualizovať",
                "close" to "Zavrieť",
                "ignore" to "Ignorovať túto verziu",
                "we_outdated" to "Wallpaper Engine nie je aktuálny, odporúča sa aktualizácia",
                "non_essential" to "Nepovinná aktualizácia",
            ),
            "hu" to mapOf(
                "title" to "Új verzió érhető el!",
                "version" to "Verzió: %s",
                "changelog_header" to "Változásnapló:",
                "update" to "Frissítés",
                "close" to "Bezárás",
                "ignore" to "Verzió figyelmen kívül hagyása",
                "we_outdated" to "A Wallpaper Engine nem naprakész, frissítés ajánlott",
                "non_essential" to "Nem kötelező frissítés",
            ),
            "ro" to mapOf(
                "title" to "Versiune nouă disponibilă!",
                "version" to "Versiune: %s",
                "changelog_header" to "Jurnal modificări:",
                "update" to "Actualizare",
                "close" to "Închide",
                "ignore" to "Ignoră această versiune",
                "we_outdated" to "Wallpaper Engine nu este la zi, actualizare recomandată",
                "non_essential" to "Actualizare neesențială",
            ),
            "tr" to mapOf(
                "title" to "Yeni sürüm mevcut!",
                "version" to "Sürüm: %s",
                "changelog_header" to "Değişiklik günlüğü:",
                "update" to "Güncelle",
                "close" to "Kapat",
                "ignore" to "Bu sürümü yoksay",
                "we_outdated" to "Wallpaper Engine güncel değil, güncelleme önerilir",
                "non_essential" to "Zorunlu olmayan güncelleme",
            ),
            "el" to mapOf(
                "title" to "Νέα έκδοση διαθέσιμη!",
                "version" to "Έκδοση: %s",
                "changelog_header" to "Αρχείο καταγραφής:",
                "update" to "Ενημέρωση",
                "close" to "Κλείσιμο",
                "ignore" to "Αγνόηση αυτής της έκδοσης",
                "we_outdated" to "Το Wallpaper Engine δεν είναι ενημερωμένο, συνιστάται ενημέρωση",
                "non_essential" to "Μη απαραίτητη ενημέρωση",
            ),
            "bg" to mapOf(
                "title" to "Налична е нова версия!",
                "version" to "Версия: %s",
                "changelog_header" to "Дневник на промените:",
                "update" to "Актуализиране",
                "close" to "Затвори",
                "ignore" to "Игнорирай тази версия",
                "we_outdated" to "Wallpaper Engine не е актуализиран, препоръчва се актуализация",
                "non_essential" to "Незадължителна актуализация",
            ),
            "uk" to mapOf(
                "title" to "Доступна нова версія!",
                "version" to "Версія: %s",
                "changelog_header" to "Журнал змін:",
                "update" to "Оновити",
                "close" to "Закрити",
                "ignore" to "Ігнорувати цю версію",
                "we_outdated" to "Wallpaper Engine не оновлено, рекомендується оновити",
                "non_essential" to "Необов'язкове оновлення",
            ),
            "ar" to mapOf(
                "title" to "يوجد إصدار جديد!",
                "version" to "الإصدار: %s",
                "changelog_header" to "سجل التغييرات:",
                "update" to "تحديث",
                "close" to "إغلاق",
                "ignore" to "تجاهل هذا الإصدار",
                "we_outdated" to "Wallpaper Engine غير محدّث، يُوصى بالتحديث",
                "non_essential" to "تحديث غير ضروري",
            ),
            "he" to mapOf(
                "title" to "גרסה חדשה זמינה!",
                "version" to "גרסה: %s",
                "changelog_header" to "יומן שינויים:",
                "update" to "עדכון",
                "close" to "סגור",
                "ignore" to "התעלם מגרסה זו",
                "we_outdated" to "Wallpaper Engine אינו מעודכן, מומלץ לעדכן",
                "non_essential" to "עדכון לא הכרחי",
            ),
            "fa" to mapOf(
                "title" to "نسخه جدید موجود است!",
                "version" to "نسخه: %s",
                "changelog_header" to "گزارش تغییرات:",
                "update" to "بروزرسانی",
                "close" to "بستن",
                "ignore" to "نادیده گرفتن این نسخه",
                "we_outdated" to "Wallpaper Engine به‌روز نیست، به‌روزرسانی توصیه می‌شود",
                "non_essential" to "به‌روزرسانی غیرضروری",
            ),
            "id" to mapOf(
                "title" to "Versi baru tersedia!",
                "version" to "Versi: %s",
                "changelog_header" to "Catatan perubahan:",
                "update" to "Perbarui",
                "close" to "Tutup",
                "ignore" to "Abaikan versi ini",
                "we_outdated" to "Wallpaper Engine belum diperbarui, disarankan untuk memperbarui",
                "non_essential" to "Pembaruan tidak penting",
            ),
            "th" to mapOf(
                "title" to "มีเวอร์ชันใหม่!",
                "version" to "เวอร์ชัน: %s",
                "changelog_header" to "บันทึกการเปลี่ยนแปลง:",
                "update" to "อัปเดต",
                "close" to "ปิด",
                "ignore" to "เพิกเฉยเวอร์ชันนี้",
                "we_outdated" to "Wallpaper Engine ไม่ใช่เวอร์ชันล่าสุด แนะนำให้อัปเดต",
                "non_essential" to "การอัปเดตที่ไม่จำเป็น",
            ),
            "vi" to mapOf(
                "title" to "Có phiên bản mới!",
                "version" to "Phiên bản: %s",
                "changelog_header" to "Nhật ký thay đổi:",
                "update" to "Cập nhật",
                "close" to "Đóng",
                "ignore" to "Bỏ qua phiên bản này",
                "we_outdated" to "Wallpaper Engine chưa cập nhật, khuyến nghị cập nhật",
                "non_essential" to "Cập nhật không bắt buộc",
            ),
            "eu" to mapOf(
                "title" to "Bertsu berria eskuragarri!",
                "version" to "Bertsioa: %s",
                "changelog_header" to "Aldaketa egunkaria:",
                "update" to "Eguneratu",
                "close" to "Itxi",
                "ignore" to "Ezikusi bertsio hau",
                "we_outdated" to "Wallpaper Engine ez dago eguneratuta, eguneratzea gomendatzen da",
                "non_essential" to "Ez-beharrezko eguneraketa",
            ),
            "sl" to mapOf(
                "title" to "Na voljo je nova različica!",
                "version" to "Različica: %s",
                "changelog_header" to "Dnevnik sprememb:",
                "update" to "Posodobi",
                "close" to "Zapri",
                "ignore" to "Prezri to različico",
                "we_outdated" to "Wallpaper Engine ni posodobljen, priporočljivo je posodobiti",
                "non_essential" to "Nenujna posodobitev",
            ),
            "lt" to mapOf(
                "title" to "Yra nauja versija!",
                "version" to "Versija: %s",
                "changelog_header" to "Pakeitimų žurnalas:",
                "update" to "Atnaujinti",
                "close" to "Uždaryti",
                "ignore" to "Ignoruoti šią versiją",
                "we_outdated" to "Wallpaper Engine nėra naujausios versijos, rekomenduojama atnaujinti",
                "non_essential" to "Nebūtinas atnaujinimas",
            ),
            "be" to mapOf(
                "title" to "Даступна новая версія!",
                "version" to "Версія: %s",
                "changelog_header" to "Часопіс зменаў:",
                "update" to "Абнавіць",
                "close" to "Закрыць",
                "ignore" to "Ігнараваць гэтую версію",
                "we_outdated" to "Wallpaper Engine не абноўлены, рэкамендуецца абнавіць",
                "non_essential" to "Неабавязковае абнаўленне",
            ),
        )

        /** native 引擎是否已安装（防重复）。 */
        @Volatile
        private var nativeInstalled = false

        /** 被模块管理音量的 MediaPlayer 实例（弱引用，避免泄漏）。 */
        private val tracked: MutableSet<MediaPlayer> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        /** 场景壁纸视频元素（SupportVideoPlayer）实例，由 native 经 JNI 创建（弱引用）。 */
        private val trackedVideoPlayers: MutableSet<Any> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        /** 壁纸暂停时由模块暂停的视频元素，恢复时仅重启这些（弱引用）。 */
        private val globallyPausedVideos: MutableSet<Any> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        /** SupportVideoPlayer 反射方法缓存（hookSupportVideoPlayers 中初始化）。 */
        private var videoIsPlayingMethod: Method? = null
        private var videoPauseMethod: Method? = null
        private var videoPlayMethod: Method? = null

        @Volatile
        private var prefs: SharedPreferences? = null
    }
}
