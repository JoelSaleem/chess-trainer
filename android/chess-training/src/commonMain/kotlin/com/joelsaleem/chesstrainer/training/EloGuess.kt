package com.joelsaleem.chesstrainer.training

import kotlinx.serialization.Serializable
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.math.max

/** Scoring for one rating estimate in Guess the Elo. */
data class EloGuess(val guess: Int, val actual: Int) {
    val error: Int get() = abs(guess - actual)
    val isHigh: Boolean get() = guess > actual

    enum class Verdict {
        spot, close, fair, off;

        val title: String
            get() = when (this) {
                spot -> L.t("guess.spotOn", "Spot on")
                close -> L.t("guess.close", "Close")
                fair -> L.t("guess.rightArea", "In the right area")
                off -> L.t("guess.notThisTime", "Not this time")
            }
    }

    /** Bands rather than a continuous score: reading a game to within a class is the achievement. */
    val verdict: Verdict
        get() = when {
            error < 75 -> Verdict.spot
            error < 150 -> Verdict.close
            error < 300 -> Verdict.fair
            else -> Verdict.off
        }

    /** 100 for a perfect read, nothing once you are 500 out. */
    val points: Int get() = max(0, 100 - error / 5)

    val summary: String
        get() {
            if (error < 15) return L.t("guess.deadOn", "Dead on.")
            return if (isHigh) L.t("guess.tooHigh", "%d points too high.", error)
            else L.t("guess.tooLow", "%d points too low.", error)
        }

    companion object {
        /** The range a guess may move in, wider than the library so the ends are not answers. */
        val range = 600..2800
        const val step = 25
    }
}

/**
 * A separate rating guess for each player in the watched game. Each side is judged against that
 * player's real rating; the overall result uses the mean of the two errors.
 */
data class EloPairGuess(val white: EloGuess, val black: EloGuess) {
    constructor(whiteGuess: Int, blackGuess: Int, whiteActual: Int, blackActual: Int) :
        this(EloGuess(whiteGuess, whiteActual), EloGuess(blackGuess, blackActual))

    val averageError: Int get() = (white.error + black.error) / 2

    val verdict: EloGuess.Verdict
        get() = when {
            averageError < 75 -> EloGuess.Verdict.spot
            averageError < 150 -> EloGuess.Verdict.close
            averageError < 300 -> EloGuess.Verdict.fair
            else -> EloGuess.Verdict.off
        }

    val points: Int get() = max(0, 100 - averageError / 5)

    val summary: String
        get() = if (averageError < 15) L.t("guess.bothSpotOn", "Both ratings were spot on.")
        else L.t("guess.averageMiss", "Average miss: %d points.", averageError)
}

/** One judged game, kept so the mode can report whether you are getting better at reading a game. */
@Serializable
data class EloGuessRecord(
    val at: Instant = Clock.System.now(),
    val gameID: String,
    val guess: Int,
    val actual: Int,
    val whiteGuess: Int? = null,
    val whiteActual: Int? = null,
    val blackGuess: Int? = null,
    val blackActual: Int? = null,
) {
    /** The two-player variant: overall guess/actual are the means of the sides. */
    constructor(
        gameID: String,
        whiteGuess: Int,
        whiteActual: Int,
        blackGuess: Int,
        blackActual: Int,
        at: Instant = Clock.System.now(),
    ) : this(
        at = at, gameID = gameID,
        guess = (whiteGuess + blackGuess) / 2, actual = (whiteActual + blackActual) / 2,
        whiteGuess = whiteGuess, whiteActual = whiteActual, blackGuess = blackGuess, blackActual = blackActual,
    )

    val error: Int
        get() {
            if (whiteGuess != null && whiteActual != null && blackGuess != null && blackActual != null) {
                return (abs(whiteGuess - whiteActual) + abs(blackGuess - blackActual)) / 2
            }
            return abs(guess - actual)
        }

    val bias: Int
        get() {
            if (whiteGuess != null && whiteActual != null && blackGuess != null && blackActual != null) {
                return ((whiteGuess - whiteActual) + (blackGuess - blackActual)) / 2
            }
            return guess - actual
        }
}

class EloGuessStats(records: List<EloGuessRecord>) {
    val judged: Int = records.size
    val averageError: Int
    val bestError: Int

    /** Positive when you tend to read games as stronger than they were — the common way to be wrong. */
    val bias: Int

    init {
        if (records.isEmpty()) {
            averageError = 0
            bestError = 0
            bias = 0
        } else {
            averageError = records.sumOf { it.error } / records.size
            bestError = records.minOf { it.error }
            bias = records.sumOf { it.bias } / records.size
        }
    }
}
