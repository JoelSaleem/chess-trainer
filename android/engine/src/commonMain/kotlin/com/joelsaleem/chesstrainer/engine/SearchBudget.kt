package com.joelsaleem.chesstrainer.engine

/**
 * How long the engine may think, per kind of question. Both a depth and a time cap
 * are given: the search stops at whichever comes first, so easy positions finish
 * early and sharp ones don't burn the whole budget.
 */
data class SearchBudget(val depth: Int, val movetimeMs: Int) {
    companion object {
        /** Grading a move just played. Runs twice per move (before and after). */
        val coaching = SearchBudget(depth = 14, movetimeMs = 700)

        /** A hint. The player is waiting and watching, so this is the tightest. */
        val hint = SearchBudget(depth = 14, movetimeMs = 600)

        /** The engine's own move at full strength. */
        val fullStrength = SearchBudget(depth = 20, movetimeMs = 2500)

        /** The engine pretending to be a given Elo; strength comes from UCI_Elo. */
        val limited = SearchBudget(depth = 12, movetimeMs = 300)

        /** Judging whether an endgame is still winning or drawn after a move. */
        val verdict = SearchBudget(depth = 14, movetimeMs = 900)

        /** Scoring a whole position for the judgement exercises. */
        val assessment = SearchBudget(depth = 14, movetimeMs = 900)
    }
}
