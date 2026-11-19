package com.joelsaleem.chesstrainer.training

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

class RushTests {
    private fun puzzle(id: String, rating: Int): Puzzle =
        Puzzle(
            id = id, fen = "8/8/8/4k3/8/8/8/R3K3 w - - 0 1", solution = listOf("a1a8"),
            themes = listOf("mate"), rating = rating, mate = true, source = null,
        )

    @Test
    fun `A fresh run has its full time`() {
        val run = RushRun(RushSettings(duration = 300.0, target = 100))
        assertTrue(run.remaining() > 299)
        assertTrue(run.hasTimeLeft())
        assertFalse(run.isOver)
    }

    @Test
    fun `The clock runs down from the start time`() {
        val started = Clock.System.now() - (120).seconds
        val run = RushRun(RushSettings(duration = 300.0), startedAt = started)
        assertTrue(run.remaining() < 181)
        assertTrue(run.remaining() > 179)
        assertTrue(run.hasTimeLeft())
    }

    @Test
    fun `Three misses ends the run, two do not`() {
        val run = RushRun(RushSettings())
        run.record(wasSolved = false)
        run.record(wasSolved = false)
        assertFalse(run.isOver)
        run.record(wasSolved = false)
        assertTrue(run.isOver)
        assertTrue(run.endedByMisses)
    }

    @Test
    fun `Streaks reset on a miss and remember their best`() {
        val run = RushRun(RushSettings())
        repeat(4) { run.record(wasSolved = true) }
        assertEquals(4, run.streak)
        run.record(wasSolved = false)
        assertEquals(0, run.streak)
        assertEquals(4, run.bestStreak)
    }

    @Test
    fun `Difficulty climbs across a run`() {
        val run = RushRun(RushSettings())
        val opening = run.targetRating(practiceRating = 1500)
        repeat(40) { run.record(wasSolved = true) }
        val later = run.targetRating(practiceRating = 1500)
        assertTrue(opening < 1100, "a run should open well below your level, got $opening")
        assertTrue(later > opening)
    }

    @Test
    fun `A puzzle is always found for a real library`() {
        // Ratings spread the way the bundled set is.
        val library = (0 until 200).map { puzzle("p$it", rating = 760 + it * 10) }
        val used = mutableSetOf<String>()
        val run = RushRun(RushSettings())

        repeat(50) {
            val target = run.targetRating(practiceRating = 1200)
            val next = requireNotNull(
                ItemSelector.nextRushPuzzle(from = library, targetRating = target, excluding = used),
            ) { "no puzzle for target rating $target" }
            assertFalse(used.contains(next.id), "a run must not repeat a puzzle")
            used.add(next.id)
            run.record(wasSolved = true)
        }
    }
}
