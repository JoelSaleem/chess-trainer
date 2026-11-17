package com.joelsaleem.chesstrainer.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

/** An evaluation, always from the point of view of the side to move. */
sealed class EngineScore {
    data class Centipawns(val cp: Int) : EngineScore()
    data class Mate(val moves: Int) : EngineScore()

    /**
     * Flip to the given colour's point of view. UCI always reports from the side
     * to move, which is the wrong frame for anything the user reads.
     */
    fun pointOfView(sideToMove: Boolean): EngineScore =
        if (sideToMove) this else when (this) {
            is Centipawns -> Centipawns(-cp)
            is Mate -> Mate(-moves)
        }

    /** Centipawns with mate flattened to a large value, for comparing moves. */
    val comparable: Int
        get() = when (this) {
            is Centipawns -> cp
            is Mate -> if (moves > 0) 10_000 - moves else -10_000 - moves
        }

    /**
     * Probability that the side this score favours goes on to win: the logistic
     * fit Lichess uses, a far better basis for judging a move than raw centipawns.
     */
    val winProbability: Double
        get() = 1 / (1 + exp(-0.00368208 * comparable.coerceIn(-1000, 1000).toDouble()))

    val text: String
        get() = when (this) {
            is Mate -> "#$moves"
            is Centipawns -> {
                val pawns = cp / 100.0
                // Locale.ROOT so the decimal is always a dot, matching Swift's String(format:).
                (if (pawns >= 0) "+" else "−") + String.format(Locale.ROOT, "%.2f", abs(pawns))
            }
        }
}

/** One principal variation from a search. */
data class EngineLine(
    val rank: Int,           // 1-based; MultiPV order
    val depth: Int,
    val score: EngineScore,
    val moves: List<String>, // UCI
) {
    val bestMove: String? get() = moves.firstOrNull()
}

data class Analysis(
    val lines: List<EngineLine>,
    val bestMove: String?,
) {
    /** True when the position has no legal moves, so nothing was searched. */
    val isTerminal: Boolean get() = lines.isEmpty()
    val score: EngineScore? get() = lines.firstOrNull()?.score
}

/** What an engine advertises about itself. */
data class EngineCapabilities(
    /** Shown to the player, so it carries the version the app is actually running. */
    val name: String,
    /** Whether chooseMove can honour a rating; when false, callers should not offer a ladder. */
    val limitsStrength: Boolean,
)

/** Errors the engine can raise. */
sealed class EngineException(message: String) : Exception(message) {
    class NetworksMissing(val path: String) :
        EngineException("The Stockfish neural network could not be read at $path.")

    class InvalidPosition(val fen: String) :
        EngineException("The engine rejected this position: $fen")
}
