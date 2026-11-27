package io.wallpaper.soundfix

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.ImageView
import java.io.File

/**
 * 列表缩略图的 GIF 动画。
 *
 * 原版通过 BitmapFactory 解码预览图，只能得到 GIF 的第一帧。
 * 这里在列表封面绑定后记录壁纸路径，并在 ImageView 收到静态 Bitmap 时
 * 替换为 AnimatedImageDrawable，让预览 GIF 正常播放。
 */
internal object GifPreview {

    private const val TAG = "WESoundFix"

    /** 自定义 tag key，避免覆盖原版可能使用的 tag */
    private const val KEY_PATH = 0x57454647 // "WEFG"

    /** 当前动画对应的壁纸路径，列表项复用时用于判断是否需要换图 */
    private const val KEY_ANIM_PATH = 0x57454648 // "WEFH"

    /** 列表项绑定：记录封面路径，并停止上一张壁纸残留的动画。 */
    fun prepareCover(itemView: View, path: String) {
        try {
            val id = itemView.resources.getIdentifier(
                "ibWallpaper", "id", itemView.context.packageName)
            if (id == 0) return
            val cover = itemView.findViewById<ImageView>(id) ?: return
            // 复用 ViewHolder 时先停掉旧动画，避免继续播放上一张壁纸的 GIF
            stopAnimation(cover)
            cover.setTag(KEY_PATH, path)
        } catch (_: Throwable) {
        }
    }

    /**
     * 原版设置完静态 Bitmap 之后调用：
     * 若当前绑定的壁纸有 preview.gif，则替换为动画；否则保持原版静态图不动。
     */
    fun applyGif(view: ImageView) {
        try {
            val path = view.getTag(KEY_PATH) as? String ?: return
            val gif = previewGif(view.context, path) ?: return
            // 已经是同一张 GIF 的动画时跳过
            if (view.drawable is AnimatedImageDrawable &&
                view.getTag(KEY_ANIM_PATH) == path) {
                return
            }
            if (Build.VERSION.SDK_INT < 28) return
            stopAnimation(view)
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(gif))
            if (drawable !is AnimatedImageDrawable) return
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            view.setImageDrawable(drawable)
            view.setTag(KEY_ANIM_PATH, path)
            drawable.start()
        } catch (t: Throwable) {
            Log.w(TAG, "load gif preview failed", t)
        }
    }

    private fun stopAnimation(view: ImageView) {
        try {
            (view.drawable as? AnimatedImageDrawable)?.stop()
        } catch (_: Throwable) {
        }
    }

    /** 查找壁纸目录下的预览 GIF。 */
    private fun previewGif(ctx: android.content.Context, path: String): File? {
        val dir = try {
            val p = if (path.startsWith("file://")) path.removePrefix("file://") else path
            if (p.isBlank()) return null
            val f = File(p)
            if (f.isAbsolute) f else File(ctx.filesDir, p)
        } catch (_: Throwable) {
            return null
        }
        if (!dir.isDirectory) return null
        for (name in listOf("preview.gif", "preview.GIF", "Preview.gif")) {
            val f = File(dir, name)
            if (f.isFile) return f
        }
        return null
    }
}
