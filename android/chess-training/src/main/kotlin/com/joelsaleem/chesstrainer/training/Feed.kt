package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.Move
import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.Position
import com.joelsaleem.chesstrainer.core.Square
import com.joelsaleem.chesstrainer.core.make
import com.joelsaleem.chesstrainer.core.move
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/*
 * ponytail: FeedLink (the brasspawn:// / App-Clip / brasspawn.com address layer) is NOT ported
 * here — it couples to Invitation and the App-Clip flow, which are out of the v1 Android scope.
 * It ports whole when the Today-feed deep-linking milestone lands; its three tests (storyLinks,
 * storyLinksAtAMove, feedFolders) defer with it. `generatedAt` and story `url` are kept as the raw
 * strings the feed sends — nothing here reads them as a parsed timestamp/URL yet; parse at the call
 * site when a consumer needs one.
 */

private val feedJson = Json { ignoreUnknownKeys = true }

/**
 * The daily feed: the finished games from the day's top events, each with the
 * move where the engine says it turned.
 *
 * The moves and results are the official broadcast's and the scores are Stockfish's.
 */
@Serializable
data class FeedFile(
    val version: Int,
    val generatedAt: String,
    /** The newest few days, in full. */
    val stories: List<FeedStory>,
    /** Every day there is, newest first — the ones not in [stories] are fetched one file each. */
    val days: List<Day>? = null,
) {
    /** One day of the history, and how many stories it has. */
    @Serializable
    data class Day(val date: String, val count: Int)

    companion object {
        /** The only version this build understands. A later one is a feed this build cannot read. */
        const val supportedVersion = 1

        fun decode(data: String): FeedFile {
            val file = feedJson.decodeFromString<FeedFile>(data)
            if (file.version != supportedVersion) throw FeedError.UnsupportedVersion(file.version)
            // A story whose moves do not play is dropped rather than shown: its replay would stop
            // halfway and nobody could tell whose fault it was.
            return file.copy(stories = file.stories.filter { it.line != null })
        }
    }
}

/** One day's stories: `days/<date>.json`, for going back through the history. */
@Serializable
data class FeedDay(
    val date: String,
    val stories: List<FeedStory>,
) {
    companion object {
        fun decode(data: String): FeedDay {
            val day = feedJson.decodeFromString<FeedDay>(data)
            return day.copy(stories = day.stories.filter { it.line != null })
        }
    }
}

sealed class FeedError(message: String) : Exception(message) {
    data class UnsupportedVersion(val version: Int) : FeedError("unsupported feed version: $version")
}

