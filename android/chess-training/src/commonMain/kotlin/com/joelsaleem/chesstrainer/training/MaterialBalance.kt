package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.PieceKind
import com.joelsaleem.chesstrainer.core.Position
import com.joelsaleem.chesstrainer.core.Square

/**
 * The material story of a position: what has been taken, and who is ahead.
 *
 * Counted against the standard starting set, not the position the exercise began
 * in — the two rows beside the board are for reading the whole game ("Black is a
 * rook up but White has three pawns for it"), not the plies since the puzzle loaded.
 */
class MaterialBalance(position: Position) {
    private val counts: Map<PieceColor, MutableMap<PieceKind, Int>> =
        mapOf(PieceColor.white to mutableMapOf(), PieceColor.black to mutableMapOf())

    init {
        for (index in 0 until 64) {
            val piece = position[Square(index)] ?: continue
            val forColor = counts.getValue(piece.color)
            forColor[piece.kind] = (forColor[piece.kind] ?: 0) + 1
        }
    }

    /**
     * The pieces [color] has captured from the opponent, heaviest first.
     * A surplus from under-promotion or a second queen is clamped away rather
     * than reported as a negative capture.
     */
    fun capturedBy(color: PieceColor): List<PieceKind> {
        val remaining = counts.getValue(color.opponent)
        return fullSet.flatMap { (kind, count) ->
            List(maxOf(0, count - (remaining[kind] ?: 0))) { kind }
        }
    }

    /**
     * How many points [color] is ahead by, counting what is on the board. Zero
     * when level or behind, so a row shows a lead without showing a deficit the
     * other row already states.
     */
    fun lead(color: PieceColor): Int = maxOf(0, total(color) - total(color.opponent))

    private fun total(color: PieceColor): Int =
        counts.getValue(color).entries.sumOf { (kind, n) -> n * (points[kind] ?: 0) }

    override fun equals(other: Any?): Boolean = other is MaterialBalance && other.counts == counts

    override fun hashCode(): Int = counts.hashCode()

    companion object {
        // Classic teaching values: bishop == knight (unlike PieceKind.value), since
        // nobody reads a material lead as "+0.25".
        private val points: Map<PieceKind, Int> = mapOf(
            PieceKind.pawn to 1, PieceKind.knight to 3, PieceKind.bishop to 3,
            PieceKind.rook to 5, PieceKind.queen to 9, PieceKind.king to 0,
        )

        // Heaviest first, so the piece that decides the balance leads the row.
        private val fullSet: List<Pair<PieceKind, Int>> = listOf(
            PieceKind.queen to 1, PieceKind.rook to 2, PieceKind.bishop to 2,
            PieceKind.knight to 2, PieceKind.pawn to 8,
        )
    }
}
