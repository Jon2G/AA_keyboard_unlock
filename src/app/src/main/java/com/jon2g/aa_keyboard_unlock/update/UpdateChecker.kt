package com.jon2g.aa_keyboard_unlock.update

import com.jon2g.aa_keyboard_unlock.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val releaseUrl: String,
    val apkDownloadUrl: String,
) {
    val hasUpdate: Boolean get() = versionCode > BuildConfig.VERSION_CODE
}

object UpdateChecker {
    private const val API_URL =
        "https://api.github.com/repos/Jon2G/AA_keyboard_unlock/releases/latest"
    private const val APK_PREFIX = "aa-keyboard-unlock-"
    private const val TIMEOUT_MS = 10_000

    fun versionCodeFromTag(tag: String): Int? {
        val version = tag.removePrefix("v").removePrefix("V")
        val parts = version.split(".")
        if (parts.size < 3) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patch = parts[2].takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        return major * 10_000 + minor * 100 + patch
    }

    fun checkForUpdates(): UpdateInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AAKeyboardUnlock/${BuildConfig.VERSION_NAME}")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val release = JSONObject(body)
            val tagName = release.optString("tag_name", "")
            if (tagName.isEmpty()) return null

            val versionCode = versionCodeFromTag(tagName) ?: return null
            val versionName = tagName.removePrefix("v").removePrefix("V")
                .takeWhile { it.isDigit() || it == '.' }
            val changelog = release.optString("body", "")
            val releaseUrl = release.optString("html_url", "")

            val assets = release.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            var fallbackUrl: String? = null

            val preferredName = "$APK_PREFIX$versionName.apk"
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                if (name.endsWith("-log.apk", ignoreCase = true)) continue
                val downloadUrl = asset.optString("browser_download_url", "")
                if (downloadUrl.isEmpty()) continue
                if (name.equals(preferredName, ignoreCase = true)) {
                    apkUrl = downloadUrl
                    break
                }
                if (fallbackUrl == null) {
                    fallbackUrl = downloadUrl
                }
            }

            val resolvedUrl = apkUrl ?: fallbackUrl ?: return null

            UpdateInfo(
                versionName = versionName,
                versionCode = versionCode,
                changelog = changelog,
                releaseUrl = releaseUrl,
                apkDownloadUrl = resolvedUrl,
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
