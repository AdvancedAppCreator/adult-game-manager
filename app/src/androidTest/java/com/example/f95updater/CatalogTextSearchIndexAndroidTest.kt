package com.example.f95updater

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CatalogTextSearchIndexAndroidTest {
    private fun document(
        key: String,
        title: String,
        aliases: List<String> = emptyList(),
    ) = CatalogTextSearchDocument(
        key = key,
        displayTitle = title,
        titles = listOf(title) + aliases,
    )

    private fun keys(matches: List<CatalogTextSearchScoredMatch>) =
        matches.map { it.document.key }

    @Test
    fun bundledAndroidFtsHandlesUnicodeAliasesAndLiteralOperators() {
        CatalogTextSearchIndex.build(
            listOf(
                document("japanese", "薬師兄妹のえっちな治験", listOf("Pharmacist Siblings Trial")),
                document("percent", "100% Pure"),
                document("underscore", "Under_score"),
                document("emoji", "😀a journey"),
            ),
        ).use { index ->
            assertEquals(listOf("japanese"), keys(index.search("薬師兄妹のえっちな治験")))
            assertEquals(listOf("japanese"), keys(index.search("えっちな")))
            assertEquals(listOf("japanese"), keys(index.search("Pharmacist Siblings")))
            assertEquals(listOf("percent"), keys(index.search("100%")))
            assertEquals(listOf("underscore"), keys(index.search("er_sc")))
            assertEquals(listOf("emoji"), keys(index.search("😀a")))
            assertTrue(index.search("Xharmacist Siblings Trial").isEmpty())
        }
    }

    @Test
    fun bundledAndroidFtsPersistsAndReplacesTranslations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = File(context.cacheDir, "catalog-search-test.db")
        database.delete()

        CatalogTextSearchIndex.build(
            listOf(document("one", "Original", listOf("Old Translation"))),
            database,
        ).close()
        CatalogTextSearchIndex.build(
            listOf(document("one", "Original", listOf("New Translation"))),
            database,
        ).use { index ->
            assertTrue(index.search("Old Translation").isEmpty())
            assertEquals(listOf("one"), keys(index.search("New Translation")))
        }
    }
}
