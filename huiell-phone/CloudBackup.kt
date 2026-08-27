package com.qwen.tts.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Mirrors Huiell's small learning files to a user-selected Drive folder.
 * Models stay on the phone; passwords and browser cookies are never backed up.
 */
class CloudBackup(private val context: Context) {
    private val prefs = context.getSharedPreferences("huiell-cloud", Context.MODE_PRIVATE)

    val configured: Boolean
        get() = treeUri() != null

    fun setFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    suspend fun restoreMissing(files: Map<String, File>): Int = withContext(Dispatchers.IO) {
        val root = root() ?: return@withContext 0
        var restored = 0
        for ((relative, local) in files) {
            if (local.isFile && local.length() > 0L) continue
            val remote = find(root, relative) ?: continue
            runCatching {
                local.parentFile?.mkdirs()
                context.contentResolver.openInputStream(remote.uri)?.use { input ->
                    local.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                } ?: error("Drive file cannot be read")
                restored += 1
            }
        }
        restored
    }

    suspend fun backup(files: Map<String, File>): Int = withContext(Dispatchers.IO) {
        val root = root() ?: return@withContext 0
        var copied = 0
        for ((relative, local) in files) {
            if (!local.isFile) continue
            val remote = ensureFile(root, relative, "application/x-ndjson") ?: continue
            runCatching {
                context.contentResolver.openOutputStream(remote.uri, "wt")?.use { output ->
                    local.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
                } ?: error("Drive file cannot be written")
                copied += 1
            }
        }
        writeDeviceManifest(root)
        copied
    }

    private fun treeUri(): Uri? = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    private fun root(): DocumentFile? = treeUri()?.let { DocumentFile.fromTreeUri(context, it) }

    private fun find(root: DocumentFile, relative: String): DocumentFile? {
        var current = root
        relative.split('/').filter { it.isNotBlank() }.forEach { part ->
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private fun ensureFile(root: DocumentFile, relative: String, mime: String): DocumentFile? {
        val parts = relative.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        var current = root
        for (part in parts.dropLast(1)) {
            current = current.findFile(part) ?: current.createDirectory(part) ?: return null
        }
        val name = parts.last()
        return current.findFile(name) ?: current.createFile(mime, name)
    }

    private fun writeDeviceManifest(root: DocumentFile) {
        val file = root.findFile("phone-device.json")
            ?: root.createFile("application/json", "phone-device.json")
            ?: return
        val json = JSONObject()
            .put("app", "Huiell")
            .put("version", "1.0.0")
            .put("updatedAt", System.currentTimeMillis())
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("containsModels", false)
            .put("containsPasswords", false)
        context.contentResolver.openOutputStream(file.uri, "wt")?.use {
            it.write(json.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        private const val KEY_TREE_URI = "backup_tree_uri"
    }
}
