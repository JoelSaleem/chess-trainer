package com.joelsaleem.chesstrainer.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from ios/Tests/ChessEngineTests/StockfishTests.swift.
 *
 * Method names are the Swift func names (camelCase); the Swift @Test display
 * string is in a comment. Instrumented tests are dexed, and D8 forbids spaces in
 * class names for minSdk < 35, so backtick names with spaces cannot be used here.
 */
class StockfishEngineTest {
    // "The engine reports itself"
    @Test
    fun engineInfo() {
        assertTrue(StockfishEngine.engineDescription.contains("Stockfish"))
    }

    // "It finds mate in one"
    @Test
    fun findsMate() = runBlocking {
        val engine = EngineHarness.makeEngine()
        // Back-rank mate: Ra8#.
        val analysis = engine.analyse("6k1/5ppp/8/8/8/8/8/R3K3 w - - 0 1", depth = 12)
        assertEquals("a1a8", analysis.bestMove)
        val score = analysis.score
        assertTrue("expected a mate score, got $score", score is EngineScore.Mate && score.moves == 1)
        engine.close()
    }

    // "It wins the hanging queen"
    @Test
    fun winsMaterial() = runBlocking {
        val engine = EngineHarness.makeEngine()
        // Black's queen on d4 is simply attacked by the rook on d1.
        val analysis = engine.analyse("4k3/8/8/8/3q4/8/8/3RK3 w - - 0 1", depth = 14)
        assertEquals("d1d4", analysis.bestMove)
        engine.close()
    }

    // "MultiPV returns several ranked lines"
    @Test
    fun multiPV() = runBlocking {
        val engine = EngineHarness.makeEngine()
        val analysis = engine.analyse(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            depth = 12,
            multiPV = 3,
        )
        assertEquals(3, analysis.lines.size)
        assertEquals(listOf(1, 2, 3), analysis.lines.map { it.rank })
        // Ranked best first, from the mover's point of view.
        val scores = analysis.lines.map { it.score.comparable }
        assertEquals(scores.sortedDescending(), scores)
        engine.close()
    }

    // "A terminal position yields no lines rather than crashing"
    @Test
    fun terminalPosition() = runBlocking {
        val engine = EngineHarness.makeEngine()
        // Already checkmated: no legal moves.
        val analysis = engine.analyse("7k/5KQ1/8/8/8/8/8/8 b - - 0 1", depth = 8)
        assertTrue(analysis.isTerminal)
        assertNull(analysis.bestMove)
        engine.close()
    }

    // "Strength limiting changes how it plays"
    @Test
    fun limitedStrength() = runBlocking {
        val engine = EngineHarness.makeEngine()
        val move = engine.chooseMove(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            elo = 1400,
            movetimeMs = 200,
        )
        assertNotNull(move)
        assertEquals(4, move!!.length)
        engine.close()
    }

    // "Repeated searches on one engine stay stable"
    @Test
    fun repeatedSearches() = runBlocking {
        val engine = EngineHarness.makeEngine()
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        repeat(8) {
            val analysis = engine.analyse(fen, depth = 10, multiPV = 2)
            assertNotNull(analysis.bestMove)
            engine.chooseMove(fen, elo = 1800, movetimeMs = 100)
        }
        engine.close()
    }

    // "The capability name drops the authorship"
    @Test
    fun shortName() {
        val short = StockfishEngine.shortDescription
        assertTrue(short.startsWith("Stockfish"))
        assertTrue(!short.contains(" by "))
        assertTrue(!short.contains("AUTHORS"))
    }
}
