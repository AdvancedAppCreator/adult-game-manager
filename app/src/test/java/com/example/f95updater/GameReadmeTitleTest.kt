package com.example.f95updater

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameReadmeTitleTest {
    @Test
    fun recognizesBareAndSpacedReadmeFilenames() {
        assertTrue(isRecognizedReadmeFilename("readme.txt"))
        assertTrue(isRecognizedReadmeFilename("README.TXT"))
        assertTrue(isRecognizedReadmeFilename("read me.txt"))
        assertTrue(isRecognizedReadmeFilename("Read Me.txt"))
    }

    @Test
    fun recognizesShortAttachedPrefixVariant() {
        assertTrue(isRecognizedReadmeFilename("\u88fd\u54c1\u7248Readme.txt"))
    }

    @Test
    fun rejectsThirdPartyReadmeFilenames() {
        assertTrue("descriptive prefix with a space reads as a phrase, not a tag",
            !isRecognizedReadmeFilename("Compressed information read me.txt"))
        assertTrue(!isRecognizedReadmeFilename("Translation Readme.txt"))
        assertTrue(!isRecognizedReadmeFilename("Patch Readme.txt"))
        assertTrue(!isRecognizedReadmeFilename("PatchReadme.txt"))
        assertTrue(!isRecognizedReadmeFilename("readme_patch.txt"))
        assertTrue(!isRecognizedReadmeFilename("vcredist readme.txt"))
        assertTrue(!isRecognizedReadmeFilename("notes.txt"))
    }

    @Test
    fun identifiesNonContentDependencyFolders() {
        assertTrue(isNonContentReadmeFolder("Redist"))
        assertTrue(isNonContentReadmeFolder("vcredist"))
        assertTrue(isNonContentReadmeFolder("Dependencies"))
        assertTrue(!isNonContentReadmeFolder("game"))
        assertTrue(!isNonContentReadmeFolder("TamaraExposed3"))
    }

    @Test
    fun readsFirstNonEmptyUnicodeTitleFromCaseInsensitiveReadme() {
        val root = Files.createTempDirectory("agm-readme").toFile()
        try {
            File(root, "README.TXT").writeText("\n\uFEFF\u5c11\u5973\u306e\u65c5\nVersion 1.0")

            assertEquals("\u5c11\u5973\u306e\u65c5", readGameReadmeTitle(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun findsReadmeInParentOfExecutableWrapperFolder() {
        val root = Files.createTempDirectory("agm-readme-wrapper").toFile()
        try {
            File(root, "readme.txt").writeText("Original Game Name")
            val bin = File(root, "win64").apply { mkdirs() }

            assertEquals("Original Game Name", readGameReadmeTitleNear(bin))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun skipsDecorativeLinesAndDecodesWindows31j() {
        val bytes = "========\r\n\u5c11\u5973\u306e\u65c5\r\n".toByteArray(Charset.forName("windows-31j"))

        assertEquals("\u5c11\u5973\u306e\u65c5", readGameReadmeTitle(bytes))
    }

    @Test
    fun skipsDatedVersionMetadataBeforeQuotedTitle() {
        val bytes = """
            ---------------------------------------------------------------------
            2024/6/15_ver.1.0
            --------------------------------------------------------------------
                         『NTREX ネトラレックス』
        """.trimIndent().toByteArray(Charset.forName("windows-31j"))

        assertEquals("NTREX ネトラレックス", readGameReadmeTitle(bytes))
    }

    @Test
    fun readmeTitleParticipatesInCatalogMatchingLabels() {
        val app = InstalledApp(
            packageName = "winlator:test",
            label = "Translated Display Name",
            versionName = "",
            versionCode = 0L,
            source = AppSource.Winlator,
            readmeTitle = "\u539f\u4f5c\u30bf\u30a4\u30c8\u30eb",
        )

        assertTrue("\u539f\u4f5c\u30bf\u30a4\u30c8\u30eb" in catalogMatchLabels(app))
    }

    @Test
    fun winlatorCanSelectReadmeAsInstallTitle() {
        val candidate = WinlatorExecutableCandidate(
            file = File("game.exe"),
            executableTitle = "game",
            readmeTitle = "Canonical Name",
        )

        assertEquals(
            "Canonical Name",
            selectedWinlatorTitle(candidate, WinlatorTitleSource.Readme, ""),
        )
    }

    @Test
    fun decorativeHeaderLineIsSkippedEvenWhenItContainsLetters() {
        // Line 1 is a decorative section header ("~greeting~") that *does* contain real
        // kanji letters, so a naive "first letter-bearing line" rule would wrongly pick it.
        val bytes = "\u007e\u6328\u62f6\u007e\n\u3080\u3059\u3081\u305b\u3044\u304b\u3064\u3002\n"
            .toByteArray(Charsets.UTF_8)

        assertEquals("\u3080\u3059\u3081\u305b\u3044\u304b\u3064\u3002", readGameReadmeTitle(bytes))
    }

    @Test
    fun extractsJapaneseBracketQuotedTitleIgnoringSurroundingText() {
        val bytes = "\u007e\u6328\u62f6\u007e\n\u3053\u306e\u5ea6\u306f\u300c\u30bd\u30b7\u30e3\u30b2\u904b\u55b6\u7269\u8a9e\u300d\u3092\u624b\u306b\u3068\u3063\u3066\u9802\u304d\u3042\u308a\u304c\u3068\u3046\u3054\u3056\u3044\u307e\u3059\n"
            .toByteArray(Charsets.UTF_8)

        assertEquals("\u30bd\u30b7\u30e3\u30b2\u904b\u55b6\u7269\u8a9e", readGameReadmeTitle(bytes))
    }

    @Test
    fun readsPlainTitleLineDirectlyWhenNoQuotesArePresent() {
        val bytes = "\u3073\u3063\u304f\u308a\uff01VR\u75f4\u5973\u5b66\u5712\n".toByteArray(Charsets.UTF_8)

        assertEquals("\u3073\u3063\u304f\u308a\uff01VR\u75f4\u5973\u5b66\u5712", readGameReadmeTitle(bytes))
    }

    @Test
    fun extractsQuotedTitleFromSingleLineWithLeadingAndTrailingText() {
        val bytes = "\u4eca\u56de\u306f\u300c\u9023\u308c\u5b50\u59c9\u59b9\u30c9\u30b9\u30b1\u30d9\u8abf\u6559\u300d\u3092\u5fa1\u8cfc\u5165\u9802\u304d\u3042\u308a\u304c\u3068\u3046\u3054\u3056\u3044\u307e\u3059"
            .toByteArray(Charsets.UTF_8)

        assertEquals("\u9023\u308c\u5b50\u59c9\u59b9\u30c9\u30b9\u30b1\u30d9\u8abf\u6559", readGameReadmeTitle(bytes))
    }

    @Test
    fun compressionDisclaimerReadmeYieldsNoTitle() {
        val bytes = """
            Hi, thanks for reading!
            Compression is un-official, use at your own risk.
            Compression tool used:
            UAGC
            This was compressed for the folks of F95zone.
            Image quality = 80%
        """.trimIndent()
            .toByteArray(Charsets.UTF_8)

        assertEquals(null, readGameReadmeTitle(bytes))
    }

    @Test
    fun skipsSectionHeadingBeforeQuotedPurchasedTitle() {
        val bytes = "■はじめに\nこの度は「本当のゲーム名」をお買い上げいただきありがとうございます。"
            .toByteArray(Charsets.UTF_8)

        assertEquals("本当のゲーム名", readGameReadmeTitle(bytes))
    }

    @Test
    fun contentBeyondFirstThirtyLinesIsNeverInspected() {
        // 30 blank filler lines push the only usable title line to line 31 — inspection is
        // capped at the first 30 lines, so this must yield no title at all rather than scanning
        // arbitrarily far into a large README looking for one.
        val bytes = ("\n".repeat(30) + "Real Game Title\n").toByteArray(Charsets.UTF_8)

        assertEquals(null, readGameReadmeTitle(bytes))
    }

    @Test
    fun titleWithinFirstThirtyLinesIsStillFound() {
        val bytes = ("\n".repeat(29) + "Real Game Title\n").toByteArray(Charsets.UTF_8)

        assertEquals("Real Game Title", readGameReadmeTitle(bytes))
    }

}
