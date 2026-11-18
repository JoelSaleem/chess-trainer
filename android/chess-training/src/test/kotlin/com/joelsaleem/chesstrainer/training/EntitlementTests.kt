package com.joelsaleem.chesstrainer.training

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class EntitlementTests {
    // A gregorian calendar in the current zone, as the Swift suite uses.
    private val zone: ZoneId = ZoneId.systemDefault()
    private val noon = Instant.ofEpochSecond(1_700_000_000)
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `A fresh account has five of everything`() {
        val progress = TrainingProgress()
        for (activity in TrainingActivity.entries) {
            assertEquals("$activity does not start at five", 5, progress.freeRemaining(activity, noon, zone))
        }
        assertEquals(2, progress.freeTacticsSkipsRemaining(noon, zone))
    }

    @Test
    fun `Five attempts, then the door`() {
        val progress = TrainingProgress()
        for (spent in 0 until 5) {
            assertEquals(5 - spent, progress.freeRemaining(TrainingActivity.tactics, noon, zone))
            progress.recordFreeUse(TrainingActivity.tactics, noon, zone)
        }
        assertEquals(0, progress.freeRemaining(TrainingActivity.tactics, noon, zone))
    }

    @Test
    fun `Tactics completion and Rush attempt have separate allowances`() {
        val progress = TrainingProgress()
        progress.recordFreeUse(TrainingActivity.tactics, noon, zone)

        assertEquals(4, progress.freeRemaining(TrainingActivity.tactics, noon, zone))
        assertEquals(5, progress.freeRemaining(TrainingActivity.rush, noon, zone))
        assertEquals(5, progress.freeRemaining(TrainingActivity.endgame, noon, zone))

        progress.recordFreeUse(TrainingActivity.rush, noon, zone)
        assertEquals(4, progress.freeRemaining(TrainingActivity.rush, noon, zone))

        // Skips have their own count and spend neither of the above.
        progress.recordFreeTacticsSkip(noon, zone)
        progress.recordFreeTacticsSkip(noon, zone)
        assertEquals(0, progress.freeTacticsSkipsRemaining(noon, zone))
        assertEquals(4, progress.freeRemaining(TrainingActivity.tactics, noon, zone))
    }

    @Test
    fun `The allowance runs out and stays out for the rest of the day`() {
        val progress = TrainingProgress()
        repeat(10) { progress.recordFreeUse(TrainingActivity.rush, noon, zone) }

        assertEquals(0, progress.freeRemaining(TrainingActivity.rush, noon, zone))
        val laterSameDay = noon.plusSeconds(6 * 3600)
        assertEquals(0, progress.freeRemaining(TrainingActivity.rush, laterSameDay, zone))
    }

    @Test
    fun `Tomorrow starts over`() {
        val progress = TrainingProgress()
        progress.recordFreeUse(TrainingActivity.tactics, noon, zone)
        val tomorrow = noon.plusSeconds(26 * 3600)

        assertEquals(5, progress.freeRemaining(TrainingActivity.tactics, tomorrow, zone))

        // And spending tomorrow does not resurrect yesterday's count.
        progress.recordFreeUse(TrainingActivity.tactics, tomorrow, zone)
        assertEquals(4, progress.freeRemaining(TrainingActivity.tactics, tomorrow, zone))
    }

    @Test
    fun `The local allowance resets at nine in the morning`() {
        val local = ZoneOffset.ofHours(2)
        val before = LocalDateTime.of(2026, 8, 21, 8, 59, 59).atZone(local).toInstant()
        val nine = LocalDateTime.of(2026, 8, 21, 9, 0).atZone(local).toInstant()

        val progress = TrainingProgress()
        progress.recordFreeUse(TrainingActivity.tactics, before, local)
        progress.recordFreeTacticsSkip(before, local)
        assertEquals(4, progress.freeRemaining(TrainingActivity.tactics, before, local))
        assertEquals(1, progress.freeTacticsSkipsRemaining(before, local))
        // Nine o'clock is a new day: the count is whole again.
        assertEquals(5, progress.freeRemaining(TrainingActivity.tactics, nine, local))
        assertEquals(2, progress.freeTacticsSkipsRemaining(nine, local))
        assertEquals(nine, DailyUsage.nextReset(before, local))

        progress.recordFreeUse(TrainingActivity.tactics, nine, local)
        assertEquals(4, progress.freeRemaining(TrainingActivity.tactics, nine, local))
        assertEquals(nine.atZone(local).plusDays(1).toInstant(), DailyUsage.nextReset(nine, local))
    }

    @Test
    fun `Progress written before the free tier existed still loads`() {
        // No dailyUsage key, as in a save from before the free tier. The ratings must survive, and
        // the free allowance defaults to full. (Android pools format; the iOS ratings flat-array
        // migration is dropped — no legacy Android saves.)
        val text = """
            {"pools":{"training.tactics":{"rating":1500,"games":0},"training.positional":{"rating":1400,"games":0},"training.endgame":{"rating":1300,"games":0}},
             "cards":{},"themes":{},"history":[],"currentStreak":2,"bestStreak":5}
        """.trimIndent()
        val progress = json.decodeFromString<TrainingProgress>(text)

        assertEquals(1500, progress.rating(TrainingMode.tactics))
        assertEquals(5, progress.freeRemaining(TrainingActivity.tactics, noon, zone))
        assertEquals(2, progress.freeTacticsSkipsRemaining(noon, zone))
    }

    @Test
    fun `Usage written before skip limits existed still decodes`() {
        // counts and tacticsSkips missing, as before skip limits existed — they default.
        val old = json.decodeFromString<DailyUsage>("""{"day":"2026-08-21T07:00:00Z"}""")
        assertEquals(2, old.remainingTacticsSkips(old.day, zone))
    }
}
