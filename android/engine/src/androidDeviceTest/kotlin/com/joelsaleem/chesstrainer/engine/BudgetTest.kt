package com.joelsaleem.chesstrainer.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from ios/Tests/ChessEngineTests/BudgetTests.swift — the three
 * Stockfish-only cases. The two Reckless cases are out of scope for v1.
 */
class BudgetTest {
    private val sharp = "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQK2R w KQkq - 0 1"

    // "Full strength answers inside its budget"
    @Test
    fun fullStrengthIsBounded() = runBlocking {
        val engine = EngineHarness.makeEngine()
        val started = System.currentTimeMillis()
        val move = engine.chooseMove(
            sharp,
            depth = SearchBudget.fullStrength.depth,
            movetimeMs = SearchBudget.fullStrength.movetimeMs,
        )
        val elapsed = (System.currentTimeMillis() - started) / 1000.0
        assertNotNull(move)
        // Generous against the budget: the engine finishes its current iteration.
        assertTrue("full-strength search took ${elapsed}s", elapsed < 8)
        engine.close()
    }

    // "Searches asked for at the same moment all come back"
    @Test
    fun overlappingSearchesFinish() = runBlocking {
        val engine = EngineHarness.makeEngine()
        val started = System.currentTimeMillis()
        coroutineScope {
            val reply = async {
                engine.chooseMove(
                    sharp,
                    depth = SearchBudget.fullStrength.depth,
                    movetimeMs = SearchBudget.fullStrength.movetimeMs,
                )
            }
            val grading = async {
                engine.analyse(
                    sharp,
                    depth = SearchBudget.coaching.depth,
                    movetimeMs = SearchBudget.coaching.movetimeMs,
                    multiPV = 2,
                )
            }
            val verdict = async {
                engine.analyse(
                    sharp,
                    depth = SearchBudget.verdict.depth,
                    movetimeMs = SearchBudget.verdict.movetimeMs,
                    multiPV = 1,
                )
            }
            assertNotNull(reply.await())
            assertTrue(grading.await().lines.isNotEmpty())
            assertTrue(verdict.await().lines.isNotEmpty())
        }
        val elapsed = (System.currentTimeMillis() - started) / 1000.0
        // The real signal is that this line is reached at all (no parked continuation).
        assertTrue("three overlapping searches took ${elapsed}s", elapsed < 30)
        engine.close()
    }

    // "Coaching stays quick enough to run after every move"
    @Test
    fun coachingIsBounded() = runBlocking {
        val engine = EngineHarness.makeEngine()
        val started = System.currentTimeMillis()
        engine.analyse(
            sharp,
            depth = SearchBudget.coaching.depth,
            movetimeMs = SearchBudget.coaching.movetimeMs,
            multiPV = 2,
        )
        val elapsed = (System.currentTimeMillis() - started) / 1000.0
        assertTrue("coaching search took ${elapsed}s", elapsed < 4)
        engine.close()
    }
}
