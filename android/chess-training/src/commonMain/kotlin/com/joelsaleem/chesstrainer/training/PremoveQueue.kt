package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.Move
import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.PieceKind
import com.joelsaleem.chesstrainer.core.Position
import com.joelsaleem.chesstrainer.core.Square
import com.joelsaleem.chesstrainer.core.isGameOver
import com.joelsaleem.chesstrainer.core.legalMoves
import com.joelsaleem.chesstrainer.core.make

/**
 * Moves given before it is your turn.
 *
 * A queue rather than a single move, because what a player has in mind while the
 * opponent thinks is usually a short plan. Each move is checked against the board
 * that actually arrives, and the first one that cannot be played takes the rest
 * with it: the moves behind it were planned on a position that never happened.
 */
class PremoveQueue {
    var moves: List<Move> = emptyList()
        private set

    /** True when the queue was thrown away because reality diverged. */
    var wasDropped: Boolean = false
        private set

    val isEmpty: Boolean get() = moves.isEmpty()
    val count: Int get() = moves.size

    /** Every square taking part in the plan, for marking on the board. */
    val squares: List<Square> get() = moves.flatMap { listOf(it.from, it.to) }

    /**
     * The position the next premove is planned against: yours to move, with
     * everything queued already played out and the opponent's pieces left as they stand.
     */
    fun planned(position: Position, side: PieceColor): Position {
        var probe = position.speculating(side)
        for (move in moves) {
            if (probe.make(move) == null) break
            probe = probe.speculating(side)
        }
        return probe
    }

    fun destinations(position: Position, side: PieceColor): Map<Square, List<Square>> {
        if (position.sideToMove == side || position.isGameOver) return emptyMap()
        val map = HashMap<Square, MutableList<Square>>()
        for (move in planned(position, side).legalMoves()) {
            map.getOrPut(move.from) { mutableListOf() }.add(move.to)
        }
        return map
    }

    /**
     * Add a move to the plan. Returns false when the piece could not make that
     * move even on the board the plan assumes.
     */
    fun queue(from: Square, to: Square, promotion: PieceKind?, position: Position, side: PieceColor): Boolean {
        if (position.sideToMove == side || position.isGameOver) return false
        val notation = Move(from = from, to = to, promotion = promotion)
        val move = planned(position, side).legalMoves().firstOrNull { it.matchesNotation(notation) }
            ?: return false
        moves = moves + move
        wasDropped = false
        return true
    }

    fun clear() {
        moves = emptyList()
        wasDropped = false
    }

    /**
     * The next move to play, or null — either because nothing is queued, or because
     * what was queued is no longer possible, in which case the whole plan is dropped.
     */
    fun next(position: Position): Move? {
        val first = moves.firstOrNull() ?: return null
        val legal = position.legalMoves().firstOrNull { it.matchesNotation(first) }
        if (legal == null) {
            moves = emptyList()
            wasDropped = true
            return null
        }
        moves = moves.drop(1)
        wasDropped = false
        return legal
    }

    override fun equals(other: Any?): Boolean =
        other is PremoveQueue && other.moves == moves && other.wasDropped == wasDropped

    override fun hashCode(): Int = 31 * moves.hashCode() + wasDropped.hashCode()
}
