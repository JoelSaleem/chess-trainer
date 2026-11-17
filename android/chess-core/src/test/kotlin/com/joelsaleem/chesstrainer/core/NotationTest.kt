package com.joelsaleem.chesstrainer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Twin of ios/Tests/ChessCoreTests/NotationTests.swift; names match its @Test strings.
class NotationTest {
    private fun fen(fen: String) = assertNotNull(Position.fromFen(fen), "FEN should parse: $fen")

    private fun Position.sanMove(text: String) = assertNotNull(move(san = text), "should parse $text")

    @Test fun `FEN round-trips through parsing and printing`() {
        val samples = listOf(
            Position.startFEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "4k3/8/8/8/8/8/4P3/4K3 b - - 5 39",
        )
        for (sample in samples) assertEquals(sample, fen(sample).fen)
    }

    @Test fun `Illegal positions are rejected`() {
        // Side not to move is in check — unreachable in a real game.
        assertNull(Position.fromFen("8/8/8/4k3/8/8/4Q3/4K3 w - - 0 1"))
        assertNull(Position.fromFen("8/8/8/8/8/5k2/5p2/5K1Q w - - 0 1"))
        // Missing a king.
        assertNull(Position.fromFen("8/8/8/4k3/8/8/8/8 w - - 0 1"))
    }

    @Test fun `SAN is produced and parsed consistently`() {
        val position = Position()
        val game = listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7", "Re1", "b5", "Bb3", "d6", "c3", "O-O")
        for (text in game) {
            val move = position.sanMove(text)
            assertEquals(text, position.san(move))
            position.make(move)
        }
        assertEquals(9, position.fullmoveNumber)
    }

    @Test fun `Disambiguation names the right piece`() {
        // Two knights on d2 and f2 can both reach e4; the file separates them.
        var position = fen("4k3/8/8/8/8/8/3N1N2/4K3 w - - 0 1")
        assertEquals("Nde4", position.san(position.sanMove("Nde4")))

        // Rooks on the same file need the rank instead.
        position = fen("4k3/8/8/R7/8/8/R7/4K3 w - - 0 1")
        assertEquals("R5a4", position.san(position.sanMove("R5a4")))
    }

    @Test fun `Checkmate is marked with #, plain check with +`() {
        // Fool's mate: after 1.f3 e5 2.g4, Qh4 is mate.
        val position = fen("rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2")
        val mate = position.sanMove("Qh4")
        assertEquals("Qh4#", position.san(mate))
        position.make(mate)
        assertTrue(position.isCheckmate)
        assertTrue(position.legalMoves().isEmpty())

        // A check that is not mate gets "+".
        val check = fen("4k3/8/8/8/8/8/8/R6K w - - 0 1")
        val rookCheck = check.sanMove("Re1+")
        assertEquals("Re1+", check.san(rookCheck))
        check.make(rookCheck)
        assertTrue(check.isCheck)
        assertFalse(check.isCheckmate)
    }

    @Test fun `Threefold repetition is detected`() {
        val position = fen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1")
        // The first cycle changes castling rights, so occurrences two and three come from the cycles after it.
        repeat(3) {
            for (text in listOf("Rh2", "Ke7", "Rh1", "Ke8")) position.make(position.sanMove(text))
        }
        assertTrue(position.isThreefoldRepetition)
    }

    @Test fun `Insufficient material covers the usual cases`() {
        assertTrue(fen("4k3/8/8/8/8/8/8/4K3 w - - 0 1").isInsufficientMaterial)
        assertTrue(fen("4k3/8/8/8/8/8/8/4KN2 w - - 0 1").isInsufficientMaterial)
        assertTrue(fen("4k3/8/8/8/8/8/8/4KB2 w - - 0 1").isInsufficientMaterial)
        assertFalse(fen("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1").isInsufficientMaterial)
        assertFalse(fen("4k3/8/8/8/8/8/8/2B1KB2 w - - 0 1").isInsufficientMaterial)
    }
}
