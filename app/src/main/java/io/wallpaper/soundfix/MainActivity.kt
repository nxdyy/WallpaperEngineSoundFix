package io.wallpaper.soundfix

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)

        val padH = (24 * resources.displayMetrics.density).toInt()
        val padV = (20 * resources.displayMetrics.density).toInt()

        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val textColor = if (isDark) Color.WHITE else Color.BLACK
        val subTextColor = if (isDark) Color.parseColor("#B0B0B0") else Color.parseColor("#666666")
        val dividerColor = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1A000000")
        val linkColor = if (isDark) Color.parseColor("#82B1FF") else Color.parseColor("#1565C0")

        val scrollView = ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(if (isDark) Color.BLACK else Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padH, padV, padH, padV)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Activation status
        val statusTv = TextView(this).apply {
            val activated = isModuleActivated()
            text = if (activated) getString(R.string.status_activated) else getString(R.string.status_not_activated)
            setTextColor(ContextCompat.getColor(this@MainActivity,
                if (activated) R.color.status_activated else R.color.status_not_activated))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
            gravity = Gravity.CENTER
        }
        container.addView(statusTv)

        // Author info
        val authorTv = TextView(this).apply {
            text = getString(R.string.author_info, "nxdyy")
            textSize = 14f
            setTextColor(textColor)
            setPadding(0, 0, 0, (4 * resources.displayMetrics.density).toInt())
            gravity = Gravity.CENTER
        }
        container.addView(authorTv)

        // GitHub link
        val githubTv = TextView(this).apply {
            text = "https://github.com/nxdyy/WallpaperEngineSoundFix"
            textSize = 13f
            setTextColor(linkColor)
            setPadding(0, 0, 0, (20 * resources.displayMetrics.density).toInt())
            gravity = Gravity.CENTER
            Linkify.addLinks(this, Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
        }
        container.addView(githubTv)

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (1 * resources.displayMetrics.density).toInt()
            ).apply {
                bottomMargin = (20 * resources.displayMetrics.density).toInt()
            }
        }
        container.addView(divider)

        // Free notice
        val noticeTv = TextView(this).apply {
            text = getString(R.string.free_notice)
            textSize = 14f
            setTextColor(if (isDark) Color.parseColor("#EF5350") else Color.parseColor("#D32F2F"))
            setPadding(0, 0, 0, (4 * resources.displayMetrics.density).toInt())
            setLineSpacing(0f, 1.35f)
        }
        container.addView(noticeTv)

        // Author website
        val siteTv = TextView(this).apply {
            text = getString(R.string.author_website, "https://www.nxdyy.cn")
            textSize = 13f
            setTextColor(linkColor)
            setPadding(0, 0, 0, (20 * resources.displayMetrics.density).toInt())
            Linkify.addLinks(this, Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
        }
        container.addView(siteTv)

        // Author Bilibili
        val bilibiliTv = TextView(this).apply {
            text = getString(R.string.author_bilibili, "https://space.bilibili.com/660595349")
            textSize = 13f
            setTextColor(linkColor)
            setPadding(0, 0, 0, (20 * resources.displayMetrics.density).toInt())
            Linkify.addLinks(this, Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
        }
        container.addView(bilibiliTv)

        // Scene export notice
        val noticeLineTv = TextView(this).apply {
            text = getString(R.string.scene_export_notice)
            textSize = 14f
            setTextColor(textColor)
            setPadding(0, 0, 0, (4 * resources.displayMetrics.density).toInt())
        }
        container.addView(noticeLineTv)

        // pkg2mpkg link + copy button row
        val linkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * resources.displayMetrics.density).toInt()
            }
        }
        val linkTv = TextView(this).apply {
            text = getString(R.string.pkg2mpkg_url)
            textSize = 13f
            setTextColor(linkColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            Linkify.addLinks(this, Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
        }
        linkRow.addView(linkTv)
        val copyBtn = TextView(this).apply {
            text = getString(R.string.copy)
            textSize = 13f
            setTextColor(linkColor)
            setPadding((12 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            isClickable = true
            isFocusable = true
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setStroke((1 * resources.displayMetrics.density).toInt(), linkColor)
                cornerRadius = 4f * resources.displayMetrics.density
                setColor(Color.TRANSPARENT)
            }
            background = bg
            setOnClickListener {
                val clip = android.content.ClipData.newPlainText("url", getString(R.string.pkg2mpkg_url))
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                (it as TextView).text = getString(R.string.copied)
                it.postDelayed({ (it as TextView).text = getString(R.string.copy) }, 1500)
            }
        }
        linkRow.addView(copyBtn)
        container.addView(linkRow)

        // Instructions
        val instrTv = TextView(this).apply {
            text = buildInstructions()
            textSize = 14.5f
            setTextColor(textColor)
            setPadding(0, 0, 0, 0)
            setLineSpacing(0f, 1.35f)
        }
        container.addView(instrTv)

        scrollView.addView(container)
        setContentView(scrollView)
    }

    private fun buildInstructions(): CharSequence {
        val lines = listOf(
            getString(R.string.instr_header),
            "",
            "1.  ${getString(R.string.instr_step1)}",
            "2.  ${getString(R.string.instr_step2)}",
            "3.  ${getString(R.string.instr_step3)}",
            "4.  ${getString(R.string.instr_step4)}",
            "",
            getString(R.string.instr_scope_header),
            getString(R.string.instr_scope_video),
            getString(R.string.instr_scope_scene_video),
            getString(R.string.instr_scope_scene_sound),
            "",
            getString(R.string.instr_principle_header),
            getString(R.string.instr_principle_video),
            getString(R.string.instr_principle_scene),
        )
        return lines.joinToString("\n")
    }

    private fun isModuleActivated(): Boolean {
        // HookEntry 类在正常应用中不存在，仅在 LSPosed 模块注入时由框架加载。
        // 如果当前进程能加载到该类（通过目标 app 的 classloader），
        // 说明我们是在模块进程中运行的（即模块已激活）。
        return try {
            Class.forName("io.wallpaper.soundfix.HookEntry", false, javaClass.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            // 不在模块进程，尝试读 LSPosed 日志文件或检查模块标记
            try {
                val f = java.io.File("/data/misc/lspd/modules.list")
                f.exists() && f.readText().contains(packageName)
            } catch (_: Throwable) {
                false
            }
        }
    }
}
