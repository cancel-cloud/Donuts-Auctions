package de.lukas.donutsauctions.parser

import kotlin.math.roundToLong

class PriceParser {

    private val moneyHintRegex = Regex("(?i)(price|cost|buy|coins?|dollars?)")
    private val numberRegex = Regex("(?i)(\\d+(?:[.,]\\d+)?(?:[.,]\\d{3})*)([km]?)")

    fun parsePrice(input: String): Long? {
        val cleaned = input
            .replace(Regex("§[0-9A-FK-OR]", RegexOption.IGNORE_CASE), "")
            .replace(" ", "")

        val match = numberRegex.find(cleaned) ?: return null
        val base = normalizeNumber(match.groupValues[1]) ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            else -> 1.0
        }

        return (base * multiplier).roundToLong()
    }

    fun parseFirstPrice(lines: List<String>): Long? {
        val prioritized = lines.filter { moneyHintRegex.containsMatchIn(it) }
        for (line in prioritized + lines) {
            val parsed = parsePrice(line)
            if (parsed != null && parsed > 0) {
                return parsed
            }
        }
        return null
    }

    private fun normalizeNumber(raw: String): Double? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        return when {
            value.contains(',') && value.contains('.') -> {
                value.replace(",", "").toDoubleOrNull()
            }
            value.count { it == ',' } > 1 -> {
                value.replace(",", "").toDoubleOrNull()
            }
            value.count { it == '.' } > 1 -> {
                value.replace(".", "").toDoubleOrNull()
            }
            value.contains(',') && value.substringAfter(',').length == 3 -> {
                value.replace(",", "").toDoubleOrNull()
            }
            value.contains('.') && value.substringAfter('.').length == 3 -> {
                value.replace(".", "").toDoubleOrNull()
            }
            value.contains(',') -> {
                value.replace(',', '.').toDoubleOrNull()
            }
            else -> value.toDoubleOrNull()
        }
    }
}
