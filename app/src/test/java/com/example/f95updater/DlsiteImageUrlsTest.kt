package com.example.f95updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlsiteImageUrlsTest {
    @Test
    fun derivesEightDigitRjCodeFromOtomiUrl() {
        val urls = dlsiteImageUrls("https://otomi-games.com/rj01568168/")!!
        assertEquals(
            "https://img.dlsite.jp/resize/images2/work/doujin/RJ01569000/RJ01568168_img_main_240x240.jpg",
            urls.thumbnailUrl,
        )
        assertEquals(
            "https://img.dlsite.jp/modpub/images2/work/doujin/RJ01569000/RJ01568168_img_main.jpg",
            urls.coverUrl,
        )
    }

    @Test
    fun groupFolderUsesCeilingSoExactThousandsStayInOwnFolder() {
        // id 1568000 -> folder RJ01568000 (not RJ01569000)
        val urls = dlsiteImageUrls("https://otomi-games.com/rj01568000/")!!
        assertTrue(urls.coverUrl!!.contains("/RJ01568000/RJ01568000_img_main.jpg"))
    }

    @Test
    fun derivesSixDigitRjCode() {
        val urls = dlsiteImageUrls("https://otomi-games.com/rj123456/")!!
        assertTrue(urls.coverUrl!!.contains("/RJ124000/RJ123456_img_main.jpg"))
    }

    @Test
    fun returnsNullWhenNoRjCode() {
        assertNull(dlsiteImageUrls("https://otomi-games.com/the-censor/"))
        assertNull(dlsiteImageUrls(null))
        assertNull(dlsiteImageUrls("https://f95zone.to/threads/12345/"))
    }

    @Test
    fun catalogImageUrlsFallsBackToDlsiteWhenNoCoverOrThumb() {
        val game = CatalogGame(
            thread_id = 1,
            source = "otomi",
            sourceUrl = "https://otomi-games.com/rj01568168/",
        )
        val urls = catalogImageUrls(game)
        assertTrue(urls.thumbnailUrl!!.contains("img.dlsite.jp"))
        assertTrue(urls.coverUrl!!.contains("RJ01568168_img_main.jpg"))
    }

    @Test
    fun catalogImageUrlsPrefersRealCoverOverDerivation() {
        val game = CatalogGame(
            thread_id = 1,
            source = "otomi",
            cover = "https://otomi-games.com/wp-content/uploads/2026/07/RJ01568168_img_main.webp",
            sourceUrl = "https://otomi-games.com/rj01568168/",
        )
        val urls = catalogImageUrls(game)
        assertEquals("https://otomi-games.com/wp-content/uploads/2026/07/RJ01568168_img_main.webp", urls.thumbnailUrl)
        assertTrue(urls.coverUrl!!.contains("otomi-games.com"))
    }
}
