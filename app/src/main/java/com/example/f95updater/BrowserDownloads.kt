package com.example.f95updater

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile

object BrowserDownloads {
    data class LegacyTargetReservation(
        val file: File,
        val verifiedExisting: Boolean,
    )

    private val archiveExtensions = setOf(
        "zip", "rar", "7z", "apk", "xapk", "apks", "apkm", "tar", "gz", "bz2", "xz",
    )
    private val extendedFileName = Regex(
        """(?:^|;)\s*filename\*\s*=\s*([^;]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val regularFileName = Regex(
        """(?:^|;)\s*filename\s*=\s*(?:"((?:\\.|[^"])*)"|([^;]*))""",
        RegexOption.IGNORE_CASE,
    )

    fun contentDispositionFileName(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val extended = extendedFileName.find(contentDisposition)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.trim('"')
            ?.let(::decodeExtendedFileName)
            ?.let(::sanitizeFileName)
        if (extended != null) return extended
        val match = regularFileName.find(contentDisposition) ?: return null
        val value = (match.groupValues[1].ifBlank { match.groupValues[2] })
            .replace(Regex("""\\(.)"""), "$1")
        return sanitizeFileName(value)
    }

    fun preferredFileName(
        preferredFileName: String?,
        contentDisposition: String?,
        pageTitle: String?,
    ): String? =
        preferredFileName?.let(::sanitizeFileName)
            ?: contentDispositionFileName(contentDisposition)
            ?: pageTitleFileName(pageTitle)

    fun sanitizeFileName(raw: String): String? {
        val baseName = raw.trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""[\u0000-\u001f\u007f/:*?"<>|]"""), "_")
            .trim()
            .trimEnd('.')
        if (baseName.isBlank() || baseName == "." || baseName == "..") return null
        return baseName
    }

    fun limitFileName(raw: String, maxChars: Int = 180, maxUtf8Bytes: Int = 240): String {
        val dot = raw.lastIndexOf('.')
        val extension = if (dot > 0 && raw.length - dot <= 12) raw.substring(dot) else ""
        val base = if (extension.isEmpty()) raw else raw.dropLast(extension.length)
        val baseBudget = (maxUtf8Bytes - extension.toByteArray(Charsets.UTF_8).size).coerceAtLeast(1)
        val limited = takeUtf8Prefix(base, minOf(maxChars - extension.length, base.length), baseBudget)
        return (limited + extension).ifBlank { "download" }
    }

    private fun takeUtf8Prefix(value: String, maxChars: Int, maxBytes: Int): String {
        var index = 0
        var chars = 0
        var bytes = 0
        while (index < value.length && chars < maxChars) {
            val codePoint = value.codePointAt(index)
            val encoded = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + encoded > maxBytes) break
            bytes += encoded
            index += Character.charCount(codePoint)
            chars++
        }
        return value.substring(0, index)
    }

    private fun decodeExtendedFileName(raw: String): String? {
        val firstQuote = raw.indexOf('\'')
        val secondQuote = if (firstQuote >= 0) raw.indexOf('\'', firstQuote + 1) else -1
        if (firstQuote <= 0 || secondQuote < 0) return null
        val charset = runCatching { Charset.forName(raw.substring(0, firstQuote)) }.getOrNull()
            ?: return null
        val encoded = raw.substring(secondQuote + 1).replace("+", "%2B")
        return runCatching { URLDecoder.decode(encoded, charset.name()) }.getOrNull()
    }

    fun pageTitleFileName(title: String?): String? {
        val candidate = title?.trim().orEmpty()
        if (candidate.isBlank() || candidate.length > 240) return null
        if ('/' in candidate || '\\' in candidate) return null
        val extension = candidate.substringAfterLast('.', "").lowercase()
        return candidate.takeIf { extension in archiveExtensions }
    }

    fun correctGenericDownloadName(file: File, fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("", "bin", "download")) return fileName
        val header = runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(512)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                buffer.copyOf(count)
            }
        }.getOrNull() ?: return fileName
        val detectedExtension = when {
            header.startsWith(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07) -> "rar"
            header.startsWith(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c) -> "7z"
            header.startsWith(0x50, 0x4b) -> if (isApk(file)) "apk" else "zip"
            header.startsWith(0x1f, 0x8b) -> "gz"
            header.startsWith(0x42, 0x5a, 0x68) -> "bz2"
            header.startsWith(0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00) -> "xz"
            header.size >= 262 &&
                header.copyOfRange(257, 262).contentEquals("ustar".encodeToByteArray()) -> "tar"
            header.startsWith(0x4d, 0x5a) -> "exe"
            header.startsWith(0x25, 0x50, 0x44, 0x46) -> "pdf"
            header.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "png"
            header.startsWith(0xff, 0xd8, 0xff) -> "jpg"
            header.startsWith(0x47, 0x49, 0x46, 0x38) -> "gif"
            header.size >= 12 &&
                header.copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray()) &&
                header.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray()) -> "webp"
            header.size >= 12 &&
                header.copyOfRange(4, 8).contentEquals("ftyp".encodeToByteArray()) -> "mp4"
            header.startsWith(0x1a, 0x45, 0xdf, 0xa3) -> "mkv"
            else -> return fileName
        }
        val base = fileName.substringBeforeLast('.', fileName).ifBlank { "download" }
        return "$base.$detectedExtension"
    }

    private fun isApk(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry("AndroidManifest.xml") != null
        }
    }.getOrDefault(false)

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { this[it] == values[it].toByte() }

    fun reserveTargetFile(folder: File, fileName: String): File {
        if (!folder.exists() && !folder.mkdirs()) {
            throw IOException("Could not create download folder ${folder.absolutePath}")
        }
        if (!folder.isDirectory || !folder.canWrite()) {
            throw IOException("Download folder is not writable: ${folder.absolutePath}")
        }
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var suffix = 0
        while (true) {
            val candidate = File(
                folder,
                if (suffix == 0) fileName else "$base ($suffix)$extension",
            )
            if (candidate.createNewFile()) return candidate
            suffix++
        }
    }

    suspend fun reserveLegacyTarget(
        folder: File,
        fileName: String,
        staged: File,
        onProgress: suspend (Long) -> Unit = {},
    ): LegacyTargetReservation {
        val preferred = File(folder, fileName)
        if (preferred.isFile &&
            preferred.length() == staged.length() &&
            filesEqual(preferred, staged, onProgress)
        ) {
            return LegacyTargetReservation(preferred, verifiedExisting = true)
        }
        return LegacyTargetReservation(
            reserveTargetFile(folder, fileName),
            verifiedExisting = false,
        )
    }

    suspend fun finalizeDownloadedFile(
        staged: File,
        target: File,
        expectedBytes: Long,
        targetContentVerified: Boolean = false,
        onProgress: suspend (Long) -> Unit = {},
    ): File {
        if (!staged.isFile) throw IOException("Downloaded staging file is missing")
        val actualBytes = staged.length()
        if (expectedBytes > 0L && actualBytes != expectedBytes) {
            throw IOException("Downloaded file size is $actualBytes bytes; expected $expectedBytes")
        }
        if (target.isFile && target.length() > 0L) {
            if (target.length() == actualBytes &&
                (targetContentVerified || filesEqual(target, staged, onProgress))
            ) {
                if (!staged.delete()) throw IOException("Could not remove verified duplicate staging file")
                onProgress(actualBytes)
                return target
            }
            throw IOException("Reserved destination contains different data: ${target.absolutePath}")
        }
        target.parentFile?.let {
            if (!it.exists() && !it.mkdirs()) {
                throw IOException("Could not create download folder ${it.absolutePath}")
            }
        }
        if (staged.parentFile?.canonicalFile == target.parentFile?.canonicalFile) {
            moveReplacing(staged, target)
            onProgress(actualBytes)
            return target
        }
        if (target.parentFile?.usableSpace?.let { it < actualBytes } == true) {
            throw IOException(
                "Not enough free space to finalize ${target.name}: " +
                    "${target.parentFile?.usableSpace ?: 0L} bytes available, $actualBytes required",
            )
        }
        val temporary = File(target.parentFile, ".agm-${UUID.randomUUID()}.part")
        try {
            copyWithProgress(staged, temporary, onProgress)
            if (temporary.length() != actualBytes) {
                throw IOException("Finalization copy size mismatch")
            }
            moveReplacing(temporary, target)
            if (!staged.delete()) throw IOException("Could not remove staging file after finalization")
            return target
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private suspend fun copyWithProgress(
        source: File,
        target: File,
        onProgress: suspend (Long) -> Unit,
    ) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    onProgress(copied)
                }
                output.fd.sync()
            }
        }
    }

    private suspend fun filesEqual(
        first: File,
        second: File,
        onProgress: suspend (Long) -> Unit = {},
    ): Boolean {
        if (first.length() != second.length()) return false
        FileInputStream(first).buffered().use { left ->
            FileInputStream(second).buffered().use { right ->
                val leftBuffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                val rightBuffer = ByteArray(leftBuffer.size)
                var compared = 0L
                while (true) {
                    val leftCount = left.read(leftBuffer)
                    val rightCount = right.read(rightBuffer)
                    if (leftCount != rightCount) return false
                    if (leftCount < 0) return true
                    for (index in 0 until leftCount) {
                        if (leftBuffer[index] != rightBuffer[index]) return false
                    }
                    compared += leftCount
                    onProgress(compared)
                }
            }
        }
    }
}
