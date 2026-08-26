package io.wallpaper.soundfix

/**
 * native 安装入口。由 HookEntry 在 SceneLib.initLibrary 后调用：
 * System.load(module nativeLibraryDir + "/libsoundfix.so") 之后执行 install。
 */
object SoundFixNative {
    @JvmStatic
    external fun install(bridge: Any): Boolean
}