@Serializable
data class FeedStory(
    val id: String,
    /** The day the round was played, as `yyyy-MM-dd`. */
    val date: String,
    val event: Event,
    val white: Player,
    val black: Player,
    /** `1-0`, `0-1` or `1/2-1/2`. */
    val result: String,
    val opening: Opening,
    /** SAN, space separated, from the initial position. */
    val moves: String,
    val key: Key? = null,
    val headline: String,
    val body: String,
    /** The language `headline`/`body` are in; absent from the English files. */
    val lang: String? = null,
    val url: String,
    val source: Source,
    /** The board the story is about, worked out by the collector. */
    val fen: String? = null,
    val last: LastMove? = null,
    /** Deep multi-section analytical breakdown of the game, when present. */
    val premium: PremiumArticle? = null,
) {
    @Serializable
    data class Player(
        val name: String,
        val short: String,
        val title: String? = null,
        val elo: Int? = null,
        val team: String? = null,
    )

    @Serializable
    data class Event(
        val name: String,
        val short: String,
        val section: String? = null,
        val round: Int? = null,
        val location: String? = null,
    )

    @Serializable
    data class Score(val cp: Int? = null, val mate: Int? = null) {
        /** Stockfish's number, from White's side: +1.08, −4.40, #3. */
        val text: String
            get() {
                mate?.let { return if (it > 0) "#$it" else "#−${-it}" }
                val pawns = (cp ?: 0).toDouble() / 100
                val sign = if (pawns > 0) "+" else if (pawns < 0) "−" else ""
                return sign + String.format(Locale.ROOT, "%.2f", abs(pawns))
            }
    }

    @Serializable
    data class Key(
        /** `mistake`: the loser's deciding move. `breakthrough`: the winner's. `swing`: a draw's. */
        val kind: String,
        /** Half-moves played up to and including the key move. */
        val ply: Int,
        val played: String,
        val better: String? = null,
        val reply: String? = null,
        val replyPlayed: Boolean? = null,
        val before: Score,
        val after: Score,
    )

    @Serializable
    data class Opening(val eco: String? = null, val name: String? = null)

    @Serializable
    data class Source(val name: String, val url: String? = null)

    @Serializable
    data class LastMove(val from: String, val to: String)

    @Serializable
    data class PremiumSection(
        val heading: String,
        val ply: Int? = null,
        val move: String? = null,
        val fen: String? = null,
        val last: LastMove? = null,
        val evalBefore: String? = null,
        val evalAfter: String? = null,
        val engineLine: String? = null,
        val body: String,
        val takeaways: List<String>? = null,
    )

    @Serializable
    data class PremiumArticle(
        val tier: String? = null,
        val subtitle: String? = null,
        val readingMinutes: Int? = null,
        val sections: List<PremiumSection>,
    )

    /** A board and the move that reached it. (Kotlin has no named tuple, unlike the Swift source.) */
    data class Board(val position: Position, val lastMove: Move?)

    val sans: List<String> get() = moves.split(" ").filter { it.isNotEmpty() }

    /** The game as positions, or null when a move does not play. */
    val line: List<Position>?
        get() {
            val position = Position()
            val positions = arrayListOf(position.copy())
            for (san in sans) {
                val move = position.move(san) ?: return null
                if (position.make(move) == null) return null
                positions.add(position.copy())
            }
            return positions
        }

    /** The half-move the story is about: the key move when there is one, else the last move. */
    val focusPly: Int get() = key?.ply ?: sans.size

    /**
     * The board the story is about, and the move that made it — read off the stored position when
     * there is one (every list row, many times a second as it scrolls) rather than replaying.
     */
    val focus: Board
        get() {
            val storedFen = fen
            if (storedFen != null) {
                val position = Position.fromFen(storedFen)
                if (position != null) return Board(position, lastMoveFrom(last))
            }
            val position = Position()
            var lastMove: Move? = null
            for (san in sans.take(focusPly)) {
                val move = position.move(san) ?: break
                if (position.make(move) == null) break
                lastMove = move
            }
            return Board(position, lastMove)
        }

    /** Resolves the board diagram and last move for a section in a Premium Article. */
    fun sectionBoard(section: PremiumSection): Board? {
        val sectionFen = section.fen
        if (sectionFen != null) {
            val position = Position.fromFen(sectionFen)
            if (position != null) return Board(position, lastMoveFrom(section.last))
        }
        val ply = section.ply ?: return null
        if (ply < 0) return null
        val position = Position()
        var lastMove: Move? = null
        for (san in sans.take(ply)) {
            val move = position.move(san) ?: return null
            if (position.make(move) == null) return null
            lastMove = move
        }
        return Board(position, lastMove)
    }

    /** Who won, or null for a draw. */
    val winner: PieceColor?
        get() = when (result) {
            "1-0" -> PieceColor.white
            "0-1" -> PieceColor.black
            else -> null
        }

    /** The story's title in the reader's language — the one sentence the feed can say everywhere. */
    val title: String
        get() = when (winner) {
            PieceColor.white -> L.t("today.beat", "%1\$s beat %2\$s", white.short, black.short)
            PieceColor.black -> L.t("today.beat", "%1\$s beat %2\$s", black.short, white.short)
            null -> L.t("today.drew", "%1\$s and %2\$s drew", white.short, black.short)
        }

    /** "Olympiad · Open · Round 9", with the round in the reader's language. */
    val occasion: String
        get() = listOfNotNull(
            event.short,
            event.section,
            event.round?.let { L.t("today.round", "Round %d", it) },
        ).joinToString(" · ")

    /** The day, as a UTC midnight instant, or null when the date cannot be read. */
    val day: Instant?
        get() = runCatching { LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()

    /**
     * Whether the story is the last day's: replayed and played on from until a whole day has passed
     * since the end of its round's day, in UTC. A story whose date cannot be read is treated as
     * recent — the rule keeps the newest games free, never locks one by mistake.
     */
    fun isRecent(now: Instant = Instant.now()): Boolean {
        val d = day ?: return true
        return now.isBefore(d.plusSeconds(2 * 86_400))
    }

    companion object {
        /** 41.Rxg7+ or 40...Rxb3 — the move as the commentary prints it. */
        fun label(ply: Int, san: String): String {
            val number = (ply + 1) / 2
            return if (ply % 2 == 1) "$number.$san" else "$number...$san"
        }

        private fun lastMoveFrom(last: LastMove?): Move? {
            last ?: return null
            val from = Square.fromName(last.from) ?: return null
            val to = Square.fromName(last.to) ?: return null
            return Move(from, to)
        }
    }
}
