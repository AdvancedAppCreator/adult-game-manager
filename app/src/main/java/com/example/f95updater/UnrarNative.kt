package com.example.f95updater

import java.io.File
import java.io.IOException

/** An entry inside a RAR archive (output of [UnrarNative.listEntries]). */
data class UnrarEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
)

/** Thrown when the archive needs a password we didn't supply (or supplied wrong). */
class UnrarPasswordRequiredException(message: String) : IOException(message)

/**
 * Callback invoked by the native extractor for progress + cancellation.
 *
 * [onChunk] is called BOTH at the start of every entry with `bytes=0` and
 * `currentName` set, and during data extraction with the byte count of each
 * decompressed chunk (`currentName` is null in that case).
 */
interface UnrarProgressCallback {
    fun onChunk(bytes: Long, currentName: String?)
    fun isCancelled(): Boolean
}

/**
 * JNI bridge to RARLAB's unrar library. Supports RAR3 / RAR4 / RAR5 read-only.
 *
 * If the shared library can't be loaded (e.g. running on an ABI we didn't build
 * for), [available] returns false and callers should fall back to the pure-Java
 * junrar code path.
 */
object UnrarNative {

    private val loaded: Boolean = runCatching { System.loadLibrary("unrarjni") }.isSuccess

    val available: Boolean get() = loaded

    @JvmStatic
    external fun listEntriesNative(path: String, password: String?): List<UnrarEntry>

    @JvmStatic
    external fun extractAllNative(
        path: String,
        password: String?,
        destDir: String,
        callback: UnrarProgressCallback?,
    )

    /** High-level wrapper: lists entries; throws if [available] is false. */
    fun listEntries(archive: File, password: String? = null): List<UnrarEntry> {
        require(loaded) { "unrarjni native library not available on this device." }
        return listEntriesNative(archive.absolutePath, password)
    }

    /** High-level wrapper: extracts all files to [destDir]. The destination must
     *  exist (the underlying library does not create parent directories itself).
     *  Subdirectories from the archive are created automatically. */
    fun extractAll(
        archive: File,
        destDir: File,
        password: String? = null,
        callback: UnrarProgressCallback? = null,
    ) {
        require(loaded) { "unrarjni native library not available on this device." }
        if (!destDir.exists()) destDir.mkdirs()
        extractAllNative(archive.absolutePath, password, destDir.absolutePath, callback)
    }
}
