package com.joelsaleem.chesstrainer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Kotlin-only: guards the port's one structural difference from Swift
// (Position is a class here, a value type there).
class PositionCopyTest {
    @Test fun `copy is independent of the original`() {
        val a = Position()
        val b = a.copy()
        assertNotNull(b.make(uci = "e2e4"))
        assertEquals(Position.startFEN, a.fen)
        assertNotEquals(a.hash, b.hash)
    }

    @Test fun `GameImport leaves its start position untouched`() {
        val read = assertNotNull(GameImport.read("1. e4 e5"))
        assertEquals(Position.startFEN, read.start.fen)
        assertNull(read.stoppedAt)
    }
}
