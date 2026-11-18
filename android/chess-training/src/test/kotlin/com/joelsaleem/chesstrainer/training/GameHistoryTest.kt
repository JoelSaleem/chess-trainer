package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.GameImport
import com.joelsaleem.chesstrainer.core.PieceColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure-logic half of the saved-games suite. The two database tests (round-trip, newest-first
 * sort) need a Context and live in src/androidTest as instrumented tests.
 */
class GameHistoryTest {
    private fun game(
        notation: String = "e4 e5 Nf3 Nc6",
        result: String = "win",
        white: String = "",
        black: String = "Club (1800)",
        yourColor: String? = "white",
    ): SavedGame = SavedGame(
        notation = notation, result = result, white = white, black = black,
        yourColor = yourColor, source = "play",
    )

    @Test
    fun `The stored notation reads back through the game importer`() {
        val saved = game()
        val imported = GameImport.read(saved.notation)
        assertNotNull(imported)
        assertEquals(4, imported?.moves?.size)
    }

    @Test
    fun `A game that began from a set-up position keeps that position`() {
        val fen = "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1"
        val saved = SavedGame(
            startFEN = fen, notation = "e4", result = "*", white = "Stockfish",
            black = "Reckless", source = "board",
        )
        assertEquals(fen, saved.startPosition.fen)
        assertFalse(saved.isYours)
    }

    @Test
    fun `Your side is named in the language being read, not the one it was played in`() {
        val saved = game()
        assertTrue(saved.white.isEmpty())
        assertEquals("You", saved.name(PieceColor.white))
        assertEquals("Club (1800)", saved.name(PieceColor.black))
    }

    @Test
    fun `An empty game is still a game`() {
        val saved = game(notation = "")
        assertTrue(saved.moves.isEmpty())
        assertEquals(0, saved.moveCount)
    }
}
