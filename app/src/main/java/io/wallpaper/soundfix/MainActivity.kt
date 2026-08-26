package io.wallpaper.soundfix

import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = getString(R.string.module_instructions)
            textSize = 15f
            setPadding(48, 48, 48, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        setContentView(tv)
    }
}
