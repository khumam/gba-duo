package com.khumam.gbaduo.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.khumam.gbaduo.R

/** The app's settings entry: the ROM folders sub-page and About. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<android.view.View>(R.id.row_rom_folders).setOnClickListener {
            startActivity(Intent(this, RomFoldersActivity::class.java))
        }
        findViewById<android.view.View>(R.id.row_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }
}
