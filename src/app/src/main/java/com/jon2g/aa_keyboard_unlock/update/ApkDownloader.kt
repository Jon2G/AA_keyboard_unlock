package com.jon2g.aa_keyboard_unlock.update

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ApkDownloader {
    private const val APK_PREFIX = "aa-keyboard-unlock-"
    private const val TIMEOUT_MS = 60_000

    fun apkFile(context: Context, versionName: String): File =
        File(context.cacheDir, "$APK_PREFIX$versionName.apk")

    fun download(
        context: Context,
        url: String,
        versionName: String,
        onProgress: (Int) -> Unit,
    ): File? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "AAKeyboardUnlock/$versionName")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val dest = apkFile(context, versionName)
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            var downloaded = 0L

            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }

            if (dest.length() > 0) dest else null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
