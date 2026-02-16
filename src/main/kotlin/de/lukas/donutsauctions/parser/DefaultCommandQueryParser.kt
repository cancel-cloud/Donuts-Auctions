package de.lukas.donutsauctions.parser

import de.lukas.donutsauctions.model.AhSearchContext
import de.lukas.donutsauctions.model.TrackedMenuType

class DefaultCommandQueryParser(
    private val nowProvider: () -> Long = System::currentTimeMillis
) : CommandQueryParser {

    private val ahCommandRegex = Regex("^/ah(?:\\s+(.*))?$", RegexOption.IGNORE_CASE)

    override fun parseAhQuery(message: String): AhSearchContext? {
        val normalized = message.trim().let { if (it.startsWith("/")) it else "/$it" }
        val match = ahCommandRegex.matchEntire(normalized) ?: return null
        val query = match.groupValues.getOrNull(1)?.trim().orEmpty()

        return AhSearchContext(
            commandRaw = normalized,
            query = query,
            timestamp = nowProvider(),
            serverId = null,
            menuType = TrackedMenuType.AUCTION
        )
    }
}
