package com.joelsaleem.chesstrainer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.Position
import com.joelsaleem.chesstrainer.core.Square
import com.joelsaleem.chesstrainer.core.legalMoves
import com.joelsaleem.chesstrainer.core.make
import com.joelsaleem.chesstrainer.training.Puzzle
import kotlinx.coroutines.delay

/**
 * The tactics solving flow, as M4 needs it: load a puzzle, validate each move against the solution,
 * auto-play the opponent's reply, and finish. Hints, the move-value overlay, sound and the rich
 * completion narration are M5+ and left out here.
 *
 * Position is a reference type, so each move reassigns a fresh copy to trigger recomposition.
 */
class TacticsModel {
    var puzzle by mutableStateOf<Puzzle?>(null)
        private set
    var position by mutableStateOf(Position())
        private set
    var legalDestinations by mutableStateOf<Map<Square, List<Square>>>(emptyMap())
        private set
    var lastMove by mutableStateOf<Pair<Square, Square>?>(null)
        private set
    var isFinished by mutableStateOf(false)
        private set
    var isReplying by mutableStateOf(false)
        private set
    var feedback by mutableStateOf<String?>(null)
        private set

    private var step = 0
    private var mistakes = 0

    /** Called once when a puzzle ends, with the rated outcome — the screen records it into progress. */
    var onComplete: ((solved: Boolean, usedHint: Boolean) -> Unit)? = null

    val orientation: PieceColor get() = puzzle?.sideToMove ?: PieceColor.white

    fun load(next: Puzzle) {
        val start = Position.fromFen(next.fen) ?: return
        puzzle = next
        position = start
        step = 0
        mistakes = 0
        isFinished = false
        isReplying = false
        feedback = null
        lastMove = null
        refreshDestinations()
    }

    /** Suspends so the opponent's reply is seen landing a beat after your move, not in the same frame. */
    suspend fun play(from: Square, to: Square) {
        val current = puzzle ?: return
        if (isFinished || step >= current.solution.size) return

        val expected = current.solution[step]
        if ("$from$to" != expected.take(4)) {
            mistakes += 1
            feedback = "Not the move — look at the most forcing options first."
            return
        }

        apply(expected)
        step += 1
        if (step >= current.solution.size) return finish(solved = true)

        isReplying = true
        legalDestinations = emptyMap()
        delay(420)
        isReplying = false

        apply(current.solution[step])
        step += 1
        if (step >= current.solution.size) return finish(solved = true)

        refreshDestinations()
        feedback = "Good — keep going."
    }

    fun retry() {
        puzzle?.let { load(it) }
    }

    private fun apply(uci: String) {
        val next = position.copy()
        val made = next.make(uci) ?: return
        position = next
        lastMove = made.from to made.to
    }

    private fun finish(solved: Boolean) {
        val current = puzzle ?: return
        isFinished = true
        legalDestinations = emptyMap()
        // Play out the rest so the whole idea is on the board.
        while (step < current.solution.size) {
            apply(current.solution[step])
            step += 1
        }
        val counted = solved && mistakes == 0
        feedback = when {
            counted -> "Solved"
            solved -> "Solved, but not first time"
            else -> "Solution"
        }
        onComplete?.invoke(counted, false)
    }

    private fun refreshDestinations() {
        val map = HashMap<Square, MutableList<Square>>()
        for (move in position.legalMoves()) map.getOrPut(move.from) { mutableListOf() }.add(move.to)
        legalDestinations = map
    }
}
