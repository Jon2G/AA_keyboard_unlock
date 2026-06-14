package com.jon2g.aa_keyboard_unlock

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_settings)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayShowTitleEnabled(true)

        val prefs = ModulePrefs.appPrefs(this)

        findViewById<MaterialSwitch>(R.id.switch_enable).apply {
            isChecked = prefs.getBoolean(ModulePrefs.KEY_ENABLED, ModulePrefs.DEFAULT_ENABLED)
            setOnCheckedChangeListener { _, checked ->
                ModulePrefs.setEnabled(this@SettingsActivity, checked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switch_debug).apply {
            isChecked = prefs.getBoolean(ModulePrefs.KEY_DEBUG, ModulePrefs.DEFAULT_DEBUG)
            setOnCheckedChangeListener { _, checked ->
                ModulePrefs.setDebug(this@SettingsActivity, checked)
            }
        }
    }
}
