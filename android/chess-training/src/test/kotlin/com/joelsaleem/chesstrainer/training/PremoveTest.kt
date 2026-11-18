package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.Piece
import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.PieceKind
import com.joelsaleem.chesstrainer.core.Position
import com.joelsaleem.chesstrainer.core.Square
import com.joelsaleem.chesstrainer.core.legalMoves
import com.joelsaleem.chesstrainer.core.make
import com.joelsaleem.chesstrainer.core.san
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from ios/Tests/ChessTrainingTests/PremoveTests.swift (names verbatim). */
class PremoveTest {
    /** White has played e4, so Black is to move: White may plan but not play. */
    private fun afterE4(): Position {
        val position = Position()
        val move = position.legalMoves().first { position.san(it) == "e4" }
        position.make(move)
        return position
    }

    @Test
    fun `A move can be queued while it is the opponent's turn`() {
        val position = afterE4()
        val queue = PremoveQueue()
        val accepted = queue.queue(Square.fromName("g1")!!, Square.fromName("f3")!!, null, position, PieceColor.white)
        assertTrue(accepted)
        assertEquals(1, queue.count)
        assertEquals(listOf(Square.fromName("g1")!!, Square.fromName("f3")!!), queue.squares)
    }

    @Test
    fun `Nothing is queued on your own turn — that is a move, not a plan`() {
        val queue = PremoveQueue()
        val accepted = queue.queue(Square.fromName("e2")!!, Square.fromName("e4")!!, null, Position(), PieceColor.white)
        assertFalse(accepted)
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `A plan several moves deep builds on itself`() {
        val position = afterE4()
        val queue = PremoveQueue()
        val knight = queue.queue(Square.fromName("g1")!!, Square.fromName("f3")!!, null, position, PieceColor.white)
        val bishop = queue.queue(Square.fromName("f1")!!, Square.fromName("c4")!!, null, position, PieceColor.white)
        assertTrue(knight)
        assertTrue(bishop)
        assertEquals(2, queue.count)
        val planned = queue.planned(position, PieceColor.white)
        assertEquals(Piece(PieceColor.white, PieceKind.knight), planned[Square.fromName("f3")!!])
        assertEquals(Piece(PieceColor.white, PieceKind.bishop), planned[Square.fromName("c4")!!])
    }

    @Test
    fun `The queue plays out when the move is still legal`() {
        val position = afterE4()
        val queue = PremoveQueue()
        queue.queue(Square.fromName("g1")!!, Square.fromName("f3")!!, null, position, PieceColor.white)
        val reply = position.legalMoves().first { position.san(it) == "e5" }
        position.make(reply)
        val next = queue.next(position)
        assertEquals(Square.fromName("g1")!!, next!!.from)
        assertEquals(Square.fromName("f3")!!, next.to)
        assertTrue(queue.isEmpty)
        assertFalse(queue.wasDropped)
    }

    @Test
    fun `A plan whose first step became impossible is dropped whole`() {
        // Black to move; White plans Rxd8, Black takes the rook first.
        val position = Position.fromFen("3q3k/8/8/8/8/8/6PP/3R2K1 b - - 0 1")!!
        val queue = PremoveQueue()
        val planned = queue.queue(Square.fromName("d1")!!, Square.fromName("d8")!!, null, position, PieceColor.white)
        assertTrue(planned)
        val takesTheRook = position.legalMoves().first {
            it.from == Square.fromName("d8")!! && it.to == Square.fromName("d1")!!
        }
        position.make(takesTheRook)
        val played = queue.next(position)
        assertNull(played)
        assertTrue(queue.isEmpty)
        assertTrue(queue.wasDropped)
    }

    @Test
    fun `No destinations are offered when it is your move`() {
        val queue = PremoveQueue()
        assertTrue(queue.destinations(Position(), PieceColor.white).isEmpty())
        assertFalse(queue.destinations(Position(), PieceColor.black).isEmpty())
    }
}
