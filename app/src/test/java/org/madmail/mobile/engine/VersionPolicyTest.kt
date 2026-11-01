package org.madmail.mobile.engine

import org.junit.Assert.*
import org.junit.Test

class VersionPolicyTest {
    @Test fun versionsCompareNumerically() {
        assertTrue(compareVersions("2.30.10", "2.30.9") > 0)
        assertTrue(compareVersions("2.9.0", "2.30.0") < 0)
        assertEquals(0, compareVersions("v2.30.5", "2.30.5"))
    }
    @Test fun shellValuesRemainOneLiteralArgument() {
        assertEquals("'a'\"'\"'b'", shellQuote("a'b"))
        assertEquals("'\$(touch /tmp/never); x'", shellQuote("\$(touch /tmp/never); x"))
        assertEquals("''", shellQuote(""))
    }
    @Test fun limitedReadsRejectOversizedResponses() {
        assertArrayEquals(byteArrayOf(1,2), byteArrayOf(1,2).inputStream().readBytesLimited(2))
        assertThrows(IllegalStateException::class.java) { ByteArray(9).inputStream().readBytesLimited(8) }
    }
}
