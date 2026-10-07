package com.example.f95updater

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.github.junrar.rarfile.HostSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File

/**
 * Header-only listing of a patch archive, using the same libraries [ArchiveExtractor] extracts with.
 * Nothing is written anywhere: the result feeds [PatchArchiveScanner], which decides whether the
 * archive may be staged at all.
 */
object PatchArchiveReader {

    /** Unix `S_IFMT` mask and `S_IFLNK` value, as stored by zip, rar and 7z metadata. */
    private const val UNIX_TYPE_MASK = 0xF000
    private const val UNIX_TYPE_LINK = 0xA000

    /** 7z sets this Windows attribute bit when the high 16 bits carry a unix mode. */
    private const val SEVENZ_UNIX_EXTENSION = 0x8000

    sealed interface Listing {
        data class Ok(val entries: List<PatchArchiveEntry>) : Listing
        data class NeedsPassword(val format: ArchiveExtractor.Format) : Listing
        data class Failed(val message: String) : Listing
    }

    suspend fun list(
        archive: File,
        format: ArchiveExtractor.Format,
        password: CharArray?,
    ): Listing = withContext(Dispatchers.IO) {
        runCatching {
            when (format) {
                ArchiveExtractor.Format.ZIP -> listZip(archive, password)
                ArchiveExtractor.Format.RAR -> listRar(archive, password)
                ArchiveExtractor.Format.SEVENZ -> listSevenZ(archive, password)
            }
        }.fold(
            onSuccess = { Listing.Ok(it) },
            onFailure = { error ->
                if (ArchiveExtractor.isPasswordError(error)) {
                    Listing.NeedsPassword(format)
                } else {
                    Listing.Failed(
                        "AGM could not read this archive's entry list: " +
                            (error.message ?: error::class.simpleName),
                    )
                }
            },
        )
    }

    private fun listZip(archive: File, password: CharArray?): List<PatchArchiveEntry> {
        val zip = if (password != null) ZipFile(archive, password) else ZipFile(archive)
        if (zip.isEncrypted && password == null) {
            throw net.lingala.zip4j.exception.ZipException(
                "Password required",
                net.lingala.zip4j.exception.ZipException.Type.WRONG_PASSWORD,
            )
        }
        return zip.fileHeaders.map { header ->
            PatchArchiveEntry(
                rawName = header.fileName,
                size = header.uncompressedSize.coerceAtLeast(0L),
                kind = when {
                    header.isDirectory -> PatchEntryKind.Directory
                    isUnixLink(zipUnixMode(header.externalFileAttributes)) -> PatchEntryKind.Link
                    else -> PatchEntryKind.File
                },
            )
        }
    }

    private fun zipUnixMode(attributes: ByteArray?): Int {
        if (attributes == null || attributes.size < 4) return 0
        return ((attributes[3].toInt() and 0xFF) shl 8) or (attributes[2].toInt() and 0xFF)
    }

    private fun listRar(archive: File, password: CharArray?): List<PatchArchiveEntry> {
        val pwd = password?.let { String(it) }
        val junrar = runCatching { listRarWithJunrar(archive, pwd) }
        junrar.getOrNull()?.let { return it }
        val error = junrar.exceptionOrNull()
        if (error != null && ArchiveExtractor.isPasswordError(error)) throw error
        if (!UnrarNative.available) {
            throw error ?: IllegalStateException("No RAR reader is available on this device.")
        }
        // RAR5 is unreadable by junrar. The native lister has no link metadata, which is why the
        // staged tree is scanned for links before anything is installed.
        return UnrarNative.listEntries(archive, pwd).map { entry ->
            PatchArchiveEntry(
                rawName = entry.name,
                size = entry.size.coerceAtLeast(0L),
                kind = if (entry.isDirectory) PatchEntryKind.Directory else PatchEntryKind.File,
            )
        }
    }

    private fun listRarWithJunrar(archive: File, password: String?): List<PatchArchiveEntry> {
        val rar = if (password != null) Archive(archive, password) else Archive(archive)
        rar.use { open ->
            if (open.isEncrypted && password == null) {
                throw UnrarPasswordRequiredException("Password required")
            }
            val entries = mutableListOf<PatchArchiveEntry>()
            var header = open.nextFileHeader()
            while (header != null) {
                entries += PatchArchiveEntry(
                    rawName = header.fileNameString,
                    size = header.fullUnpackSize.coerceAtLeast(0L),
                    kind = when {
                        header.isDirectory -> PatchEntryKind.Directory
                        isRarLink(header) -> PatchEntryKind.Link
                        else -> PatchEntryKind.File
                    },
                )
                header = open.nextFileHeader()
            }
            return entries
        }
    }

