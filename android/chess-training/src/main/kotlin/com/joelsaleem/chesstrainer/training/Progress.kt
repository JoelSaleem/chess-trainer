@file:UseSerializers(InstantIso8601Serializer::class)

package com.joelsaleem.chesstrainer.training

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Serializable
enum class TrainingMode { tactics, positional, endgame }

/**
 * A rating and what it was earned on. The identifier is what goes on disk, so cases can be renamed
 * without anyone losing a rating.
 *
 * ponytail: the online(minutes) pool is out of v1 (online play is out of scope) and not ported.
 */
sealed class RatedPool {
    abstract val id: String

    data class Training(val mode: TrainingMode) : RatedPool() {
        override val id: String get() = "training.${mode.name}"
    }

    data class Rush(val minutes: Int) : RatedPool() {
        override val id: String get() = "rush.$minutes"
    }

    object Engine : RatedPool() {
        override val id: String get() = "engine"
    }

    companion object {
        fun training(mode: TrainingMode): RatedPool = Training(mode)
        fun rush(minutes: Int): RatedPool = Rush(minutes)
        val engine: RatedPool get() = Engine
    }
}

/**
 * A rating and how many results shaped it. The count sets how hard the next result moves the number,
 * so a settled pool does not steady a fresh one.
 */
@Serializable
data class PoolRating(val rating: Int = 1200, val games: Int = 0)

/** One scheduled item in the spaced-repetition queue. */
@Serializable
data class ReviewCard(
    val ease: Double = 2.5,
    val intervalDays: Int = 0,
    val due: Instant,
    val repetitions: Int = 0,
    val lapses: Int = 0,
    val lastSolved: Boolean = false,
) {
    constructor(now: Instant) : this(due = now)
}

@Serializable
data class ThemeRecord(val seen: Int = 0, val solved: Int = 0) {
    val accuracy: Double get() = if (seen == 0) 0.0 else solved.toDouble() / seen

    /**
     * Upper end of a Wilson interval for the accuracy. Ranking motifs by raw accuracy makes small
     * samples shout — one miss reads as "0%, your worst weakness". The upper bound asks the more
     * useful question (how good could you plausibly be?), so a motif only rises once there is
     * evidence you are truly worse.
     */
    val ceiling: Double
        get() {
            if (seen <= 0) return 1.0
            val z = 1.0
            val p = accuracy
            val n = seen.toDouble()
            val denominator = 1 + z * z / n
            val centre = p + z * z / (2 * n)
            val margin = z * sqrt((p * (1 - p) + z * z / (4 * n)) / n)
            return min(1.0, (centre + margin) / denominator)
        }
}

/** A motif worth aiming puzzles at, paired with its record. (Kotlin has no named tuple.) */
data class ThemeTarget(val name: String, val record: ThemeRecord)

@Serializable
data class AttemptRecord(
    val at: Instant,
    val mode: TrainingMode,
    val itemID: String,
    val correct: Boolean,
    val ratingAfter: Int,
)

/**
 * Everything the trainer remembers about you, as the offline training layer needs it.
 *
 * ponytail: this is the v1 offline subset. Online play (the Glicko / TimeControl / OnlineRecord /
 * MatchResult pools and methods) and the paywall free-tier (DailyUsage) are out of v1 and not
 * ported; engine-game / rush / watch recording hangs off the milestones that add those screens.
 * Every field defaults, so a save missing a field still loads (kotlinx fills the default) — the iOS
 * `ratings`/`onlineRating` flat-array migration is dropped, as Android has no legacy saves.
 */
