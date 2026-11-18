package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.PieceColor
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A tactics puzzle: one position, one winning idea. [solution] alternates solver
 * move, opponent reply, solver move… and always ends on a solver move.
 */
@Serializable
data class Puzzle(
    val id: String,
    val fen: String,
    val solution: List<String>,
    val themes: List<String>,
    val rating: Int,
    val mate: Boolean,
    val source: String? = null,
) {
    val solverMoveCount: Int get() = (solution.size + 1) / 2
    val sideToMove: PieceColor get() = sideToMoveOf(fen)
}

/** A quiet position where judgement, not calculation, decides. */
@Serializable
data class PositionalExercise(
    val id: String,
    val fen: String,
    /** Evaluation from White's point of view, which is what the assessment asks about. */
    val cp: Int,
    val best: Candidate,
    val alternatives: List<Candidate>,
    val rating: Int,
    val themes: List<String>,
) {
    @Serializable
    data class Candidate(val uci: String, val cp: Int, val pv: List<String>? = null)

    val sideToMove: PieceColor get() = sideToMoveOf(fen)
}

/** An endgame played out against the engine to a required result. */
@Serializable
data class EndgameDrill(
    val id: String,
    val name: String,
    val fen: String,
    val goal: Goal,
    val rating: Int,
    val idea: String,
    val themes: List<String>,
) {
    @Serializable
    enum class Goal { win, draw }
}

/** A complete rated game between two humans, for Guess the Elo. */
@Serializable
data class AnnotatedGame(
    val id: String,
    val white: Int,
    val black: Int,
    val result: String,
    val speed: String? = null,
    val timeControl: String? = null,
    val termination: String? = null,
    val eco: String? = null,
    val opening: String? = null,
    val date: String? = null,
    /** UCI moves, space separated. */
    val moves: String,
) {
    val uciMoves: List<String> get() = if (moves.isBlank()) emptyList() else moves.split(" ")
    val averageRating: Int get() = (white + black) / 2

    /** "Blitz · 5+0 · Sicilian Defense" — everything that is not a strength clue. */
    val subtitle: String
        get() = listOfNotNull(speed, timeControl?.let { readableClock(it) }, opening).joinToString(" · ")

    companion object {
        /** "300+3" in the archive, "5+3" to players. */
        fun readableClock(control: String): String {
            val parts = control.split("+")
            val seconds = parts.firstOrNull()?.toIntOrNull() ?: return control
            val increment = if (parts.size > 1) parts[1] else "0"
            return if (seconds % 60 == 0) "${seconds / 60}+$increment" else "${seconds}s+$increment"
        }
    }
}

/**
 * A game somebody played, kept to be watched. Moves are as recorded, in the
 * notation a book prints (SAN), so the move generator must agree with the record.
 */
@Serializable
data class ClassicGame(
    val id: String,
    val white: String,
    val black: String,
    val event: String,
    val site: String? = null,
    val year: Int,
    val result: String,
    val eco: String? = null,
    /** True for the games named in the importer's own list. */
    val notable: Boolean,
    /** SAN, space separated. */
    val moves: String,
) {
    val players: String get() = "$white — $black"

    /** "Hastings, 1895 · 1–0" */
    val occasion: String
        get() = listOfNotNull(event.ifEmpty { null }, year.toString()).joinToString(", ") + " · " + result

    val plyCount: Int get() = if (moves.isBlank()) 0 else moves.split(" ").size
    val moveCount: Int get() = (plyCount + 1) / 2

    /** Everything a search box should match: the two players, where and when. */
    val haystack: String get() = "$white $black $event $year ${eco ?: ""}".lowercase()
}

/** Side to move, read from a FEN's second field. */
private fun sideToMoveOf(fen: String): PieceColor =
    if (fen.split(" ").getOrNull(1) == "w") PieceColor.white else PieceColor.black

class ContentLibrary(
    val puzzles: List<Puzzle> = emptyList(),
    val exercises: List<PositionalExercise> = emptyList(),
    val drills: List<EndgameDrill> = emptyList(),
    val games: List<AnnotatedGame> = emptyList(),
    val classics: List<ClassicGame> = emptyList(),
) {
    companion object {
        // Lenient like Swift's JSONDecoder, which ignores keys it doesn't declare.
        private val json = Json { ignoreUnknownKeys = true }

        @Serializable private data class PuzzleFile(val puzzles: List<Puzzle> = emptyList())
        @Serializable private data class ExerciseFile(val exercises: List<PositionalExercise> = emptyList())
        @Serializable private data class DrillFile(val drills: List<EndgameDrill> = emptyList())
        @Serializable private data class GameFile(val games: List<AnnotatedGame> = emptyList())
        @Serializable private data class ClassicFile(val games: List<ClassicGame> = emptyList())

        /**
         * Load the data files from a directory, tolerating any being absent — a build
         * without the generated tactics should still run the endgames.
         */
        fun load(directory: File): ContentLibrary {
            fun <T> decode(name: String, deserializer: DeserializationStrategy<T>): T? {
                val file = File(directory, name).takeIf { it.isFile } ?: return null
                return runCatching { json.decodeFromString(deserializer, file.readText()) }.getOrNull()
            }
            return ContentLibrary(
                puzzles = decode("tactics.json", PuzzleFile.serializer())?.puzzles ?: emptyList(),
                exercises = decode("positions.json", ExerciseFile.serializer())?.exercises ?: emptyList(),
                drills = decode("endgames.json", DrillFile.serializer())?.drills ?: emptyList(),
                games = decode("games.json", GameFile.serializer())?.games ?: emptyList(),
                classics = decode("classics.json", ClassicFile.serializer())?.games ?: emptyList(),
            )
        }
    }
}

object Themes {
    // Themes that describe a puzzle rather than name a skill.
    private val descriptive: Set<String> = setOf(
        "opening", "middlegame", "endgame",
        "oneMove", "short", "long", "veryLong",
        "crushing", "advantage", "equality", "mate",
        "master", "masterVsMaster", "superGM",
    )

    fun isMotif(theme: String): Boolean = theme !in descriptive

    /** "mateIn2" -> "Mate in 2", or whatever a translator called it. */
    fun readable(theme: String): String = L.t("theme.$theme", spaced(theme))

    private fun spaced(theme: String): String {
        val sb = StringBuilder()
        for (character in theme) {
            if ((character.isUpperCase() || character.isDigit()) && sb.isNotEmpty() && sb.last() != ' ') {
                sb.append(' ')
            }
            sb.append(character.lowercaseChar())
        }
        val s = sb.toString()
        return s.take(1).uppercase() + s.drop(1)
    }
}
