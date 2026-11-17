package com.joelsaleem.chesstrainer.core

/** Standard algebraic notation for a legal move in this position: "Nxe5+". */
fun Position.san(move: Move): String {
    if (move.kind == Move.Kind.kingsideCastle) return withSuffix("O-O", move)
    if (move.kind == Move.Kind.queensideCastle) return withSuffix("O-O-O", move)

    val piece = this[move.from] ?: return move.uci
    val text = StringBuilder()
    if (piece.kind == PieceKind.pawn) {
        if (move.isCapture) text.append('a' + move.from.file).append('x')
        text.append(move.to)
        move.promotion?.let { text.append('=').append(it.letter.uppercaseChar()) }
    } else {
        text.append(piece.kind.letter.uppercaseChar())
        text.append(disambiguation(move, piece))
        if (move.isCapture) text.append('x')
        text.append(move.to)
    }
    return withSuffix(text.toString(), move)
}

/** File if that distinguishes identical rivals, otherwise rank, otherwise both. */
private fun Position.disambiguation(move: Move, piece: Piece): String {
    val rivals = legalMoves().filter { it.to == move.to && it.from != move.from && this[it.from] == piece }
    if (rivals.isEmpty()) return ""
    if (rivals.all { it.from.file != move.from.file }) return ('a' + move.from.file).toString()
    if (rivals.all { it.from.rank != move.from.rank }) return (move.from.rank + 1).toString()
    return move.from.toString()
}

/** Appends "+" or "#" by looking at the position the move produces. */
private fun Position.withSuffix(text: String, move: Move): String {
    val next = copy()
    next.makeUnchecked(move)
    if (next.isCheckmate) return "$text#"
    if (next.isCheck) return "$text+"
    return text
}

private val sanNoise = listOf("!", "?", "+", "#", "e.p.", " ")
private val printedNoise = listOf("!", "?", "+", "#")

/**
 * Parse SAN against this position. Lenient about decorations like "!?" and
 * "e.p.", strict about the move itself being legal.
 */
fun Position.move(san: String): Move? {
    var text = san.trim()
    for (noise in sanNoise) text = text.replace(noise, "")
    if (text.isEmpty()) return null

    val moves = legalMoves()
    if (text == "O-O" || text == "0-0") return moves.firstOrNull { it.kind == Move.Kind.kingsideCastle }
    if (text == "O-O-O" || text == "0-0-0") return moves.firstOrNull { it.kind == Move.Kind.queensideCastle }

    // Only print the moves that could be this one: printing plays each move to
    // test for check, which is what costs when parsing whole games.
    var destination: Square? = null
    for (index in text.length - 2 downTo 0) {
        destination = Square.fromName(text.substring(index, index + 2))
        if (destination != null) break
    }
    val first = text[0]
    val kind = (if (first in "KQRBN") PieceKind.fromLetter(first) else null) ?: PieceKind.pawn
    val candidates = moves.filter { (destination == null || it.to == destination) && this[it.from]?.kind == kind }

    // Matching by regenerating notation cannot disagree with san().
    return candidates.firstOrNull { candidate ->
        var printed = san(candidate)
        for (noise in printedNoise) printed = printed.replace(noise, "")
        printed == text
    }
}
