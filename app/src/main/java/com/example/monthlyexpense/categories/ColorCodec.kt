package com.example.monthlyexpense.categories

import java.util.Locale

object ColorCodec {
    fun parse(value: String): Long? {
        if (!value.startsWith('#')) return null
        val digits = value.drop(1)
        if ((digits.length != 6 && digits.length != 8) || digits.any { it.digitToIntOrNull(16) == null }) {
            return null
        }
        return try {
            val parsed = digits.toLong(16)
            if (digits.length == 6) parsed or 0xFF000000L else parsed
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun format(argb: Long): String = String.format(
        Locale.ROOT,
        "#%08X",
        argb and 0xFFFFFFFFL
    )
}
