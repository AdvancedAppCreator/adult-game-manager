package com.example.f95updater

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

internal class SingleFlightLoader<T : Any, P : Any>(
    private val scope: CoroutineScope,
    private val load: suspend (suspend (P) -> Unit) -> T,
) {
    private data class Cached<T : Any, P : Any>(
        val value: T,
        val progress: P?,
    )

    private data class Build<T : Any, P : Any>(
        val generation: Long,
        val deferred: Deferred<T>,
        val progress: StateFlow<P?>,
    )

    private val stateLock = Any()
    @Volatile private var cached: Cached<T, P>? = null
    @Volatile private var closed = false
    private var generation = 0L
    private var activeBuild: Build<T, P>? = null

    suspend fun get(onProgress: suspend (P) -> Unit = {}): T {
        val callerContext = currentCoroutineContext()
        while (true) {
            if (closed) error("Single-flight loader is closed")
            cached?.let { cachedResult ->
                cachedResult.progress?.let { onProgress(it) }
                return cachedResult.value
            }
            val build = synchronized(stateLock) {
                if (closed) error("Single-flight loader is closed")
                if (cached != null) {
                    null
                } else {
                    activeBuild
                        ?.takeIf { it.generation == generation && it.deferred.isActive }
                        ?: createBuildLocked()
                }
            }
            if (build == null) continue
            try {
                return awaitBuild(build, onProgress)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                callerContext.ensureActive()
                if (closed) error("Single-flight loader is closed")
            }
        }
    }

    fun invalidate() {
        val staleBuild = synchronized(stateLock) {
            if (closed) return
            generation++
            cached = null
            activeBuild.also { activeBuild = null }
        }
        staleBuild?.deferred?.cancel(
            kotlinx.coroutines.CancellationException("Single-flight load invalidated"),
        )
    }

    fun close() {
        val staleBuild = synchronized(stateLock) {
            if (closed) return
            closed = true
            generation++
            cached = null
            activeBuild.also { activeBuild = null }
        }
        staleBuild?.deferred?.cancel(
            kotlinx.coroutines.CancellationException("Single-flight loader closed"),
        )
    }

    private fun createBuildLocked(): Build<T, P> {
        val buildGeneration = generation
        val progress = MutableStateFlow<P?>(null)
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            val loaded = load { update -> progress.value = update }
            val published = synchronized(stateLock) {
                if (!closed && generation == buildGeneration) {
                    cached = Cached(loaded, progress.value)
                    activeBuild = null
                    true
                } else {
                    false
                }
            }
            if (!published) {
                throw kotlinx.coroutines.CancellationException("Single-flight load became stale")
            }
            loaded
        }
        return Build(buildGeneration, deferred, progress)
            .also {
                activeBuild = it
                deferred.start()
            }
    }

    private suspend fun awaitBuild(
        build: Build<T, P>,
        onProgress: suspend (P) -> Unit,
    ): T = coroutineScope {
        val deliveredProgress = AtomicReference<P?>(null)
        val progressJob = launch(start = CoroutineStart.UNDISPATCHED) {
            build.progress.filterNotNull().collect {
                onProgress(it)
                deliveredProgress.set(it)
            }
        }
        val loaded = try {
            build.deferred.await()
        } finally {
            progressJob.cancelAndJoin()
        }
        build.progress.value
            ?.takeIf { it != deliveredProgress.get() }
            ?.let { onProgress(it) }
        loaded
    }
}
