package com.joelsaleem.chesstrainer.training

import java.io.File

/**
 * Reads the training-content JSON files from a directory and parses them, tolerating any being
 * absent. The parsing lives in commonMain ([ContentLibrary.parse]); only the file reading is here,
 * because a web build has no filesystem and loads the same strings over the network instead.
 */
fun ContentLibrary.Companion.load(directory: File): ContentLibrary {
    fun text(name: String): String? = File(directory, name).takeIf { it.isFile }?.readText()
    return ContentLibrary.parse(
        tactics = text("tactics.json"),
        positions = text("positions.json"),
        endgames = text("endgames.json"),
        games = text("games.json"),
        classics = text("classics.json"),
    )
}
