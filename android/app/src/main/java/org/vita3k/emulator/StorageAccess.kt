package org.vita3k.emulator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
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

    fun createInstallPickerIntent(extensions: Set<String>): Intent {
        if (!isArchiveImport(extensions)) return createFilePickerIntent(arrayOf("*/*"))
        // Let the user select an installed document provider, even when ZIP/VPK
        // is advertised as octet-stream or omitted from its openable results.
        val request = Intent(Intent.ACTION_GET_CONTENT)
            .setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(request, "Choose a ZIP or VPK archive")
    }

    fun selectedDocument(result: Intent?): Uri? {
        val clip = result?.clipData
        if (clip != null && clip.itemCount != 1) return null
        val data = result?.data
        val item = clip?.getItemAt(0)?.uri
        if (data != null && item != null && data != item) return null
        return (data ?: item)?.takeIf { it.scheme == "content" }
    }

    private fun isArchiveImport(extensions: Set<String>): Boolean =
        extensions.isNotEmpty() && extensions.all { it.lowercase(Locale.ROOT) in setOf(".zip", ".vpk", "zip", "vpk") }

    @JvmStatic
    fun createFolderPickerIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    @JvmStatic
    fun stageDocument(context: Context, uri: Uri, extensions: Set<String>): String? =
        synchronized(stagingLock) { stageFile(context, uri, documentMetadata(context, uri), extensions) }

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
                val path = stageFile(context, document.uri, DocumentMetadata(document.name, document.length().takeIf { it > 0 }), extensions)
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

    private data class DocumentMetadata(val name: String?, val size: Long?)

    private fun documentMetadata(context: Context, uri: Uri): DocumentMetadata {
        if (uri.scheme != "content") return DocumentMetadata(null, null)
        val fallback = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use {
                    if (!it.moveToFirst()) return@use null
                    val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
                    DocumentMetadata(
                        if (nameColumn >= 0 && !it.isNull(nameColumn)) it.getString(nameColumn) else fallback,
                        if (sizeColumn >= 0 && !it.isNull(sizeColumn)) it.getLong(sizeColumn).takeIf { n -> n >= 0 } else null
                    )
                } ?: DocumentMetadata(fallback, null)
        } catch (_: IllegalArgumentException) {
            DocumentMetadata(fallback, null)
        } catch (_: SecurityException) {
            DocumentMetadata(null, null)
        }
    }

    private fun stageFile(context: Context, uri: Uri, document: DocumentMetadata, extensions: Set<String>): String? {
        if (uri.scheme != "content") return null
        val archiveImport = isArchiveImport(extensions)
        val displayName = document.name?.takeIf { candidate ->
            candidate.length in 1..180 && candidate != "." && candidate != ".." &&
                candidate.none { it == '/' || it == '\\' || it.code < 32 }
        }
        val name = displayName?.takeIf { matchesAllowedExtension(it, extensions) }
            ?: if (archiveImport && extensions.any { it.trimStart('.').equals("zip", ignoreCase = true) }) {
                "selected-archive.zip"
            } else return null

        val roots = listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
            .map { File(it, "scoped-imports") }
            .distinctBy(File::getAbsolutePath)
        val used = roots.filter(File::isDirectory).sumOf { dir ->
            dir.walkTopDown().filter(File::isFile).sumOf(File::length)
        }
        val root = roots.firstOrNull { dir ->
            (dir.isDirectory || dir.mkdir()) &&
                minOf(MAX_STAGED_BYTES - used, dir.usableSpace - MIN_FREE_BYTES) >= (document.size ?: 1L)
        } ?: return null
        val remaining = minOf(MAX_STAGED_BYTES - used, root.usableSpace - MIN_FREE_BYTES)
        if (remaining <= 0) return null
        val stage = File(root, UUID.randomUUID().toString())
        if (!stage.mkdir()) return null
        val target = File(stage, name)
        var complete = false
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            BufferedInputStream(input, 128 * 1024).use { source ->
                if (archiveImport) {
                    source.mark(4)
                    val zipHeader = source.read() == 0x50 && source.read() == 0x4b &&
                        source.read() == 0x03 && source.read() == 0x04
                    if (!zipHeader) return null
                    source.reset()
                }
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
                    if (written == 0L || (document.size != null && written != document.size)) {
                        throw IOException("Incomplete import")
                    }
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
