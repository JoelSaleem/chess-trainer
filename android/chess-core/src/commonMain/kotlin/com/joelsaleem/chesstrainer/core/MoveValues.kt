package com.joelsaleem.chesstrainer.core

/** What every legal move in a position is worth. Lives here so the board can draw it without the engine. */
class MoveValues(
    /** Score after each move, in centipawns from the mover's point of view. */
    val byMove: Map<String, Int>,
    /** The best score available, for measuring everything else against. */
    val best: Int,
) {
    /** Centipawns given up by playing this move instead of the best one. */
    fun loss(uci: String): Int? = byMove[uci]?.let { maxOf(0, best - it) }

    fun score(uci: String): Int? = byMove[uci]
}
