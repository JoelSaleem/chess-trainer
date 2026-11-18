package com.joelsaleem.chesstrainer.training

import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * A timed run: solve as many puzzles as you can before the clock runs out.
 *
 * The point is different from practice. Practice is about understanding a
 * position; a run is about *recognition* — seeing a pattern before you have
 * time to reason your way to it. That is why the difficulty ramps rather than
 * tracking your rating: the early puzzles should be almost free, so the clock
 * is the opponent rather than the position.
 */
data class RushSettings(
    /** Seconds. */
    var duration: Double = 300.0,
    var target: Int = 100,
) {
    companion object {
        val choices: List<Double> = listOf(180.0, 300.0, 600.0)

        fun label(duration: Double): String = "${duration.toInt() / 60} min"
    }
}

/** One puzzle in a run, and how hard it was. */
data class RushAttempt(val rating: Int, val solved: Boolean)

class RushRun(
    val settings: RushSettings,
    val startedAt: Instant = Instant.now(),
) {
    /**
     * Every puzzle the run put up, with its rating. Kept because a run is
     * rated the way a session of tactics is — one Elo step per puzzle against
     * that puzzle's own rating — and the rating is only known while the
     * puzzle is on screen.
     */
    var attempts: List<RushAttempt> = emptyList()
        private set
    var solved = 0
        private set
    var missed = 0
        private set
    var streak = 0
        private set
    var bestStreak = 0
        private set
    var attempted = 0
        private set

    val isOver: Boolean get() = missed >= allowedMisses || solved >= settings.target
    val endedByMisses: Boolean get() = missed >= allowedMisses
    val completedTarget: Boolean get() = solved >= settings.target

    /** Seconds left on the clock. */
    fun remaining(now: Instant = Instant.now()): Double =
        max(0.0, settings.duration - Duration.between(startedAt, now).toMillis() / 1000.0)

    fun hasTimeLeft(now: Instant = Instant.now()): Boolean = remaining(now) > 0

    fun record(wasSolved: Boolean, rating: Int = 1200) {
        attempts = attempts + RushAttempt(rating, wasSolved)
        attempted += 1
        if (wasSolved) {
            solved += 1
            streak += 1
            bestStreak = max(bestStreak, streak)
        } else {
            missed += 1
            streak = 0
        }
    }

    /**
     * Difficulty for the next puzzle in the run.
     *
     * Starts well below your practice rating and climbs past it, so a run
     * opens with recognition you already own and ends somewhere you have to
     * reach for.
     */
    fun targetRating(practiceRating: Int): Int {
        val progress = min(attempted, 60).toDouble() / 60
        val floor = practiceRating.toDouble() - 500
        val ceiling = practiceRating.toDouble() + 350
        return (floor + (ceiling - floor) * progress).toInt()
    }

    companion object {
        /** Three misses ends a run early. Without a penalty the fastest strategy is
         * to guess, which trains the opposite of what this is for. */
        const val allowedMisses = 3
    }
}

// ponytail: plain data class; the @Serializable persistence representation (Instant needs a
// serializer) is deferred to M3.4 when progress/records get wired to storage.
data class RushRecord(
    val solved: Int,
    val bestStreak: Int,
    /** Seconds. */
    val duration: Double,
    val achievedAt: Instant,
)
