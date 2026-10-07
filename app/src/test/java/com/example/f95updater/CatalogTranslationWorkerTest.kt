package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CatalogTranslationWorkerTest {
    @Test
    fun catalogGenerationIsStableAndChangesWithCatalogMetadata() {
        val registry = CatalogSourceRegistry(
            generatedAt = "2026-07-14T00:00:00Z",
            catalogs = listOf(
                CatalogSourceInfo(
                    id = "otomi",
                    generatedAt = "2026-07-14T00:00:00Z",
                    count = 10,
                ),
            ),
        )

        val generation = catalogTranslationGeneration(registry, entryCount = 10)

        assertEquals(generation, catalogTranslationGeneration(registry, entryCount = 10))
        assertNotEquals(generation, catalogTranslationGeneration(registry, entryCount = 11))
        assertNotEquals(
            generation,
            catalogTranslationGeneration(
                registry.copy(generatedAt = "2026-07-15T00:00:00Z"),
                entryCount = 10,
            ),
        )
    }
}
