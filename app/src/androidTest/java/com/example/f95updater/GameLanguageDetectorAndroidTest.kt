package com.example.f95updater

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameLanguageDetectorAndroidTest {
    @Test
    fun detectsJapaneseDialogueFromGameScripts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.cacheDir.resolve("language-detection-japanese").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            root.resolve("scenario.rpy").writeText(
                """
                "今日はいい天気ですね。"
                "この町には不思議な伝説があります。"
                "一緒に冒険へ出かけましょう。"
                "あなたの名前を教えてください。"
                "物語はここから始まります。"
                """.trimIndent().repeat(20),
            )

            val result = GameLanguageDetector.detect(root)

            assertNotNull(result)
            assertEquals("ja", result?.languageTag)
        } finally {
            root.deleteRecursively()
        }
    }
}
