@file:OptIn(ExperimentalUuidApi::class)

package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.Position
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A game that was actually played, kept whole.
 *
 * [TrainingProgress.games] already records that a game happened — result, accuracy, how strong the
 * opponent was — because those are the numbers a rating is made of. It says nothing about what was
 * played. This is the moves, so a game can be watched back afterwards and picked up from any point.
 *
 * Its own store rather than another field on the progress file: that file is read and rewritten
 * whole on every change, and a couple of hundred games of notation is not something to re-encode
 * because somebody solved a puzzle.
 *
 * ponytail: plain data class — the storage annotations (Room `@Entity`/`@PrimaryKey`, or the column
 * mapping for a raw-SQLite store) live with the store in GameHistory.kt, so this stays a value the
 * pure logic and its JVM tests can use without a database.
 */
data class SavedGame(
    /** A string, so it keys the same watch marks and favourites the classics use. */
    val id: String = Uuid.random().toString(),
    val playedAt: Instant = Clock.System.now(),
    /** Where the line begins: the opening position, or a board that was set up. */
    val startFEN: String = Position().fen,
    /** SAN moves separated by spaces — the form [com.joelsaleem.chesstrainer.core.GameImport] reads. */
    val notation: String,
    /** "win", "draw" or "loss" from your side; "*" when neither side was yours. */
    val result: String,
    val white: String,
    val black: String,
    /**
     * "white", "black", or null when you held neither side. Your own side's name is stored empty
     * rather than as "You": the word is translated, and a history written in the language the app
     * happened to be in would read wrong ever after. [name] puts the right word in at the time of asking.
     */
    val yourColor: String? = null,
    val opponentElo: Int? = null,
    val accuracy: Int? = null,
    /** Which screen it came from: "play", "board", "online". */
    val source: String,
) {
    /** Who held a side, in the language being read now. */
    fun name(color: PieceColor): String {
        val stored = if (color == PieceColor.white) white else black
        return stored.ifEmpty { L.t("history.you", "You") }
    }

    val players: String get() = "${name(PieceColor.white)} — ${name(PieceColor.black)}"

    val moves: List<String> get() = notation.split(" ").filter { it.isNotEmpty() }

    val moveCount: Int get() = moves.size

    /** Where the line begins, or the opening position if the stored FEN has stopped being one. */
    val startPosition: Position get() = Position.fromFen(startFEN) ?: Position()

    /** True when you took part, rather than set two engines going and watched. */
    val isYours: Boolean get() = yourColor != null
}
