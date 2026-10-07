package com.example.f95updater

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher

class MegaClientTest {
    private val newLink = "https://mega.nz/file/qj51WKgR#2XE5r-HpRD_Dpcl7H24wLIDJH69085V7RHf9l80-V3g"
    private val legacyLink = "https://mega.nz/#!qj51WKgR!2XE5r-HpRD_Dpcl7H24wLIDJH69085V7RHf9l80-V3g"

    @Test
    fun parsesPublicFileLink() {
        val parsed = MegaClient.parseFileLink(newLink)

        assertNotNull(parsed)
        parsed!!
        assertEquals("qj51WKgR", parsed.fileId)
        assertEquals(16, parsed.aesKey.size)
        assertEquals(8, parsed.ivHigh8.size)
    }

    @Test
    fun keyDerivationIsDeterministicAndLegacyFormMatches() {
        val first = MegaClient.parseFileLink(newLink)!!
        val second = MegaClient.parseFileLink(newLink)!!
        val legacy = MegaClient.parseFileLink(legacyLink)!!

        assertArrayEquals(first.aesKey, second.aesKey)
        assertEquals(first.fileId, legacy.fileId)
        assertArrayEquals(first.aesKey, legacy.aesKey)
        assertArrayEquals(first.ivHigh8, legacy.ivHigh8)
    }

    @Test
    fun malformedLinksReturnNull() {
        assertNull(MegaClient.parseFileLink(""))
        assertNull(MegaClient.parseFileLink("https://example.com/x"))
        assertNull(MegaClient.parseFileLink("https://mega.nz/file/onlyid"))
    }

    @Test
    fun ctrCipherRoundTripUsesDerivedKeyAndIv() {
        val parsed = MegaClient.parseFileLink(newLink)!!
        val plaintext = "MEGA CTR round trip".toByteArray(Charsets.UTF_8)
        val encrypted = MegaClient.createCtrCipher(parsed.aesKey, parsed.ivHigh8, Cipher.ENCRYPT_MODE)
            .doFinal(plaintext)
        val decrypted = MegaClient.createCtrCipher(parsed.aesKey, parsed.ivHigh8, Cipher.DECRYPT_MODE)
            .doFinal(encrypted)

        assertArrayEquals(plaintext, decrypted)
    }
}

