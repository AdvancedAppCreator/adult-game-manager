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
 *  • Size cap (MAX_TOTAL_BYTES = 10 GiB) — refuses to extract if any entry's
 *    declared size would push us past the cap. Catches zip bombs.
 *  • Compression-ratio cap (MAX_RATIO = 100) — refuses if any single entry inflates
 *    more than 100× its compressed size.
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
    private const val MAX_RATIO = 100
    private const val BUFFER_SIZE = 64 * 1024

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
            when (format) {
                Format.ZIP -> extractZip(archive, password, destRoot, suggestedName, forcedSubfolderName, partial, onProgress)
                Format.RAR -> extractRar(archive, password, destRoot, suggestedName, forcedSubfolderName, partial, onProgress)
                Format.SEVENZ -> extractSevenZ(archive, password, destRoot, suggestedName, forcedSubfolderName, partial, onProgress)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            cleanup(partial)
            Outcome.Cancelled.also { throw ce }
        } catch (t: Throwable) {
            cleanup(partial)
            Outcome.Failed("Extraction failed: ${t.message ?: t::class.simpleName}", t)
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

        val names = headers.map { it.fileName }
        val commonRoot = commonRootFolder(names)
        val destSubName = forcedSubfolderName ?: commonRoot ?: suggestedName
        val totalBytes = headers.sumOf { if (it.isDirectory) 0L else it.uncompressedSize.coerceAtLeast(0L) }
        if (totalBytes > MAX_TOTAL_BYTES) {
            return Outcome.Failed("Archive would expand to ${humanSize(totalBytes)} — over the 10 GiB safety cap.")
        }

        val rootContainer = createSubfolder(destRoot, destSubName)
            ?: return Outcome.Failed("Could not create destination folder.")
        partial.add(rootContainer)

        var bytesWritten = 0L
        var entriesProcessed = 0
        val entriesTotal = headers.size

        for (h in headers) {
            if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
            val raw = h.fileName.replace('\\', '/')
            val rel = if (commonRoot != null) raw.removePrefix("$commonRoot/").removePrefix(commonRoot) else raw
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
            val ratio = if (h.compressedSize > 0) h.uncompressedSize.toDouble() / h.compressedSize else 0.0
            if (ratio > MAX_RATIO) {
                return Outcome.Failed("Entry $rel has suspicious compression ratio ${ratio.toInt()}× (zip bomb?)")
            }
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
        val entries = UnrarNative.listEntries(archive, pwd)
        if (entries.isEmpty()) return Outcome.Failed("Archive is empty")

        val names = entries.map { it.name.replace('\\', '/') }
        val commonRoot = commonRootFolder(names)
        val destSubName = forcedSubfolderName ?: commonRoot ?: suggestedName
        val totalBytes = entries.filter { !it.isDirectory }.sumOf { it.size.coerceAtLeast(0L) }
        if (totalBytes > MAX_TOTAL_BYTES) {
            return Outcome.Failed("Archive would expand to ${humanSize(totalBytes)} — over the 10 GiB safety cap.")
        }

        val rootContainer = createSubfolder(destRoot, destSubName) as? ExtractRoot.FileRoot
            ?: return Outcome.Failed("Could not create destination folder.")
        partial.add(rootContainer)

        // Extract everything into a staging folder under the parent so we can
        // strip the archive's common root afterwards without recursing into the
        // dest we just created.
        val stagingDir = File(rootContainer.file.parentFile, ".unrar-staging-${System.currentTimeMillis()}")
        stagingDir.mkdirs()
        partial.add(ExtractRoot.FileRoot(stagingDir))

        val entriesTotal = entries.size
        var entriesProcessed = 0
        var bytesWritten = 0L
        val ctx = kotlin.coroutines.coroutineContext

        try {
            UnrarNative.extractAll(archive, stagingDir, pwd, object : UnrarProgressCallback {
                override fun onChunk(bytes: Long, currentName: String?) {
                    if (currentName != null) {
                        // Marks start of a new entry.
                        entriesProcessed = (entriesProcessed + 1).coerceAtMost(entriesTotal)
                        onProgress(Progress(bytesWritten, totalBytes, entriesProcessed, entriesTotal, currentName))
                    } else if (bytes > 0) {
                        bytesWritten += bytes
                        onProgress(Progress(bytesWritten, totalBytes, entriesProcessed, entriesTotal, ""))
                    }
                }
                override fun isCancelled(): Boolean = !ctx.isActive
            })
        } catch (e: InterruptedException) {
            stagingDir.deleteRecursively()
            throw kotlinx.coroutines.CancellationException("Cancelled")
        } catch (e: UnrarPasswordRequiredException) {
            stagingDir.deleteRecursively()
            throw e
        } catch (e: Throwable) {
            stagingDir.deleteRecursively()
            throw e
        }

        // Move staged contents into rootContainer, stripping commonRoot if it was present.
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
        return Outcome.Ok(rootContainer, bytesWritten.coerceAtLeast(totalBytes))
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

            val names = headers.map { it.fileNameString.replace('\\', '/') }
            val commonRoot = commonRootFolder(names)
            val destSubName = forcedSubfolderName ?: commonRoot ?: suggestedName
            val totalBytes = headers.sumOf { if (it.isDirectory) 0L else it.fullUnpackSize.coerceAtLeast(0L) }
            if (totalBytes > MAX_TOTAL_BYTES) {
                return Outcome.Failed("Archive would expand to ${humanSize(totalBytes)} — over the 10 GiB safety cap.")
            }

            val rootContainer = createSubfolder(destRoot, destSubName)
                ?: return Outcome.Failed("Could not create destination folder.")
            partial.add(rootContainer)

            var bytesWritten = 0L
            var entriesProcessed = 0
            val entriesTotal = headers.size
            for (fh in headers) {
                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                val raw = fh.fileNameString.replace('\\', '/')
                val rel = if (commonRoot != null) raw.removePrefix("$commonRoot/").removePrefix(commonRoot) else raw
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
                val ratio = if (fh.fullPackSize > 0) fh.fullUnpackSize.toDouble() / fh.fullPackSize else 0.0
                if (ratio > MAX_RATIO) {
                    return Outcome.Failed("Entry $rel has suspicious compression ratio ${ratio.toInt()}× (zip bomb?)")
                }
                val ctx = coroutineContext
                openWrite(out).use { os ->
                    val delegate = os
                    a.extractFile(fh, object : FilterOutputStream(delegate) {
                        override fun write(b: Int) {
                            if (!ctx.isActive) throw kotlinx.coroutines.CancellationException()
                            delegate.write(b)
                        }

                        override fun write(b: ByteArray, off: Int, len: Int) {
                            if (!ctx.isActive) throw kotlinx.coroutines.CancellationException()
                            delegate.write(b, off, len)
                        }
                    })
                }
                bytesWritten += fh.fullUnpackSize.coerceAtLeast(0L)
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
        val sz = if (password != null) SevenZFile(archive, password) else SevenZFile(archive)
        sz.use { f ->
            // 7z lib doesn't expose "isEncrypted" before reading; it'll throw if password is required.
            val allEntries = generateSequence { runCatching { f.nextEntry }.getOrNull() }.toList()
            if (allEntries.isEmpty()) return Outcome.Failed("Archive is empty or password-protected")

            val names = allEntries.map { it.name.replace('\\', '/') }
            val commonRoot = commonRootFolder(names)
            val destSubName = forcedSubfolderName ?: commonRoot ?: suggestedName
            val totalBytes = allEntries.sumOf { if (it.isDirectory) 0L else it.size.coerceAtLeast(0L) }
            if (totalBytes > MAX_TOTAL_BYTES) {
                return Outcome.Failed("Archive would expand to ${humanSize(totalBytes)} — over the 10 GiB safety cap.")
            }

            val rootContainer = createSubfolder(destRoot, destSubName)
                ?: return Outcome.Failed("Could not create destination folder.")
            partial.add(rootContainer)

            // 7z doesn't allow random access — re-open and walk again to actually read content.
        }
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
            val rootContainer = lookupOrCreate(destRoot, forcedSubfolderName ?: commonRoot ?: suggestedName)
                ?: return@use Outcome.Failed("Could not access destination folder.")
            var bytesWritten = 0L
            var entriesProcessed = 0
            // Walk: count first by scanning, then second time for content. We took the cheap
            // count above; here we use Int.MAX since 7z doesn't give us count up front cheaply.
            val entriesTotal = Int.MAX_VALUE  // unknown to caller; just keep the bytes meter
            while (entry != null) {
                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                val raw = entry!!.name.replace('\\', '/')
                val rel = if (commonRoot != null) raw.removePrefix("$commonRoot/").removePrefix(commonRoot) else raw
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

    private fun lookupOrCreate(parent: ExtractRoot, name: String): ExtractRoot? =
        createSubfolder(parent, name)

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
