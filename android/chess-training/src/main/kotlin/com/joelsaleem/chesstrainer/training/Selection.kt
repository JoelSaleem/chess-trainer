package com.joelsaleem.chesstrainer.training

import kotlin.math.abs
import kotlin.random.Random

/**
 * Picks the next item to train on.
 *
 * ponytail: only the Progress-free selector ([nextRushPuzzle]) is ported in M3.1/M3.2.
 * M3.3 adds the spaced-repetition selectors (nextPuzzle / nextExercise / nextDrill) plus
 * SelectionReason and Selection<Item> to this object — they couple to TrainingProgress,
 * which lands in M3.3. In Swift nextRushPuzzle is an `extension ItemSelector` in Rush.swift;
 * Kotlin can't split an object across files, so it lives here with the rest of the selector.
 */
object ItemSelector {
    /**
     * Pick the next puzzle of a run: nearest the target difficulty, not yet
     * seen in this run, and never repeated within it.
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
