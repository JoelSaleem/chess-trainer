package com.joelsaleem.chesstrainer.core

/**
 * A game or a position, read out of whatever somebody pasted in: a FEN, a PGN,
 * bare book moves, or UCI moves from a log.
 */
class GameImport(
    /** Where the line starts. The standard array unless a FEN said otherwise. */
    val start: Position,
    /** The moves that were readable, in order. */
    val moves: List<Move>,
    /** The position after the last readable move. */
    val final: Position,
    /** Set when the text ran out mid-line: the token that could not be played, and how far it got. */
    val stoppedAt: StoppedAt?,
) {
    data class StoppedAt(val token: String, val afterMoves: Int)

    val isEmpty: Boolean get() = moves.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is GameImport && start.fen == other.start.fen && moves == other.moves &&
            final.fen == other.final.fen && stoppedAt == other.stoppedAt

    override fun hashCode(): Int = listOf(start.fen, moves, final.fen, stoppedAt).hashCode()

    companion object {
        /** Returns null only when there is no position to be had at all, not when some moves failed. */
        fun read(text: String): GameImport? {
            val headers = pgnHeaders(text)
            val start = headers["FEN"]?.let(Position::fromFen) ?: bareFEN(text) ?: Position()

            val position = start.copy()
            val moves = mutableListOf<Move>()
            var stopped: StoppedAt? = null
            for (token in tokens(text)) {
                val made = move(token, position)?.let { position.make(it) }
                if (made == null) {
                    stopped = StoppedAt(token, moves.size)
                    break
                }
                moves += made
            }

            // Neither a position of its own nor a single move: not an import, a typo.
            if (moves.isEmpty() && start.fen == Position().fen && bareFEN(text) == null && headers["FEN"] == null) {
                return null
            }
            return GameImport(start, moves, position, stopped)
        }

        /** One move, in either of the two ways they are written down. */
        private fun move(token: String, position: Position): Move? {
            position.move(san = token)?.let { return it }
            // UCI: e2e4, or e7e8q for a promotion.
            val uci = Move.fromUci(token) ?: return null
            return position.legalMoves().firstOrNull { it.matchesNotation(uci) }
        }

        /** `[Event "..."]` and friends. */
        private fun pgnHeaders(text: String): Map<String, String> {
            val headers = mutableMapOf<String, String>()
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) continue
                val body = trimmed.substring(1, trimmed.length - 1)
                val space = body.indexOf(' ')
                if (space < 0) continue
                headers[body.substring(0, space)] = body.substring(space + 1).trim('"')
            }
            return headers
        }

        /** A FEN pasted on its own, with no PGN around it. */
        private fun bareFEN(text: String): Position? {
            for (line in text.lines()) {
                val trimmed = line.trim()
                val fields = trimmed.split(' ').filter { it.isNotEmpty() }
                if (fields.size < 4 || '/' !in fields[0]) continue
                Position.fromFen(trimmed)?.let { return it }
            }
            return null
        }

        /**
         * The mainline move tokens, with headers, brace comments, variations,
         * numbering, glyphs and the result removed.
         */
        private fun tokens(text: String): List<String> {
            val stripped = StringBuilder()
            var braces = 0
            var brackets = 0
            var inHeader = false
            for (character in text) {
                when (character) {
                    '{' -> braces += 1
                    '}' -> braces = maxOf(0, braces - 1)
                    '(' -> brackets += 1
                    ')' -> brackets = maxOf(0, brackets - 1)
                    '[' -> inHeader = true
                    ']' -> inHeader = false
                    ';' -> braces += 1 // a semicolon comments out the rest of its line
                    '\n' -> {
                        braces = 0
                        stripped.append(' ')
                    }
                    else -> if (braces == 0 && brackets == 0 && !inHeader) stripped.append(character)
                }
            }
            return splitOnWhitespace(stripped).mapNotNull(::cleanToken)
        }

        private fun splitOnWhitespace(text: CharSequence): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            for (c in text) {
                if (c.isWhitespace()) {
                    if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                } else {
                    current.append(c)
                }
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }

        /** "12." and "12..." are numbering, and "12.e4" is numbering with a move stuck to it. */
        private fun cleanToken(raw: String): String? {
            var token = raw
            while (token.isNotEmpty() && (token[0].isNumber() || token[0] == '.')) {
                val dot = token.indexOf('.')
                if (dot >= 0 && token.substring(0, dot).all { it.isNumber() }) {
                    token = token.substring(dot + 1).trimStart('.')
                } else if (token.all { it.isNumber() || it == '.' || it == '-' || it == '/' }) {
                    return null // a move number, or a result like 1-0 (as on iOS, this also drops "0-0")
                } else {
                    break
                }
            }
            if (token.isEmpty() || token == "*" || token.startsWith("$")) return null
            if (token in results) return null
            return token
        }

        private val results = setOf("1-0", "0-1", "1/2-1/2", "½-½")

        /** Swift's `Character.isNumber`: any Unicode number, so "½" counts. */
        private fun Char.isNumber(): Boolean =
            category == CharCategory.DECIMAL_DIGIT_NUMBER || category == CharCategory.LETTER_NUMBER ||
                category == CharCategory.OTHER_NUMBER
    }
}
