package org.madmail.mobile.core

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Sizes in 1024-based units, so a quota entered as 1024 MB reads back as 1 GB.
 * Android's Formatter counts in 1000s, which turned a 1024 MB quota into "1.07 GB".
 */
internal object ByteSize {
    private val units = listOf("B", "KB", "MB", "GB", "TB", "PB")

    fun format(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return "${DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(locale)).format(value)} ${units[unit]}"
    }
}
