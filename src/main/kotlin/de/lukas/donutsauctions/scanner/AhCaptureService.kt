package de.lukas.donutsauctions.scanner

import de.lukas.donutsauctions.db.AuctionRepository
import de.lukas.donutsauctions.model.AhSearchContext
import de.lukas.donutsauctions.model.AuctionListingRecord
import de.lukas.donutsauctions.parser.ItemSignatureBuilder
import de.lukas.donutsauctions.parser.PriceParser
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.registry.Registries
import net.minecraft.screen.GenericContainerScreenHandler
import net.minecraft.screen.slot.Slot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class AhCaptureService(
    private val repository: AuctionRepository,
    private val classifier: AhScreenClassifier,
    private val priceParser: PriceParser,
    private val deduplicator: SnapshotDeduplicator,
    private val signatureBuilder: ItemSignatureBuilder = ItemSignatureBuilder(),
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    private val capturedPageKeys = linkedSetOf<String>()

    private val pageRegex = Regex("(?i)page\\D*(\\d+)")
    private val sellerRegex = Regex("(?i)(seller|owner)\\s*[:>-]\\s*(.+)$")
    private val priceLineRegex = Regex("(?i)\\bprice\\b")
    private val sellerLineRegex = Regex("(?i)\\b(seller|owner)\\b")
    private val timeLeftLineRegex = Regex("(?i)\\btime\\s*left\\b")

    @Synchronized
    fun resetSession() {
        capturedPageKeys.clear()
    }

    fun capture(client: MinecraftClient, searchContext: AhSearchContext?): AhCaptureResult {
        val screen = client.currentScreen as? HandledScreen<*> ?: return AhCaptureResult.Noop("Current screen is not a handled menu")
        val player = client.player ?: return AhCaptureResult.Noop("Player unavailable")
        val handler = player.currentScreenHandler ?: return AhCaptureResult.Noop("Screen handler unavailable")

        val title = screen.title.string
        val topSlotCount = determineTopSlotCount(handler)
        if (topSlotCount <= 0) {
            return AhCaptureResult.Noop("Top slot count is 0")
        }

        val topSlots = handler.slots.take(topSlotCount)
        val topSlotsByIndex = topSlots.associateBy { it.index }
        val controls = findControlSlots(topSlots)
        val listingSlots = topSlots.filter { slot ->
            if (slot.stack.isEmpty) return@filter false
            if (controls.containsKey(slot.index)) return@filter false
            isAuctionListing(slot.stack, client)
        }

        val hasAuctionControls = controls.values.contains("SEARCH") && controls.values.any { it in setOf("SORT", "FILTER", "NEXT", "AUCTION") }
        val looksLikeAuction = classifier.isAuctionScreen(title, controls.values.toSet()) || hasAuctionControls || listingSlots.isNotEmpty()
        if (!looksLikeAuction) {
            return AhCaptureResult.Noop("Screen does not look like auction (title='$title', controls=${controls.values.sorted()}, listingCandidates=${listingSlots.size})")
        }

        val sortMode = controls.entries.firstOrNull { it.value == "SORT" }?.let { topSlotsByIndex[it.key] }?.let { inferSelectedMode(it.stack, client) }
        val filterMode = controls.entries.firstOrNull { it.value == "FILTER" }?.let { topSlotsByIndex[it.key] }?.let { inferSelectedMode(it.stack, client) }
        val page = pageRegex.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val query = searchContext?.query.orEmpty()
        val pageCaptureKey = buildPageCaptureKey(searchContext, page, title)
        if (capturedPageKeys.contains(pageCaptureKey)) {
            return AhCaptureResult.Noop("Page already captured in current session (key=$pageCaptureKey)")
        }

        val capturedAt = nowProvider()
        val records = listingSlots.map { slot ->
            toListingRecord(
                client = client,
                stack = slot.stack,
                slot = slot,
                query = query,
                capturedAt = capturedAt,
                page = page,
                sortMode = sortMode,
                filterMode = filterMode
            )
        }
        val blockedCount = records.count { isExcludedItemId(it.itemId) }
        val filteredRecords = records.filterNot { isExcludedItemId(it.itemId) }

        if (filteredRecords.isEmpty()) {
            return AhCaptureResult.Noop(
                "No listing records extracted after exclusions (listingCandidates=${listingSlots.size}, blocked=$blockedCount)"
            )
        }

        val snapshotHash = buildSnapshotHash(filteredRecords, query, page, sortMode, filterMode)
        if (!deduplicator.shouldCapture(snapshotHash)) {
            return AhCaptureResult.Duplicate
        }

        val storedRecords = filteredRecords.map { it.copy(snapshotHash = snapshotHash) }
        val snapshotId = repository.insertSnapshot(
            searchContext = searchContext,
            page = page,
            sortMode = sortMode,
            filterMode = filterMode,
            snapshotHash = snapshotHash,
            rawTitle = title
        )
        repository.insertListings(snapshotId, storedRecords)
        capturedPageKeys.add(pageCaptureKey)

        return AhCaptureResult.Captured(
            snapshotHash = snapshotHash,
            listingCount = storedRecords.size,
            capturedAt = capturedAt,
            page = page
        )
    }

    private fun determineTopSlotCount(handler: net.minecraft.screen.ScreenHandler): Int {
        if (handler is GenericContainerScreenHandler) {
            return handler.rows * 9
        }

        val fallback = handler.slots.size - 36
        return fallback.coerceAtLeast(0)
    }

    private fun findControlSlots(topSlots: List<Slot>): Map<Int, String> {
        val mapping = mutableMapOf<Int, String>()

        topSlots.forEach { slot ->
            val stack = slot.stack
            if (stack.isEmpty) return@forEach

            val displayName = sanitize(stack.name.string)
            val type = stack.item

            val control = when {
                displayName.contains("SEARCH", ignoreCase = true) || type == Items.OAK_SIGN -> "SEARCH"
                displayName.contains("SORT", ignoreCase = true) || type == Items.CAULDRON -> "SORT"
                displayName.contains("FILTER", ignoreCase = true) || type == Items.HOPPER -> "FILTER"
                displayName.contains("NEXT", ignoreCase = true) || type == Items.ARROW -> "NEXT"
                displayName.contains("AUCTION", ignoreCase = true) && type == Items.ANVIL -> "AUCTION"
                else -> null
            }

            if (control != null) {
                mapping[slot.index] = control
            }
        }

        return mapping
    }

    private fun isAuctionListing(stack: ItemStack, client: MinecraftClient): Boolean {
        val loreLines = tooltipLines(stack, client)
        if (loreLines.isEmpty()) {
            return false
        }

        val hasPrice = loreLines.any { priceLineRegex.containsMatchIn(it) }
        val hasSeller = loreLines.any { sellerLineRegex.containsMatchIn(it) }
        val hasTimeLeft = loreLines.any { timeLeftLineRegex.containsMatchIn(it) }

        return hasPrice && hasSeller && hasTimeLeft
    }

    private fun inferSelectedMode(stack: ItemStack, client: MinecraftClient): String? {
        val loreLines = tooltipLines(stack, client)
        for (line in loreLines) {
            val trimmed = sanitize(line).trim()
            if (trimmed.startsWith(".") || trimmed.startsWith("-") || trimmed.startsWith("•")) {
                return trimmed.trimStart('.', '-', '•', ' ').ifBlank { null }
            }
        }
        return null
    }

    private fun toListingRecord(
        client: MinecraftClient,
        stack: ItemStack,
        slot: Slot,
        query: String,
        capturedAt: Long,
        page: Int?,
        sortMode: String?,
        filterMode: String?
    ): AuctionListingRecord {
        val loreLines = tooltipLines(stack, client)
        val loreRaw = loreLines.joinToString("\n")
        val totalPrice = priceParser.parseFirstPrice(loreLines)
        val amount = stack.count.coerceAtLeast(1)
        val unitPrice = totalPrice?.toDouble()?.div(amount)
        val itemId = Registries.ITEM.getId(stack.item).toString()
        val displayName = sanitize(stack.name.string)
        val itemSignature = signatureBuilder.build(
            itemId = itemId,
            displayName = displayName,
            loreLines = loreLines
        )
        val seller = loreLines.firstNotNullOfOrNull { line ->
            val match = sellerRegex.find(sanitize(line))
            match?.groupValues?.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
        }

        return AuctionListingRecord(
            capturedAt = capturedAt,
            query = query,
            page = page,
            slot = slot.index,
            itemId = itemId,
            itemSignature = itemSignature,
            name = displayName,
            amount = amount,
            totalPrice = totalPrice,
            unitPrice = unitPrice,
            seller = seller,
            rawLore = loreRaw,
            sortMode = sortMode,
            filterMode = filterMode,
            snapshotHash = ""
        )
    }

    private fun tooltipLines(stack: ItemStack, client: MinecraftClient): List<String> {
        return try {
            stack.getTooltip(Item.TooltipContext.DEFAULT, client.player, TooltipType.BASIC).map { sanitize(it.string) }
        } catch (_: Throwable) {
            listOf(sanitize(stack.name.string))
        }
    }

    private fun buildSnapshotHash(
        records: List<AuctionListingRecord>,
        query: String,
        page: Int?,
        sortMode: String?,
        filterMode: String?
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(query.toByteArray(StandardCharsets.UTF_8))
        digest.update((page ?: -1).toString().toByteArray(StandardCharsets.UTF_8))
        digest.update((sortMode ?: "-").toByteArray(StandardCharsets.UTF_8))
        digest.update((filterMode ?: "-").toByteArray(StandardCharsets.UTF_8))

        records
            .sortedWith(compareBy({ it.slot }, { it.itemId }, { it.amount }, { it.totalPrice ?: 0L }))
            .forEach { record ->
                digest.update(record.slot.toString().toByteArray(StandardCharsets.UTF_8))
                digest.update(record.itemId.toByteArray(StandardCharsets.UTF_8))
                digest.update(record.amount.toString().toByteArray(StandardCharsets.UTF_8))
                digest.update((record.totalPrice ?: -1L).toString().toByteArray(StandardCharsets.UTF_8))
            }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildPageCaptureKey(searchContext: AhSearchContext?, page: Int?, title: String): String {
        val queryPart = searchContext?.query?.trim().orEmpty().lowercase()
        val serverPart = searchContext?.serverId.orEmpty()
        val pagePart = page?.toString() ?: "title:${sanitize(title).lowercase()}"
        return "$serverPart|$queryPart|$pagePart"
    }

    private fun sanitize(value: String): String {
        return value.replace(Regex("§[0-9A-FK-OR]", RegexOption.IGNORE_CASE), "").trim()
    }

    companion object {
        internal fun isExcludedItemId(itemId: String): Boolean {
            return itemId.lowercase().contains("shulker_box")
        }
    }
}
