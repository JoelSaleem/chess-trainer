package com.joelsaleem.chesstrainer.core

/**
 * A chess position and the rules that govern it.
 *
 * Move generation is pseudo-legal followed by a make/test/unmake legality
 * filter: slower than strictly legal generation, far harder to get subtly
 * wrong. Speed is not the constraint here; Stockfish does the searching.
 *
 * Swift's `Position` is a value type. This is a class, so any place the Swift
 * code copies a position by assignment must call [copy] here.
 */
class Position private constructor(
    internal val board: Array<Piece?>,
    sideToMove: PieceColor,
    castling: CastlingRights,
    enPassant: Square?,
    halfmoveClock: Int,
    fullmoveNumber: Int,
    hash: Long,
    /** Hashes reached since the last irreversible move, for repetition detection. */
    internal val history: MutableList<Long>,
) {
    var sideToMove: PieceColor = sideToMove
        internal set
    var castling: CastlingRights = castling
        internal set
    var enPassant: Square? = enPassant
        internal set
    var halfmoveClock: Int = halfmoveClock
        internal set
    var fullmoveNumber: Int = fullmoveNumber
        internal set
    var hash: Long = hash
        internal set

    val squares: List<Piece?> get() = board.asList()

    fun copy(): Position =
        Position(board.copyOf(), sideToMove, castling, enPassant, halfmoveClock, fullmoveNumber, hash, history.toMutableList())

    val fen: String
        get() {
            val placement = StringBuilder()
            for (rank in 7 downTo 0) {
                var empty = 0
                for (file in 0 until 8) {
                    val piece = board[rank * 8 + file]
                    if (piece != null) {
                        if (empty > 0) { placement.append(empty); empty = 0 }
                        placement.append(piece.character)
                    } else {
                        empty += 1
                    }
                }
                if (empty > 0) placement.append(empty)
                if (rank > 0) placement.append('/')
            }
            val ep = enPassant?.toString() ?: "-"
            val side = if (sideToMove == PieceColor.white) "w" else "b"
            return "$placement $side ${castling.fenString} $ep $halfmoveClock $fullmoveNumber"
        }

    internal fun computeHash(): Long {
        var value = 0L
        for (index in 0 until 64) {
            val piece = board[index] ?: continue
            value = value xor Zobrist.pieces[Zobrist.index(piece)][index]
        }
        value = value xor Zobrist.castling[castling.rawValue]
        enPassant?.let { value = value xor Zobrist.enPassantFile[it.file] }
        if (sideToMove == PieceColor.black) value = value xor Zobrist.sideToMove
        return value
    }

    // Queries

    operator fun get(square: Square): Piece? = board[square.index]

    /**
     * The same position, but with [color] to move. Deliberately unchecked: it
     * stands for an intention (a premove), not a game.
     */
    fun speculating(color: PieceColor): Position {
        val copy = copy()
        copy.sideToMove = color
        copy.enPassant = null
        copy.hash = copy.computeHash()
        // The repetition history belongs to the real game, not to a sketch.
        copy.history.clear()
        copy.history.add(copy.hash)
        return copy
    }

    fun king(color: PieceColor): Square? {
        val king = Piece(color, PieceKind.king)
        for (index in 0 until 64) if (board[index] == king) return Square(index)
        return null
    }

    fun isInCheck(color: PieceColor): Boolean {
        val square = king(color) ?: return false
        return isAttacked(square, color.opponent)
    }

    val isCheck: Boolean get() = isInCheck(sideToMove)

    /** Is [square] attacked by any piece of [by]? Looks outward from the square. */
    fun isAttacked(square: Square, by: PieceColor): Boolean {
        val file = square.file
        val rank = square.rank

        // Pawns: step back along the direction that colour's pawns capture.
        val pawnRankOffset = if (by == PieceColor.white) -1 else 1
        if (attackerAt(file, rank, pawnCaptureFiles, pawnRankOffset, Piece(by, PieceKind.pawn))) return true
        if (attackerAt(file, rank, knightOffsets, Piece(by, PieceKind.knight))) return true
        if (attackerAt(file, rank, kingOffsets, Piece(by, PieceKind.king))) return true
        for ((df, dr) in rookDirections) if (slidingAttacker(file, rank, df, dr, by, PieceKind.rook)) return true
        for ((df, dr) in bishopDirections) if (slidingAttacker(file, rank, df, dr, by, PieceKind.bishop)) return true
        return false
    }

    private fun attackerAt(file: Int, rank: Int, offsets: List<Pair<Int, Int>>, piece: Piece): Boolean =
        offsets.any { (df, dr) -> pieceAt(file + df, rank + dr) == piece }

    private fun attackerAt(file: Int, rank: Int, offsets: IntArray, rankOffset: Int, piece: Piece): Boolean =
        offsets.any { df -> pieceAt(file + df, rank + rankOffset) == piece }

    private fun pieceAt(f: Int, r: Int): Piece? = if (f in 0..7 && r in 0..7) board[r * 8 + f] else null

    /** Walks one ray; a queen counts as both a rook and a bishop. */
    private fun slidingAttacker(file: Int, rank: Int, df: Int, dr: Int, color: PieceColor, kind: PieceKind): Boolean {
        var f = file + df
        var r = rank + dr
        while (f in 0..7 && r in 0..7) {
            val piece = board[r * 8 + f]
            if (piece != null) return piece.color == color && (piece.kind == kind || piece.kind == PieceKind.queen)
            f += df
            r += dr
        }
        return false
    }

    companion object {
        const val startFEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        operator fun invoke(): Position = fromFen(startFEN)!!

        private val pawnCaptureFiles = intArrayOf(-1, 1)
        internal val knightOffsets = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
        internal val kingOffsets = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
        internal val rookDirections = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        internal val bishopDirections = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

        fun fromFen(fen: String): Position? {
            val fields = fen.split(' ').filter { it.isNotEmpty() }
            if (fields.size < 4) return null

            val board = arrayOfNulls<Piece>(64)
            var rank = 7
            var file = 0
            for (character in fields[0]) {
                val digit = character.digitToIntOrNull()
                if (character == '/') {
                    if (file != 8) return null
                    rank -= 1
                    file = 0
                    if (rank < 0) return null
                } else if (digit != null && digit in 1..8) {
                    file += digit
                    if (file > 8) return null
                } else {
                    val piece = Piece.fromCharacter(character) ?: return null
                    if (file >= 8) return null
                    board[rank * 8 + file] = piece
                    file += 1
                }
            }
            if (rank != 0 || file != 8) return null

            val side = fields[1].first()
            if (side != 'w' && side != 'b') return null

            var rights = CastlingRights.none
            if (fields[2] != "-") {
                for (character in fields[2]) {
                    rights += when (character) {
                        'K' -> CastlingRights.whiteKingside
                        'Q' -> CastlingRights.whiteQueenside
                        'k' -> CastlingRights.blackKingside
                        'q' -> CastlingRights.blackQueenside
                        else -> return null
                    }
                }
            }

            val ep = if (fields[3] != "-") Square.fromName(fields[3]) ?: return null else null

            val position = Position(
                board = board,
                sideToMove = if (side == 'w') PieceColor.white else PieceColor.black,
                castling = rights,
                enPassant = ep,
                halfmoveClock = if (fields.size > 4) fields[4].toIntOrNull() ?: 0 else 0,
                fullmoveNumber = if (fields.size > 5) fields[5].toIntOrNull() ?: 1 else 1,
                hash = 0,
                history = mutableListOf(),
            )
            position.hash = position.computeHash()
            // The position as given counts as the first occurrence for repetition.
            position.history.add(position.hash)

            // Both kings present, and the side not to move not in check, or the
            // engine answers "bestmove (none)" and it looks like a crash.
            if (position.king(PieceColor.white) == null || position.king(PieceColor.black) == null) return null
            if (position.isInCheck(position.sideToMove.opponent)) return null
            return position
        }
    }
}
