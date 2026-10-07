package com.example.f95updater

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Generic compressed-archive extractor used by the "Install game in JoiPlay" flow.
 *
 * Supports ZIP (with or without password), RAR, and 7Z. All extraction goes through
 * a unified interface that streams entries one at a time, reports progress as bytes
 * processed, and writes to a SAF DocumentFile tree (or a File destination when the
 * caller has MANAGE_EXTERNAL_STORAGE).
 *
 * Safety:
 *  • Zip-slip guard — entry names that try to escape the destination are rejected.
 *  • Declared and actual output limits prevent archives from exceeding the extraction cap.
 *  • If the archive lays everything under a single top-level folder, we strip it so
 *    the destination doesn't end up doubly nested.
 *  • Cancellation deletes whatever's been written so far.
 */
object ArchiveExtractor {

    enum class Format { ZIP, RAR, SEVENZ }

    sealed class Outcome {
        data class Ok(val rootFolder: ExtractRoot, val bytesWritten: Long) : Outcome()
        data class NeedsPassword(val format: Format) : Outcome()
        data class Failed(val message: String, val cause: Throwable? = null) : Outcome()
        object Cancelled : Outcome()
    }

    /** Wraps either a DocumentFile (SAF) or a File (full-storage) destination. */
    sealed class ExtractRoot {
        abstract val displayPath: String
        data class Saf(val doc: DocumentFile) : ExtractRoot() {
            override val displayPath = doc.uri.toString()
        }
        data class FileRoot(val file: File) : ExtractRoot() {
            override val displayPath = file.absolutePath
        }
    }

    data class Progress(
        val bytesWritten: Long,
        val bytesTotal: Long,
        val entriesProcessed: Int,
        val entriesTotal: Int,
        val currentEntry: String,
    ) {
        val percent: Float
            get() = if (bytesTotal > 0) (bytesWritten.toFloat() / bytesTotal) else 0f
    }

    private const val MAX_TOTAL_BYTES: Long = 10L * 1024L * 1024L * 1024L  // 10 GiB
    private const val MAX_ENTRIES = 100_000
    private const val FREE_SPACE_RESERVE_BYTES = 256L * 1024L * 1024L
    private const val BUFFER_SIZE = 64 * 1024

    private class ArchiveLimitException(message: String) : java.io.IOException(message)

    /** Detect format by reading the first few bytes (more reliable than extension). */
    fun detectFormat(input: InputStream): Format? {
        val head = ByteArray(8)
        val n = input.read(head)
        if (n < 4) return null
        // ZIP: PK\x03\x04 or PK\x05\x06 (empty) or PK\x07\x08 (spanned)
        if (head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) return Format.ZIP
        // RAR4: Rar!\x1A\x07\x00 ; RAR5: Rar!\x1A\x07\x01\x00
        if (head[0] == 0x52.toByte() && head[1] == 0x61.toByte() &&
            head[2] == 0x72.toByte() && head[3] == 0x21.toByte()
        ) return Format.RAR
        // 7Z: 37 7A BC AF 27 1C
        if (n >= 6 && head[0] == 0x37.toByte() && head[1] == 0x7A.toByte() &&
            head[2] == 0xBC.toByte() && head[3] == 0xAF.toByte() &&
            head[4] == 0x27.toByte() && head[5] == 0x1C.toByte()
        ) return Format.SEVENZ
        return null
    }

    fun detectFormatByExt(name: String?): Format? {
        if (name.isNullOrBlank()) return null
        return when (name.lowercase().substringAfterLast('.', "")) {
            "zip" -> Format.ZIP
            "rar" -> Format.RAR
            "7z" -> Format.SEVENZ
            else -> null
        }
    }

