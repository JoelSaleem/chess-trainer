package com.joelsaleem.chesstrainer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Kotlin-only.
class TypesTest {
    @Test fun `square names round-trip`() {
        for (i in 0 until 64) assertEquals(i, Square.fromName(Square(i).toString())!!.index)
    }

    @Test fun `e4 is index 28`() {
        assertEquals(28, Square.fromName("e4")!!.index)
        assertNull(Square.fromName("i9"))
    }

    @Test fun `piece characters`() {
        assertEquals(Piece(PieceColor.black, PieceKind.knight), Piece.fromCharacter('n'))
        assertEquals('Q', Piece(PieceColor.white, PieceKind.queen).character)
    }

    @Test fun `castling fen strings`() {
        assertEquals("KQkq", CastlingRights.all.fenString)
        assertEquals("-", CastlingRights.none.fenString)
    }
}
