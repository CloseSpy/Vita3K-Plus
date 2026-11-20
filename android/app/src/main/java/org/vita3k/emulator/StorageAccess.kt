package org.vita3k.emulator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

object StorageAccess {
    private const val MAX_STAGED_BYTES = 12L * 1024 * 1024 * 1024
    private const val MIN_FREE_BYTES = 128L * 1024 * 1024
    private const val MAX_BATCH_FILES = 128
    private val stagingLock = Any()

    @JvmStatic
    fun createFilePickerIntent(mimeTypes: Array<String>): Intent {
        val types = mimeTypes.map(String::trim).filter(String::isNotEmpty).distinct()
        return Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setType(types.singleOrNull() ?: "*/*")
            .apply { if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray()) }
    }

    @JvmStatic
    fun createFolderPickerIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    @JvmStatic
    fun stageDocument(context: Context, uri: Uri, extensions: Set<String>): String? =
        synchronized(stagingLock) { stageFile(context, uri, displayName(context, uri), extensions) }

    fun stageTree(context: Context, uri: Uri, extensions: Set<String>): List<String> = synchronized(stagingLock) {
        stageTreeLocked(context, uri, extensions)
    }

    private fun stageTreeLocked(context: Context, uri: Uri, extensions: Set<String>): List<String> {
        if (uri.scheme != "content" || extensions.isEmpty()) return emptyList()
        return try {
            val tree = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
            val files = tree.listFiles().filter {
                it.isFile && matchesAllowedExtension(it.name, extensions)
            }.sortedBy { it.name }
            if (files.isEmpty() || files.size > MAX_BATCH_FILES) return emptyList()
            val staged = mutableListOf<File>()
            for (document in files) {
                val path = stageFile(context, document.uri, document.name, extensions)
                if (path == null) {
                    staged.forEach { it.delete(); it.parentFile?.delete() }
                    return emptyList()
                }
                staged.add(File(path))
            }
            staged.map(File::getAbsolutePath)
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    @JvmStatic
    fun matchesAllowedExtension(pathOrName: String?, extensions: Set<String>): Boolean {
        if (extensions.isEmpty()) return true
        val name = pathOrName?.lowercase(Locale.ROOT) ?: return false
        return extensions.any { name.endsWith("." + it.trimStart('.').lowercase(Locale.ROOT)) }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme != "content") return null
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun stageFile(context: Context, uri: Uri, displayName: String?, extensions: Set<String>): String? {
        val name = displayName ?: return null
        if (uri.scheme != "content" || name.length !in 1..180 || name == "." || name == ".." ||
            name.any { it == '/' || it == '\\' || it.code < 32 } || !matchesAllowedExtension(name, extensions)
        ) return null
        val root = File(context.filesDir, "scoped-imports")
        if (!root.isDirectory && !root.mkdir()) return null
        val remaining = minOf(
            MAX_STAGED_BYTES - root.walkTopDown().filter(File::isFile).sumOf(File::length),
            root.usableSpace - MIN_FREE_BYTES
        )
        if (remaining <= 0) return null
        val stage = File(root, UUID.randomUUID().toString())
        if (!stage.mkdir()) return null
        val target = File(stage, name)
        var complete = false
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var written = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > remaining) throw IOException("Import exceeds available staging space")
                        output.write(buffer, 0, count)
                    }
                    if (written == 0L) throw IOException("Empty import")
                }
            }
            complete = true
            return target.absolutePath
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        } finally {
            if (!complete) {
                target.delete()
                stage.delete()
            }
        }
    }
}
