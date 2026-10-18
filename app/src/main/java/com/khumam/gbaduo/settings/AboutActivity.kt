package com.khumam.gbaduo.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.khumam.gbaduo.R

/** Settings > About: version, source and a word on what GBADuo is. */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            setBackgroundColor(getColor(R.color.background))
        }

        root.addView(title("GBADuo", 26f, true))
        root.addView(body("Version ${version ?: "?"}\n\n" +
            "Play GBA, GBC and GB games with a companion screen on a dual-screen handheld.\n\n" +
            "Fan-made, not affiliated with Nintendo. Use your own legally dumped ROMs."))

        val link = TextView(this).apply {
            text = getString(R.string.github_url)
            setTextColor(getColor(R.color.accent))
            textSize = 15f
            setPadding(0, dp(12), 0, 0)
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url)))) }
            }
        }
        root.addView(link)

        setContentView(root)
    }

    private fun title(text: String, size: Float, bold: Boolean) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_primary))
        textSize = size
        setTypeface(typeface, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 15f
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
