package com.joelsaleem.chesstrainer.training

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class EloGuessTests {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `A guess is scored against the average of the two players`() {
        val guess = EloGuess(guess = 1600, actual = 1550)
        assertEquals(50, guess.error)
        assertTrue(guess.isHigh)
        assertEquals(EloGuess.Verdict.spot, guess.verdict)
        assertEquals(90, guess.points)
    }

    @Test
    fun `White and black guesses are scored independently`() {
        val guess = EloPairGuess(whiteGuess = 1700, blackGuess = 1400, whiteActual = 1600, blackActual = 1600)

        assertEquals(100, guess.white.error)
        assertEquals(200, guess.black.error)
        assertEquals(150, guess.averageError)
        assertEquals(EloGuess.Verdict.fair, guess.verdict)
        assertEquals(70, guess.points)

        val record = EloGuessRecord(
            gameID = "pair", whiteGuess = 1700, whiteActual = 1600, blackGuess = 1400, blackActual = 1600,
        )
        assertEquals(150, record.error)
        assertEquals(-50, record.bias)
    }

    @Test
    fun `Verdicts widen in bands, not by a hair`() {
        assertEquals(EloGuess.Verdict.spot, EloGuess(1500, 1500).verdict)
        assertEquals(EloGuess.Verdict.close, EloGuess(1600, 1500).verdict)
        assertEquals(EloGuess.Verdict.fair, EloGuess(1750, 1500).verdict)
        assertEquals(EloGuess.Verdict.off, EloGuess(2000, 1500).verdict)
        // Five hundred out is worth nothing, and nothing is the floor.
        assertEquals(0, EloGuess(2200, 1500).points)
    }

    @Test
    fun `Statistics report the miss and the direction of it`() {
        val stats = EloGuessStats(
            listOf(
                EloGuessRecord(gameID = "a", guess = 1700, actual = 1500),
                EloGuessRecord(gameID = "b", guess = 1600, actual = 1500),
                EloGuessRecord(gameID = "c", guess = 1500, actual = 1500),
            ),
        )
        assertEquals(3, stats.judged)
        assertEquals(100, stats.averageError)
        assertEquals(0, stats.bestError)
        // Consistently high, which is the useful half of the report.
        assertEquals(100, stats.bias)
    }

    @Test
    fun `A judged game is remembered`() {
        val progress = TrainingProgress()
        val verdict = progress.record(guess = 1450, game = "gabc", actual = 1500)
        assertEquals(50, verdict.error)
        assertEquals(1, progress.eloGuesses.size)
        assertEquals(50, progress.eloGuessStats.averageError)
    }

    @Test
    fun `Progress written by an older build still loads`() {
        // The GuessTheElo fields are missing, as in a save written before the mode existed; the
        // ratings and streaks must survive. The iOS `ratings` flat-array migration is dropped
        // (Android has no legacy saves), so this uses the Android pools format.
        val text = """
            {"pools":{"training.tactics":{"rating":1683,"games":5},"training.positional":{"rating":1420,"games":0},"training.endgame":{"rating":1310,"games":0}},
             "cards":{},"themes":{},"history":[],"currentStreak":4,"bestStreak":9}
        """.trimIndent()
        val progress = json.decodeFromString<TrainingProgress>(text)

        assertEquals(1683, progress.rating(TrainingMode.tactics))
        assertEquals(4, progress.currentStreak)
        assertEquals(9, progress.bestStreak)
        assertTrue(progress.eloGuesses.isEmpty())
    }
}
