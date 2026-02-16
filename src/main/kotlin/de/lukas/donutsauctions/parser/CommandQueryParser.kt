package de.lukas.donutsauctions.parser

import de.lukas.donutsauctions.model.AhSearchContext

fun interface CommandQueryParser {
    fun parseAhQuery(message: String): AhSearchContext?
}
