package io.wallpaper.soundfix

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * 激活状态上报通道。
 * 目标应用进程中的 HookEntry 通过 call("report") 写入激活时间戳，
 * 模块主页 MainActivity 读取该标记判断模块是否处于激活状态
 * （普通应用无法读取 LSPosed 配置，只能由 hook 侧主动上报）。
 * 通过 uid 校验只接受目标应用包名的调用，防止任意应用伪造标记。
 */
class StatusProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_REPORT) return null
        val ctx = context ?: return null
        val caller = ctx.packageManager.getNameForUid(Binder.getCallingUid()) ?: return null
        if (caller !in ALLOWED_CALLERS) return null
        try {
            ctx.getFileStreamPath(MARKER_FILE).writeText(System.currentTimeMillis().toString())
        } catch (_: Throwable) {
        }
        return Bundle.EMPTY
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    companion object {
        const val AUTHORITY = "io.wallpaper.soundfix.status"
        private const val METHOD_REPORT = "report"
        private const val MARKER_FILE = "activated_at"
        private val ALLOWED_CALLERS = setOf(
            "io.wallpaperengine.weclient",
            "io.wallpaperengine.nxdyy",
        )
    }
}
