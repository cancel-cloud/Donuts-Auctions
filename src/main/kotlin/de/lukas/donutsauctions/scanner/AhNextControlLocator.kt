package de.lukas.donutsauctions.scanner

import net.minecraft.item.Items
import net.minecraft.screen.slot.Slot

object AhNextControlLocator {

    data class Candidate(
        val topPosition: Int,
        val slotId: Int,
        val slotIndex: Int,
        val isArrow: Boolean,
        val displayName: String
    )

    fun findStrictNextSlotId(topSlots: List<Slot>): Int? {
        return findStrictNextCandidate(topSlots)?.slotId
    }

    fun findStrictNextSlotIndex(topSlots: List<Slot>): Int? {
        return findStrictNextCandidate(topSlots)?.slotIndex
    }

    internal fun findStrictNextCandidate(topSlots: List<Slot>): Candidate? {
        val candidates = topSlots.mapIndexed { position, slot ->
            val stack = slot.stack
            Candidate(
                topPosition = position,
                slotId = slot.id,
                slotIndex = slot.index,
                isArrow = !stack.isEmpty && stack.item == Items.ARROW,
                displayName = sanitizeLabel(stack.name.string)
            )
        }
        return selectStrictNextCandidate(topSlots.size, candidates)
    }

    internal fun selectStrictNextCandidate(topSlotCount: Int, candidates: List<Candidate>): Candidate? {
        if (topSlotCount < 9) {
            return null
        }

        val bottomRowStart = topSlotCount - 9
        return candidates
            .asSequence()
            .filter { it.topPosition in bottomRowStart until topSlotCount }
            .filter { it.isArrow }
            .filter { nextLabelRegex.containsMatchIn(it.displayName) }
            .maxByOrNull { it.topPosition }
    }

    internal fun sanitizeLabel(value: String): String {
        return value.replace(colorCodeRegex, "").trim()
    }

    private val colorCodeRegex = Regex("§[0-9A-FK-OR]", RegexOption.IGNORE_CASE)
    private val nextLabelRegex = Regex("(?i)\\bnext(?:\\s*page)?\\b")
}
