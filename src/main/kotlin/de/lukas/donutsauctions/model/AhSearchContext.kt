package de.lukas.donutsauctions.model

data class AhSearchContext(
    val commandRaw: String,
    val query: String,
    val timestamp: Long,
    val serverId: String?,
    val menuType: TrackedMenuType
)
