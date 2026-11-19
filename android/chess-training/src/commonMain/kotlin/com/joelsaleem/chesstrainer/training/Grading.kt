package com.joelsaleem.chesstrainer.training

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** How good a move was. */
enum class MoveGrade {
    best, excellent, good, inaccuracy, mistake, blunder;

    val label: String
        get() = when (this) {
            best -> L.t("grade.best", "Best move")
            excellent -> L.t("grade.excellent", "Excellent")
            good -> L.t("grade.good", "Good")
            inaccuracy -> L.t("grade.inaccuracy", "Inaccuracy")
            mistake -> L.t("grade.mistake", "Mistake")
            blunder -> L.t("grade.blunder", "Blunder")
        }

    val isMistake: Boolean get() = this == inaccuracy || this == mistake || this == blunder

    companion object {
        /** Ceilings on win probability given up, best-first. */
        val thresholds: List<Pair<MoveGrade, Double>> = listOf(
            best to 0.005, excellent to 0.02, good to 0.05,
            inaccuracy to 0.10, mistake to 0.20, blunder to Double.POSITIVE_INFINITY,
        )
    }
}

data class MoveAssessment(
    val grade: MoveGrade,
    /** Win probability given up, 0..1. */
    val winProbabilityLost: Double,
    /** Centipawns given up, for readers who think in pawns. */
    val centipawnsLost: Int,
)

object Coach {
    /**
     * Grade a move by how much winning chance it threw away, not by raw centipawns.
     * Both scores must be from the mover's point of view.
     */
    fun assess(before: Int, after: Int, wasEngineChoice: Boolean): MoveAssessment {
        val lost = max(0.0, winProbability(before) - winProbability(after))
        val grade = if (wasEngineChoice) MoveGrade.best else MoveGrade.thresholds.first { lost <= it.second }.first
        return MoveAssessment(grade, lost, max(0, before - after))
    }

    /** Lichess' logistic fit from centipawns to expected score. */
    fun winProbability(centipawns: Int): Double {
        val clamped = max(-1000, min(1000, centipawns)).toDouble()
        return 1 / (1 + exp(-0.00368208 * clamped))
    }

    /** Game accuracy, capped per move so one catastrophe doesn't swamp a sound game. */
    fun accuracy(centipawnLosses: List<Int>): Int {
        if (centipawnLosses.isEmpty()) return 100
        val average = centipawnLosses.sumOf { min(300, it) } / centipawnLosses.size
        return max(0, 100 - average / 3)
    }
}
