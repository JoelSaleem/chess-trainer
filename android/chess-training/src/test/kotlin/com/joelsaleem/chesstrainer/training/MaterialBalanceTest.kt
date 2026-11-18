package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.PieceKind
import com.joelsaleem.chesstrainer.core.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from ios/Tests/ChessTrainingTests/MaterialBalanceTests.swift (names verbatim). */
class MaterialBalanceTest {
    @Test
    fun `Nothing is captured in the starting position`() {
        val material = MaterialBalance(Position())
        assertTrue(material.capturedBy(PieceColor.white).isEmpty())
        assertTrue(material.capturedBy(PieceColor.black).isEmpty())
        assertEquals(0, material.lead(PieceColor.white))
        assertEquals(0, material.lead(PieceColor.black))
    }

    @Test
    fun `A missing piece is credited to the side that took it`() {
        // White is a knight and a pawn down, Black a rook down.
        val position = Position.fromFen("1nbqkbnr/pppppppp/8/8/8/8/PPPPPPP1/R1BQKBNR w KQk - 0 1")!!
        val material = MaterialBalance(position)
        assertEquals(listOf(PieceKind.knight, PieceKind.pawn), material.capturedBy(PieceColor.black))
        assertEquals(listOf(PieceKind.rook), material.capturedBy(PieceColor.white))
        // Rook 5 against knight 3 plus pawn 1.
        assertEquals(1, material.lead(PieceColor.white))
        assertEquals(0, material.lead(PieceColor.black))
    }

    @Test
    fun `A promoted pawn does not read as a captured queen`() {
        // Two white queens, seven white pawns: one pawn promoted, nothing taken.
        val position = Position.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPP1/RNBQKBNQ w kq - 0 1")!!
        val material = MaterialBalance(position)
        assertEquals(listOf(PieceKind.rook, PieceKind.pawn), material.capturedBy(PieceColor.black))
        assertTrue(material.capturedBy(PieceColor.white).isEmpty())
    }
}
