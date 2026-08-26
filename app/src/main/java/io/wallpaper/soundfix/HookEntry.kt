package io.wallpaper.soundfix

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
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
 */
class HookEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        log(Log.INFO, TAG, "loaded in process ${param.processName}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        if (!param.isFirstPackage || param.packageName != TARGET_PACKAGE) return
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

        // 路径 C：壁纸暂停时同步暂停 AudioRecorder（FFT 采集）
        try {
            hookAudioRecorderPause(cl)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "hook AudioRecorder pause failed", t)
        }
    }

    /**
     * Hook GLWallpaperEngine.updatePausedState：壁纸暂停/恢复时同步控制 AudioRecorder。
     *
     * 原应用 bug：updatePausedState 只暂停渲染（GLSurfaceView）和 ParallaxController，
     * AudioRecorder（Visualizer FFT 采集）继续运行并持续向 native 层 sendAudioData，
     * 浪费 CPU。此处按 shouldBePaused() 结果同步启停：
     *   暂停（离开桌面/省电）→ stopAudioListener
     *   恢复（回到桌面）     → startAudioListener
     *
     * 注意：audioRecorder 字段非 null 即表示当前壁纸启用了 audioprocessing，
     * null 时（未启用或已卸载）无需任何操作；两个方法内部有状态检查，幂等安全。
     */
    private fun hookAudioRecorderPause(cl: ClassLoader) {
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
                val recorder = recorderField.get(engine) ?: return@intercept result
                val shouldPause = shouldBePausedMethod.invoke(engine) as Boolean
                val running = stateField.get(recorder) as Boolean
                if (shouldPause && running) {
                    stopMethod.invoke(recorder)
                    SoundBridge.pauseAll()
                    log(Log.INFO, TAG, "AudioRecorder + SoundBridge paused (not visible / power saving)")
                } else if (!shouldPause && !running) {
                    startMethod.invoke(recorder)
                    SoundBridge.resumeAll()
                    log(Log.INFO, TAG, "AudioRecorder + SoundBridge resumed (visible)")
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "sync AudioRecorder state failed", t)
            }
            result
        }
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
            // 先加载模块 native 库并安装虚表补丁（native 内部会 dlopen libscenejni.so）
            if (!nativeInstalled) {
                try {
                    val nativeDir = moduleApplicationInfo.nativeLibraryDir
                    System.load("$nativeDir/libsoundfix.so")
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

        pref.javaClass.getMethod("setKey", String::class.java).invoke(pref, PREF_KEY)
        pref.javaClass.getMethod("setTitle", CharSequence::class.java)
            .invoke(pref, if (isChinese(context)) "壁纸音量" else "Wallpaper volume")
        pref.javaClass.getMethod("setSummary", CharSequence::class.java)
            .invoke(pref, if (isChinese(context)) "修复壁纸无声：控制视频壁纸播放音量" else "Fix silent wallpapers: control video volume")
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
            app?.getSharedPreferences("${TARGET_PACKAGE}_preferences", Context.MODE_PRIVATE)?.also { p ->
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

    private fun isChinese(context: Context): Boolean =
        context.resources.configuration.locales[0].language == "zh"

    companion object {
        private const val TAG = "WESoundFix"
        private const val TARGET_PACKAGE = "io.wallpaperengine.weclient"
        private const val PREF_KEY = "general_volume"
        private const val DEFAULT_VOLUME = 100

        /** native 引擎是否已安装（防重复）。 */
        @Volatile
        private var nativeInstalled = false

        /** 被模块管理音量的 MediaPlayer 实例（弱引用，避免泄漏）。 */
        private val tracked: MutableSet<MediaPlayer> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        @Volatile
        private var prefs: SharedPreferences? = null
    }
}
