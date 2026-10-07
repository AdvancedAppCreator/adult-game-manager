package com.example.f95updater

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

object SharedGamesRoot {
    data class Resolved(
        val uri: Uri,
        val document: DocumentFile,
        val absoluteDirectory: File?,
    )

    suspend fun get(context: Context): Resolved? {
        val uri = JoiPlayScanner.getRootUri(context) ?: return null
        val absoluteDirectory = resolveTreeUri(context, uri)
        val document = if (uri.scheme == "file") {
            absoluteDirectory?.takeIf { it.isDirectory }?.let(DocumentFile::fromFile)
        } else {
            DocumentFile.fromTreeUri(context, uri)
        }?.takeIf { it.isDirectory } ?: return null
        return Resolved(uri, document, absoluteDirectory)
    }

    fun extractionRoot(resolved: Resolved, requireAbsolutePath: Boolean): ArchiveExtractor.ExtractRoot? {
        val directory = resolved.absoluteDirectory
        if (directory != null && directory.isDirectory && directory.canWrite()) {
            return ArchiveExtractor.ExtractRoot.FileRoot(directory)
        }
        if (requireAbsolutePath) return null
        return ArchiveExtractor.ExtractRoot.Saf(resolved.document)
    }

    fun resolveTreeUri(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.canonicalFile
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val parsed = parseDocumentId(documentId) ?: return null
        val volumeRoot = when {
            parsed.volume.equals("primary", ignoreCase = true) ->
                Environment.getExternalStorageDirectory()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val manager = context.getSystemService(StorageManager::class.java)
                manager.storageVolumes.firstOrNull {
                    it.uuid?.equals(parsed.volume, ignoreCase = true) == true
                }?.directory
            }
            else -> null
        } ?: return null
        return runCatching {
            parsed.relativePath
                .split('/')
                .filter { it.isNotBlank() }
                .fold(volumeRoot) { current, child -> File(current, child) }
                .canonicalFile
        }.getOrNull()
    }

    data class DocumentPath(val volume: String, val relativePath: String)

    internal fun parseDocumentId(documentId: String): DocumentPath? {
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null
        return DocumentPath(
            volume = documentId.substring(0, separator),
            relativePath = documentId.substring(separator + 1).trimStart('/'),
        )
    }
}
