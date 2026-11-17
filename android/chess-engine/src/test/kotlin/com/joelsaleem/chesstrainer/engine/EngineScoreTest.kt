package com.joelsaleem.chesstrainer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin-only sanity check for the pure EngineScore math (no Swift twin). */
class EngineScoreTest {
    @Test
    fun `centipawns comparable is the raw value`() {
        assertEquals(35, EngineScore.Centipawns(35).comparable)
    }

    @Test
    fun `mate flattens to a large signed value`() {
        assertEquals(9_999, EngineScore.Mate(1).comparable)
        assertEquals(-9_998, EngineScore.Mate(-2).comparable)
    }

    @Test
    fun `pointOfView flips only for the opponent`() {
        val s = EngineScore.Centipawns(80)
        assertEquals(s, s.pointOfView(sideToMove = true))
        assertEquals(EngineScore.Centipawns(-80), s.pointOfView(sideToMove = false))
        assertEquals(EngineScore.Mate(-3), EngineScore.Mate(3).pointOfView(sideToMove = false))
    }

    @Test
    fun `winProbability is one half at level and rises with advantage`() {
        assertEquals(0.5, EngineScore.Centipawns(0).winProbability, 1e-9)
        assertTrue(
            EngineScore.Centipawns(300).winProbability > EngineScore.Centipawns(0).winProbability,
        )
    }

    @Test
    fun `text formats mate and pawns`() {
        assertEquals("#3", EngineScore.Mate(3).text)
        assertEquals("+1.50", EngineScore.Centipawns(150).text)
    }
}
