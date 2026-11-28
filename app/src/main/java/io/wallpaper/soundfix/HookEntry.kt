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
import android.widget.Toast
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

    /** 当前浏览页实例，电脑端传输的网页壁纸自动导入后用来刷新列表。 */
    @Volatile
    private var browseActivityRef: java.lang.ref.WeakReference<android.app.Activity>? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        try {
            log(Log.DEBUG, TAG, "module apk: ${moduleApplicationInfo.sourceDir}")
        } catch (_: Throwable) {
        }
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

        // 连接状态条显示下载进度（壁纸从电脑传输到手机时）
        try {
            hookDownloadProgress(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook download progress failed", t)
        }

        // 连接电脑时上报的设备名加 [WEP] 前缀
        try {
            hookDeviceName(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook device name failed", t)
        }

        // 长按“分享文件”：网页壁纸打包为 ZIP，并绕开目标应用的 FileProvider 冲突
        try {
            hookShareWallpaper(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook wallpaper share failed", t)
        }

        // 网页壁纸的详情页数据
        try {
            hookWebWallpaperDetails(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook web wallpaper details failed", t)
        }

        // 壁纸类型角标（传输完成后自动判定并缓存）
        try {
            hookWallpaperTypeBadges(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook wallpaper type badge failed", t)
        }

        // 网页壁纸列表缩略图的 GIF 动画
        try {
            hookGifPreviews()
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook gif previews failed", t)
        }

        // mpkg 场景壁纸传输/导入完成后的声音风险提示
        try {
            hookMpkgImportWarning(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook mpkg import warning failed", t)
        }

        // WEP_ 前缀文件与“等待下载”占位名不一致时补删占位，避免重复壁纸
        try {
            hookWepPendingCleanup(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook wep pending cleanup failed", t)
        }

        // 壁纸自定义页面文案汉化
        try {
            hookPropertyLocalization(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook property localization failed", t)
        }

        // 网页壁纸渲染（系统 WebView + VirtualDisplay）
        try {
            hookWebWallpaper(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook web wallpaper failed", t)
        }

        // 网页壁纸列表项、缩略图与预览页
        try {
            hookWebWallpaperList(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook web wallpaper list failed", t)
        }

        // 壁纸引擎设置中的网页壁纸选项（帧率限制、渲染倍率）
        try {
            hookWebWallpaperSettings(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook web wallpaper settings failed", t)
        }

        // “添加”页面的导入网页壁纸（ZIP）
        try {
            hookWebWallpaperImport(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook web wallpaper import failed", t)
        }

        // 底栏切换页面后保持标题的 PLUS 标识
        try {
            hookNavBranding(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook nav branding failed", t)
        }
    }

    /**
     * 网页壁纸支持：
     * - onSurfaceCreated / loadWallpaper 命中网页壁纸时，用 VirtualDisplay + WebView 接管渲染
     * - 未命中时完全走原版逻辑
     * - onSurfaceDestroyed / onDestroy 释放 WebView 与 VirtualDisplay
     */
    private fun hookWebWallpaper(cl: ClassLoader) {
        val engineClass = cl.loadClass("io.wallpaperengine.weclient.WEWallpaperService\$GLWallpaperEngine")

        val surfaceCreated = engineClass.getDeclaredMethod(
            "onSurfaceCreated", android.view.SurfaceHolder::class.java)
        hook(surfaceCreated).intercept { chain ->
            try {
                val engine = chain.thisObject
                val ctx = engineContext(engine)
                val holder = chain.getArg(0) as? android.view.SurfaceHolder
                val dir = WebWallpaper.pickWallpaper(ctx ?: return@intercept chain.proceed(), null)
                if (dir != null && ctx != null && holder != null) {
                    val frame = holder.surfaceFrame
                    var w = frame.width()
                    var h = frame.height()
                    if (w <= 0 || h <= 0) {
                        w = ctx.resources.displayMetrics.widthPixels
                        h = ctx.resources.displayMetrics.heightPixels
                    }
                    if (WebWallpaper.start(ctx, dir, holder.surface, w, h, engine)) {
                        stopSceneAudio()
                        enableWallpaperTouch(engine)
                        log(Log.INFO, TAG, "web wallpaper takeover on surfaceCreated")
                        return@intercept null
                    }
                }
                WebWallpaper.stop(engine)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "web wallpaper surface hook failed", t)
                WebWallpaper.stop(chain.thisObject)
            }
            chain.proceed()
        }

        val loadWallpaper = engineClass.getDeclaredMethod(
            "loadWallpaper", String::class.java)
        hook(loadWallpaper).intercept { chain ->
            try {
                val engine = chain.thisObject
                val ctx = engineContext(engine)
                val path = chain.getArg(0) as? String
                val dir = WebWallpaper.pickWallpaper(ctx ?: return@intercept chain.proceed(), path)
                if (dir != null && ctx != null) {
                    val holder = engineHolder(engine)
                    val surface = holder?.surface
                    if (surface != null && holder != null && surface.isValid) {
                        val frame = holder.surfaceFrame
                        var w = frame.width()
                        var h = frame.height()
                        if (w <= 0 || h <= 0) {
                            w = ctx.resources.displayMetrics.widthPixels
                            h = ctx.resources.displayMetrics.heightPixels
                        }
                        if (WebWallpaper.start(ctx, dir, surface, w, h, engine)) {
                            stopSceneAudio()
                            enableWallpaperTouch(engine)
                            log(Log.INFO, TAG, "web wallpaper takeover on loadWallpaper")
                            return@intercept null
                        }
                    }
                }
                if (dir == null) WebWallpaper.stop(engine)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "web wallpaper load hook failed", t)
                WebWallpaper.stop(chain.thisObject)
            }
            chain.proceed()
        }

        // 触摸事件转发给网页，网页壁纸才能响应手指移动/点击
        val touchEvent = engineClass.getDeclaredMethod(
            "onTouchEvent", android.view.MotionEvent::class.java)
        hook(touchEvent).intercept { chain ->
            try {
                val ev = chain.getArg(0) as? android.view.MotionEvent
                if (ev != null && WebWallpaper.dispatchTouch(chain.thisObject, ev)) {
                    return@intercept null
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "web touch dispatch failed", t)
            }
            chain.proceed()
        }

        val surfaceDestroyed = engineClass.getDeclaredMethod(
            "onSurfaceDestroyed", android.view.SurfaceHolder::class.java)
        hook(surfaceDestroyed).intercept { chain ->
            WebWallpaper.stop(chain.thisObject)
            chain.proceed()
        }

        val destroy = engineClass.getDeclaredMethod("onDestroy")
        hook(destroy).intercept { chain ->
            WebWallpaper.stop(chain.thisObject)
            chain.proceed()
        }

        log(Log.INFO, TAG, "web wallpaper hook installed")
    }

    /** 网页壁纸：列表条目、缩略图与预览页。 */
    private fun hookWebWallpaperList(cl: ClassLoader) {
        val browseClass = cl.loadClass("io.wallpaperengine.weclient.BrowseActivity")
        val onCreate = browseClass.getDeclaredMethod("onCreate", Bundle::class.java)
        hook(onCreate).intercept { chain ->
            val result = chain.proceed()
            try {
                injectWebWallpapers(chain.thisObject, cl)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "inject web wallpapers failed", t)
            }
            try {
                applyPlusBranding(chain.thisObject as? android.app.Activity)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "apply plus branding failed", t)
            }
            result
        }

        // 返回主页时 ActionBar 标题可能被系统重置，重新应用一次
        val browseOnResume = browseClass.getDeclaredMethod("onResume")
        hook(browseOnResume).intercept { chain ->
            val result = chain.proceed()
            try {
                applyPlusBranding(chain.thisObject as? android.app.Activity)
            } catch (_: Throwable) {
            }
            result
        }

        // 列表缩略图：从壁纸目录的 preview/default 图片解码
        val utilCompanion = cl.loadClass("io.wallpaperengine.weutil.Util\$Companion")
        val sceneLibClass = cl.loadClass("io.wallpaperengine.wrapper.SceneLib")
        val sparseClass = cl.loadClass("io.wallpaperengine.weutil.WallpaperInfoSparse")
        val previewBitmap = utilCompanion.getDeclaredMethod(
            "getWallpaperPreviewBitmap", Context::class.java, sceneLibClass, sparseClass)
        hook(previewBitmap).intercept { chain ->
            try {
                val ctx = chain.getArg(0) as? Context
                val item = chain.getArg(2)
                val path = item?.javaClass?.getMethod("getFilePath")?.invoke(item) as? String
                val bmp = if (ctx != null && path != null) {
                    WebWallpaper.previewBitmap(ctx, path)
                } else null
                if (bmp != null) return@intercept bmp
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "web preview bitmap hook failed", t)
            }
            chain.proceed()
        }

        // 预览页：把原来的 Video/Scene 视图替换为网页预览
        val previewClass = cl.loadClass("io.wallpaperengine.weclient.PreviewActivity")
        val previewOnCreate = previewClass.getDeclaredMethod("onCreate", Bundle::class.java)
        hook(previewOnCreate).intercept { chain ->
            val result = chain.proceed()
            try {
                val activity = chain.thisObject as? android.app.Activity
                if (activity != null && !activity.isFinishing) {
                    val selected = activity.javaClass.getMethod("getSelectedWallpaper")
                        .invoke(activity)
                    val path = selected?.javaClass?.getMethod("getFilePath")
                        ?.invoke(selected) as? String
                    val dir = WebWallpaper.webDir(activity, path)
                    if (dir != null) {
                        val field = activity.javaClass
                            .getDeclaredField("wallpaperContainer")
                            .apply { isAccessible = true }
                        val container = field.get(activity) as? android.view.ViewGroup
                        if (container != null) {
                            // 原版要等 Scene/Video 加载完成才会把容器设为可见，
                            // 网页壁纸不会触发该回调，这里直接切换界面状态。
                            container.visibility = android.view.View.VISIBLE
                            WebWallpaper.addPreview(activity, container, dir)
                            // 把 project.json 的自定义选项填进预览页的属性抽屉
                            WebWallpaper.addPreviewProperties(activity, dir)
                            try {
                                activity.javaClass.getDeclaredField("hasLoadedWallpaper")
                                    .apply { isAccessible = true }
                                    .setBoolean(activity, true)
                            } catch (_: Throwable) {
                            }
                            hideViewField(activity, "loadingPanel")
                            hideViewField(activity, "backgroundImage")
                        }
                    }
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "web preview hook failed", t)
            }
            result
        }

        val previewOnDestroy = previewClass.getDeclaredMethod("onDestroy")
        hook(previewOnDestroy).intercept { chain ->
            WebWallpaper.stopPreview()
            chain.proceed()
        }

        // 离开/回到预览页时暂停或恢复网页预览，避免退出后声音还在播放
        val previewOnPause = previewClass.getDeclaredMethod("onPause")
        hook(previewOnPause).intercept { chain ->
            val result = chain.proceed()
            try {
                WebWallpaper.pausePreview()
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "pause web preview failed", t)
            }
            result
        }

        val previewOnResume = previewClass.getDeclaredMethod("onResume")
        hook(previewOnResume).intercept { chain ->
            val result = chain.proceed()
            try {
                WebWallpaper.resumePreview()
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "resume web preview failed", t)
            }
            result
        }

        log(Log.INFO, TAG, "web wallpaper list hook installed")
    }

    /**
     * 在壁纸引擎的“常规设置”页面添加网页壁纸选项：
     * - 网页壁纸帧率限制（15/20/24/30/60 fps）
     * - 网页壁纸渲染倍率（100%/75%/50%）
     */
    private fun hookWebWallpaperSettings(cl: ClassLoader) {
        val fragmentClass = cl.loadClass(
            "io.wallpaperengine.weclient.GeneralSettingsActivity\$SettingsFragment")
        val onCreatePreferences = fragmentClass.getDeclaredMethod(
            "onCreatePreferences", Bundle::class.java, String::class.java)
        hook(onCreatePreferences).intercept { chain ->
            val result = chain.proceed()
            try {
                addWebWallpaperPreferences(chain.thisObject, cl)
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)
                    ?.targetException ?: t
                log(Log.WARN, TAG,
                    "add web wallpaper preferences failed: ${cause.javaClass.name}: ${cause.message}",
                    cause)
            }
            result
        }
        log(Log.INFO, TAG, "web wallpaper settings hook installed")
    }

    // ------------------------------------------------------------ 网页壁纸导入

    private val REQ_IMPORT_WEB_WALLPAPER = 0x5A31
    private val TAG_IMPORT_BUTTON = "wesf_web_import_button"

    /**
     * 在“添加”页面增加“导入网页壁纸”按钮，
     * 并接收系统文件选择器的结果。
     */
    private fun hookWebWallpaperImport(cl: ClassLoader) {
        val fragmentClass = cl.loadClass(
            "io.wallpaperengine.weclient.ui.importfile.ImportFileFragment")
        val onCreateView = fragmentClass.getMethod(
            "onCreateView",
            android.view.LayoutInflater::class.java,
            android.view.ViewGroup::class.java,
            Bundle::class.java)
        hook(onCreateView).intercept { chain ->
            val result = chain.proceed()
            try {
                addWebImportButton(chain.thisObject, result as? android.view.View)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "add web import button failed", t)
            }
            result
        }

        // BrowseActivity 没有重写 onActivityResult，结果由 FragmentActivity 分发
        val activityClass = cl.loadClass("androidx.fragment.app.FragmentActivity")
        val onActivityResult = activityClass.getDeclaredMethod(
            "onActivityResult",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Intent::class.java)
        hook(onActivityResult).intercept { chain ->
            val result = chain.proceed()
            try {
                handleWebImportResult(
                    chain.thisObject,
                    chain.getArg(0) as? Int ?: -1,
                    chain.getArg(1) as? Int ?: 0,
                    chain.getArg(2) as? Intent,
                    cl)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "handle web import result failed", t)
            }
            result
        }
        log(Log.INFO, TAG, "web wallpaper import hook installed")
    }

    private fun addWebImportButton(fragment: Any?, view: android.view.View?) {
        if (fragment == null || view == null) return
        if (view.findViewWithTag<android.view.View>(TAG_IMPORT_BUTTON) != null) return
        val ctx = view.context
        val res = ctx.resources
        val pkg = ctx.packageName
        val localId = res.getIdentifier("btImportLocally", "id", pkg)
        if (localId == 0) return
        val local = view.findViewById<android.view.View>(localId) ?: return
        val parent = local.parent as? android.widget.LinearLayout ?: return

        val button = android.widget.Button(ctx)
        button.text = "导入网页壁纸"
        button.isAllCaps = false
        button.tag = TAG_IMPORT_BUTTON
        // 复制“导入文件”按钮的外观，保持界面风格一致
        try {
            button.background =
                local.background?.constantState?.newDrawable(res, ctx.theme)?.mutate()
            button.backgroundTintList = local.backgroundTintList
            button.stateListAnimator = local.stateListAnimator
            button.minHeight = local.minimumHeight
            button.minWidth = local.minimumWidth
            button.setPadding(
                local.paddingLeft, local.paddingTop, local.paddingRight, local.paddingBottom)
            if (local is android.widget.TextView) {
                button.setTextColor(local.textColors)
                button.typeface = local.typeface
                button.gravity = local.gravity
                button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, local.textSize)
            }
        } catch (_: Throwable) {
        }
        val lp = local.layoutParams
        if (lp != null) button.layoutParams = android.widget.LinearLayout.LayoutParams(lp)

        // 放在“本地导入”及其说明文字之后
        var index = parent.indexOfChild(local) + 1
        val hintId = res.getIdentifier("tvImportFileHint", "id", pkg)
        if (hintId != 0) {
            val hint = view.findViewById<android.view.View>(hintId)
            if (hint != null && hint.parent === parent) {
                index = parent.indexOfChild(hint) + 1
            }
        }
        parent.addView(button, index)
        button.setOnClickListener {
            try {
                val activity = fragment.javaClass.getMethod("requireActivity")
                    .invoke(fragment) as? android.app.Activity ?: return@setOnClickListener
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream"))
                }
                activity.startActivityForResult(intent, REQ_IMPORT_WEB_WALLPAPER)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "open zip picker failed", t)
            }
        }
    }

    private fun handleWebImportResult(
        activity: Any?,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        cl: ClassLoader
    ) {
        if (requestCode != REQ_IMPORT_WEB_WALLPAPER) return
        val act = activity as? android.app.Activity ?: return
        if (act.javaClass.name != "io.wallpaperengine.weclient.BrowseActivity") return
        if (resultCode != android.app.Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return

        Thread {
            try {
                val name = WebWallpaper.importZip(act, uri)
                log(Log.INFO, TAG, "web wallpaper imported: $name")
                act.runOnUiThread {
                    Toast.makeText(act, "网页壁纸导入成功", Toast.LENGTH_SHORT).show()
                    // 重建界面而不是在运行中修改列表，避免异步缩略图与条目错位
                    try {
                        act.recreate()
                    } catch (t: Throwable) {
                        log(Log.WARN, TAG, "recreate browse activity failed", t)
                    }
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "import web wallpaper failed", t)
                val message = t.message ?: "导入失败"
                act.runOnUiThread {
                    Toast.makeText(act, message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun addWebWallpaperPreferences(fragment: Any?, cl: ClassLoader) {
        if (fragment == null) return
        val ctx = fragment.javaClass.getMethod("getContext").invoke(fragment) as? Context ?: return
        val screen = fragment.javaClass.getMethod("getPreferenceScreen").invoke(fragment) ?: return

        val prefClass = cl.loadClass("androidx.preference.Preference")
        val seekClass = cl.loadClass("androidx.preference.SeekBarPreference")
        val groupClass = cl.loadClass("androidx.preference.PreferenceGroup")
        val listenerClass = cl.loadClass("androidx.preference.Preference\$OnPreferenceChangeListener")
        val addPreference = groupClass.getMethod("addPreference", prefClass)
        val findPreference = groupClass.getMethod("findPreference", CharSequence::class.java)

        // general_fps 位于“常规”分组内，需要加到它的父分组里才能紧跟其后
        val anchor = try {
            findPreference.invoke(screen, "general_fps")
        } catch (_: Throwable) {
            null
        }
        val target: Any = if (anchor != null) {
            try {
                prefClass.getMethod("getParent").invoke(anchor) ?: screen
            } catch (_: Throwable) {
                screen
            }
        } else {
            screen
        }

        val fpsPref = addWebSeekPreference(
            target, ctx, prefClass, seekClass, listenerClass, findPreference, addPreference,
            key = WebWallpaper.KEY_FPS,
            title = "网页壁纸帧率限制",
            min = 10, max = 60, step = 5, defValue = 20,
            cl = cl)

        val scalePref = addWebSeekPreference(
            target, ctx, prefClass, seekClass, listenerClass, findPreference, addPreference,
            key = WebWallpaper.KEY_RENDER_SCALE,
            title = "网页壁纸渲染精度（％）",
            min = 50, max = 100, step = 25, defValue = 100,
            cl = cl)

        // 常规分组最后：清除旧版本随机域名累积的浏览器数据
        addWebClearPreference(
            target, ctx, cl, prefClass, findPreference, addPreference, fragment)

        // Preference 按 order 排序（Collections.sort），
        // 把“FPS 限制”之后的条目整体后移，给两个新条目腾出 order 1、2。
        try {
            val setOrder = prefClass.getMethod("setOrder", Int::class.javaPrimitiveType)
            val getOrder = prefClass.getMethod("getOrder")
            val getKey = prefClass.getMethod("getKey")
            val count = groupClass.getMethod("getPreferenceCount").invoke(target) as Int
            for (i in 0 until count) {
                val item = groupClass.getMethod(
                    "getPreference", Int::class.javaPrimitiveType).invoke(target, i)
                val key = getKey.invoke(item)
                if (key == WebWallpaper.KEY_FPS || key == WebWallpaper.KEY_RENDER_SCALE) continue
                val order = getOrder.invoke(item) as Int
                if (order >= 1) setOrder.invoke(item, order + 2)
            }
            setOrder.invoke(fpsPref, 1)
            setOrder.invoke(scalePref, 2)
            try {
                groupClass.getMethod("notifyHierarchyChanged").invoke(target)
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "reorder web wallpaper preferences failed", t)
        }
    }

    private fun addWebSeekPreference(
        screen: Any,
        ctx: Context,
        prefClass: Class<*>,
        seekClass: Class<*>,
        listenerClass: Class<*>,
        findPreference: java.lang.reflect.Method,
        addPreference: java.lang.reflect.Method,
        key: String,
        title: String,
        min: Int,
        max: Int,
        step: Int,
        defValue: Int,
        cl: ClassLoader
    ): Any? {
        // 页面重建时避免重复添加
        if (findPreference.invoke(screen, key) != null) return null

        // 旧版本 ListPreference 存的是字符串，SeekBarPreference 读 int 会类型转换崩溃
        migratePreferenceToInt(ctx, key)

        val pref = seekClass.getConstructor(Context::class.java).newInstance(ctx)
        seekClass.getMethod("setKey", String::class.java).invoke(pref, key)
        prefClass.getMethod("setTitle", CharSequence::class.java).invoke(pref, title)
        seekClass.getMethod("setMin", Int::class.javaPrimitiveType).invoke(pref, min)
        seekClass.getMethod("setMax", Int::class.javaPrimitiveType).invoke(pref, max)
        try {
            seekClass.getMethod("setSeekBarIncrement", Int::class.javaPrimitiveType)
                .invoke(pref, step)
        } catch (_: Throwable) {
        }
        try {
            seekClass.getMethod("setShowSeekBarValue", Boolean::class.javaPrimitiveType)
                .invoke(pref, true)
        } catch (_: Throwable) {
        }
        // 去掉条目左侧的图标占位空白
        try {
            prefClass.getMethod("setIconSpaceReserved", Boolean::class.javaPrimitiveType)
                .invoke(pref, false)
        } catch (_: Throwable) {
        }
        val current = readDefaultPreferenceInt(ctx, key, defValue).coerceIn(min, max)
        seekClass.getMethod("setValue", Int::class.javaPrimitiveType).invoke(pref, current)

        // 调整后重载壁纸，让新设置立即生效
        val listener = java.lang.reflect.Proxy.newProxyInstance(
            cl, arrayOf(listenerClass)
        ) { _, method, args ->
            when (method.name) {
                "onPreferenceChange" -> {
                    try {
                        WebWallpaper.scheduleApplySettings()
                    } catch (_: Throwable) {
                    }
                    true
                }
                "hashCode" -> System.identityHashCode(method)
                "equals" -> false
                "toString" -> "WebWallpaperPreferenceListener"
                else -> null
            }
        }
        seekClass.getMethod("setOnPreferenceChangeListener", listenerClass)
            .invoke(pref, listener)

        addPreference.invoke(screen, pref)
        log(Log.INFO, TAG, "web wallpaper preference added: $key")
        return pref
    }

    /** 在常规分组末尾添加“清除网页壁纸数据”条目。 */
    private fun addWebClearPreference(
        target: Any,
        ctx: Context,
        cl: ClassLoader,
        prefClass: Class<*>,
        findPreference: java.lang.reflect.Method,
        addPreference: java.lang.reflect.Method,
        fragment: Any
    ): Any? {
        val key = "web_wallpaper_clear_data"
        if (findPreference.invoke(target, key) != null) return null
        val pref = prefClass.getConstructor(Context::class.java).newInstance(ctx)
        prefClass.getMethod("setKey", String::class.java).invoke(pref, key)
        prefClass.getMethod("setTitle", CharSequence::class.java)
            .invoke(pref, "清除网页壁纸数据")
        prefClass.getMethod("setSummary", CharSequence::class.java)
            .invoke(pref, "删除网页壁纸保存的设置、Cookie 和浏览器缓存")
        try {
            prefClass.getMethod("setIconSpaceReserved", Boolean::class.javaPrimitiveType)
                .invoke(pref, false)
        } catch (_: Throwable) {
        }
        try {
            val clickClass = cl.loadClass(
                "androidx.preference.Preference\$OnPreferenceClickListener")
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                cl, arrayOf(clickClass)
            ) { _, method, _ ->
                when (method.name) {
                    "onPreferenceClick" -> {
                        showClearWebDataDialog(fragment)
                        true
                    }
                    "hashCode" -> System.identityHashCode(method)
                    "equals" -> false
                    "toString" -> "WebWallpaperClearListener"
                    else -> null
                }
            }
            prefClass.getMethod("setOnPreferenceClickListener", clickClass)
                .invoke(pref, listener)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "set clear data click listener failed", t)
        }
        addPreference.invoke(target, pref)
        try {
            // 大 order 保证排在常规分组最后（后续腾位逻辑会再加 2）
            prefClass.getMethod("setOrder", Int::class.javaPrimitiveType).invoke(pref, 9999)
        } catch (_: Throwable) {
        }
        log(Log.INFO, TAG, "web wallpaper preference added: $key")
        return pref
    }

    private fun showClearWebDataDialog(fragment: Any) {
        val activity = try {
            fragment.javaClass.getMethod("requireActivity").invoke(fragment)
                as? android.app.Activity
        } catch (_: Throwable) {
            null
        } ?: return
        try {
            AlertDialog.Builder(activity)
                .setTitle("清除网页壁纸数据")
                .setMessage("将删除所有网页壁纸保存的设置（localStorage）、Cookie 和浏览器缓存，" +
                    "清除后壁纸会重新加载。")
                .setPositiveButton("清除") { _, _ ->
                    try {
                        WebWallpaper.clearWebData(activity)
                        Toast.makeText(activity, "已清除网页壁纸数据", Toast.LENGTH_SHORT).show()
                    } catch (t: Throwable) {
                        log(Log.WARN, TAG, "clear web wallpaper data failed", t)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "show clear data dialog failed", t)
        }
    }

    private fun readDefaultPreferenceInt(ctx: Context, key: String, def: Int): Int {
        val sp = defaultSharedPreferences(ctx) ?: return def
        return when (val v = sp.all[key]) {
            is Int -> v
            is Long -> v.toInt()
            is String -> v.toIntOrNull() ?: def
            else -> def
        }
    }

    /** 把旧版本保存的字符串值迁移为整数。 */
    private fun migratePreferenceToInt(ctx: Context, key: String) {
        try {
            val sp = defaultSharedPreferences(ctx) ?: return
            val v = sp.all[key]
            if (v is String) {
                val n = v.toIntOrNull()
                val editor = sp.edit()
                if (n != null) editor.putInt(key, n) else editor.remove(key)
                editor.apply()
            }
        } catch (_: Throwable) {
        }
    }

    private fun defaultSharedPreferences(ctx: Context): android.content.SharedPreferences? {
        return try {
            val pm = Class.forName("androidx.preference.PreferenceManager")
            pm.getMethod("getDefaultSharedPreferences", Context::class.java)
                .invoke(null, ctx) as android.content.SharedPreferences
        } catch (_: Throwable) {
            try {
                ctx.getSharedPreferences(
                    ctx.packageName + "_preferences", Context.MODE_PRIVATE)
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** 把 files/webwallpapers 下的网页壁纸加入浏览列表。 */
    private fun injectWebWallpapers(activity: Any?, cl: ClassLoader) {
        val ctx = activity as? Context ?: return
        val entries = WebWallpaper.entries(ctx)

        val vm = activity.javaClass.getMethod("getWallpaperViewModel").invoke(activity)
        val live = vm.javaClass.getMethod("getWallpapers").invoke(vm) ?: return
        val list = live.javaClass.getMethod("getValue").invoke(live) as? MutableList<Any> ?: return
        val infoClass = cl.loadClass("io.wallpaperengine.weutil.WallpaperInfoSparse")
        val getPath = infoClass.getMethod("getFilePath")

        // 清掉目录已被删除的网页壁纸条目
        var removed = false
        val existing = HashSet<String>()
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val path = try {
                getPath.invoke(item) as? String
            } catch (_: Throwable) {
                null
            } ?: continue
            if (path.startsWith("webwallpapers/")) {
                val dir = java.io.File(ctx.filesDir, path)
                if (!dir.isDirectory) {
                    iterator.remove()
                    removed = true
                    log(Log.INFO, TAG, "stale web wallpaper removed from list: $path")
                    continue
                }
            }
            existing.add(path)
        }

        var added = false
        for (e in entries) {
            val path = "webwallpapers/${e.name}"
            if (existing.contains(path)) continue
            val item = infoClass.getConstructor().newInstance()
            infoClass.getMethod("setTitle", String::class.java).invoke(item, e.title)
            infoClass.getMethod("setFilePath", String::class.java).invoke(item, path)
            infoClass.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(item, 100)
            infoClass.getMethod("setType", String::class.java).invoke(item, "Web")
            infoClass.getMethod("setLastModifiedDate", Long::class.javaPrimitiveType)
                .invoke(item, e.lastModified)
            infoClass.getMethod("setLocal", Boolean::class.javaPrimitiveType).invoke(item, false)
            infoClass.getMethod("setOfficial", Boolean::class.javaPrimitiveType).invoke(item, false)
            infoClass.getMethod("setError", Boolean::class.javaPrimitiveType).invoke(item, false)
            list.add(item)
            added = true
            log(Log.INFO, TAG, "web wallpaper added to list: ${e.title}")
        }
        if (!added && !removed) return

        try {
            val ktClass = cl.loadClass("io.wallpaperengine.weclient.ui.WallpaperViewModelKt")
            val notify = ktClass.getDeclaredMethod(
                "notifyObserver", cl.loadClass("androidx.lifecycle.MutableLiveData"))
            notify.invoke(null, live)
        } catch (t: Throwable) {
            try {
                live.javaClass.getMethod("postValue", Any::class.java).invoke(live, list)
            } catch (_: Throwable) {
            }
        }
    }

    private fun engineContext(engine: Any?): Context? {
        if (engine == null) return null
        try {
            val f = engine.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }
            (f.get(engine) as? Context)?.let { return it }
        } catch (_: Throwable) {
        }
        return try {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 已经启用触摸的 Engine。setTouchEventsEnabled 会同步触发
     * updateSurface → onSurfaceCreated，如果不加标记会无限递归。
     */
    private val touchEnabledEngines = java.util.WeakHashMap<Any, Boolean>()

    /** 让壁纸服务接收触摸事件（原版只在设置里开启触摸输入时才启用）。 */
    private fun enableWallpaperTouch(engine: Any?) {
        if (engine == null) return
        if (touchEnabledEngines[engine] == true) return
        touchEnabledEngines[engine] = true
        try {
            engine.javaClass
                .getMethod("setTouchEventsEnabled", Boolean::class.javaPrimitiveType)
                .invoke(engine, true)
            log(Log.INFO, TAG, "web wallpaper touch enabled")
        } catch (t: Throwable) {
            touchEnabledEngines.remove(engine)
            log(Log.WARN, TAG, "enable wallpaper touch failed", t)
        }
    }

    /** 隐藏 PreviewActivity 中指定的 View 字段（封面图、加载圈等）。 */
    private fun hideViewField(activity: android.app.Activity, name: String) {
        try {
            val f = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }
            (f.get(activity) as? android.view.View)?.visibility = android.view.View.GONE
        } catch (_: Throwable) {
        }
    }

    private fun engineHolder(engine: Any?): android.view.SurfaceHolder? {
        if (engine == null) return null
        return try {
            val f = engine.javaClass.getDeclaredField("holder").apply { isAccessible = true }
            f.get(engine) as? android.view.SurfaceHolder
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 在壁纸引擎 ActionBar 的标题与副标题后追加醒目的 PLUS 徽章。
     * 全部通过反射调用，避免模块与目标应用加载了不同的 AppCompat 类。
     * NavigationUI 切换页面时会重设标题，因此保留已有文本，只追加徽章。
     */
    private fun applyPlusBranding(activity: android.app.Activity?) {
        if (activity == null) return
        try {
            val bar = activity.javaClass.getMethod("getSupportActionBar")
                .invoke(activity) ?: return
            val density = activity.resources.displayMetrics.density
            val setTitle = bar.javaClass.getMethod("setTitle", CharSequence::class.java)
            val setSubtitle = bar.javaClass.getMethod("setSubtitle", CharSequence::class.java)
            val currentTitle = bar.javaClass.getMethod("getTitle")
                .invoke(bar)?.toString().orEmpty()
            if (!currentTitle.endsWith("PLUS")) {
                setTitle.invoke(bar, plusTitle(currentTitle.ifBlank { "Wallpaper Engine" }, density))
            }
            val currentSubtitle = bar.javaClass.getMethod("getSubtitle")
                .invoke(bar)?.toString().orEmpty()
            if (!currentSubtitle.endsWith("PLUS")) {
                setSubtitle.invoke(
                    bar, plusTitle(currentSubtitle.ifBlank { "壁纸引擎" }, density))
            }
        } catch (_: Throwable) {
        }
    }

    /** 底栏切换页面时 NavigationUI 会重设标题，在其后重新追加 PLUS。 */
    private fun hookNavBranding(cl: ClassLoader) {
        try {
            val activityKt = cl.loadClass("androidx.navigation.ui.ActivityKt")
            val appCompat = cl.loadClass("androidx.appcompat.app.AppCompatActivity")
            val navController = cl.loadClass("androidx.navigation.NavController")
            val appBarConfig = cl.loadClass("androidx.navigation.ui.AppBarConfiguration")
            val m = activityKt.getDeclaredMethod(
                "setupActionBarWithNavController",
                appCompat, navController, appBarConfig)
            hook(m).intercept { chain ->
                val result = chain.proceed()
                try {
                    val activity = chain.getArg(0) as? android.app.Activity
                    if (activity?.javaClass?.name ==
                        "io.wallpaperengine.weclient.BrowseActivity") {
                        addBrandingListener(chain.getArg(1), activity)
                    }
                } catch (t: Throwable) {
                    log(Log.WARN, TAG, "add nav branding listener failed", t)
                }
                result
            }
            log(Log.INFO, TAG, "nav branding hook installed")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "hook nav branding failed", t)
        }
    }

    private fun addBrandingListener(navController: Any?, activity: android.app.Activity) {
        if (navController == null) return
        try {
            val loader = navController.javaClass.classLoader ?: return
            val listenerClass = loader.loadClass(
                "androidx.navigation.NavController\$OnDestinationChangedListener")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                loader, arrayOf(listenerClass)
            ) { _, method, _ ->
                when (method.name) {
                    "onDestinationChanged" -> {
                        // 排在 NavigationUI 的监听之后执行，等它先设置好标题
                        activity.window.decorView.post { applyPlusBranding(activity) }
                        null
                    }
                    "hashCode" -> System.identityHashCode(method)
                    "equals" -> false
                    "toString" -> "WESoundFixBrandingListener"
                    else -> null
                }
            }
            navController.javaClass.getMethod(
                "addOnDestinationChangedListener", listenerClass)
                .invoke(navController, proxy)
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "add branding listener failed", t)
        }
    }

    private fun plusTitle(base: String, density: Float): CharSequence {
        val text = "$base PLUS"
        val sp = android.text.SpannableString(text)
        sp.setSpan(
            PlusBadgeSpan(0xFF3D8BFF.toInt(), android.graphics.Color.WHITE, density),
            base.length + 1,
            text.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    /** 圆角背景的 PLUS 徽章，绘制在 ActionBar 标题文字之后。 */
    private class PlusBadgeSpan(
        private val bgColor: Int,
        private val fgColor: Int,
        private val density: Float
    ) : android.text.style.ReplacementSpan() {
        override fun getSize(
            paint: android.graphics.Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: android.graphics.Paint.FontMetricsInt?
        ): Int {
            val oldSize = paint.textSize
            paint.textSize = oldSize * 0.66f
            val w = paint.measureText(text, start, end) + density * 10f
            paint.textSize = oldSize
            return w.toInt()
        }

        override fun draw(
            canvas: android.graphics.Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: android.graphics.Paint
        ) {
            val label = text.subSequence(start, end).toString()
            val oldSize = paint.textSize
            val oldColor = paint.color
            val oldAlign = paint.textAlign
            val badgeSize = oldSize * 0.66f
            paint.textSize = badgeSize
            val textW = paint.measureText(label)
            val padH = density * 5f
            val w = textW + padH * 2f
            val h = badgeSize * 1.5f
            val centerY = (top + bottom) / 2f
            val rect = android.graphics.RectF(x, centerY - h / 2f, x + w, centerY + h / 2f)
            paint.color = bgColor
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
            paint.color = fgColor
            paint.textAlign = android.graphics.Paint.Align.CENTER
            val baseY = centerY - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(label, x + w / 2f, baseY, paint)
            paint.textSize = oldSize
            paint.color = oldColor
            paint.textAlign = oldAlign
        }
    }

    /**
     * 壁纸自定义页面汉化：
     * 所有属性标题和下拉选项都经过 WallpaperProperties.localizeText，
     * 在此把第三方壁纸直接写死的英文（Flip、Audio recording 等）
     * 以及 zh-chs 缺失词条替换为中文。
     */
    private fun hookPropertyLocalization(cl: ClassLoader) {
        val cls = cl.loadClass("io.wallpaperengine.weclient.WallpaperProperties")
        val m = cls.getDeclaredMethod("localizeText", String::class.java)
        hook(m).intercept { chain ->
            val result = chain.proceed()
            try {
                PropertyLocalization.translate(chain.getArg(0) as? String, result as? String ?: "")
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "localize property text failed", t)
                result
            }
        }
        try {
            PropertyLocalization.preload()
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "preload property localizations failed", t)
        }
        log(Log.INFO, TAG, "property localization hook installed")
    }

    /**
     * mpkg 场景壁纸校验：
     * 传输完成和本地导入完成最终都会调用 WallpaperInfoSparse.setFilePath，
     * 在此之后检查扩展名、场景类型与创意工坊信息并弹出提示。
     */
    private fun hookMpkgImportWarning(cl: ClassLoader) {
        val infoClass = cl.loadClass("io.wallpaperengine.weutil.WallpaperInfoSparse")
        val m = infoClass.getDeclaredMethod("setFilePath", String::class.java)
        hook(m).intercept { chain ->
            val result = chain.proceed()
            try {
                SceneImportWarning.onFilePathSet(chain.thisObject, chain.getArg(0) as? String)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "mpkg import check failed", t)
            }
            result
        }
        log(Log.INFO, TAG, "mpkg import warning hook installed")
    }

    /**
     * 电脑端使用 WEP_ 前缀文件名传输时，beginUpload 生成的“等待下载”占位
     * 仍使用原文件名（<id>.mpkg）；transmissionStart 只按 WEP_<id>.mpkg 删除占位，
     * 占位会永远停留在列表里，表现为传输完成后出现两个相同壁纸。
     * 这里在按 WEP_ 路径清理时，把去掉前缀的原路径也清理一次。
     */
    private fun hookWepPendingCleanup(cl: ClassLoader) {
        val browseClass = cl.loadClass("io.wallpaperengine.weclient.BrowseActivity")
        val method = browseClass.getDeclaredMethod(
            "removeWallpapersWithPath", String::class.java)
        method.isAccessible = true
        hook(method).intercept { chain ->
            val result = chain.proceed()
            try {
                val path = chain.getArg(0) as? String
                val prefix = "downloads/WEP_"
                if (path != null && path.length > prefix.length &&
                    path.startsWith(prefix, ignoreCase = true)) {
                    val original = "downloads/" + path.substring(prefix.length)
                    method.invoke(chain.thisObject, original)
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "remove wep pending placeholder failed", t)
            }
            result
        }
        log(Log.INFO, TAG, "wep pending cleanup hook installed")
    }

    /**
     * 壁纸类型角标：
     * - 列表适配器创建时保存 SceneLib 并后台扫描未判定类型的壁纸
     * - 列表项绑定时在封面左上角显示类型
     */
    private fun hookWallpaperTypeBadges(cl: ClassLoader) {
        val adapterClass = cl.loadClass("io.wallpaperengine.weclient.ui.browse.BrowseRecyclerViewAdapter")
        val sceneLibClass = cl.loadClass("io.wallpaperengine.wrapper.SceneLib")

        val ctor = adapterClass.getDeclaredConstructor(
            android.app.Activity::class.java, sceneLibClass, java.util.List::class.java)
        hook(ctor).intercept { chain ->
            val result = chain.proceed()
            try {
                WallpaperTypeBadge.onAdapterCreated(chain.thisObject, chain.getArg(1), chain.getArg(2))
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "scan wallpaper types failed", t)
            }
            result
        }

        val viewHolderClass =
            cl.loadClass("io.wallpaperengine.weclient.ui.browse.BrowseRecyclerViewAdapter\$ViewHolder")
        val bind = adapterClass.getDeclaredMethod(
            "onBindViewHolder", viewHolderClass, Int::class.javaPrimitiveType)
        hook(bind).intercept { chain ->
            val result = chain.proceed()
            try {
                WallpaperTypeBadge.onBind(chain.thisObject, chain.getArg(0), chain.getArg(1) as Int)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "bind wallpaper type badge failed", t)
            }
            result
        }

        log(Log.INFO, TAG, "wallpaper type badge hook installed")
    }

    /**
     * 列表封面收到静态 Bitmap（GIF 第一帧）后，在下一帧替换为 AnimatedImageDrawable，
     * 让网页壁纸的 preview.gif 在列表里正常播放动画。
     * 先让原版完成加载，避免干扰异步回调导致封面错位。
     */
    private fun hookGifPreviews() {
        val method = android.widget.ImageView::class.java.getDeclaredMethod(
            "setImageBitmap", android.graphics.Bitmap::class.java)
        hook(method).intercept { chain ->
            val result = chain.proceed()
            try {
                val view = chain.thisObject as? android.widget.ImageView
                if (view != null) {
                    view.post { GifPreview.applyGif(view) }
                }
            } catch (_: Throwable) {
            }
            result
        }
        log(Log.INFO, TAG, "gif preview hook installed")
    }

    /**
     * 将 BrowseActivity 顶部的连接状态条改为下载进度显示：
     * 文本替换为“正在从…下载… / 标题 / 进度 / 百分比 | 速度”，
     * 背景绿色条按下载比例填充。
     *
     * 事件来源 TransferService.updateNotification(DownloadInfo)，该方法在主线程
     * 被调用，覆盖 transmissionStart / transmissionContinue / transmissionCancel。
     */
    private fun hookDownloadProgress(cl: ClassLoader) {
        val browseClass = cl.loadClass("io.wallpaperengine.weclient.BrowseActivity")

        // 捕获 BrowseActivity 实例与状态条视图
        val onCreate = browseClass.getDeclaredMethod("onCreate", Bundle::class.java)
        hook(onCreate).intercept { chain ->
            val result = chain.proceed()
            try {
                val activity = chain.thisObject as android.app.Activity
                browseActivityRef = java.lang.ref.WeakReference(activity)
                ensureDownloadWatcher(activity)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "init download watcher failed", t)
            }
            try {
                DownloadProgressUi.attach(chain.thisObject)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "attach download progress ui failed", t)
            }
            result
        }

        // 原应用刷新连接状态后重新覆盖为进度显示
        val showInfo = browseClass.getDeclaredMethod("showConnectionInfo")
        hook(showInfo).intercept { chain ->
            val result = chain.proceed()
            try {
                DownloadProgressUi.onConnectionInfoShown(chain.thisObject)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "refresh download progress ui failed", t)
            }
            result
        }

        // 回到前台时若下载仍在进行，重新应用进度显示
        val onResume = browseClass.getDeclaredMethod("onResume")
        hook(onResume).intercept { chain ->
            val result = chain.proceed()
            try {
                DownloadProgressUi.onConnectionInfoShown(chain.thisObject)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "resume download progress ui failed", t)
            }
            result
        }

        // 传输事件
        val downloadInfoClass = cl.loadClass("io.wallpaperengine.wedata.DownloadInfo")
        val transferClass = cl.loadClass("io.wallpaperengine.weclient.TransferService")
        val updateNotification =
            transferClass.getDeclaredMethod("updateNotification", downloadInfoClass)
        hook(updateNotification).intercept { chain ->
            val result = chain.proceed()
            try {
                (chain.thisObject as? Context)?.let { ensureDownloadWatcher(it) }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "ensure download watcher failed", t)
            }
            try {
                DownloadProgressUi.onDownloadInfo(chain.getArg(0))
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "update download progress failed", t)
            }
            try {
                // 文件接收完成时判定类型并写入缓存
                WallpaperTypeBadge.onTransferInfo(chain.getArg(0))
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "detect wallpaper type failed", t)
            }
            result
        }

        log(Log.INFO, TAG, "download progress hook installed")
    }

    /**
     * 启动下载目录监听：电脑端“发送至移动设备”传来的网页壁纸 ZIP
     * 写入 files/downloads 后自动导入，并刷新已打开的壁纸列表。
     */
    private fun ensureDownloadWatcher(ctx: Context) {
        WebWallpaper.startDownloadWatcher(ctx.applicationContext) {
            try {
                browseActivityRef?.get()?.recreate()
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "refresh wallpaper list failed", t)
            }
        }
    }

    /**
     * 连接电脑时上报的设备名加 [WEP] 前缀，方便在电脑端区分装有本模块的客户端。
     * 电脑端“移动设备”列表的大标题来自 TransferService 认证时传入的设备名，
     * 即 Util.getCustomDeviceName()（读取系统 Settings.Global 的 device_name）。
     */
    private fun hookDeviceName(cl: ClassLoader) {
        val cls = cl.loadClass("io.wallpaperengine.weutil.Util\$Companion")
        val method = cls.getDeclaredMethod("getCustomDeviceName", Context::class.java)
        hook(method).intercept { chain ->
            val name = chain.proceed() as? String ?: ""
            if (name.startsWith("[WEP]")) name else "[WEP] $name"
        }
        log(Log.INFO, TAG, "device name branding hook installed")
    }

    // ------------------------------------------------------------ 分享与详情页

    /**
     * 接管长按菜单的“分享文件”：
     * 网页壁纸目录打包成 ZIP 后分享；其余文件优先走 FileProvider，
     * 不可用时（例如与原版应用存在 authority 冲突）写入 MediaStore 兜底，避免崩溃。
     */
    private fun hookShareWallpaper(cl: ClassLoader) {
        try {
            val browseClass = cl.loadClass("io.wallpaperengine.weclient.BrowseActivity")
            val wallClass = cl.loadClass("io.wallpaperengine.weutil.WallpaperInfoSparse")
            val dialogClass = cl.loadClass("androidx.appcompat.app.AlertDialog")
            val method = browseClass.getDeclaredMethod(
                "onWallpaperLongClick\$lambda\$60",
                dialogClass, wallClass, browseClass, android.view.View::class.java)
            hook(method).intercept { chain ->
                try {
                    val dialog = chain.getArg(0) as? android.app.Dialog
                    val wall = chain.getArg(1)
                    val activity = chain.getArg(2) as? android.app.Activity
                    if (activity != null && wall != null) {
                        dialog?.dismiss()
                        shareWallpaper(activity, wall)
                        return@intercept null
                    }
                } catch (t: Throwable) {
                    log(Log.WARN, TAG, "share wallpaper failed", t)
                }
                chain.proceed()
            }
            log(Log.INFO, TAG, "wallpaper share hook installed")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "hook wallpaper share failed", t)
        }
    }

    private fun shareWallpaper(activity: android.app.Activity, wall: Any) {
        val title = try {
            wall.javaClass.getMethod("getTitle").invoke(wall) as? String
        } catch (_: Throwable) {
            null
        } ?: "wallpaper"
        val filePath = try {
            wall.javaClass.getMethod("getFilePath").invoke(wall) as? String
        } catch (_: Throwable) {
            null
        } ?: return
        val isLocal = try {
            wall.javaClass.getMethod("isLocal").invoke(wall) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }

        // 本地导入的壁纸本身就是 content:// URI，直接分享
        if (isLocal && filePath.startsWith("content://")) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_STREAM, Uri.parse(filePath))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, null))
            return
        }

        val src = if (filePath.startsWith("/")) {
            java.io.File(filePath)
        } else {
            java.io.File(activity.filesDir, filePath)
        }
        if (!src.exists()) {
            Toast.makeText(activity, "分享失败：文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        val packaging = src.isDirectory
        Toast.makeText(
            activity,
            if (packaging) "正在打包网页壁纸…" else "正在准备分享…",
            Toast.LENGTH_SHORT
        ).show()

        Thread {
            try {
                val shareFile: java.io.File
                val mime: String
                if (packaging) {
                    val base = sanitizeFileName(title).ifBlank { "webwallpaper" }
                    val zip = java.io.File(activity.cacheDir, "share/$base.zip")
                    zip.parentFile?.mkdirs()
                    zipDirectory(src, zip)
                    shareFile = zip
                    mime = "application/zip"
                } else {
                    shareFile = src
                    mime = shareMimeFor(src.name)
                }
                val uri = fileProviderUri(activity, shareFile)
                    ?: mediaStoreUri(activity, shareFile, mime)
                activity.runOnUiThread {
                    try {
                        if (activity.isFinishing) return@runOnUiThread
                        if (uri == null) {
                            Toast.makeText(
                                activity, "分享失败：无法创建文件 URI", Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_SUBJECT, title)
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        activity.startActivity(Intent.createChooser(intent, null))
                    } catch (t: Throwable) {
                        Toast.makeText(
                            activity, "分享失败：${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (t: Throwable) {
                activity.runOnUiThread {
                    Toast.makeText(
                        activity, "打包失败：${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun zipDirectory(dir: java.io.File, zipFile: java.io.File) {
        java.util.zip.ZipOutputStream(
            java.io.BufferedOutputStream(java.io.FileOutputStream(zipFile))
        ).use { zos ->
            val base = dir.toPath()
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val name = base.relativize(f.toPath()).toString().replace('\\', '/')
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                f.inputStream().use { input -> input.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(64)
    }

    private fun shareMimeFor(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
            "mpkg", "pkg" -> "application/mpkg"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "gif" -> "image/gif"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    /** 优先使用目标进程内的 FileProvider；authority 冲突或缺失时返回 null。 */
    private fun fileProviderUri(activity: android.app.Activity, file: java.io.File): Uri? {
        val authorities = linkedSetOf(activity.packageName, "io.wallpaperengine.weclient")
        for (authority in authorities) {
            try {
                val cls = Class.forName(
                    "androidx.core.content.FileProvider", false, activity.classLoader)
                val method = cls.getMethod(
                    "getUriForFile", Context::class.java, String::class.java, java.io.File::class.java)
                val uri = method.invoke(null, activity, authority, file)
                if (uri is Uri) return uri
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** 兜底分享：写入系统下载目录，返回 MediaStore 的 content:// URI（API 29+）。 */
    private fun mediaStoreUri(
        activity: android.app.Activity,
        file: java.io.File,
        mime: String
    ): Uri? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        return try {
            val resolver = activity.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val output = resolver.openOutputStream(uri) ?: return null
            output.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "media store share failed", t)
            null
        }
    }

    /** 网页壁纸的详情页：补齐大小、类型、分辨率、FPS 等信息。 */
    private fun hookWebWallpaperDetails(cl: ClassLoader) {
        try {
            val cls = cl.loadClass(
                "io.wallpaperengine.weclient.FileDetailsActivity\$DetailsFragment")
            val method = cls.getDeclaredMethod(
                "onCreatePreferences", Bundle::class.java, String::class.java)
            hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    applyWebWallpaperDetails(chain.thisObject)
                } catch (t: Throwable) {
                    log(Log.WARN, TAG, "apply web wallpaper details failed", t)
                }
                result
            }
            log(Log.INFO, TAG, "web wallpaper details hook installed")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "hook web wallpaper details failed", t)
        }
    }

    private fun applyWebWallpaperDetails(fragment: Any?) {
        if (fragment == null) return
        val wall = try {
            fragment.javaClass.getDeclaredField("selectedWallpaper")
                .apply { isAccessible = true }.get(fragment)
        } catch (_: Throwable) {
            null
        } ?: return
        val path = try {
            wall.javaClass.getMethod("getFilePath").invoke(wall) as? String
        } catch (_: Throwable) {
            null
        } ?: return
        val activity = try {
            fragment.javaClass.getMethod("requireActivity").invoke(fragment)
                as? android.app.Activity
        } catch (_: Throwable) {
            null
        } ?: return
        val dir = if (path.startsWith("/")) {
            java.io.File(path)
        } else {
            java.io.File(activity.filesDir, path)
        }
        val project = java.io.File(dir, "project.json")
        if (!dir.isDirectory || !project.isFile) return

        val json = try {
            org.json.JSONObject(project.readText())
        } catch (_: Throwable) {
            null
        }
        val screen = try {
            fragment.javaClass.getMethod("getPreferenceScreen").invoke(fragment)
        } catch (_: Throwable) {
            null
        } ?: return
        val find = try {
            screen.javaClass.getMethod("findPreference", CharSequence::class.java)
        } catch (_: Throwable) {
            return
        }

        fun setSummary(key: String, value: String) {
            try {
                val pref = find.invoke(screen, key) ?: return
                pref.javaClass.getMethod("setSummary", CharSequence::class.java)
                    .invoke(pref, value)
            } catch (_: Throwable) {
            }
        }

        val metrics = activity.resources.displayMetrics
        val title = json?.optString("title", "").orEmpty().ifBlank { dir.name }
        val type = json?.optString("type", "").orEmpty().ifBlank { "web" }
        val entry = json?.optString("file", "").orEmpty().ifBlank { "index.html" }
        val workshopId = json?.optString("workshopid", "").orEmpty()
        setSummary("file_details_filetitle", title)
        setSummary("file_details_filename", "${dir.name}/$entry")
        setSummary("file_details_filesize", formatFileSize(directorySize(dir)))
        setSummary("file_details_workshop_id",
            workshopId.ifBlank { "本地导入" })
        setSummary("file_details_type",
            if (type.equals("web", true)) "网页（Web）" else type)
        setSummary("file_details_resolution",
            "自适应（${metrics.widthPixels} × ${metrics.heightPixels}）")
        setSummary("file_details_fps",
            "${WebWallpaper.fpsLimit(activity)} FPS（模块限制）")
    }

    private fun directorySize(dir: java.io.File): Long {
        var total = 0L
        return try {
            dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
            total
        } catch (_: Throwable) {
            total
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
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

    /**
     * 网页壁纸接管渲染时，停止旧场景壁纸残留的声音（SoundBridge）与视频元素，
     * 避免切换壁纸后场景背景音乐继续播放。
     */
    private fun stopSceneAudio() {
        try {
            SoundBridge.stopAll()
        } catch (_: Throwable) {
        }
        try {
            pauseTrackedVideos()
        } catch (_: Throwable) {
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
                // 网页壁纸：不可见/省电时暂停该 Engine 的 WebView
                WebWallpaper.setPaused(engine, shouldPause)
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
                val firstRun = !prefs.getBoolean(shownKey, false)
                val needChangelog =
                    prefs.getInt(CHANGELOG_VERSION_KEY, 0) < currentModuleVersion()
                if (!firstRun && !needChangelog) return@intercept result
                if (firstRun) prefs.edit().putBoolean(shownKey, true).apply()

                Handler(Looper.getMainLooper()).postDelayed({
                    if (firstRun) {
                        // 下载来源弹窗流程结束后再显示更新日志
                        showSourceDialog(activity) { maybeShowChangelog(activity, prefs) }
                    } else {
                        maybeShowChangelog(activity, prefs)
                    }
                }, 500)
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "first-run dialog failed", t)
            }
            result
        }
    }

    /**
     * 每个版本只显示一次当前版本的更新日志。
     * 请求 logv<当前 versionCode>（例如 versionCode 为 9 时请求 /logv9）。
     */
    private fun maybeShowChangelog(ctx: Context, prefs: SharedPreferences) {
        val current = currentModuleVersion()
        if (prefs.getInt(CHANGELOG_VERSION_KEY, 0) >= current) return
        Thread {
            try {
                val changelog = httpGet(API_BASE + "logv" + current)?.trim()
                if (changelog.isNullOrBlank()) return@Thread
                Handler(Looper.getMainLooper()).post {
                    try {
                        AlertDialog.Builder(ctx)
                            .setTitle("更新日志（v$MODULE_VERSION_NAME）")
                            .setMessage(changelog)
                            .setPositiveButton("确定", null)
                            .setCancelable(false)
                            .show()
                        prefs.edit().putInt(CHANGELOG_VERSION_KEY, current).apply()
                    } catch (t: Throwable) {
                        log(Log.WARN, TAG, "show changelog failed", t)
                    }
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "fetch changelog failed", t)
            }
        }.start()
    }

    private fun showSourceDialog(ctx: Context, onDone: () -> Unit) {
        val sources = arrayOf(
            "pan.nxdyy.cn", "123云盘", "GitHub",
            "夸克网盘", "迅雷网盘", "QQ群", "公众号", "其他"
        )
        AlertDialog.Builder(ctx)
            .setTitle("你从哪里下载的模块")
            .setSingleChoiceItems(sources, -1) { dialog, which ->
                dialog.dismiss()
                // 前三项是作者自己的渠道，直接结束；其他渠道继续询问是否付费
                if (which <= 2) onDone() else showPaidResultDialog(ctx, onDone)
            }
            .setCancelable(false)
            .show()
    }

    private fun showPaidResultDialog(ctx: Context, onDone: () -> Unit) {
        val items = arrayOf("付费了😡😡", "没有😍😍")
        AlertDialog.Builder(ctx)
            .setTitle("你付费了吗")
            .setSingleChoiceItems(items, -1) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    showFreeSoftwareDialog(ctx, onDone)
                } else {
                    showWrongChannelDialog(ctx, onDone)
                }
            }
            .setCancelable(false)
            .show()
    }

    /** 非官方渠道且没有付费：提醒可能下载到了非正规渠道。 */
    private fun showWrongChannelDialog(ctx: Context, onDone: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle("你很有可能不是从正确的渠道下载的本软件")
            .setMessage(
                "每一位创作者都值得被尊重，我并不希望我的软件被盗取，" +
                    "并且被发布到别人的链接上，盗取资源的人还能因此获利。" +
                    "麻烦点个举报感激不尽")
            .setPositiveButton("打开真正作者主页") { _, _ ->
                openAuthorPage(ctx)
                onDone()
            }
            .setCancelable(false)
            .show()
    }

    /** 非官方渠道且已付费：强调软件免费并提供作者主页。 */
    private fun showFreeSoftwareDialog(ctx: Context, onDone: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle("本软件为免费软件！！")
            .setMessage(
                "每一位创作者都值得被尊重，我并不希望我的软件被盗取，" +
                    "并且被发布到别人的链接上，盗取资源的人还能因此获利。" +
                    "麻烦点个举报感激不尽。\n\n" +
                    "本软件在 GitHub、123 云盘以及私有下载站（pan.nxdyy.cn）免费开源。" +
                    "如果你是付费下载的，一定要点举报！")
            .setPositiveButton("打开真正作者主页") { _, _ ->
                openAuthorPage(ctx)
                onDone()
            }
            .setCancelable(false)
            .show()
    }

    private fun openAuthorPage(ctx: Context) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://space.bilibili.com/660595349")))
        } catch (_: Throwable) {
        }
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
     * 只请求 version；发现新版本后再请求 updateinfo 获取版本名、日志、
     * 下载地址、必要/预览标记与要求的 WE 版本。
     */
    private fun hookUpdateCheck(cl: ClassLoader) {
        val browseClass = cl.loadClass("io.wallpaperengine.weclient.BrowseActivity")
        val onCreateMethod = browseClass.getDeclaredMethod("onCreate", Bundle::class.java)
            .apply { isAccessible = true }

        hook(onCreateMethod).intercept { chain ->
            val result = chain.proceed()
            try {
                val activity = chain.thisObject as? Context ?: return@intercept result
                val prefs = obtainPrefs() ?: return@intercept result
                Thread {
                    try {
                        val currentVersion = currentModuleVersion()
                        val ignoredVersion = prefs.getInt(UPDATE_IGNORED_KEY, 0)

                        // 1) 仅请求 version
                        val serverVersion = httpGet(API_BASE + "version")?.toIntOrNull()
                            ?: return@Thread
                        if (serverVersion <= currentVersion) return@Thread
                        if (serverVersion <= ignoredVersion) return@Thread

                        // 2) 有新版本才请求 updateinfo
                        val info = fetchUpdateInfo()
                        val latest = info?.verCode?.takeIf { it > 0 } ?: serverVersion
                        if (latest <= currentVersion || latest <= ignoredVersion) return@Thread

                        val weOutdated = checkWeOutdated(activity, info?.weVer)

                        // 3) 弹窗（集成模式下可能重复 hook，按时间戳去重）
                        if (!markUpdateDialogShown(prefs)) return@Thread
                        Handler(Looper.getMainLooper()).post {
                            try {
                                showUpdateDialog(
                                    activity, latest, info, weOutdated,
                                    prefs, UPDATE_IGNORED_KEY)
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

    /** 服务器返回的更新详情（/updateinfo）。 */
    private data class UpdateInfo(
        val verName: String,
        val verCode: Int,
        val log: String,
        val weVer: String,
        val must: Boolean,
        val pre: Boolean,
        val url: String
    )

    /** 有新版本时才调用的详情请求，失败返回 null。 */
    private fun fetchUpdateInfo(): UpdateInfo? {
        val raw = httpGet(API_BASE + "updateinfo") ?: return null
        return try {
            val json = org.json.JSONObject(raw)
            UpdateInfo(
                verName = json.optString("vername", ""),
                verCode = json.optInt("vercode", 0),
                log = json.optString("log", ""),
                weVer = json.optString("wever", ""),
                must = json.optBoolean("must", false),
                pre = json.optBoolean("pre", false),
                url = json.optString("url", "")
            )
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "parse update info failed", t)
            null
        }
    }

    /** 服务器要求的 WE 版本是否高于本机安装版本。 */
    private fun checkWeOutdated(ctx: Context, serverWeVer: String?): Boolean {
        val target = serverWeVer?.toIntOrNull() ?: return false
        return try {
            val installed = ctx.packageManager
                .getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
            installed < target
        } catch (_: Throwable) {
            false
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
        ctx: Context,
        serverVersion: Int,
        info: UpdateInfo?,
        weOutdated: Boolean,
        prefs: SharedPreferences,
        ignoredKey: String
    ) {
        val i18n = getUpdateI18N(ctx)
        val zh = ctx.resources.configuration.locales[0].language == "zh"
        val versionStr = info?.verName?.takeIf { it.isNotBlank() }
            ?: if (serverVersion > 100) {
                "${serverVersion / 100}.${serverVersion % 100}"
            } else {
                "v$serverVersion"
            }
        val changelog = info?.log.orEmpty()
        val must = info?.must == true
        val pre = info?.pre == true
        val updateUrl = info?.url?.takeIf { it.isNotBlank() }
        val msg = buildString {
            append(i18n["version"]!!.format(versionStr))
            if (pre) append(if (zh) "\n预览版本（测试版）" else "\nPre-release build")
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
            if (must) append(if (zh) "\n\n此版本为必要更新" else "\n\nThis update is mandatory")
        }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(i18n["title"])
            .setMessage(msg)
            .setPositiveButton(i18n["update"]) { _, _ ->
                val url = updateUrl ?: "https://github.com/nxdyy/WallpaperEnginePlus/releases"
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .setNegativeButton(i18n["close"]) { _, _ -> }
            .setCancelable(false)
        // 必要更新不提供“忽略此版本”
        if (!must) {
            builder.setNeutralButton(i18n["ignore"]) { _, _ ->
                prefs.edit().putInt(ignoredKey, serverVersion).apply()
            }
        }
        builder.show()
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

    /** 程序化创建 SeekBarPreference 与更新检查入口，加入第一个分类（General）。 */
    private fun injectVolumePreference(fragment: Any?, cl: ClassLoader) {
        if (fragment == null) return

        val findPreference = fragment.javaClass.getMethod("findPreference", CharSequence::class.java)
        val screen = fragment.javaClass.getMethod("getPreferenceScreen").invoke(fragment) ?: return
        val count = screen.javaClass.getMethod("getPreferenceCount").invoke(screen) as Int
        if (count < 1) return
        val generalCategory = screen.javaClass.getMethod("getPreference", Int::class.javaPrimitiveType).invoke(screen, 0) ?: return
        val prefBaseClass = cl.loadClass("androidx.preference.Preference")

        val context = fragment.javaClass.getMethod("requireContext").invoke(fragment) as Context

        // 音量滑条
        if (findPreference.invoke(fragment, PREF_KEY) == null) {
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

            generalCategory.javaClass.getMethod("addPreference", prefBaseClass).invoke(generalCategory, pref)
            log(Log.INFO, TAG, "volume preference injected")
        }

        // 检查壁纸引擎更新
        if (findPreference.invoke(fragment, CHECK_WE_PREF_KEY) == null) {
            addActionPreference(cl, context, generalCategory, prefBaseClass,
                CHECK_WE_PREF_KEY, "检查壁纸引擎更新",
                "检查 Wallpaper Engine 是否有新版本"
            ) { checkWallpaperEngineUpdate(context) }
        }

        // 检查模块更新
        if (findPreference.invoke(fragment, CHECK_MODULE_PREF_KEY) == null) {
            addActionPreference(cl, context, generalCategory, prefBaseClass,
                CHECK_MODULE_PREF_KEY, "检查模块更新",
                "检查声音修复模块是否有新版本"
            ) { checkModuleUpdate(context) }
        }
    }

    /** 创建一个可点击的 Preference 并加入指定分类。 */
    private fun addActionPreference(
        cl: ClassLoader,
        context: Context,
        category: Any,
        prefBaseClass: Class<*>,
        key: String,
        title: String,
        summary: String,
        onClick: () -> Unit
    ) {
        val prefClass = cl.loadClass("androidx.preference.Preference")
        val pref = prefClass.getConstructor(Context::class.java).newInstance(context)
        prefClass.getMethod("setKey", String::class.java).invoke(pref, key)
        prefClass.getMethod("setTitle", CharSequence::class.java).invoke(pref, title)
        prefClass.getMethod("setSummary", CharSequence::class.java).invoke(pref, summary)
        prefClass.getMethod("setIconSpaceReserved", Boolean::class.javaPrimitiveType).invoke(pref, false)
        prefClass.getMethod("setPersistent", Boolean::class.javaPrimitiveType).invoke(pref, false)

        val listenerClass = cl.loadClass("androidx.preference.Preference\$OnPreferenceClickListener")
        val listener = java.lang.reflect.Proxy.newProxyInstance(
            listenerClass.classLoader, arrayOf(listenerClass)
        ) { proxy, method, args ->
            when (method.name) {
                "onPreferenceClick" -> {
                    try {
                        onClick()
                    } catch (t: Throwable) {
                        log(Log.WARN, TAG, "preference click failed", t)
                    }
                    true
                }
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "WESoundFixActionPreference"
                else -> null
            }
        }
        prefClass.getMethod("setOnPreferenceClickListener", listenerClass).invoke(pref, listener)
        category.javaClass.getMethod("addPreference", prefBaseClass).invoke(category, pref)
    }

    /** 检查模块更新（手动触发）：version → 有新版本时再取 updateinfo。 */
    private fun checkModuleUpdate(ctx: Context) {
        Thread {
            try {
                val current = currentModuleVersion()
                val server = httpGet(API_BASE + "version")?.toIntOrNull()
                if (server == null) {
                    postDialog(ctx, "检查模块更新", "检查失败，请稍后重试")
                    return@Thread
                }
                if (server <= current) {
                    postDialog(ctx, "检查模块更新", "已是最新版本（版本号：$current）")
                    return@Thread
                }

                val info = fetchUpdateInfo()
                val latest = info?.verCode?.takeIf { it > 0 } ?: server
                val weOutdated = checkWeOutdated(ctx, info?.weVer)
                val prefs = obtainPrefs()

                Handler(Looper.getMainLooper()).post {
                    try {
                        if (prefs != null) {
                            showUpdateDialog(
                                ctx, latest, info, weOutdated, prefs, UPDATE_IGNORED_KEY)
                        } else {
                            showSimpleDialog(ctx, "发现模块新版本",
                                "当前版本：$current\n最新版本：$latest")
                        }
                    } catch (t: Throwable) {
                        log(Log.WARN, TAG, "show module update dialog failed", t)
                    }
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "module update check failed", t)
                postDialog(ctx, "检查模块更新", "检查失败，请稍后重试")
            }
        }.start()
    }

    /** 检查 Wallpaper Engine 更新（手动触发）：读取 updateinfo 中要求的 wever。 */
    private fun checkWallpaperEngineUpdate(ctx: Context) {
        Thread {
            try {
                val info = fetchUpdateInfo()
                val server = info?.weVer?.toIntOrNull()
                if (server == null) {
                    postDialog(ctx, "检查壁纸引擎更新", "检查失败，请稍后重试")
                    return@Thread
                }
                val installed = try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                        .longVersionCode.toInt()
                } catch (_: Throwable) {
                    -1
                }
                if (installed > 0 && server > installed) {
                    postDialog(ctx, "发现 Wallpaper Engine 新版本",
                        "当前版本：$installed\n最新版本：$server")
                } else {
                    postDialog(ctx, "检查壁纸引擎更新",
                        if (installed > 0) "已是最新版本（版本号：$installed）"
                        else "已是最新版本")
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "we update check failed", t)
                postDialog(ctx, "检查壁纸引擎更新", "检查失败，请稍后重试")
            }
        }.start()
    }

    private fun postDialog(ctx: Context, title: String, message: String) {
        Handler(Looper.getMainLooper()).post { showSimpleDialog(ctx, title, message) }
    }

    private fun showSimpleDialog(ctx: Context, title: String, message: String) {
        try {
            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show()
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "show dialog failed", t)
        }
    }

    /** 模块自身 versionCode（集成模式或取不到时回退到构建配置）。 */
    private fun currentModuleVersion(): Int = try {
        val info = moduleApplicationInfo
        val longCode = info.javaClass.getMethod("getLongVersionCode").invoke(info) as? Number
        longCode?.toInt() ?: info.javaClass.getField("versionCode").getInt(info)
    } catch (_: Throwable) {
        MODULE_VERSION_CODE
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
        private const val CHECK_WE_PREF_KEY = "soundfix_check_we_update"
        private const val CHECK_MODULE_PREF_KEY = "soundfix_check_module_update"
        private const val UPDATE_IGNORED_KEY = "we_soundfix_update_ignored_version"
        private const val UPDATE_DIALOG_TS_KEY = "we_soundfix_update_dialog_ts"
        private const val UPDATE_DIALOG_DEDUP_MS = 30_000L
        /** 已展示更新日志的版本号，保证每个版本只弹一次 */
        private const val CHANGELOG_VERSION_KEY = "we_soundfix_changelog_version"
        private const val API_BASE = "https://project.nxdyy.cn/WallpaperEnginePlus/"
        /** 与 build.gradle.kts 的 versionCode 保持一致，作为取不到模块信息时的回退值 */
        private const val MODULE_VERSION_CODE = 9
        private const val MODULE_VERSION_NAME = "2.0"

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