    private fun isRarLink(header: FileHeader): Boolean {
        val host = runCatching { header.hostOS }.getOrNull()
        val unixHost = host == HostSystem.unix || host == HostSystem.beos || host == HostSystem.macos
        return unixHost && isUnixLink(header.fileAttr)
    }

    private fun listSevenZ(archive: File, password: CharArray?): List<PatchArchiveEntry> {
        val sevenZ = if (password != null) SevenZFile(archive, password) else SevenZFile(archive)
        sevenZ.use { file ->
            val entries = mutableListOf<PatchArchiveEntry>()
            var entry = file.nextEntry
            while (entry != null) {
                val attributes = if (entry.hasWindowsAttributes) entry.windowsAttributes else 0
                val unixMode = if (attributes and SEVENZ_UNIX_EXTENSION != 0) attributes ushr 16 else 0
                entries += PatchArchiveEntry(
                    rawName = entry.name.orEmpty(),
                    size = entry.size.coerceAtLeast(0L),
                    kind = when {
                        entry.isDirectory -> PatchEntryKind.Directory
                        entry.isAntiItem -> PatchEntryKind.Link
                        isUnixLink(unixMode) -> PatchEntryKind.Link
                        else -> PatchEntryKind.File
                    },
                )
                entry = file.nextEntry
            }
            return entries
        }
    }

    private fun isUnixLink(mode: Int): Boolean =
        mode != 0 && (mode and UNIX_TYPE_MASK) == UNIX_TYPE_LINK
}

/** Verification of an already staged patch tree, before anything is copied into a game. */
object StagedPatchVerifier {

    sealed interface Outcome {
        data object Ok : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    /**
     * Confirms the staged tree contains exactly the approved relative paths and sizes, and that no
     * staged entry is a symbolic link. This is the backstop for archive formats whose headers do not
     * expose link metadata.
     */
    fun verify(stagingRoot: File, scan: PatchArchiveScan.Accepted): Outcome {
        if (!stagingRoot.isDirectory) {
            return Outcome.Rejected("The staged patch folder is missing.")
        }
        val basePath = stagingRoot.absoluteFile.path
        val actual = mutableMapOf<String, Long>()
        val queue = ArrayDeque<File>()
        queue += stagingRoot.absoluteFile
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles()
                ?: return Outcome.Rejected("AGM cannot read ${current.absolutePath}.")
            for (child in children) {
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                    return Outcome.Rejected(
                        "The staged patch contains a link (${child.absolutePath}), so it was rejected.",
                    )
                }
                if (child.isDirectory) {
                    queue += child
                } else if (child.isFile) {
                    val relative = child.path.removePrefix(basePath)
                        .replace(File.separatorChar, '/')
                        .trimStart('/')
                    actual[relative] = child.length()
                } else {
                    return Outcome.Rejected(
                        "The staged patch contains ${child.absolutePath}, which is not a regular file.",
                    )
                }
            }
        }

        val expected = scan.files.associate { it.relativePath to it.size }
        val missing = expected.keys - actual.keys
        if (missing.isNotEmpty()) {
            return Outcome.Rejected(
                "The staged patch is missing ${missing.size} approved file(s), " +
                    "starting with '${missing.first()}'.",
            )
        }
        val unexpected = actual.keys - expected.keys
        if (unexpected.isNotEmpty()) {
            return Outcome.Rejected(
                "The staged patch produced ${unexpected.size} file(s) AGM did not approve, " +
                    "starting with '${unexpected.first()}'.",
            )
        }
        val mismatched = expected.entries.firstOrNull { actual[it.key] != it.value }
        if (mismatched != null) {
            return Outcome.Rejected(
                "Staged file '${mismatched.key}' is ${actual[mismatched.key]} bytes but the archive " +
                    "declared ${mismatched.value}.",
            )
        }
        return Outcome.Ok
    }
}
