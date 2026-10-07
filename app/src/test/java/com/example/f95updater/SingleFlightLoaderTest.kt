package com.example.f95updater

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightLoaderTest {
    @Test
    fun concurrentCallersShareOneLoadAndPublishedResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val loadCount = AtomicInteger()
            val loadStarted = CompletableDeferred<Unit>()
            val releaseLoad = CompletableDeferred<Unit>()
            val expected = Any()
            val loader = SingleFlightLoader<Any, Int>(scope) {
                loadCount.incrementAndGet()
                loadStarted.complete(Unit)
                releaseLoad.await()
                expected
            }

            val callers = List(12) { async { loader.get() } }
            withTimeout(2_000) { loadStarted.await() }
            releaseLoad.complete(Unit)
            val results = withTimeout(2_000) { callers.awaitAll() }

            assertEquals(1, loadCount.get())
            results.forEach { assertSame(expected, it) }
            assertSame(expected, loader.get())
            assertEquals(1, loadCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingCallerDoesNotCancelSharedLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val loadCount = AtomicInteger()
            val loadStarted = CompletableDeferred<Unit>()
            val releaseLoad = CompletableDeferred<Unit>()
            val expected = Any()
            val loader = SingleFlightLoader<Any, Int>(scope) {
                loadCount.incrementAndGet()
                loadStarted.complete(Unit)
                releaseLoad.await()
                expected
            }

            val cancelledCaller = async { loader.get() }
            withTimeout(2_000) { loadStarted.await() }
            val survivingCaller = async { loader.get() }
            cancelledCaller.cancel()
            releaseLoad.complete(Unit)

            assertSame(expected, withTimeout(2_000) { survivingCaller.await() })
            assertTrue(cancelledCaller.isCancelled)
            assertEquals(1, loadCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun invalidationCancelsStaleLoadAndRetriesForActiveCaller() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val loadCount = AtomicInteger()
            val firstLoadStarted = CompletableDeferred<Unit>()
            val expected = Any()
            val loader = SingleFlightLoader<Any, Int>(scope) {
                when (loadCount.incrementAndGet()) {
                    1 -> {
                        firstLoadStarted.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        error("Cancelled stale load resumed unexpectedly")
                    }
                    else -> expected
                }
            }

            val caller = async { loader.get() }
            withTimeout(2_000) { firstLoadStarted.await() }
            loader.invalidate()

            assertSame(expected, withTimeout(2_000) { caller.await() })
            assertEquals(2, loadCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedLoadIsSharedAndNextCallRetries() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val loadCount = AtomicInteger()
            val firstLoadStarted = CompletableDeferred<Unit>()
            val firstObservedProgress = CompletableDeferred<Unit>()
            val secondObservedProgress = CompletableDeferred<Unit>()
            val failFirstLoad = CompletableDeferred<Unit>()
            val expected = Any()
            val loader = SingleFlightLoader<Any, Int>(scope) { emitProgress ->
                when (loadCount.incrementAndGet()) {
                    1 -> {
                        firstLoadStarted.complete(Unit)
                        emitProgress(1)
                        failFirstLoad.await()
                        error("first load failed")
                    }
                    else -> expected
                }
            }

            val first = async {
                runCatching {
                    loader.get { firstObservedProgress.complete(Unit) }
                }
            }
            val second = async {
                runCatching {
                    loader.get { secondObservedProgress.complete(Unit) }
                }
            }
            withTimeout(2_000) { firstLoadStarted.await() }
            withTimeout(2_000) {
                firstObservedProgress.await()
                secondObservedProgress.await()
            }
            failFirstLoad.complete(Unit)

            assertTrue(withTimeout(2_000) { first.await() }.isFailure)
            assertTrue(withTimeout(2_000) { second.await() }.isFailure)
            assertEquals(1, loadCount.get())
            assertSame(expected, withTimeout(2_000) { loader.get() })
            assertEquals(2, loadCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentCallersAndCachedCallerReceiveSharedProgress() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val emitProgress = CompletableDeferred<Unit>()
            val finishLoad = CompletableDeferred<Unit>()
            val firstProgress = CompletableDeferred<Int>()
            val secondProgress = CompletableDeferred<Int>()
            val loader = SingleFlightLoader<Any, Int>(scope) { reportProgress ->
                emitProgress.await()
                reportProgress(42)
                finishLoad.await()
                Any()
            }

            val first = async { loader.get { firstProgress.complete(it) } }
            val second = async { loader.get { secondProgress.complete(it) } }
            emitProgress.complete(Unit)
            assertEquals(42, withTimeout(2_000) { firstProgress.await() })
            assertEquals(42, withTimeout(2_000) { secondProgress.await() })
            finishLoad.complete(Unit)
            withTimeout(2_000) { awaitAll(first, second) }

            var cachedProgress: Int? = null
            loader.get { cachedProgress = it }
            assertEquals(42, cachedProgress)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun closeCancelsActiveLoadAndRejectsFutureCalls() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val loadStarted = CompletableDeferred<Unit>()
            val loader = SingleFlightLoader<Any, Int>(scope) {
                loadStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
                Any()
            }

            val activeCall = async { runCatching { loader.get() } }
            withTimeout(2_000) { loadStarted.await() }
            loader.close()

            assertTrue(withTimeout(2_000) { activeCall.await() }.isFailure)
            assertTrue(runCatching { loader.get() }.exceptionOrNull() is IllegalStateException)
            loader.invalidate()
            loader.close()
        } finally {
            scope.cancel()
        }
    }
}
