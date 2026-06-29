package com.jon2g.aa_keyboard_unlock

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.jon2g.aa_keyboard_unlock.prefs.ModulePrefs
import com.jon2g.aa_keyboard_unlock.update.UpdateChecker
import com.jon2g.aa_keyboard_unlock.update.UpdateDialogFragment
import com.jon2g.aa_keyboard_unlock.update.UpdatePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private var isCheckingUpdates = false

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

        findViewById<TextView>(R.id.text_debug_build).text =
            getString(
                if (BuildConfig.MODULE_DEBUG) R.string.debug_build_on else R.string.debug_build_off
            )

        findViewById<TextView>(R.id.text_app_version).text =
            getString(R.string.app_version_label, getString(R.string.app_name), BuildConfig.VERSION_NAME)

        findViewById<TextView>(R.id.text_created_by).text =
            getString(R.string.created_by, getString(R.string.creator_name))

        findViewById<TextView>(R.id.text_license).text = getString(R.string.mit_license)

        findViewById<MaterialButton>(R.id.button_view_github).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.repo_url))))
        }

        findViewById<MaterialButton>(R.id.button_check_updates).setOnClickListener {
            checkForUpdates(silent = false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (UpdatePrefs.isUpdateJustInstalled(this)) {
            UpdatePrefs.setUpdateJustInstalled(this, false)
        }
        checkForUpdates(silent = true)
    }

    private fun checkForUpdates(silent: Boolean) {
        if (isCheckingUpdates) return
        if (supportFragmentManager.findFragmentByTag(UpdateDialogFragment::class.java.simpleName) != null) {
            return
        }

        isCheckingUpdates = true
        val progress = findViewById<ProgressBar>(R.id.progress_check_updates)
        val checkButton = findViewById<MaterialButton>(R.id.button_check_updates)
        if (!silent) {
            progress.visibility = View.VISIBLE
            checkButton.isEnabled = false
        }

        lifecycleScope.launch {
            val updateInfo = withContext(Dispatchers.IO) {
                UpdateChecker.checkForUpdates()
            }

            isCheckingUpdates = false
            if (!silent) {
                progress.visibility = View.GONE
                checkButton.isEnabled = true
            }

            when {
                updateInfo == null -> {
                    if (!silent) {
                        Snackbar.make(checkButton, R.string.update_check_failed, Snackbar.LENGTH_SHORT).show()
                    }
                }
                !updateInfo.hasUpdate -> {
                    if (!silent) {
                        Snackbar.make(checkButton, R.string.update_up_to_date, Snackbar.LENGTH_SHORT).show()
                    }
                }
                updateInfo.versionCode <= UpdatePrefs.getLastRemoteDismissed(this@SettingsActivity) -> {
                    // User dismissed this version
                }
                else -> {
                    UpdateDialogFragment.newInstance(updateInfo).show(
                        supportFragmentManager,
                        UpdateDialogFragment::class.java.simpleName,
                    )
                }
            }
        }
    }
}
