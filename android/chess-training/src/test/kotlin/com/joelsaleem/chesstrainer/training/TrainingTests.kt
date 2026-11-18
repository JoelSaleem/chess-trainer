package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.Position
import com.joelsaleem.chesstrainer.core.Square
import com.joelsaleem.chesstrainer.core.make
import com.joelsaleem.chesstrainer.core.move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureTests {
    @Test
    fun `Open and half-open files are identified`() {
        // White has no d-pawn, Black has no e-pawn; the c-file is empty of both.
        val position = Position.fromFen("r3k2r/pp1p1ppp/8/8/8/8/PP2PPPP/R3K2R w KQkq - 0 1")!!
        val features = PositionFeatures(position)

        val byName = features.files.associateBy { it.name }
        assertEquals(true, byName["c"]?.isOpen)
        assertEquals(PieceColor.white, byName["d"]?.halfOpenFor)
        assertEquals(false, byName["a"]?.isOpen)
    }

    @Test
    fun `A rook on an open file is recognised`() {
        val position = Position.fromFen("4k3/pp4pp/8/8/8/8/PP4PP/3RK3 w - - 0 1")!!
        val features = PositionFeatures(position)
        val rook = features.heavyPieces.first { it.square == Square.fromName("d1")!! }
        assertTrue(rook.onOpenFile)
        assertFalse(rook.onSeventhRank)
    }

    @Test
    fun `An outpost needs a defender and no pawn that can chase it`() {
        // Knight on e5, defended by the d4 pawn. Black has no d- or f-pawn left
        // to drive it away, so it is a genuine outpost.
        val secure = Position.fromFen("4k3/pp4pp/8/4N3/3P4/8/PP4PP/4K3 w - - 0 1")!!
        assertEquals(true, PositionFeatures(secure).knights.firstOrNull()?.isOutpost)

        // Same knight, but now a black f-pawn can play f6 and evict it.
        val contested = Position.fromFen("4k3/pp3ppp/8/4N3/3P4/8/PP4PP/4K3 w - - 0 1")!!
        assertEquals(false, PositionFeatures(contested).knights.firstOrNull()?.isOutpost)

        // Undefended knight is not an outpost however deep it sits.
        val loose = Position.fromFen("4k3/pp4pp/8/4N3/8/8/PP4PP/4K3 w - - 0 1")!!
        assertEquals(false, PositionFeatures(loose).knights.firstOrNull()?.isOutpost)
    }

    @Test
    fun `Pawn structure faults are found`() {
        // White a-pawn is isolated; the White d-pawn on d5 is passed.
        val position = Position.fromFen("4k3/1p4pp/8/3P4/8/8/P5PP/4K3 w - - 0 1")!!
        val structure = PositionFeatures(position).structure[PieceColor.white]!!
        assertTrue(structure.isolated.contains(Square.fromName("a2")!!))
        assertTrue(structure.passed.contains(Square.fromName("d5")!!))
    }

    @Test
    fun `Mobility is measured for both sides`() {
        val position = Position.fromFen(Position.startFEN)!!
        val features = PositionFeatures(position)
        assertEquals(20, features.mobility[PieceColor.white])
        assertEquals(20, features.mobility[PieceColor.black])
    }
}

class MoveDescriptionTests {
    private fun describe(fen: String, san: String): List<String> {
        val position = Position.fromFen(fen)!!
        val move = position.move(san)!!
        val resulting = position.copy()
        resulting.make(move)
        return MoveDescription.clauses(
            before = PositionFeatures(position),
            after = PositionFeatures(resulting),
            move = move,
            position = position,
            resulting = resulting,
        )
    }

    @Test
    fun `Checkmate is described on its own`() {
        val clauses = describe("6k1/5ppp/8/8/8/8/8/R3K3 w - - 0 1", "Ra8#")
        assertEquals(listOf("delivers checkmate"), clauses)
    }

    @Test
    fun `Taking the open file is named`() {
        val clauses = describe("4k3/pp4pp/8/8/8/8/PP4PP/R3K3 w - - 0 1", "Rd1")
        assertTrue(clauses.any { it.contains("open d-file") })
    }

    @Test
    fun `Winning material is distinguished from an even trade`() {
        val clauses = describe("4k3/8/8/8/3q4/8/8/3RK3 w - - 0 1", "Rxd4")
        assertTrue(clauses.any { it.contains("wins material") })
    }

    @Test
    fun `Compact notation is expanded into a readable move`() {
        val position = Position.fromFen("3kq3/pQ6/8/3p4/3P2p1/2P5/PP4PP/4rNK1 b - - 2 37")!!
        val move = position.move("Rxf1+")!!
        val resulting = position.copy()
        resulting.make(move)
        val sentence = MoveDescription.detailedSentence(
            san = "Rxf1+", move = move, position = position, resulting = resulting,
        )!!

        assertTrue(sentence.contains("rook from e1 to f1"))
        assertTrue(sentence.contains("capturing the knight"))
        assertTrue(sentence.contains("giving check"))
    }

    @Test
    fun `A summary reads the position`() {
        val position = Position.fromFen("4k3/pp4pp/8/4N3/3P4/8/PP4PP/3RK3 w - - 0 1")!!
        val points = MoveDescription.summary(PositionFeatures(position))
        assertTrue(points.any { it.contains("secure knight on e5") })
        assertTrue(points.any { it.contains("Material") })
    }
}
