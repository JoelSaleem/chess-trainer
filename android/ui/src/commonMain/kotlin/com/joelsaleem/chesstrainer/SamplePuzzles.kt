package com.joelsaleem.chesstrainer

import com.joelsaleem.chesstrainer.training.Puzzle

/**
 * ponytail: a couple of hardcoded puzzles so M4 can solve one on-device and be screenshot-verified.
 * The real content pipeline — a bundled tactics.json in assets, read through ContentLibrary — is a
 * later increment (it needs the generated data and the asset-to-file plumbing).
 */
object SamplePuzzles {
    val all: List<Puzzle> = listOf(
        // Back-rank mate: Ra1-a8#. (The same position MoveDescriptionTests proves is checkmate.)
        Puzzle(
            id = "sample-backrank",
            fen = "6k1/5ppp/8/8/8/8/8/R3K3 w - - 0 1",
            solution = listOf("a1a8"),
            themes = listOf("mate", "mateIn1", "backRankMate"),
            rating = 900,
            mate = true,
        ),
    )
}
