package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedGamesRootTest {
    @Test
    fun parsesPrimaryAndRemovableVolumeDocumentIds() {
        assertEquals(
            SharedGamesRoot.DocumentPath("primary", "Games/Adult"),
            SharedGamesRoot.parseDocumentId("primary:Games/Adult"),
        )
        assertEquals(
            SharedGamesRoot.DocumentPath("1234-5678", "Games"),
            SharedGamesRoot.parseDocumentId("1234-5678:Games"),
        )
    }

    @Test
    fun rejectsMalformedDocumentIds() {
        assertNull(SharedGamesRoot.parseDocumentId(""))
        assertNull(SharedGamesRoot.parseDocumentId("primary"))
        assertNull(SharedGamesRoot.parseDocumentId(":Games"))
    }
}
