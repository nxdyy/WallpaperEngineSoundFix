package io.wallpaper.soundfix

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.Locale

/**
 * 壁纸自定义页面文案汉化。
 *
 * 原版 WallpaperProperties.localizeText() 负责所有属性标题与下拉选项文案：
 * - 第三方壁纸作者常直接把英文写在 project.json 的 text/label 里（如 "Flip"、"Audio recording"）
 * - 部分标准 key 在 zh-chs 词条中缺失时也会回退成英文
 *
 * 这里直接读取目标应用自带的 assets/locale/ui_en-us.json 与 ui_zh-chs.json，
 * 构建「英文原文 → 中文」和「文案 key → 中文」映射；仅作用于精确匹配的标准文案，
 * 不会改动作者自定义的描述或 HTML 内容。
 */
internal object PropertyLocalization {

    private const val TAG = "WESoundFix"

    @Volatile
    private var loaded = false

    private val enToZh = HashMap<String, String>()
    private val enToZhLower = HashMap<String, String>()
    private val keyToEn = HashMap<String, String>()
    private val keyToZh = HashMap<String, String>()

    /** zh-chs 缺失的标准词条补充 */
    private val EXTRA_ZH = mapOf(
        "Blur size" to "模糊大小",
        "Drop shadow opacity" to "投影不透明度",
        "Reflectivity distance" to "反射距离",
        "Stiffness" to "刚度",
        "Models" to "模型",
        "Particles" to "粒子",
        "Sounds" to "声音",
        "CEF devtools:" to "CEF 开发者工具：",
        "Open Devtools" to "打开开发者工具",
        "No web wallpaper active." to "当前没有活动的网页壁纸。",
        "Set the alpha value to a random value." to "将透明度设为随机值。",
        "Collision rectangle" to "碰撞矩形",
        "Collides particles against a rectangle of limited size and only from one side." to
            "使粒子与限定大小的矩形发生碰撞，且仅从一侧碰撞。",
    )

    /** 提前在后台加载词条，避免首次打开自定义页时卡顿。 */
    fun preload() {
        val ctx = appContext() ?: return
        Thread {
            ensureLoaded(ctx)
        }.start()
    }

    /**
     * 返回本地化后的文案。
     * @param input    原应用传入的文案（可能是 key，也可能是作者写的英文原文）
     * @param original 原应用自身的本地化结果
     */
    fun translate(input: String?, original: String): String {
        if (input.isNullOrEmpty()) return original
        if (!isChinese()) return original
        val ctx = appContext() ?: return original
        ensureLoaded(ctx)

        // 1) 输入本身就是英文标准文案（第三方壁纸无法命中 key）
        enToZh[input]?.let { return it }
        enToZhLower[input.lowercase(Locale.ROOT)]?.let { return it }

        // 2) 输入是 key：原版未翻译或回退成英文时用中文词条
        val english = keyToEn[input]
        if (original.isEmpty() || (english != null && original == english)) {
            keyToZh[input]?.let { return it }
            if (english != null) enToZh[english]?.let { return it }
            if (original.isEmpty() && enToZh[input] == null && keyToZh[input] == null) return input
        }
        return original
    }

    private fun isChinese(): Boolean =
        Locale.getDefault().language.equals("zh", ignoreCase = true)

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val en = loadAsset(ctx, "locale/ui_en-us.json")
                val zh = loadAsset(ctx, "locale/ui_zh-chs.json")
                if (en != null && zh != null) {
                    val keys = en.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val enVal = en.optString(key, "")
                        val zhVal = zh.optString(key, "")
                        if (enVal.isEmpty() || zhVal.isEmpty()) continue
                        keyToEn[key] = enVal
                        keyToZh[key] = zhVal
                        enToZh.putIfAbsent(enVal, zhVal)
                        enToZhLower.putIfAbsent(enVal.lowercase(Locale.ROOT), zhVal)
                    }
                }
                for ((enVal, zhVal) in EXTRA_ZH) {
                    enToZh.putIfAbsent(enVal, zhVal)
                    enToZhLower.putIfAbsent(enVal.lowercase(Locale.ROOT), zhVal)
                }
                Log.i(TAG, "property localization loaded: ${enToZh.size} entries")
            } catch (t: Throwable) {
                Log.w(TAG, "load property localizations failed", t)
            } finally {
                loaded = true
            }
        }
    }

    private fun loadAsset(ctx: Context, name: String): JSONObject? = try {
        ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    } catch (_: Throwable) {
        null
    }

    private fun appContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }
}
