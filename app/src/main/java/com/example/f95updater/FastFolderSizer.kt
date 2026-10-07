package com.example.f95updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Reusable multithreaded [File]-based folder sizer shared by the JoiPlay and Winlator size scans
 * (AGM holds MANAGE_EXTERNAL_STORAGE, so plain [File] IO is orders of magnitude faster than SAF).
 *
 * Two phases, no recursion:
 *  - Phase A iteratively (breadth-first) discovers every sub-directory, assigning each an inherited
 *    *category bucket* via [childCategoryOf] so callers can split totals (e.g. game/save/backup).
 *  - Phase B sizes each directory's immediate files in a bounded worker pool, accumulating bytes per
 *    bucket. A file's bucket is its immediate parent folder's bucket.
 *
 * All filesystem access is defensively wrapped (a permission denial contributes 0 bytes, never
 * throws) and cancellation is cooperative (bails as soon as the coroutine becomes inactive).
 */
object FastFolderSizer {

    private fun parallelism(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    private data class Dir<B>(val file: File, val category: B)

    /**
     * Sizes [root], returning bytes summed per category bucket. [rootCategory] is the bucket of the
     * root folder's own files; [childCategoryOf] derives a child folder's bucket from its name and
     * its parent's bucket. [onProgress] receives the running total periodically.
     */
    suspend fun <B : Any> sizeByCategory(
        root: File,
        rootCategory: B,
        childCategoryOf: (name: String?, parent: B) -> B,
        onProgress: suspend (runningTotal: Long) -> Unit = {},
    ): Map<B, Long> = coroutineScope {
        // Phase A — iterative BFS folder discovery with bucket propagation.
        val allDirs = ArrayList<Dir<B>>()
        val rootDir = Dir(root, rootCategory)
        allDirs.add(rootDir)
        var frontier = listOf(rootDir)
        while (frontier.isNotEmpty()) {
            if (!currentCoroutineContext().isActive) return@coroutineScope emptyMap()
            val next = ArrayList<Dir<B>>()
            for (dir in frontier) {
                val entries = runCatching { dir.file.listFiles() }.getOrNull() ?: continue
                for (entry in entries) {
                    if (runCatching { entry.isDirectory }.getOrDefault(false)) {
                        val child = Dir(entry, childCategoryOf(entry.name, dir.category))
                        allDirs.add(child)
                        next.add(child)
                    }
                }
            }
            frontier = next
        }

        // Phase B — bounded-parallel sizing of each directory's immediate files.
        val totals = ConcurrentHashMap<B, AtomicLong>()
        val running = AtomicLong(0L)
        val counter = AtomicInteger(0)
        val channel = Channel<Dir<B>>(Channel.UNLIMITED)
        for (dir in allDirs) channel.send(dir)
        channel.close()
        val workers = (0 until parallelism()).map {
            launch(Dispatchers.IO) {
                for (dir in channel) {
                    if (!currentCoroutineContext().isActive) break
                    val entries = runCatching { dir.file.listFiles() }.getOrNull() ?: continue
                    var dirSum = 0L
                    for (entry in entries) {
                        if (!runCatching { entry.isDirectory }.getOrDefault(false)) {
                            dirSum += runCatching { entry.length() }.getOrDefault(0L)
                        }
                    }
                    if (dirSum != 0L) {
                        totals.computeIfAbsent(dir.category) { AtomicLong(0L) }.addAndGet(dirSum)
                    }
                    val total = running.addAndGet(dirSum)
                    if (counter.incrementAndGet() % 16 == 0) onProgress(total)
                }
            }
        }
        workers.joinAll()
        if (!currentCoroutineContext().isActive) return@coroutineScope emptyMap()
        onProgress(running.get())
        totals.mapValues { it.value.get() }
    }

    /** Convenience: total bytes under [root] with no category split. */
    suspend fun sizeTotal(
        root: File,
        onProgress: suspend (runningTotal: Long) -> Unit = {},
    ): Long =
        sizeByCategory(root, Unit, { _, _ -> Unit }, onProgress).values.sum()
}
