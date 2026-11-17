@file:Suppress("EnumEntryName")

package com.joelsaleem.chesstrainer.core

// Swift's `Undo` struct is not ported: nothing in the iOS app uses it.

data class Move(
    val from: Square,
    val to: Square,
    val promotion: PieceKind? = null,
    val kind: Kind = Kind.quiet,
) {
    enum class Kind { quiet, capture, doublePawnPush, enPassant, kingsideCastle, queensideCastle }

    val isCapture: Boolean get() = kind == Kind.capture || kind == Kind.enPassant
    val isCastle: Boolean get() = kind == Kind.kingsideCastle || kind == Kind.queensideCastle

    /** Long algebraic notation, the form UCI speaks: "e2e4", "e7e8q". */
    val uci: String get() = "$from$to${promotion?.letter ?: ""}"

    override fun toString(): String = uci

    /** Compares only the parts notation carries, ignoring the internal kind. */
    fun matchesNotation(other: Move): Boolean = from == other.from && to == other.to && promotion == other.promotion

    companion object {
        /** The kind is unknown from notation alone; Position resolves it against the legal list. */
        fun fromUci(uci: CharSequence): Move? {
            if (uci.length != 4 && uci.length != 5) return null
            val from = Square.fromName(uci.subSequence(0, 2)) ?: return null
            val to = Square.fromName(uci.subSequence(2, 4)) ?: return null
            val promotion = if (uci.length == 5) PieceKind.fromLetter(uci[4]) ?: return null else null
            return Move(from, to, promotion)
        }
    }
}
