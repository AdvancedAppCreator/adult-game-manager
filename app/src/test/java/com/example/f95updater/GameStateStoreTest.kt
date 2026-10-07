package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Test

class GameStateStoreTest {

    @Test
    fun appliedSetsAndClears() {
        val start = mapOf("a" to GameState.Playing)
        val set = GameStateStore.applied(start, listOf("b", "c"), GameState.Completed)
        assertEquals(GameState.Completed, set["b"])
        assertEquals(GameState.Completed, set["c"])
        assertEquals(GameState.Playing, set["a"])

        val cleared = GameStateStore.applied(set, listOf("a", "b"), GameState.None)
        assertEquals(null, cleared["a"])
        assertEquals(null, cleared["b"])
        assertEquals(GameState.Completed, cleared["c"])
    }

    @Test
    fun countsExcludeNone() {
        val map = mapOf(
            "a" to GameState.Playing,
            "b" to GameState.Playing,
            "c" to GameState.Backlog,
        )
        val counts = GameStateStore.counts(map)
        assertEquals(2, counts[GameState.Playing])
        assertEquals(1, counts[GameState.Backlog])
        assertEquals(null, counts[GameState.Completed])
    }

    @Test
    fun fromNameFallsBackToNone() {
        assertEquals(GameState.Completed, GameState.fromName("Completed"))
        assertEquals(GameState.None, GameState.fromName("bogus"))
        assertEquals(GameState.None, GameState.fromName(null))
    }
}
