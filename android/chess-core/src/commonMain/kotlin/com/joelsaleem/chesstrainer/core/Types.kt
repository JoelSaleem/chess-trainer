// Basic types. Squares are 0...63 with a1 = 0 and h8 = 63, so file and rank
// arithmetic is plain integer maths and the board is one flat array.
@file:Suppress("EnumEntryName")

package com.joelsaleem.chesstrainer.core

import kotlin.jvm.JvmInline

enum class PieceColor {
    white, black;

    val rawValue: Int get() = ordinal

    val opponent: PieceColor get() = if (this == white) black else white

    /** Direction a pawn of this colour advances, in squares. */
    val pawnStep: Int get() = if (this == white) 8 else -8

    /** Rank a pawn of this colour starts on. */
    val pawnRank: Int get() = if (this == white) 1 else 6

    /** Rank a pawn of this colour promotes on. */
    val promotionRank: Int get() = if (this == white) 7 else 0
}

enum class PieceKind(val letter: Char, val value: Int) {
    pawn('p', 100),
    knight('n', 300),
    bishop('b', 325),
    rook('r', 500),
    queen('q', 900),
    king('k', 0);

    val rawValue: Int get() = ordinal

    companion object {
        fun fromLetter(letter: Char): PieceKind? = entries.firstOrNull { it.letter == letter.lowercaseChar() }
    }
}

data class Piece(val color: PieceColor, val kind: PieceKind) {
    val character: Char get() = if (color == PieceColor.white) kind.letter.uppercaseChar() else kind.letter

    companion object {
        fun fromCharacter(character: Char): Piece? {
            val kind = PieceKind.fromLetter(character) ?: return null
            return Piece(if (character.isUpperCase()) PieceColor.white else PieceColor.black, kind)
        }
    }
}

/** A board square, 0...63, a1 = 0. */
@JvmInline
value class Square(val index: Int) {
    val file: Int get() = index and 7
    val rank: Int get() = index shr 3

    override fun toString(): String = "${'a' + file}${'1' + rank}"

    /** Rank counted from a side's own back rank: a1 is rank 1 for White, a8 for Black. */
    fun relativeRank(color: PieceColor): Int = if (color == PieceColor.white) rank + 1 else 8 - rank

    companion object {
        fun of(file: Int, rank: Int): Square = Square(rank * 8 + file)

        fun fromName(name: CharSequence): Square? {
            if (name.length != 2) return null
            val f = name[0]
            val r = name[1]
            if (f !in 'a'..'h' || r !in '1'..'8') return null
            return Square((r - '1') * 8 + (f - 'a'))
        }
    }
}

@JvmInline
value class CastlingRights(val rawValue: Int) {
    val isEmpty: Boolean get() = rawValue == 0

    operator fun contains(other: CastlingRights): Boolean = (rawValue and other.rawValue) == other.rawValue

    operator fun plus(other: CastlingRights) = CastlingRights(rawValue or other.rawValue)

    operator fun minus(other: CastlingRights) = CastlingRights(rawValue and other.rawValue.inv())

    val fenString: String
        get() {
            if (isEmpty) return "-"
            val text = StringBuilder()
            if (whiteKingside in this) text.append('K')
            if (whiteQueenside in this) text.append('Q')
            if (blackKingside in this) text.append('k')
            if (blackQueenside in this) text.append('q')
            return text.toString()
        }

    companion object {
        val none = CastlingRights(0)
        val whiteKingside = CastlingRights(1 shl 0)
        val whiteQueenside = CastlingRights(1 shl 1)
        val blackKingside = CastlingRights(1 shl 2)
        val blackQueenside = CastlingRights(1 shl 3)
        val all = CastlingRights(0b1111)
    }
}