    /**
     * Extract [archive] into a new subdirectory of [destRoot]. [archive] must be a
     * local file (we copy SAF source URIs to cache before calling). Reports progress
     * via [onProgress]. Caller-side coroutine cancellation deletes partial output.
     *
     * @param suggestedName fallback name for the extracted subfolder; the archive
     *        itself may dictate a different name if all entries share one top-level dir.
     * @param forcedSubfolderName when set, extract into this exact subfolder name while
     *        still stripping a common archive root. Used when replacing an existing game.
     */
    suspend fun extract(
        context: Context,
        archive: File,
        format: Format,
        password: CharArray?,
        destRoot: ExtractRoot,
        suggestedName: String,
        forcedSubfolderName: String? = null,
        onProgress: (Progress) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val partial = mutableListOf<Any>()  // tracked for cleanup on cancel
        try {
            val outcome = when (format) {
                Format.ZIP -> extractZip(archive, password, destRoot, suggestedName, forcedSubfolderName, partial, onProgress)
                Format.RAR -> extractRar(archive, password, destRoot, suggestedName, forcedSubfolderName, partial, onProgress)
                Format.SEVENZ -> extractSevenZ(archive, password, destRoot, suggestedName, forcedSubfolderName, partial, onProgress)
            }
            // A NeedsPassword result means nothing usable was written; discard any
            // partial output so the next password attempt starts from a clean slate.
            if (outcome is Outcome.NeedsPassword) cleanup(partial)
            outcome
        } catch (ce: kotlinx.coroutines.CancellationException) {
            cleanup(partial)
            Outcome.Cancelled.also { throw ce }
        } catch (t: Throwable) {
            cleanup(partial)
            // Wrong/missing password surfaces as a thrown exception in several code
            // paths (zip4j, 7z, junrar). Normalise those to NeedsPassword so callers
            // can retry with another password instead of hard-failing.
            if (isPasswordError(t)) Outcome.NeedsPassword(format)
            else Outcome.Failed("Extraction failed: ${t.message ?: t::class.simpleName}", t)
        }
    }

    /**
     * True when [t] indicates a missing or wrong archive password. Used to normalise
     * the many ways the underlying libraries report this (native unrar throws a plain
     * IOException("Password required") from listEntries; zip4j throws a typed
     * ZipException; 7z throws PasswordRequiredException / an "encrypted…password"
     * message).
     */
    internal fun isPasswordError(t: Throwable): Boolean {
        if (t is UnrarPasswordRequiredException) return true
        if ("password" in t::class.java.name.lowercase()) return true
        val m = t.message?.lowercase()
        if (m != null && "password" in m) return true
        if (t is net.lingala.zip4j.exception.ZipException &&
            t.type == net.lingala.zip4j.exception.ZipException.Type.WRONG_PASSWORD
        ) return true
        val cause = t.cause
        return cause != null && cause !== t && isPasswordError(cause)
    }

