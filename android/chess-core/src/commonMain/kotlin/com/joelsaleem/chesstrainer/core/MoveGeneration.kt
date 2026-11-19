package com.joelsaleem.chesstrainer.core

/** Every legal move in this position. */
fun Position.legalMoves(): List<Move> =
    pseudoLegalMoves().filterNot { move ->
        val copy = copy()
        copy.makeUnchecked(move)
        copy.isInCheck(sideToMove)
    }

/** Legal moves starting from a given square — what the board UI needs. */
fun Position.legalMoves(from: Square): List<Move> = legalMoves().filter { it.from == from }

/** Moves that respect piece movement rules but may leave the king in check. */
internal fun Position.pseudoLegalMoves(): List<Move> {
    val moves = ArrayList<Move>(48)
    for (index in 0 until 64) {
        val piece = board[index] ?: continue
        if (piece.color != sideToMove) continue
        val square = Square(index)
        when (piece.kind) {
            PieceKind.pawn -> generatePawnMoves(square, moves)
            PieceKind.knight -> generateStepMoves(square, Position.knightOffsets, moves)
            PieceKind.king -> generateStepMoves(square, Position.kingOffsets, moves)
            PieceKind.bishop -> generateSlidingMoves(square, Position.bishopDirections, moves)
            PieceKind.rook -> generateSlidingMoves(square, Position.rookDirections, moves)
            PieceKind.queen -> {
                generateSlidingMoves(square, Position.rookDirections, moves)
                generateSlidingMoves(square, Position.bishopDirections, moves)
            }
        }
    }
    generateCastlingMoves(moves)
    return moves
}

private fun Position.generateStepMoves(square: Square, offsets: List<Pair<Int, Int>>, moves: MutableList<Move>) {
    for ((df, dr) in offsets) {
        val file = square.file + df
        val rank = square.rank + dr
        if (file !in 0..7 || rank !in 0..7) continue
        val target = Square.of(file, rank)
        val occupant = board[target.index]
        if (occupant == null) {
            moves += Move(square, target, kind = Move.Kind.quiet)
        } else if (occupant.color != sideToMove) {
            moves += Move(square, target, kind = Move.Kind.capture)
        }
    }
}

private fun Position.generateSlidingMoves(square: Square, directions: List<Pair<Int, Int>>, moves: MutableList<Move>) {
    for ((df, dr) in directions) {
        var file = square.file + df
        var rank = square.rank + dr
        while (file in 0..7 && rank in 0..7) {
            val target = Square.of(file, rank)
            val occupant = board[target.index]
            if (occupant != null) {
                if (occupant.color != sideToMove) moves += Move(square, target, kind = Move.Kind.capture)
                break
            }
            moves += Move(square, target, kind = Move.Kind.quiet)
            file += df
            rank += dr
        }
    }
}

private val promotionKinds = listOf(PieceKind.queen, PieceKind.rook, PieceKind.bishop, PieceKind.knight)

private fun Position.generatePawnMoves(square: Square, moves: MutableList<Move>) {
    val step = sideToMove.pawnStep
    val promotionRank = sideToMove.promotionRank
    val forward = square.index + step

    fun append(move: Move, promoting: Boolean) {
        if (promoting) {
            for (kind in promotionKinds) moves += move.copy(promotion = kind)
        } else {
            moves += move
        }
    }

    // Single and double pushes.
    if (forward in 0..63 && board[forward] == null) {
        val target = Square(forward)
        append(Move(square, target, kind = Move.Kind.quiet), promoting = target.rank == promotionRank)

        if (square.rank == sideToMove.pawnRank) {
            val double = forward + step
            if (double in 0..63 && board[double] == null) {
                moves += Move(square, Square(double), kind = Move.Kind.doublePawnPush)
            }
        }
    }

    // Captures, including en passant.
    for (df in intArrayOf(-1, 1)) {
        val file = square.file + df
        val rank = square.rank + if (sideToMove == PieceColor.white) 1 else -1
        if (file !in 0..7 || rank !in 0..7) continue
        val target = Square.of(file, rank)
        val occupant = board[target.index]
        if (occupant != null) {
            if (occupant.color == sideToMove) continue
            append(Move(square, target, kind = Move.Kind.capture), promoting = target.rank == promotionRank)
        } else if (target == enPassant) {
            moves += Move(square, target, kind = Move.Kind.enPassant)
        }
    }
}

private fun Position.generateCastlingMoves(moves: MutableList<Move>) {
    val kingSquare = king(sideToMove) ?: return
    if (isInCheck(sideToMove)) return

    val backRank = if (sideToMove == PieceColor.white) 0 else 7
    if (kingSquare != Square.of(4, backRank)) return

    val white = sideToMove == PieceColor.white
    val kingsideRight = if (white) CastlingRights.whiteKingside else CastlingRights.blackKingside
    val queensideRight = if (white) CastlingRights.whiteQueenside else CastlingRights.blackQueenside
    val enemy = sideToMove.opponent
    val rook = Piece(sideToMove, PieceKind.rook)
    val row = backRank * 8

    // Kingside: f and g empty; the king may not start, pass through, or land on an attacked square.
    if (kingsideRight in castling &&
        board[row + 5] == null && board[row + 6] == null && board[row + 7] == rook &&
        !isAttacked(Square.of(5, backRank), enemy) && !isAttacked(Square.of(6, backRank), enemy)
    ) {
        moves += Move(kingSquare, Square.of(6, backRank), kind = Move.Kind.kingsideCastle)
    }

    // Queenside: b, c and d empty. Only the rook passes over b, so b may be attacked.
    if (queensideRight in castling &&
        board[row + 1] == null && board[row + 2] == null && board[row + 3] == null && board[row] == rook &&
        !isAttacked(Square.of(3, backRank), enemy) && !isAttacked(Square.of(2, backRank), enemy)
    ) {
        moves += Move(kingSquare, Square.of(2, backRank), kind = Move.Kind.queensideCastle)
    }
}
