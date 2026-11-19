package com.joelsaleem.chesstrainer.training

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.datetime.Instant

/**
 * The store the saved games live in — a SQLite table of its own, kept apart from the progress JSON
 * so a few hundred games of notation are not re-encoded every time a puzzle is solved. Nothing is
 * trimmed: a game is a few hundred bytes, so ten thousand is a couple of megabytes.
 *
 * ponytail: the platform SQLite API (SQLiteOpenHelper) rather than Room. The design named Room, but
 * Room needs its KSP annotation processor, which is not verified against AGP 9.5.1's built-in Kotlin,
 * and this is one table with two queries (insert, newest-first). Swapping to Room later is local to
 * this file — the [SavedGame] value and the insert/all surface stay the same.
 */
class GameHistory(context: Context, name: String? = DATABASE_NAME) :
    SQLiteOpenHelper(context, name, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id TEXT PRIMARY KEY NOT NULL,
                playedAt INTEGER NOT NULL,
                startFEN TEXT NOT NULL,
                notation TEXT NOT NULL,
                result TEXT NOT NULL,
                white TEXT NOT NULL,
                black TEXT NOT NULL,
                yourColor TEXT,
                opponentElo INTEGER,
                accuracy INTEGER,
                source TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 only so far; a real migration lands with the first schema change.
    }

    /** Insert or replace a game — the id is its key, as for the watch marks and favourites it shares. */
    fun insert(game: SavedGame) {
        writableDatabase.insertWithOnConflict(TABLE, null, values(game), SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Every game, newest first. */
    fun all(): List<SavedGame> {
        readableDatabase.query(TABLE, null, null, null, null, null, "playedAt DESC").use { cursor ->
            val games = ArrayList<SavedGame>(cursor.count)
            while (cursor.moveToNext()) games.add(cursor.toGame())
            return games
        }
    }

    private fun values(game: SavedGame) = ContentValues().apply {
        put("id", game.id)
        put("playedAt", game.playedAt.toEpochMilliseconds())
        put("startFEN", game.startFEN)
        put("notation", game.notation)
        put("result", game.result)
        put("white", game.white)
        put("black", game.black)
        put("yourColor", game.yourColor)
        put("opponentElo", game.opponentElo)
        put("accuracy", game.accuracy)
        put("source", game.source)
    }

    private fun Cursor.toGame() = SavedGame(
        id = getString(getColumnIndexOrThrow("id")),
        playedAt = Instant.fromEpochMilliseconds(getLong(getColumnIndexOrThrow("playedAt"))),
        startFEN = getString(getColumnIndexOrThrow("startFEN")),
        notation = getString(getColumnIndexOrThrow("notation")),
        result = getString(getColumnIndexOrThrow("result")),
        white = getString(getColumnIndexOrThrow("white")),
        black = getString(getColumnIndexOrThrow("black")),
        yourColor = stringOrNull("yourColor"),
        opponentElo = intOrNull("opponentElo"),
        accuracy = intOrNull("accuracy"),
        source = getString(getColumnIndexOrThrow("source")),
    )

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.intOrNull(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    companion object {
        private const val DATABASE_NAME = "game-history.db"
        private const val TABLE = "saved_games"
        private const val VERSION = 1
    }
}
