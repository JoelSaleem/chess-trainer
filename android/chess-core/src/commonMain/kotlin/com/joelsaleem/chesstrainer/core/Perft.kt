package com.joelsaleem.chesstrainer.core

/** Count leaf nodes at a fixed depth; the standard proof that a move generator is correct. */
fun Position.perft(depth: Int): Long {
    if (depth <= 0) return 1
    val moves = legalMoves()
    if (depth == 1) return moves.size.toLong()
    return moves.sumOf { move -> copy().also { it.makeUnchecked(move) }.perft(depth - 1) }
}

/** Per-move breakdown, for locating exactly where a count diverges. */
fun Position.perftDivide(depth: Int): List<Pair<String, Long>> =
    legalMoves()
        .map { move -> move.uci to copy().also { it.makeUnchecked(move) }.perft(depth - 1) }
        .sortedBy { it.first }
