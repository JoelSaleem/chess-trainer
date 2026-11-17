package com.joelsaleem.chesstrainer.core

/** Play a move given in notation, if it is legal here. */
fun Position.make(notation: Move): Move? {
    val move = legalMoves().firstOrNull { it.matchesNotation(notation) } ?: return null
    makeUnchecked(move)
    return move
}

fun Position.make(uci: String): Move? {
    val parsed = Move.fromUci(uci) ?: return null
    return make(parsed)
}

/**
 * Apply a move already known to be generated from this position. Used by the
 * legality filter on a copy, so it must not itself check legality.
 */
internal fun Position.makeUnchecked(move: Move) {
    val moving = board[move.from.index]!!
    val previousCastling = castling
    val previousEnPassant = enPassant

    // Remove the captured piece first; en passant takes from a square the moving pawn never lands on.
    var captured: Piece? = null
    if (move.kind == Move.Kind.enPassant) {
        val capturedSquare = Square.of(move.to.file, move.from.rank)
        captured = board[capturedSquare.index]
        board[capturedSquare.index] = null
        hash = hash xor Zobrist.pieces[Zobrist.index(captured!!)][capturedSquare.index]
    } else {
        board[move.to.index]?.let { occupant ->
            captured = occupant
            hash = hash xor Zobrist.pieces[Zobrist.index(occupant)][move.to.index]
        }
    }

    board[move.from.index] = null
    hash = hash xor Zobrist.pieces[Zobrist.index(moving)][move.from.index]

    val placed = move.promotion?.let { Piece(moving.color, it) } ?: moving
    board[move.to.index] = placed
    hash = hash xor Zobrist.pieces[Zobrist.index(placed)][move.to.index]

    // The rook hops over the king; the king move above already happened.
    if (move.isCastle) {
        val backRank = if (moving.color == PieceColor.white) 0 else 7
        val (rookFrom, rookTo) = if (move.kind == Move.Kind.kingsideCastle) {
            Square.of(7, backRank) to Square.of(5, backRank)
        } else {
            Square.of(0, backRank) to Square.of(3, backRank)
        }
        val rook = board[rookFrom.index]!!
        board[rookFrom.index] = null
        board[rookTo.index] = rook
        hash = hash xor Zobrist.pieces[Zobrist.index(rook)][rookFrom.index]
        hash = hash xor Zobrist.pieces[Zobrist.index(rook)][rookTo.index]
    }

    updateCastlingRights(moving, move)

    enPassant = if (move.kind == Move.Kind.doublePawnPush) {
        Square.of(move.from.file, (move.from.rank + move.to.rank) / 2)
    } else {
        null
    }

    previousEnPassant?.let { hash = hash xor Zobrist.enPassantFile[it.file] }
    enPassant?.let { hash = hash xor Zobrist.enPassantFile[it.file] }
    if (previousCastling != castling) {
        hash = hash xor Zobrist.castling[previousCastling.rawValue]
        hash = hash xor Zobrist.castling[castling.rawValue]
    }

    halfmoveClock = if (moving.kind == PieceKind.pawn || captured != null) 0 else halfmoveClock + 1
    if (sideToMove == PieceColor.black) fullmoveNumber += 1
    sideToMove = sideToMove.opponent
    hash = hash xor Zobrist.sideToMove

    // A pawn move or capture makes every earlier position unreachable.
    if (halfmoveClock == 0) history.clear()
    history.add(hash)
}

/** Castling rights die when the king moves, a rook leaves its corner, or a rook is captured on it. */
private fun Position.updateCastlingRights(moving: Piece, move: Move) {
    if (moving.kind == PieceKind.king) {
        castling -= if (moving.color == PieceColor.white) {
            CastlingRights.whiteKingside + CastlingRights.whiteQueenside
        } else {
            CastlingRights.blackKingside + CastlingRights.blackQueenside
        }
    }
    for (square in arrayOf(move.from, move.to)) {
        when (square.index) {
            0 -> castling -= CastlingRights.whiteQueenside
            7 -> castling -= CastlingRights.whiteKingside
            56 -> castling -= CastlingRights.blackQueenside
            63 -> castling -= CastlingRights.blackKingside
        }
    }
}

// Game state

val Position.isCheckmate: Boolean get() = isCheck && legalMoves().isEmpty()
val Position.isStalemate: Boolean get() = !isCheck && legalMoves().isEmpty()

val Position.isFiftyMoveRule: Boolean get() = halfmoveClock >= 100

val Position.isThreefoldRepetition: Boolean
    get() {
        val current = history.lastOrNull() ?: return false
        return history.count { it == current } >= 3
    }

/** Positions from which no sequence of legal moves can produce checkmate. */
val Position.isInsufficientMaterial: Boolean
    get() {
        var knights = 0
        var bishopsOnLight = 0
        var bishopsOnDark = 0
        for (index in 0 until 64) {
            val piece = board[index] ?: continue
            when (piece.kind) {
                PieceKind.king -> continue
                PieceKind.knight -> knights += 1
                PieceKind.bishop -> {
                    val square = Square(index)
                    if ((square.file + square.rank) % 2 == 0) bishopsOnDark += 1 else bishopsOnLight += 1
                }
                else -> return false // any pawn, rook or queen can still mate
            }
        }
        val bishops = bishopsOnLight + bishopsOnDark
        if (knights == 0 && bishops == 0) return true // bare kings
        if (knights == 1 && bishops == 0) return true // king and knight
        // Any number of bishops, all on one colour, can never mate.
        if (knights == 0 && bishops >= 1) return bishopsOnLight == 0 || bishopsOnDark == 0
        return false
    }

val Position.isDraw: Boolean get() = isStalemate || isInsufficientMaterial || isFiftyMoveRule || isThreefoldRepetition

val Position.isGameOver: Boolean get() = isCheckmate || isDraw
