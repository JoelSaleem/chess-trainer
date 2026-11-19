package com.joelsaleem.chesstrainer.training

import kotlinx.serialization.Serializable
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.max

/**
 * The parts of the app a subscription unlocks.
 *
 * Playing — against the engine or a person — is not on this list and never will be. What is sold is
 * the training: the puzzle library, the judgement exercises, the drills and the run.
 *
 * ponytail: the StoreKit purchase flow itself (iOS SubscriptionStore) is app-layer and out of v1 —
 * what is ported here is the free-tier accounting the trainer owns, which the UI reads to decide
 * when to offer the (as-yet-unwired) upgrade.
 */
@Serializable
enum class TrainingActivity {
    tactics, rush, positional, endgame, guessTheElo;

    /**
     * How many of these a free account gets each day. Five of each, everywhere — enough to feel the
     * loop and watch the rating move. Counted on the attempt, never on opening the screen.
     */
    val dailyFreeLimit: Int get() = 5
}

/**
 * What a free account has used today, against a local training day that starts at 09:00 — a calendar
 * boundary rather than a rolling 24 hours, so it stays at 09:00 across a time-zone or DST change.
 */
@Serializable
class DailyUsage(
    var day: Instant = Clock.System.now(),
    var counts: Map<TrainingActivity, Int> = emptyMap(),
    var tacticsSkips: Int = 0,
) {
    fun used(activity: TrainingActivity, now: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): Int =
        if (periodStart(day, zone) == periodStart(now, zone)) (counts[activity] ?: 0) else 0

    fun remaining(activity: TrainingActivity, now: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): Int =
        max(0, activity.dailyFreeLimit - used(activity, now, zone))

    fun remainingTacticsSkips(now: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): Int {
        val used = if (periodStart(day, zone) == periodStart(now, zone)) tacticsSkips else 0
        return max(0, freeTacticsSkips - used)
    }

    fun record(activity: TrainingActivity, now: Instant, zone: TimeZone = TimeZone.currentSystemDefault()) {
        resetIfNeeded(now, zone)
        counts = counts + (activity to (counts[activity] ?: 0) + 1)
    }

    fun recordTacticsSkip(now: Instant, zone: TimeZone = TimeZone.currentSystemDefault()) {
        resetIfNeeded(now, zone)
        tacticsSkips += 1
    }

    private fun resetIfNeeded(now: Instant, zone: TimeZone) {
        if (periodStart(day, zone) == periodStart(now, zone)) return
        day = now
        counts = emptyMap()
        tacticsSkips = 0
    }

    companion object {
        const val resetHour = 9
        const val freeTacticsSkips = 2

        /** The next 09:00 boundary in the supplied zone. */
        fun nextReset(after: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): Instant =
            periodStart(after, zone).plus(1, DateTimeUnit.DAY, zone)

        /** The 09:00 boundary at or before [instant], in [zone]. */
        private fun periodStart(instant: Instant, zone: TimeZone): Instant {
            val local = instant.toLocalDateTime(zone)
            val d = local.date
            val todayReset = LocalDateTime(d.year, d.monthNumber, d.dayOfMonth, resetHour, 0)
            val resetDate = if (local >= todayReset) d else d.minus(1, DateTimeUnit.DAY)
            return LocalDateTime(resetDate.year, resetDate.monthNumber, resetDate.dayOfMonth, resetHour, 0).toInstant(zone)
        }
    }
}
