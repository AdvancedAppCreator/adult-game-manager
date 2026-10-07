package com.example.f95updater

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/**
 * Migrates a managed game's save data from the previous installation folder into the freshly
 * extracted one. Every detected artifact must copy and verify, otherwise the upgrade is aborted.
 */
object ManagedUpgradeSaveMigration {
    /** Save folders recognised across Ren'Py, RPG Maker, KiriKiri and HTML/Electron games. */
    val SAVE_DIRECTORIES: List<String> = listOf(
        "game/saves",
        "saves",
        "www/save",
        "save",
        "savedata",
        "userdata",
    )

    private val SAVE_FILE_PATTERNS = listOf(
        Regex("""(?i)^Save.*\.rvdata$"""),
        Regex("""(?i)^Save.*\.rvdata2$"""),
        Regex("""(?i)^Save.*\.rxdata$"""),
        Regex("""(?i)^Save.*\.lsd$"""),
    )

    data class Artifacts(
        val directories: List<String>,
        val files: List<String>,
    ) {
        val total: Int get() = directories.size + files.size
        val isEmpty: Boolean get() = total == 0
    }

    fun isSaveFileName(name: String): Boolean = SAVE_FILE_PATTERNS.any { it.matches(name) }

    fun detect(oldRoot: File): Artifacts {
        val directories = SAVE_DIRECTORIES.filter { File(oldRoot, it).isDirectory }
        val files = (oldRoot.listFiles() ?: emptyArray())
            .filter { it.isFile && isSaveFileName(it.name) }
            .map { it.name }
            .sorted()
        return Artifacts(directories, files)
    }

    /**
     * Copies every detected save artifact from [oldRoot] to the same relative path under [newRoot]
     * and verifies the destination byte-for-byte.
     *
     * @return the number of copied artifacts; 0 when the old installation had no recognised saves.
     * @throws IOException when any detected artifact could not be copied or verified.
     */
    fun migrate(oldRoot: File, newRoot: File): Int {
        val artifacts = detect(oldRoot)
        if (artifacts.isEmpty) return 0
        for (relative in artifacts.directories) {
            val source = File(oldRoot, relative)
            val destination = File(newRoot, relative)
            if (destination.exists() && !destination.deleteRecursively()) {
                throw IOException("Could not clear the new save folder '$relative'.")
            }
            if (!destination.mkdirs() && !destination.isDirectory) {
                throw IOException("Could not create the new save folder '$relative'.")
            }
            copyTree(source, destination, relative)
            verifyTree(source, destination, relative)
        }
        for (name in artifacts.files) {
            val source = File(oldRoot, name)
            val destination = File(newRoot, name)
            copyFile(source, destination, name)
            verifyFile(source, destination, name)
        }
        return artifacts.total
    }

    private fun copyTree(source: File, destination: File, label: String) {
        for (child in source.walkTopDown()) {
            val relative = child.relativeTo(source).invariantSeparatorsPath
            if (relative.isEmpty()) continue
            val target = File(destination, relative)
            if (child.isDirectory) {
                if (!target.mkdirs() && !target.isDirectory) {
                    throw IOException("Could not create '$label/$relative'.")
                }
            } else if (child.isFile) {
                copyFile(child, target, "$label/$relative")
            }
        }
    }

    private fun copyFile(source: File, destination: File, label: String) {
        val sourceTimes = readTimes(source, label)
        val parent = destination.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create the folder for '$label'.")
        }
        try {
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (error: IOException) {
            throw IOException("Could not copy save data '$label': ${error.message}", error)
        }
        preserveTimes(destination, sourceTimes, label)
    }

    private fun verifyTree(source: File, destination: File, label: String) {
        for (child in source.walkTopDown()) {
            if (!child.isFile) continue
            val relative = child.relativeTo(source).invariantSeparatorsPath
            verifyFile(child, File(destination, relative), "$label/$relative")
        }
    }

    private fun verifyFile(source: File, destination: File, label: String) {
        if (!destination.isFile) throw IOException("Copied save data is missing: '$label'.")
        if (destination.length() != source.length()) {
            throw IOException("Copied save data has the wrong size: '$label'.")
        }
        if (!sha256(source).contentEquals(sha256(destination))) {
            throw IOException("Copied save data does not match the original: '$label'.")
        }
        val sourceTimes = readTimes(source, label)
        val destinationTimes = readTimes(destination, label)
        if (!sameTime(sourceTimes.modified, destinationTimes.modified)) {
            throw IOException("Copied save data has the wrong modified time: '$label'.")
        }
    }

    private data class FileTimes(
        val modified: FileTime,
        val created: FileTime,
    )

    private fun readTimes(file: File, label: String): FileTimes = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        FileTimes(
            modified = attributes.lastModifiedTime(),
            created = attributes.creationTime(),
        )
    } catch (error: Exception) {
        throw IOException("Could not read save timestamps for '$label': ${error.message}", error)
    }

    private fun preserveTimes(destination: File, times: FileTimes, label: String) {
        try {
            val view = Files.getFileAttributeView(
                destination.toPath(),
                BasicFileAttributeView::class.java,
            ) ?: throw IOException("Basic file timestamps are unavailable.")
            view.setTimes(times.modified, null, null)
            runCatching { view.setTimes(null, null, times.created) }
        } catch (error: Exception) {
            throw IOException("Could not preserve save timestamps for '$label': ${error.message}", error)
        }
    }

    private fun sameTime(expected: FileTime, actual: FileTime): Boolean =
        expected.toMillis() == actual.toMillis()

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

}
