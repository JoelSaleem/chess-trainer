package com.joelsaleem.chesstrainer.training

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The database half of the saved-games suite — needs a Context, so it runs on a device. Method names
 * are camelCase: D8 forbids spaces in DEX class names below minSdk 35, and a backtick name with
 * spaces generates lambda classes that break dexing.
 */
@RunWith(AndroidJUnit4::class)
class GameHistoryStoreTest {
    // name = null gives an in-memory database, torn down with the connection.
    private fun store(): GameHistory =
        GameHistory(ApplicationProvider.getApplicationContext<Context>(), name = null)

    private fun game(notation: String = "e4 e5 Nf3 Nc6", playedAt: Instant = Instant.now()): SavedGame =
        SavedGame(
            notation = notation, result = "win", white = "", black = "Club (1800)",
            yourColor = "white", source = "play", playedAt = playedAt,
        )

    /** "A game goes in and comes back out" */
    @Test
    fun roundTrips() {
        val history = store()
        history.insert(game())
        val found = history.all()
        assertEquals(1, found.size)
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6"), found[0].moves)
        assertEquals(4, found[0].moveCount)
    }

    /** "Games come back newest first" */
    @Test
    fun sortsByDate() {
        val history = store()
        history.insert(game(notation = "e4", playedAt = Instant.ofEpochSecond(1_000)))
        history.insert(game(notation = "d4", playedAt = Instant.ofEpochSecond(2_000)))
        val found = history.all()
        assertEquals(listOf("d4", "e4"), found.map { it.notation })
    }
}
