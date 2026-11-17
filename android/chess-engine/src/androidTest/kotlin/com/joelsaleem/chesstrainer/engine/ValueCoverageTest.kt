package com.joelsaleem.chesstrainer.engine

import com.joelsaleem.chesstrainer.core.Position
import com.joelsaleem.chesstrainer.core.legalMoves
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ported from ios/Tests/ChessEngineTests/ValueCoverageTests.swift. */
class ValueCoverageTest {
    private val quiet = "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQK2R w KQkq - 0 1"
    private val mating = "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1"

    // "A quiet position values every legal move"
    @Test
    fun quietIsComplete() = runBlocking {
        val engine = EngineHarness.makeEngine()
        val legal = Position.fromFen(quiet)!!.legalMoves().size
        val values = engine.valueEveryMove(quiet)
        assertEquals(legal, values.byMove.size)
        engine.close()
    }

    // "A position with a forced mate values every legal move too"
    @Test
    fun mateIsComplete() = runBlocking {
        val engine = EngineHarness.makeEngine()
        val legal = Position.fromFen(mating)!!.legalMoves().size
        val values = engine.valueEveryMove(mating)
        assertEquals(legal, values.byMove.size)
        engine.close()
    }
}
