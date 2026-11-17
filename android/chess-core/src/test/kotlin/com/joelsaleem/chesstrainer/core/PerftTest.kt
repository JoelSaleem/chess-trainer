package com.joelsaleem.chesstrainer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Twin of ios/Tests/ChessCoreTests/PerftTests.swift.
class PerftTest {
    private class Case(val name: String, val fen: String, val expected: List<Int>)

    private val positions = listOf(
        Case("starting position", Position.startFEN, listOf(20, 400, 8902, 197_281, 4_865_609)),
        Case(
            "kiwipete",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            listOf(48, 2039, 97_862, 4_085_603),
        ),
        Case("endgame with en passant", "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", listOf(14, 191, 2812, 43_238, 674_624)),
        Case("promotion and pins", "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", listOf(6, 264, 9467)),
        Case("under-promotion", "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", listOf(44, 1486, 62_379)),
        Case(
            "dense middlegame",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            listOf(46, 2079, 89_890),
        ),
    )

    @Test fun `Perft counts match the published values`() {
        for (case in positions) {
            val position = assertNotNull(Position.fromFen(case.fen), "FEN should parse: ${case.name}")
            case.expected.forEachIndexed { index, expected ->
                val depth = index + 1
                assertEquals(expected.toLong(), position.perft(depth), "${case.name} depth $depth")
            }
        }
    }
}
