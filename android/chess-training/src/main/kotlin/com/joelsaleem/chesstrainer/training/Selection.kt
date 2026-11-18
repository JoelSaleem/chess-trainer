package com.joelsaleem.chesstrainer.training

import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Why a particular item was chosen. Shown to the user, because a trainer that silently decides what
 * you practise is indistinguishable from a shuffle — one you can see reasoning is one you can argue with.
 */
sealed class SelectionReason {
    /** Due for spaced repetition. */
    object Review : SelectionReason()

    data class Weakness(val motif: String, val accuracy: Double, val seen: Int) : SelectionReason()

    /** Simply near your rating. */
    object Level : SelectionReason()

    val explanation: String?
        get() = when (this) {
            is Review -> L.t("selection.review", "Review — you have seen this one before.")
            is Weakness -> L.t(
                "selection.chosenForYou", "Chosen for you: %s — %d%% of %d so far.",
                Themes.readable(motif).lowercase(), (accuracy * 100).roundToInt(), seen,
            )
            is Level -> null
        }
}

data class Selection<Item>(val item: Item, val reason: SelectionReason)

/**
 * Picks the next item to train on.
 *
 * ponytail: [nextRushPuzzle] is Progress-free (ported in M3.1); the Progress-coupled selectors were
 * added in M3.3. In Swift nextRushPuzzle is an `extension ItemSelector` in Rush.swift — Kotlin can't
 * split an object across files, so it lives here with the other selectors.
 */
object ItemSelector {
    /** How often a puzzle is chosen to attack a weakness rather than by rating. */
    private const val targetShare = 0.6

    /**
     * Choose the next tactics puzzle. In order of precedence: due reviews first, then (most of the
     * time) a motif you are measurably worse at than your own average, then simply near your rating.
     *
     * Targeting deliberately does not apply every time — drilling only weak motifs stops measuring
     * the rest, and the rating would then drift on stale evidence.
     */
    fun nextPuzzle(
        from: List<Puzzle>,
        progress: TrainingProgress,
        now: Instant = Instant.now(),
        random: (Int) -> Int = { Random.nextInt(it) },
        chance: () -> Double = { Random.nextDouble() },
    ): Selection<Puzzle>? {
        if (from.isEmpty()) return null

        val due = progress.dueItemIDs(now).toSet()
        val dueOnes = from.filter { it.id in due }
        if (dueOnes.isNotEmpty()) return Selection(dueOnes[random(dueOnes.size)], SelectionReason.Review)

        val target = progress.rating(TrainingMode.tactics) + 60
        fun inRange(window: Int): List<Puzzle> =
            from.filter { abs(it.rating - target) <= window && !progress.hasSeen(it.id) }

        val targets = progress.trainingTargets()
        if (targets.isNotEmpty() && chance() < targetShare) {
            val wanted = targets.map { it.name }.toSet()
            for (window in listOf(250, 450, 800)) {
                val pool = inRange(window).filter { puzzle -> puzzle.themes.any { it in wanted } }
                if (pool.isNotEmpty()) {
                    val chosen = pool[random(pool.size)]
                    val motif = targets.firstOrNull { chosen.themes.contains(it.name) }
                    if (motif != null) {
                        return Selection(
                            chosen,
                            SelectionReason.Weakness(motif.name, motif.record.accuracy, motif.record.seen),
                        )
                    }
                }
            }
        }

        for (window in listOf(150, 300, 500, 1200)) {
            val pool = inRange(window)
            if (pool.isNotEmpty()) return Selection(pool[random(pool.size)], SelectionReason.Level)
        }
        return Selection(from[random(from.size)], SelectionReason.Level)
    }

    fun nextExercise(
        from: List<PositionalExercise>,
        progress: TrainingProgress,
        random: (Int) -> Int = { Random.nextInt(it) },
    ): PositionalExercise? {
        if (from.isEmpty()) return null
        val rating = progress.rating(TrainingMode.positional)
        for (window in listOf(200, 400, 700, 2000)) {
            val pool = from.filter { abs(it.rating - rating) <= window && !progress.hasSeen(it.id) }
            if (pool.isNotEmpty()) return pool[random(pool.size)]
        }
        return from[random(from.size)]
    }

    /**
     * Endgame drills are few, so rather than filtering by a window we take the closest handful by
     * rating and pick among them — otherwise the same drill would come up over and over.
     */
    fun nextDrill(
        from: List<EndgameDrill>,
        progress: TrainingProgress,
        random: (Int) -> Int = { Random.nextInt(it) },
    ): EndgameDrill? {
        if (from.isEmpty()) return null
        val rating = progress.rating(TrainingMode.endgame)
        val closest = from.sortedBy { abs(it.rating - rating) }
        val pool = closest.take(5)
        return pool[random(pool.size)]
    }

    /**
     * Pick the next puzzle of a run: nearest the target difficulty, not yet seen in this run, and
     * never repeated within it.
     */
    fun nextRushPuzzle(
        from: List<Puzzle>,
        targetRating: Int,
        excluding: Set<String>,
        random: (Int) -> Int = { Random.nextInt(it) },
    ): Puzzle? {
        if (from.isEmpty()) return null
        for (window in listOf(120, 250, 500, 1500)) {
            val pool = from.filter { abs(it.rating - targetRating) <= window && it.id !in excluding }
            if (pool.isNotEmpty()) return pool[random(pool.size)]
        }
        return from.firstOrNull { it.id !in excluding }
    }
}
