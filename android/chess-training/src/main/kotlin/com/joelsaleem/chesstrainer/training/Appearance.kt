package com.joelsaleem.chesstrainer.training

import kotlinx.serialization.Serializable

/*
 * ponytail: the presentation members of these types — the display `name`/`summary`
 * strings and the colour/texture data (LightTone.shading, BoardStyle.squares/textures,
 * PieceSet.darkArtPrefix, BoardDimension.isDimensional, PieceSet.usesGlyphs) — are the
 * board/settings UI's concern and land with it in M5+. Ported here now: the data model,
 * its serialization, and the one piece of logic the settings describe (limitsStrength).
 *
 * The enum entry names must match the Swift String raw values exactly — kotlinx serializes
 * an enum by its entry name, and that is what a saved Appearance round-trips through.
 */

@Serializable
enum class AppTypeface { system, cormorantGaramond, jetBrainsMono }

/**
 * Which engine plays and grades.
 *
 * Lives here rather than in chess-engine because chess-training does not depend on the
 * engine module — the trainer must be testable without an engine.
 */
@Serializable
enum class EngineChoice {
    stockfish,
    reckless;

    /**
     * Whether this engine can play at a requested rating. Mirrors
     * `EngineCapabilities.limitsStrength`, which is the authority — this copy exists only
     * so the settings can describe the choice before an engine has been built.
     */
    val limitsStrength: Boolean get() = this == stockfish
}

@Serializable
enum class LightTone { snow, chalk, boxwood, frost, mist }

@Serializable
enum class BoardDimension { flat, dimensional }

@Serializable
enum class Carving { plain, banded, parlour, bronze }

@Serializable
enum class PieceSet { ebony, emerald, sapphire, claret, glyph }

@Serializable
enum class BoardStyle { wood, lamplight, amber, forest, ocean, ivory, rose, sand, slate }

/**
 * Which pieces and which board, plus the handful of player switches that travel with them.
 *
 * Every field has a default, and decoding fills the default for any key a saved file omits —
 * this is what lets a save written before a field existed still load (e.g. an old save with
 * no `engine` loads as Stockfish rather than failing). Use a lenient [kotlinx.serialization.json.Json]
 * (`ignoreUnknownKeys = true`) so a key from a newer version does not break an older reader.
 */
@Serializable
data class Appearance(
    var typeface: AppTypeface = AppTypeface.system,
    var pieces: PieceSet = PieceSet.ebony,
    var board: BoardStyle = BoardStyle.lamplight,
    var soundsOn: Boolean = true,
    /** 0..1. The mute control moves this to zero and restores the previous non-zero value. */
    var volume: Double = 0.7,
    var dimension: BoardDimension = BoardDimension.flat,
    var carving: Carving = Carving.banded,
    var lightTone: LightTone = LightTone.boxwood,
    var showsCoordinates: Boolean = true,
    var engine: EngineChoice = EngineChoice.stockfish,
    var showsCompletionSummary: Boolean = true,
    var showsOnline: Boolean = true,
)
