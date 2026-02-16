package de.lukas.donutsauctions.parser

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class ItemSignatureBuilder {

    private val volatileLinePatterns = listOf(
        Regex("(?i)^\\s*price\\b"),
        Regex("(?i)^\\s*(seller|owner)\\b"),
        Regex("(?i)^\\s*time\\s*left\\b"),
        Regex("(?i)^\\s*worth\\b")
    )

    fun build(itemId: String, displayName: String, loreLines: List<String>): String {
        val normalizedLines = loreLines
            .map { sanitize(it) }
            .filter { it.isNotBlank() }
        val stableLines = normalizedLines
            .filterNot { line -> volatileLinePatterns.any { pattern -> pattern.containsMatchIn(line) } }

        val featureMarkers = extractFeatureMarkers(stableLines)

        val base = buildString {
            append(sanitize(itemId))
            append('|')
            append(sanitize(displayName))
            append('|')
            append(stableLines.joinToString("|"))
            append('|')
            append(featureMarkers.sorted().joinToString("|"))
        }

        return sha256(base)
    }

    private fun extractFeatureMarkers(stableLines: List<String>): Set<String> {
        val markers = linkedSetOf<String>()

        stableLines.forEach { line ->
            val normalized = line.lowercase()
            when {
                normalized.contains("sharpness")
                    || normalized.contains("unbreaking")
                    || normalized.contains("protection")
                    || normalized.contains("fortune")
                    || normalized.contains("mending")
                    || normalized.contains("efficiency")
                    || normalized.contains("silk touch")
                    || normalized.contains("smite")
                    || normalized.contains("fire aspect")
                    || normalized.contains("power")
                    || normalized.contains("infinity")
                -> markers.add("ench:${normalized}")
            }

            if (normalized.contains("potion")
                || normalized.contains("effect")
                || normalized.contains("duration")
            ) {
                markers.add("potion:${normalized}")
            }

            if (normalized.contains("durability")
                || normalized.contains("damage")
            ) {
                markers.add("durability:${normalized}")
            }

            if (normalized.contains("map")
                || normalized.contains("pattern")
                || normalized.contains("banner")
                || normalized.contains("trim")
            ) {
                markers.add("visual:${normalized}")
            }
        }

        return markers
    }

    private fun sanitize(value: String): String {
        return value
            .replace(Regex("§[0-9A-FK-OR]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(value.toByteArray(StandardCharsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