@Serializable
class TrainingProgress(
    var pools: Map<String, PoolRating> = emptyMap(),
    var cards: Map<String, ReviewCard> = emptyMap(),
    var themes: Map<String, ThemeRecord> = emptyMap(),
    var history: List<AttemptRecord> = emptyList(),
    var eloGuesses: List<EloGuessRecord> = emptyList(),
    /** What the free tier has spent today. */
    var dailyUsage: DailyUsage = DailyUsage(),
    var appearance: Appearance = Appearance(),
    var currentStreak: Int = 0,
    var bestStreak: Int = 0,
    var lastActiveDay: Instant? = null,
) {
    // Guess the Elo

    /** Records a judged game and returns the verdict, so the caller need not score it again to show it. */
    fun record(guess: Int, game: String, actual: Int, now: Instant = Instant.now()): EloGuess {
        eloGuesses = eloGuesses + EloGuessRecord(at = now, gameID = game, guess = guess, actual = actual)
        return EloGuess(guess = guess, actual = actual)
    }

    val eloGuessStats: EloGuessStats get() = EloGuessStats(eloGuesses)

    // Free tier

    fun freeRemaining(activity: TrainingActivity, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Int =
        dailyUsage.remaining(activity, now, zone)

    fun recordFreeUse(activity: TrainingActivity, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) =
        dailyUsage.record(activity, now, zone)

    fun freeTacticsSkipsRemaining(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Int =
        dailyUsage.remainingTacticsSkips(now, zone)

    fun recordFreeTacticsSkip(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) =
        dailyUsage.recordTacticsSkip(now, zone)

    // Ratings

    fun rating(mode: TrainingMode): Int = rating(RatedPool.training(mode))

    fun rating(pool: RatedPool): Int = pools[pool.id]?.rating ?: starting(pool)

    /** Where a pool begins. A rush pool opens near your tactics rating, not at 1200. */
    private fun starting(pool: RatedPool): Int = when (pool) {
        is RatedPool.Rush -> pools[RatedPool.training(TrainingMode.tactics).id]?.rating ?: 1200
        else -> 1200
    }

    // Recording

    /**
     * Standard Elo. K shrinks as the rating settles, so early sessions find your level quickly and
     * later ones stop jumping around.
     */
    private fun updateRating(mode: TrainingMode, itemRating: Int, correct: Boolean): Int {
        val current = rating(mode)
        val played = history.count { it.mode == mode }
        val k = if (played < 20) 60.0 else if (played < 60) 32.0 else 20.0
        val expected = 1 / (1 + 10.0.pow((itemRating - current) / 400.0))
        val next = current + k * ((if (correct) 1 else 0) - expected)
        val held = pools[RatedPool.training(mode).id] ?: PoolRating()
        val updated = held.copy(rating = max(400, next.roundToInt()), games = held.games + 1)
        pools = pools + (RatedPool.training(mode).id to updated)
        return updated.rating
    }

    /**
     * SM-2, trimmed to the two outcomes a puzzle actually has. A missed puzzle returns tomorrow; a
     * solved one is pushed out by its ease factor.
     */
    private fun schedule(itemID: String, solved: Boolean, usedHint: Boolean, now: Instant) {
        var card = cards[itemID] ?: ReviewCard(now)
        card = if (solved) {
            val quality = if (usedHint) 3.0 else 5.0
            val ease = max(1.3, card.ease + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02)))
            val interval = when (card.repetitions) {
                0 -> 1
                1 -> 4
                else -> (card.intervalDays * ease).roundToInt()
            }
            card.copy(ease = ease, intervalDays = interval, repetitions = card.repetitions + 1)
        } else {
            card.copy(ease = max(1.3, card.ease - 0.2), intervalDays = 1, lapses = card.lapses + 1)
        }
        card = card.copy(due = now.plusSeconds(card.intervalDays.toLong() * 86_400), lastSolved = solved)
        cards = cards + (itemID to card)
    }

    fun record(
        mode: TrainingMode,
        itemID: String,
        itemRating: Int,
        correct: Boolean,
        themes: List<String> = emptyList(),
        usedHint: Boolean = false,
        now: Instant = Instant.now(),
    ) {
        val ratingAfter = updateRating(mode, itemRating, correct && !usedHint)
        schedule(itemID, correct, usedHint, now)

        var updated = this.themes
        for (theme in themes) {
            if (!Themes.isMotif(theme)) continue
            val rec = updated[theme] ?: ThemeRecord()
            updated = updated + (theme to rec.copy(seen = rec.seen + 1, solved = rec.solved + if (correct) 1 else 0))
        }
        this.themes = updated

        history = history + AttemptRecord(now, mode, itemID, correct, ratingAfter)
        if (history.size > 2000) history = history.drop(history.size - 2000)
        bumpStreak(now)
    }

    private fun bumpStreak(now: Instant) {
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val last = lastActiveDay?.atZone(zone)?.toLocalDate()
        if (last == null) {
            currentStreak = 1
            bestStreak = max(bestStreak, 1)
            lastActiveDay = today.atStartOfDay(zone).toInstant()
            return
        }
        if (today == last) return
        val dayGap = ChronoUnit.DAYS.between(last, today)
        currentStreak = if (dayGap == 1L) currentStreak + 1 else 1
        bestStreak = max(bestStreak, currentStreak)
        lastActiveDay = today.atStartOfDay(zone).toInstant()
    }

    // Reporting

    fun hasSeen(itemID: String): Boolean = cards.containsKey(itemID)

    /** Everything whose interval has elapsed — missed items back the next day, solved ones later. */
    fun dueItemIDs(now: Instant = Instant.now()): List<String> =
        cards.filterValues { !it.due.isAfter(now) }.keys.toList()

    /**
     * The motifs worth aiming puzzles at: those measurably below your own average. Compared against
     * your own baseline rather than a fixed number — a 60% solver and an 85% solver need different
     * thresholds.
     */
    fun trainingTargets(minimumSeen: Int = 4, margin: Double = 0.05, limit: Int = 6): List<ThemeTarget> {
        val candidates = themes.filter { it.value.seen >= minimumSeen }
        if (candidates.size < 3) return emptyList() // too early to know anything
        val solved = candidates.values.sumOf { it.solved }
        val seen = candidates.values.sumOf { it.seen }
        if (seen <= 0) return emptyList()
        val baseline = solved.toDouble() / seen
        return candidates.filter { it.value.ceiling < baseline - margin }
            .toList()
            .sortedBy { it.second.ceiling }
            .take(limit)
            .map { ThemeTarget(it.first, it.second) }
    }
}
