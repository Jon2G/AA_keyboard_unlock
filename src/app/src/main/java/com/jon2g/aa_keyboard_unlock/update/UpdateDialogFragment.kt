package com.jon2g.aa_keyboard_unlock.update

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jon2g.aa_keyboard_unlock.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateDialogFragment : DialogFragment() {

    private var updateInfo: UpdateInfo? = null
    private var isDownloading = false
    private var downloadJob: kotlinx.coroutines.Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireArguments().getParcelable(ARG_UPDATE_INFO, ParcelableUpdateInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            requireArguments().getParcelable<ParcelableUpdateInfo>(ARG_UPDATE_INFO)
        }?.toUpdateInfo()
            ?: throw IllegalStateException("Missing update info")
        updateInfo = info

        val view = layoutInflater.inflate(R.layout.dialog_update, null)
        val versionText = view.findViewById<TextView>(R.id.text_update_version)
        val changelogText = view.findViewById<TextView>(R.id.text_update_changelog)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_update_download)
        val statusText = view.findViewById<TextView>(R.id.text_update_status)
        val laterButton = view.findViewById<MaterialButton>(R.id.button_update_later)
        val installButton = view.findViewById<MaterialButton>(R.id.button_update_install)
        val githubButton = view.findViewById<MaterialButton>(R.id.button_update_github)

        versionText.text = getString(R.string.update_version_label, info.versionName)
        changelogText.text = info.changelog.ifBlank { getString(R.string.update_no_changelog) }

        laterButton.setOnClickListener {
            UpdatePrefs.setLastRemoteDismissed(requireContext(), info.versionCode)
            dismissAllowingStateLoss()
        }

        installButton.setOnClickListener {
            if (isDownloading) return@setOnClickListener
            if (!ApkInstaller.canInstallPackages(requireContext())) {
                ApkInstaller.requestInstallPermission(requireContext())
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.update_install_permission)
                return@setOnClickListener
            }
            startDownload(info, progressBar, statusText, installButton, laterButton, githubButton)
        }

        githubButton.setOnClickListener {
            ApkInstaller.openReleaseInBrowser(requireContext(), info.releaseUrl)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.update_available_title)
            .setView(view)
            .create()

        dialog.setOnShowListener {
            dialog.setCanceledOnTouchOutside(false)
        }

        return dialog
    }

    private fun startDownload(
        info: UpdateInfo,
        progressBar: ProgressBar,
        statusText: TextView,
        installButton: MaterialButton,
        laterButton: MaterialButton,
        githubButton: MaterialButton,
    ) {
        isDownloading = true
        installButton.isEnabled = false
        laterButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.update_downloading)

        downloadJob = lifecycleScope.launch {
            val apkFile = withContext(Dispatchers.IO) {
                ApkDownloader.download(requireContext(), info.apkDownloadUrl, info.versionName) { pct ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        progressBar.progress = pct
                    }
                }
            }

            isDownloading = false
            if (apkFile == null) {
                statusText.text = getString(R.string.update_download_failed)
                installButton.isEnabled = true
                laterButton.isEnabled = true
                githubButton.visibility = View.VISIBLE
                return@launch
            }

            UpdatePrefs.setUpdateJustInstalled(requireContext(), true)
            UpdatePrefs.setLastRemoteDismissed(requireContext(), info.versionCode)

            if (!ApkInstaller.install(requireContext(), apkFile)) {
                statusText.text = getString(R.string.update_install_failed)
                installButton.isEnabled = true
                laterButton.isEnabled = true
                githubButton.visibility = View.VISIBLE
                return@launch
            }

            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        downloadJob?.cancel()
        super.onDestroyView()
    }

    companion object {
        private const val ARG_UPDATE_INFO = "update_info"

        fun newInstance(info: UpdateInfo): UpdateDialogFragment {
            return UpdateDialogFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_UPDATE_INFO, ParcelableUpdateInfo.from(info))
                }
            }
        }
    }
}
