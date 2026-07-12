package com.tdarby.comet.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BlobDownloadPolicyTest {
    @Test
    fun acceptsFileReaderDataUrlAndCalculatesDecodedSize() {
        val result = BlobDownloadPolicy.validate("data:text/plain;base64,SGVsbG8=")

        assertNotNull(result)
        assertEquals(5, result?.decodedBytes)
        assertEquals("SGVsbG8=", "data:text/plain;base64,SGVsbG8=".substring(result!!.base64Start))
    }

    @Test
    fun acceptsEmptyBlob() {
        assertEquals(0, BlobDownloadPolicy.validate("data:;base64,")?.decodedBytes)
    }

    @Test
    fun rejectsNonDataUrlAndNonBase64Payloads() {
        assertNull(BlobDownloadPolicy.validate("https://example.com/file"))
        assertNull(BlobDownloadPolicy.validate("data:text/plain,hello"))
        assertNull(BlobDownloadPolicy.validate("data:text/plain;base64,not base64"))
        assertNull(BlobDownloadPolicy.validate("data:text/plain;base64,SGV=sbG8"))
    }

    @Test
    fun rejectsOversizedPayloadBeforeDecode() {
        assertNull(BlobDownloadPolicy.validate("data:;base64,AQID", maxDecodedBytes = 2))
    }

    @Test
    fun acceptsPayloadAtDecodedLimit() {
        assertEquals(
            4,
            BlobDownloadPolicy.validate("data:;base64,AQIDBA==", maxDecodedBytes = 4)?.decodedBytes
        )
    }

    @Test
    fun tvMemoryLimitIsFixedAtSixteenMib() {
        assertEquals(16 * 1024 * 1024, BlobDownloadPolicy.MAX_DECODED_BYTES)
    }

    @Test
    fun rejectsPathologicalHeaderWithoutScanningForAnUnboundedComma() {
        assertNull(BlobDownloadPolicy.validate("data:" + "x".repeat(600) + ",base64,AAAA"))
    }
}
