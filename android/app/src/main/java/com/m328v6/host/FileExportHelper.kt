package com.m328v6.host

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

/**
 * Saves Base64-encoded exports (.xlsx, .csv, .png, .log) from the WebView
 * into the device's public Downloads directory (Download/M328V6/ or fallback to Download/).
 */
object FileExportHelper {

    data class SaveResult(val filename: String, val displayPath: String)

    fun saveBase64ToDownloads(
        context: Context,
        rawFilename: String,
        mimeType: String,
        base64Data: String
    ): SaveResult {
        val safeName = rawFilename.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank {
            "M328V6_${System.currentTimeMillis()}.bin"
        }
        val cleanMime = mimeType.substringBefore(';').trim().ifBlank {
            when {
                safeName.endsWith(".log", ignoreCase = true) || safeName.endsWith(".txt", ignoreCase = true) -> "text/plain"
                safeName.endsWith(".csv", ignoreCase = true) -> "text/csv"
                safeName.endsWith(".png", ignoreCase = true) -> "image/png"
                safeName.endsWith(".xlsx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                else -> "application/octet-stream"
            }
        }
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)

        // Pre-create the subfolder on disk to avoid MediaProvider ENOENT on some OEM ROMs
        try {
            val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val subDir = File(pubDir, "M328V6")
            if (!subDir.exists()) {
                subDir.mkdirs()
            }
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver

            // 1. Attempt with subfolder Download/M328V6/
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, cleanMime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/M328V6/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    try {
                        resolver.openOutputStream(uri)?.use { out ->
                            out.write(bytes)
                            out.flush()
                        } ?: throw IllegalStateException("Unable to open output stream")
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        return SaveResult(safeName, "Download/M328V6/$safeName")
                    } catch (e: Exception) {
                        try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                        // Fall through to retry in root Download/
                    }
                }
            } catch (_: Exception) {
                // Fall through to retry in root Download/
            }

            // 2. Fallback attempt: directly into root Download/ (guaranteed to exist on all devices)
            try {
                val fallbackValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, cleanMime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val fallbackUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, fallbackValues)
                if (fallbackUri != null) {
                    resolver.openOutputStream(fallbackUri)?.use { out ->
                        out.write(bytes)
                        out.flush()
                    } ?: throw IllegalStateException("Unable to open fallback output stream")
                    fallbackValues.clear()
                    fallbackValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(fallbackUri, fallbackValues, null, null)
                    return SaveResult(safeName, "Download/$safeName")
                }
            } catch (_: Exception) {
                // Fall through to legacy filesystem fallback
            }
        }

        // 3. Legacy filesystem fallback (Android < 10 or MediaStore failures)
        @Suppress("DEPRECATION")
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val m328Dir = File(downloadDir, "M328V6")
        val targetDir = if (m328Dir.exists() || m328Dir.mkdirs()) m328Dir else downloadDir
        val outFile = File(targetDir, safeName)
        try {
            FileOutputStream(outFile).use { it.write(bytes) }
            val relPath = if (targetDir == m328Dir) "Download/M328V6/$safeName" else "Download/$safeName"
            return SaveResult(safeName, relPath)
        } catch (_: Exception) {
            // Last resort: app internal external files dir
            val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val fallbackFile = File(extDir, safeName)
            FileOutputStream(fallbackFile).use { it.write(bytes) }
            return SaveResult(safeName, fallbackFile.absolutePath)
        }
    }
}
