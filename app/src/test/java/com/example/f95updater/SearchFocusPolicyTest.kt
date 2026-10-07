package com.example.f95updater

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFocusPolicyTest {
    private val bounds = Rect(left = 10f, top = 10f, right = 110f, bottom = 60f)

    @Test
    fun tapInsideSearchKeepsFocus() {
        assertFalse(shouldClearLibrarySearchFocus(bounds, Offset(50f, 30f)))
    }

    @Test
    fun tapOutsideSearchClearsFocus() {
        assertTrue(shouldClearLibrarySearchFocus(bounds, Offset(150f, 30f)))
    }
}
