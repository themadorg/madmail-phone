package org.madmail.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ByteSizeTest {
    @Test fun quotasUse1024BasedUnits() {
        assertEquals("1 GB", ByteSize.format(1024L * 1024 * 1024, Locale.US))
        assertEquals("512 MB", ByteSize.format(512L * 1024 * 1024, Locale.US))
        assertEquals("1.5 GB", ByteSize.format(1536L * 1024 * 1024, Locale.US))
        assertEquals("1,000 MB", ByteSize.format(1000L * 1024 * 1024, Locale.US))
        assertEquals("0 B", ByteSize.format(0, Locale.US))
        assertEquals("2 KB", ByteSize.format(2048, Locale.US))
    }
}
