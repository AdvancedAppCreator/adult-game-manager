package com.example.f95updater

import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ExtractedContentInspector {
    suspend fun listFiles(root: ArchiveExtractor.ExtractRoot): List<String> =
        withContext(Dispatchers.IO) {
            when (root) {
                is ArchiveExtractor.ExtractRoot.FileRoot -> listFileRoot(root.file)
                is ArchiveExtractor.ExtractRoot.Saf -> listSafRoot(root.doc)
            }
        }

    fun resolveFile(root: ArchiveExtractor.ExtractRoot, relativePath: String): File? {
        val fileRoot = (root as? ArchiveExtractor.ExtractRoot.FileRoot)?.file ?: return null
        return runCatching {
            val canonicalRoot = fileRoot.canonicalFile
            val candidate = File(canonicalRoot, relativePath.replace('/', File.separatorChar)).canonicalFile
            val insideRoot =
                candidate.path == canonicalRoot.path ||
                    candidate.path.startsWith(canonicalRoot.path + File.separator)
            candidate.takeIf { insideRoot && it.isFile }
        }.getOrNull()
    }

    private fun listFileRoot(root: File): List<String> =
        root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()

    private fun listSafRoot(root: DocumentFile): List<String> {
        val result = mutableListOf<String>()
        fun visit(directory: DocumentFile, prefix: String) {
            directory.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                val path = if (prefix.isBlank()) name else "$prefix/$name"
                if (child.isDirectory) visit(child, path) else if (child.isFile) result += path
            }
        }
        visit(root, "")
        return result
    }
}