    suspend fun deleteExtractedRoot(root: ExtractRoot): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            when (root) {
                is ExtractRoot.Saf -> root.doc.delete()
                is ExtractRoot.FileRoot -> root.file.deleteRecursively()
            }
        }.getOrDefault(false)
    }

    /**
     * Cheap, header-level check: does [archive] require a password to extract at all?
     * ZIP/RAR are answered from headers; 7z reads the first file entry's first byte
     * (no full decompression). Never throws — returns false when it genuinely can't tell.
     */
    suspend fun needsPassword(archive: File, format: Format): Boolean = withContext(Dispatchers.IO) {
        when (format) {
            Format.ZIP -> runCatching { ZipFile(archive).isEncrypted }.getOrDefault(false)
            Format.RAR -> runCatching {
                if (UnrarNative.available) { UnrarNative.listEntries(archive, null); false }
                else Archive(archive).use { it.isEncrypted }
            }.getOrElse { isPasswordError(it) }
            Format.SEVENZ -> runCatching {
                SevenZFile(archive).use { f ->
                    var e = f.nextEntry
                    while (e != null && (e.isDirectory || e.size == 0L)) e = f.nextEntry
                    if (e == null) false else { f.read(ByteArray(1)); false }
                }
            }.getOrElse { isPasswordError(it) }
        }
    }

    // ---------- ZIP ----------

    private suspend fun extractZip(
        archive: File,
        password: CharArray?,
        destRoot: ExtractRoot,
        suggestedName: String,
        forcedSubfolderName: String?,
        partial: MutableList<Any>,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        val zf = if (password != null) ZipFile(archive, password) else ZipFile(archive)
        if (zf.isEncrypted && password == null) {
            return Outcome.NeedsPassword(Format.ZIP)
        }
        val headers = zf.fileHeaders
        if (headers.isEmpty()) return Outcome.Failed("Archive is empty")
        validateEntryCount(headers.size)?.let { return Outcome.Failed(it) }

        val names = headers.map { it.fileName }
        val commonRoot = commonRootFolder(names)
        val destSubName = forcedSubfolderName ?: commonRoot ?: suggestedName
        val totalBytes = headers.sumOf { if (it.isDirectory) 0L else it.uncompressedSize.coerceAtLeast(0L) }
        if (totalBytes > MAX_TOTAL_BYTES) {
            return Outcome.Failed("Archive would expand to ${humanSize(totalBytes)} — over the 10 GiB safety cap.")
        }
        validateFreeSpace(destRoot, totalBytes)?.let { return Outcome.Failed(it) }

        val rootContainer = createExtractionRoot(
            destRoot,
            destSubName,
            reuseExisting = forcedSubfolderName != null,
        )
            ?: return Outcome.Failed("Could not create destination folder.")
        partial.add(rootContainer)

        var bytesWritten = 0L
        var entriesProcessed = 0
        val entriesTotal = headers.size

        for (h in headers) {
            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
            val raw = h.fileName.replace('\\', '/')
            val rel = stripCommonRoot(raw, commonRoot)
            if (rel.isBlank() || rel.startsWith("/") || rel.contains("../")) {
                // zip-slip guard
                continue
            }
            entriesProcessed++
            if (h.isDirectory) {
                ensureFolder(rootContainer, rel)
                onProgress(Progress(bytesWritten, totalBytes, entriesProcessed, entriesTotal, rel))
                continue
            }
            val parent = ensureFolder(rootContainer, rel.substringBeforeLast('/', ""))
            val name = rel.substringAfterLast('/')
            val out = createOrReplaceFile(parent, name) ?: continue
            zf.getInputStream(h).use { ins ->
                bytesWritten += streamCopy(ins, out, bytesWritten, totalBytes) { written, current ->
                    onProgress(Progress(written, totalBytes, entriesProcessed, entriesTotal, current ?: rel))
                }
            }
        }
        return Outcome.Ok(rootContainer, bytesWritten)
    }

    // ---------- RAR ----------

    private suspend fun extractRar(
        archive: File,
        password: CharArray?,
        destRoot: ExtractRoot,
        suggestedName: String,
        forcedSubfolderName: String?,
        partial: MutableList<Any>,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        // Native unrar (RARLAB) supports RAR3/4/5; only available on arm64-v8a
        // builds AND only when the destination is a real filesystem path (it
        // can't write into SAF document trees). For SAF destinations, or if the
        // .so didn't load, fall through to junrar (RAR4-only).
        if (UnrarNative.available && destRoot is ExtractRoot.FileRoot) {
            return runCatching {
                extractRarNative(archive, password, destRoot, suggestedName, forcedSubfolderName, partial, onProgress)
            }.getOrElse { t ->
                if (t is UnrarPasswordRequiredException) {
                    return Outcome.NeedsPassword(Format.RAR)
                }
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (isPasswordError(t)) {
                    // Native unrar reported a missing/wrong password via a generic
                    // IOException (listEntriesNative doesn't map it to
                    // UnrarPasswordRequiredException). junrar can't read RAR5, so don't
                    // fall back — surface as NeedsPassword so we can prompt / auto-try.
                    return Outcome.NeedsPassword(Format.RAR)
                }
                AppLog.w("Extract", "native unrar failed, falling back to junrar: ${t.message}")
                extractRarJunrar(archive, password, destRoot, suggestedName, forcedSubfolderName, partial, onProgress)
            }
        }
        return extractRarJunrar(archive, password, destRoot, suggestedName, forcedSubfolderName, partial, onProgress)
    }

    private suspend fun extractRarNative(
        archive: File,
        password: CharArray?,
        destRoot: ExtractRoot.FileRoot,
        suggestedName: String,
        forcedSubfolderName: String?,
        partial: MutableList<Any>,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        val pwd = password?.let { String(it) }
        // Try to pre-list entries (cheap; gives common-root + size cap up front). RAR
        // archives with ENCRYPTED HEADERS can't be listed even with the correct password
        // (the native list path only applies the password after opening the archive).
        // When that happens and we DO have a password, fall through to a blind extract via
        // extractAll (which supplies the password during open) and derive the structure
        // from the staged output afterwards.
        val entries: List<UnrarEntry>? = try {
            UnrarNative.listEntries(archive, pwd)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (isPasswordError(t) && pwd != null) null else throw t
        }
        if (entries != null && entries.isEmpty()) return Outcome.Failed("Archive is empty")
        entries?.let { validateEntryCount(it.size) }?.let { return Outcome.Failed(it) }

        val listedCommonRoot = entries?.map { it.name.replace('\\', '/') }?.let { commonRootFolder(it) }
        val destSubName = forcedSubfolderName ?: listedCommonRoot ?: suggestedName
        val declaredTotal = entries?.filter { !it.isDirectory }?.sumOf { it.size.coerceAtLeast(0L) } ?: 0L
        if (declaredTotal > MAX_TOTAL_BYTES) {
            return Outcome.Failed("Archive would expand to ${humanSize(declaredTotal)} — over the 10 GiB safety cap.")
        }
        validateFreeSpace(destRoot, declaredTotal)?.let { return Outcome.Failed(it) }

        val rootContainer = createExtractionRoot(
            destRoot,
            destSubName,
            reuseExisting = forcedSubfolderName != null,
        ) as? ExtractRoot.FileRoot
            ?: return Outcome.Failed("Could not create destination folder.")
        partial.add(rootContainer)

        // Extract everything into a staging folder under the parent so we can
        // strip the archive's common root afterwards without recursing into the
        // dest we just created.
        val stagingDir = File(rootContainer.file.parentFile, ".unrar-staging-${System.currentTimeMillis()}")
        stagingDir.mkdirs()
        partial.add(ExtractRoot.FileRoot(stagingDir))

        val entriesTotal = entries?.size ?: 0
        var entriesProcessed = 0
        var bytesWritten = 0L
        var capExceeded = false
        val ctx = kotlin.coroutines.coroutineContext

        try {
            UnrarNative.extractAll(archive, stagingDir, pwd, object : UnrarProgressCallback {
                override fun onChunk(bytes: Long, currentName: String?) {
                    if (currentName != null) {
                        // Marks start of a new entry.
                        entriesProcessed = if (entriesTotal > 0) (entriesProcessed + 1).coerceAtMost(entriesTotal)
                                           else entriesProcessed + 1
                        onProgress(Progress(bytesWritten, declaredTotal, entriesProcessed, entriesTotal, currentName))
                    } else if (bytes > 0) {
                        bytesWritten += bytes
                        onProgress(Progress(bytesWritten, maxOf(declaredTotal, bytesWritten), entriesProcessed, entriesTotal, ""))
                    }
                }
                override fun isCancelled(): Boolean {
                    if (bytesWritten > MAX_TOTAL_BYTES) { capExceeded = true; return true }
                    return !ctx.isActive
                }
            })
        } catch (e: InterruptedException) {
            stagingDir.deleteRecursively()
            if (capExceeded) return Outcome.Failed("Archive exceeded the 10 GiB safety cap during extraction.")
            throw kotlinx.coroutines.CancellationException("Cancelled")
        } catch (e: UnrarPasswordRequiredException) {
            stagingDir.deleteRecursively()
            throw e
        } catch (e: Throwable) {
            stagingDir.deleteRecursively()
            throw e
        }
        if (capExceeded) {
            stagingDir.deleteRecursively()
            return Outcome.Failed("Archive exceeded the 10 GiB safety cap during extraction.")
        }

        // Move staged contents into rootContainer, stripping the common root. If we
        // couldn't pre-list (encrypted headers), infer the root from the staged output.
        val commonRoot = listedCommonRoot ?: stagingDir.listFiles()
            ?.filter { it.name.isNotBlank() }
            ?.singleOrNull()?.takeIf { it.isDirectory }?.name
        val source = if (commonRoot != null) File(stagingDir, commonRoot) else stagingDir
        if (source.exists()) {
            source.listFiles()?.forEach { child ->
                val target = File(rootContainer.file, child.name)
                if (target.exists()) target.deleteRecursively()
                if (!child.renameTo(target)) {
                    // Cross-filesystem rename can fail; fall back to copy.
                    child.copyRecursively(target, overwrite = true)
                    child.deleteRecursively()
                }
            }
        }
        stagingDir.deleteRecursively()
        return Outcome.Ok(rootContainer, bytesWritten.coerceAtLeast(declaredTotal))
    }

    private suspend fun extractRarJunrar(
        archive: File,
        password: CharArray?,
        destRoot: ExtractRoot,
        suggestedName: String,
        forcedSubfolderName: String?,
        partial: MutableList<Any>,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        val rar = try {
            if (password != null) Archive(archive, String(password)) else Archive(archive)
        } catch (e: com.github.junrar.exception.UnsupportedRarV5Exception) {
            return Outcome.Failed(
                "This archive is RAR5 and the native unrar library is unavailable on this device's CPU. " +
                        "Workarounds: extract with a file manager first, or ask the uploader for ZIP/7Z."
            )
        } catch (e: com.github.junrar.exception.RarException) {
            return Outcome.Failed("Could not open RAR archive: ${e.message}", e)
        }
        rar.use { a ->
            if (a.isEncrypted && password == null) return Outcome.NeedsPassword(Format.RAR)
            val headers = mutableListOf<FileHeader>()
            var h = a.nextFileHeader()
            while (h != null) { headers.add(h); h = a.nextFileHeader() }
            if (headers.isEmpty()) return Outcome.Failed("Archive is empty")
            validateEntryCount(headers.size)?.let { return Outcome.Failed(it) }

            val names = headers.map { it.fileNameString.replace('\\', '/') }
            val commonRoot = commonRootFolder(names)
            val destSubName = forcedSubfolderName ?: commonRoot ?: suggestedName
            val totalBytes = headers.sumOf { if (it.isDirectory) 0L else it.fullUnpackSize.coerceAtLeast(0L) }
            if (totalBytes > MAX_TOTAL_BYTES) {
                return Outcome.Failed("Archive would expand to ${humanSize(totalBytes)} — over the 10 GiB safety cap.")
            }
            validateFreeSpace(destRoot, totalBytes)?.let { return Outcome.Failed(it) }

            val rootContainer = createExtractionRoot(
                destRoot,
                destSubName,
                reuseExisting = forcedSubfolderName != null,
            )
                ?: return Outcome.Failed("Could not create destination folder.")
            partial.add(rootContainer)

            var bytesWritten = 0L
            var entriesProcessed = 0
            val entriesTotal = headers.size
            for (fh in headers) {
                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                val raw = fh.fileNameString.replace('\\', '/')
                val rel = stripCommonRoot(raw, commonRoot)
                if (rel.isBlank() || rel.startsWith("/") || rel.contains("../")) continue
                entriesProcessed++
                if (fh.isDirectory) {
                    ensureFolder(rootContainer, rel)
                    onProgress(Progress(bytesWritten, totalBytes, entriesProcessed, entriesTotal, rel))
                    continue
                }
                val parent = ensureFolder(rootContainer, rel.substringBeforeLast('/', ""))
                val name = rel.substringAfterLast('/')
                val out = createOrReplaceFile(parent, name) ?: continue
                val ctx = coroutineContext
                var entryWritten = 0L
                openWrite(out).use { os ->
                    val delegate = os
                    a.extractFile(fh, object : FilterOutputStream(delegate) {
                        override fun write(b: Int) {
                            if (!ctx.isActive) throw kotlinx.coroutines.CancellationException()
                            enforceActualLimit(bytesWritten, entryWritten, 1)
                            delegate.write(b)
                            entryWritten++
                        }

                        override fun write(b: ByteArray, off: Int, len: Int) {
                            if (!ctx.isActive) throw kotlinx.coroutines.CancellationException()
                            enforceActualLimit(bytesWritten, entryWritten, len)
                            delegate.write(b, off, len)
                            entryWritten += len
                        }
                    })
                }
                bytesWritten += entryWritten
                onProgress(Progress(bytesWritten, totalBytes, entriesProcessed, entriesTotal, rel))
            }
            return Outcome.Ok(rootContainer, bytesWritten)
        }
    }

    // ---------- 7Z ----------

    private suspend fun extractSevenZ(
        archive: File,
        password: CharArray?,
        destRoot: ExtractRoot,
        suggestedName: String,
        forcedSubfolderName: String?,
        partial: MutableList<Any>,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        var preparedRoot: ExtractRoot? = null
        val sz = if (password != null) SevenZFile(archive, password) else SevenZFile(archive)
        sz.use { f ->
            // 7z lib doesn't expose "isEncrypted" before reading; it'll throw if password is required.
            val allEntries = generateSequence { runCatching { f.nextEntry }.getOrNull() }.toList()
            if (allEntries.isEmpty()) return Outcome.Failed("Archive is empty or password-protected")
            validateEntryCount(allEntries.size)?.let { return Outcome.Failed(it) }

            val names = allEntries.map { it.name.replace('\\', '/') }
            val commonRoot = commonRootFolder(names)
            val destSubName = forcedSubfolderName ?: commonRoot ?: suggestedName
            val totalBytes = allEntries.sumOf { if (it.isDirectory) 0L else it.size.coerceAtLeast(0L) }
            if (totalBytes > MAX_TOTAL_BYTES) {
                return Outcome.Failed("Archive would expand to ${humanSize(totalBytes)} — over the 10 GiB safety cap.")
            }
            validateFreeSpace(destRoot, totalBytes)?.let { return Outcome.Failed(it) }

            val rootContainer = createExtractionRoot(
                destRoot,
                destSubName,
                reuseExisting = forcedSubfolderName != null,
            )
                ?: return Outcome.Failed("Could not create destination folder.")
            partial.add(rootContainer)
            preparedRoot = rootContainer

            // 7z doesn't allow random access — re-open and walk again to actually read content.
        }
        val rootContainer = preparedRoot
            ?: return Outcome.Failed("Could not access destination folder.")
        // Re-open for sequential read
        val sz2 = if (password != null) SevenZFile(archive, password) else SevenZFile(archive)
        return sz2.use { f ->
            var entry = f.nextEntry
            if (entry == null) return@use Outcome.Failed("Archive empty on re-read")
            val names = mutableListOf<String>()
            val probe = if (password != null) SevenZFile(archive, password) else SevenZFile(archive)
            probe.use { p ->
                var e = p.nextEntry
                while (e != null) {
                    names += e.name.replace('\\', '/')
                    e = p.nextEntry
                }
            }
            val commonRoot = commonRootFolder(names)
            var bytesWritten = 0L
            var entriesProcessed = 0
            // Walk: count first by scanning, then second time for content. We took the cheap
            // count above; here we use Int.MAX since 7z doesn't give us count up front cheaply.
            val entriesTotal = Int.MAX_VALUE  // unknown to caller; just keep the bytes meter
            while (entry != null) {
                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                val raw = entry!!.name.replace('\\', '/')
                val rel = stripCommonRoot(raw, commonRoot)
                if (rel.isBlank() || rel.startsWith("/") || rel.contains("../")) {
                    entry = f.nextEntry; continue
                }
                entriesProcessed++
                if (entry!!.isDirectory) {
                    ensureFolder(rootContainer, rel)
                    onProgress(Progress(bytesWritten, MAX_TOTAL_BYTES, entriesProcessed, entriesTotal, rel))
                    entry = f.nextEntry; continue
                }
                val parent = ensureFolder(rootContainer, rel.substringBeforeLast('/', ""))
                val name = rel.substringAfterLast('/')
                val out = createOrReplaceFile(parent, name)
                if (out == null) { entry = f.nextEntry; continue }
                openWrite(out).use { os ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                        val n = f.read(buf)
                        if (n < 0) break
                        enforceActualLimit(bytesWritten, 0L, n)
                        os.write(buf, 0, n)
                        bytesWritten += n
                        onProgress(Progress(bytesWritten, bytesWritten, entriesProcessed, entriesTotal, rel))
                    }
                }
                entry = f.nextEntry
            }
            Outcome.Ok(rootContainer, bytesWritten)
        }
    }

    // ---------- helpers ----------

    private fun commonRootFolder(names: List<String>): String? {
        if (names.isEmpty()) return null
        val firstSegments = names.map { it.replace('\\', '/').trimStart('/').substringBefore('/') }
        val unique = firstSegments.toSet()
        if (unique.size != 1) return null
        val candidate = unique.single()
        if (candidate.isBlank()) return null
        // Need at least one entry to actually be deeper than top-level (otherwise commonRoot
        // is just a single file at root, not a folder).
        val anyDeeper = names.any { it.contains('/') }
        return if (anyDeeper) candidate else null
    }

    private fun stripCommonRoot(path: String, commonRoot: String?): String {
        if (commonRoot == null) return path
        return when {
            path == commonRoot -> ""
            path.startsWith("$commonRoot/") -> path.substring(commonRoot.length + 1)
            else -> path
        }
    }

    private fun createSubfolder(parent: ExtractRoot, name: String): ExtractRoot? {
        val safeName = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim('.').ifBlank { "extracted" }
        return when (parent) {
            is ExtractRoot.Saf -> {
                val existing = parent.doc.findFile(safeName)
                val target = existing ?: parent.doc.createDirectory(safeName)
                target?.let { ExtractRoot.Saf(it) }
            }
            is ExtractRoot.FileRoot -> {
                val f = File(parent.file, safeName).apply { mkdirs() }
                if (f.isDirectory) ExtractRoot.FileRoot(f) else null
            }
        }
    }

    private fun createExtractionRoot(
        parent: ExtractRoot,
        name: String,
        reuseExisting: Boolean,
    ): ExtractRoot? {
        if (reuseExisting) return createSubfolder(parent, name)
        val safeName = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim('.').ifBlank { "extracted" }
        return when (parent) {
            is ExtractRoot.Saf -> {
                var candidate = safeName
                var suffix = 2
                while (parent.doc.findFile(candidate) != null) {
                    candidate = "$safeName-$suffix"
                    suffix++
                }
                parent.doc.createDirectory(candidate)?.let { ExtractRoot.Saf(it) }
            }
            is ExtractRoot.FileRoot -> {
                var candidate = File(parent.file, safeName)
                var suffix = 2
                while (candidate.exists()) {
                    candidate = File(parent.file, "$safeName-$suffix")
                    suffix++
                }
                if (candidate.mkdirs()) ExtractRoot.FileRoot(candidate) else null
            }
        }
    }

    private fun ensureFolder(parent: ExtractRoot, relativePath: String): ExtractRoot {
        if (relativePath.isBlank()) return parent
        val parts = relativePath.split('/').filter { it.isNotBlank() }
        var cursor: ExtractRoot = parent
        for (p in parts) {
            cursor = createSubfolder(cursor, p) ?: return cursor
        }
        return cursor
    }

    private fun createOrReplaceFile(parent: ExtractRoot, name: String): Any? {
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "file" }
        return when (parent) {
            is ExtractRoot.Saf -> {
                parent.doc.findFile(safe)?.delete()
                parent.doc.createFile("application/octet-stream", safe)
            }
            is ExtractRoot.FileRoot -> {
                val f = File(parent.file, safe)
                if (f.exists()) f.delete()
                f.parentFile?.mkdirs()
                f.createNewFile()
                f
            }
        }
    }

    private fun openWrite(target: Any): java.io.OutputStream = when (target) {
        is DocumentFile -> {
            // SAF
            val ctx = StaticContext.appContext
                ?: error("StaticContext.appContext not initialized")
            ctx.contentResolver.openOutputStream(target.uri, "wt")
                ?: error("Could not open SAF output stream for ${target.uri}")
        }
        is File -> target.outputStream()
        else -> error("Unknown target type: ${target::class}")
    }

    private suspend fun streamCopy(
        input: InputStream,
        target: Any,
        bytesWrittenSoFar: Long,
        totalBytes: Long,
        onProgress: (written: Long, current: String?) -> Unit,
    ): Long {
        var written = 0L
        var lastReportAt = 0L
        var lastReportTimeNs = System.nanoTime()
        openWrite(target).use { os ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                val n = input.read(buf)
                if (n < 0) break
                enforceActualLimit(bytesWrittenSoFar, written, n)
                os.write(buf, 0, n)
                written += n
                // Report on bytes OR time — whichever hits first. The time-based
                // heartbeat keeps the UI moving even when one entry takes seconds
                // to flush.
                val now = System.nanoTime()
                if (written - lastReportAt >= 256 * 1024L ||
                    now - lastReportTimeNs >= 200_000_000L
                ) {
                    lastReportAt = written
                    lastReportTimeNs = now
                    onProgress(bytesWrittenSoFar + written, null)
                }
            }
        }
        return written
    }

    private fun validateEntryCount(count: Int): String? =
        if (count > MAX_ENTRIES) {
            "Archive contains $count entries — over the $MAX_ENTRIES entry safety cap."
        } else {
            null
        }

    private fun validateFreeSpace(destination: ExtractRoot, declaredBytes: Long): String? {
        if (declaredBytes <= 0L) return null
        val directory = when (destination) {
            is ExtractRoot.FileRoot -> destination.file
            is ExtractRoot.Saf -> return null
        }
        val available = directory.usableSpace
        val required = declaredBytes + FREE_SPACE_RESERVE_BYTES
        return if (available in 1 until required) {
            "Archive needs about ${humanSize(declaredBytes)}, but only ${humanSize(available)} is available."
        } else {
            null
        }
    }

    private fun enforceActualLimit(
        bytesWrittenSoFar: Long,
        currentEntryBytes: Long,
        nextWriteBytes: Int,
    ) {
        if (nextWriteBytes < 0 ||
            bytesWrittenSoFar > MAX_TOTAL_BYTES - currentEntryBytes - nextWriteBytes
        ) {
            throw ArchiveLimitException("Archive exceeded the 10 GiB safety cap during extraction.")
        }
    }

    private fun cleanup(partial: List<Any>) {
        for (item in partial) {
            runCatching {
                when (item) {
                    is ExtractRoot.Saf -> item.doc.delete()
                    is ExtractRoot.FileRoot -> item.file.deleteRecursively()
                    is DocumentFile -> item.delete()
                    is File -> item.deleteRecursively()
                    else -> Unit
                }
            }
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}

/** Tiny holder so non-Activity code can get a Context for the ContentResolver. */
object StaticContext {
    @Volatile var appContext: Context? = null
}
