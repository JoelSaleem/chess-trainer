package com.joelsaleem.chesstrainer.core

/**
 * Zobrist hashing, used to detect threefold repetition.
 *
 * Filled from fixed seeds (the same as iOS) so a stored game hashes the same
 * way in the next launch.
 */
internal object Zobrist {
    val pieces: Array<LongArray> = SplitMix64(0x9E3779B97F4A7C15uL).let { g -> Array(12) { LongArray(64) { g.next() } } }
    val castling: LongArray = SplitMix64(0xBF58476D1CE4E5B9uL).let { g -> LongArray(16) { g.next() } }
    val enPassantFile: LongArray = SplitMix64(0x94D049BB133111EBuL).let { g -> LongArray(8) { g.next() } }
    val sideToMove: Long = SplitMix64(0x2545F4914F6CDD1DuL).next()

    fun index(piece: Piece): Int = piece.color.rawValue * 6 + piece.kind.rawValue
}

/** Small, fast, fully specified PRNG; reproducible by design. */
internal class SplitMix64(private var state: ULong) {
    fun next(): Long {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return (z xor (z shr 31)).toLong()
    }
}
