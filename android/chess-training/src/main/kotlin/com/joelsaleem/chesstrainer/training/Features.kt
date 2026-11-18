package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.Piece
import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.PieceKind
import com.joelsaleem.chesstrainer.core.Position
import com.joelsaleem.chesstrainer.core.Square
import com.joelsaleem.chesstrainer.core.legalMoves
import kotlin.math.abs

/**
 * Positional features of a position — open files, rook placement, outposts, pawn
 * structure, king safety: the vocabulary a coach would use, which a centipawn
 * number is not.
 */
class PositionFeatures(position: Position) {
    data class FileInfo(val index: Int, val whitePawns: Int, val blackPawns: Int) {
        val name: String get() = ('a' + index).toString()
        val isOpen: Boolean get() = whitePawns == 0 && blackPawns == 0

        /** Half-open for the side that has no pawn on it. */
        val halfOpenFor: PieceColor?
            get() = when {
                whitePawns == 0 && blackPawns > 0 -> PieceColor.white
                blackPawns == 0 && whitePawns > 0 -> PieceColor.black
                else -> null
            }
    }

    data class HeavyPiece(
        val square: Square,
        val color: PieceColor,
        val kind: PieceKind,
        val onOpenFile: Boolean,
        val onHalfOpenFile: Boolean,
        val onSeventhRank: Boolean,
    )

    data class Knight(val square: Square, val color: PieceColor, val isOutpost: Boolean)

    data class PawnStructure(
        val isolated: List<Square>,
        val doubled: List<Square>,
        val passed: List<Square>,
        val backward: List<Square>,
        val count: Int,
    )

    data class KingSafety(
        val square: Square?,
        /** Pawns shielding the king, counting those directly in front double. */
        val shield: Double,
        val openFilesNearby: Int,
        val hasCastled: Boolean,
    )

    enum class Phase { opening, middlegame, endgame }

    val files: List<FileInfo>
    val heavyPieces: List<HeavyPiece>
    val knights: List<Knight>
    val structure: Map<PieceColor, PawnStructure>
    val hasBishopPair: Map<PieceColor, Boolean>
    val material: Map<PieceColor, Int>
    val kingSafety: Map<PieceColor, KingSafety>
    val mobility: Map<PieceColor, Int>
    val phase: Phase

    /** Material in centipawns, positive when White is ahead. */
    val materialBalance: Int get() = (material[PieceColor.white] ?: 0) - (material[PieceColor.black] ?: 0)

    init {
        val pawnsByFile: Map<PieceColor, Array<MutableList<Square>>> = mapOf(
            PieceColor.white to Array(8) { mutableListOf<Square>() },
            PieceColor.black to Array(8) { mutableListOf<Square>() },
        )
        val pieces = ArrayList<Pair<Square, Piece>>()

        for (index in 0 until 64) {
            val square = Square(index)
            val piece = position[square] ?: continue
            pieces.add(square to piece)
            if (piece.kind == PieceKind.pawn) pawnsByFile.getValue(piece.color)[square.file].add(square)
        }

        files = (0 until 8).map { file ->
            FileInfo(
                index = file,
                whitePawns = pawnsByFile.getValue(PieceColor.white)[file].size,
                blackPawns = pawnsByFile.getValue(PieceColor.black)[file].size,
            )
        }

        val fileTable = files
        heavyPieces = pieces.mapNotNull { (square, piece) ->
            if (piece.kind != PieceKind.rook && piece.kind != PieceKind.queen) return@mapNotNull null
            val file = fileTable[square.file]
            HeavyPiece(
                square = square,
                color = piece.color,
                kind = piece.kind,
                onOpenFile = file.isOpen,
                onHalfOpenFile = file.halfOpenFor == piece.color,
                onSeventhRank = piece.kind == PieceKind.rook && square.relativeRank(piece.color) == 7,
            )
        }

        knights = pieces.mapNotNull { (square, piece) ->
            if (piece.kind != PieceKind.knight) return@mapNotNull null
            Knight(square, piece.color, isOutpost(square, piece.color, pieces))
        }

        structure = mapOf(
            PieceColor.white to pawnStructure(PieceColor.white, pawnsByFile, pieces),
            PieceColor.black to pawnStructure(PieceColor.black, pawnsByFile, pieces),
        )

        val bishops = mutableMapOf(PieceColor.white to 0, PieceColor.black to 0)
        val materialCount = mutableMapOf(PieceColor.white to 0, PieceColor.black to 0)
        for ((_, piece) in pieces) {
            if (piece.kind == PieceKind.bishop) bishops[piece.color] = bishops.getValue(piece.color) + 1
            materialCount[piece.color] = materialCount.getValue(piece.color) + piece.kind.value
        }
        hasBishopPair = mapOf(
            PieceColor.white to (bishops.getValue(PieceColor.white) >= 2),
            PieceColor.black to (bishops.getValue(PieceColor.black) >= 2),
        )
        material = materialCount

        kingSafety = mapOf(
            PieceColor.white to kingSafety(PieceColor.white, pieces, fileTable),
            PieceColor.black to kingSafety(PieceColor.black, pieces, fileTable),
        )

        mobility = mapOf(
            PieceColor.white to mobility(PieceColor.white, position),
            PieceColor.black to mobility(PieceColor.black, position),
        )

        val heavy = pieces.count { "qrbn".contains(it.second.kind.letter) }
        phase = if (heavy >= 12) Phase.opening else if (heavy >= 6) Phase.middlegame else Phase.endgame
    }

    companion object {
        /** Squares a pawn of this colour attacks. */
        fun pawnAttacks(square: Square, color: PieceColor): List<Square> {
            val rank = square.rank + if (color == PieceColor.white) 1 else -1
            if (rank < 0 || rank >= 8) return emptyList()
            return listOf(square.file - 1, square.file + 1)
                .filter { it in 0..7 }
                .map { Square.of(it, rank) }
        }

        /**
         * A knight is on an outpost when it stands in enemy territory, a friendly
         * pawn defends it, and no enemy pawn can ever come to chase it away.
         */
        fun isOutpost(square: Square, color: PieceColor, pieces: List<Pair<Square, Piece>>): Boolean {
            val rank = square.relativeRank(color)
            if (rank < 4 || rank > 6) return false

            val defended = pieces.any { (candidateSquare, piece) ->
                piece.kind == PieceKind.pawn && piece.color == color &&
                    pawnAttacks(candidateSquare, color).contains(square)
            }
            if (!defended) return false

            val enemy = color.opponent
            val canBeChased = pieces.any { (candidateSquare, piece) ->
                if (piece.kind != PieceKind.pawn || piece.color != enemy) return@any false
                if (abs(candidateSquare.file - square.file) != 1) return@any false
                // Only a pawn that has not yet passed the square can still attack it.
                if (color == PieceColor.white) candidateSquare.rank > square.rank
                else candidateSquare.rank < square.rank
            }
            return !canBeChased
        }

        fun pawnStructure(
            color: PieceColor,
            pawnsByFile: Map<PieceColor, Array<MutableList<Square>>>,
            pieces: List<Pair<Square, Piece>>,
        ): PawnStructure {
            val own = pawnsByFile.getValue(color)
            val enemy = pawnsByFile.getValue(color.opponent)
            val isolated = ArrayList<Square>()
            val doubled = ArrayList<Square>()
            val passed = ArrayList<Square>()
            val backward = ArrayList<Square>()

            for (file in 0 until 8) {
                if (own[file].isEmpty()) continue
                val neighbours = (if (file > 0) own[file - 1].size else 0) + (if (file < 7) own[file + 1].size else 0)
                if (neighbours == 0) isolated.addAll(own[file])
                if (own[file].size > 1) doubled.addAll(own[file])

                for (square in own[file]) {
                    val ahead = { list: List<Square> ->
                        list.any { if (color == PieceColor.white) it.rank > square.rank else it.rank < square.rank }
                    }
                    val blocked = ahead(enemy[file]) ||
                        (file > 0 && ahead(enemy[file - 1])) ||
                        (file < 7 && ahead(enemy[file + 1]))
                    if (!blocked) passed.add(square)

                    // Backward: no friendly pawn on an adjacent file is level with or behind
                    // it, and an enemy pawn covers the square in front.
                    val supported = listOf(
                        if (file > 0) own[file - 1] else emptyList(),
                        if (file < 7) own[file + 1] else emptyList(),
                    ).any { list ->
                        list.any { if (color == PieceColor.white) it.rank <= square.rank else it.rank >= square.rank }
                    }
                    val frontRank = square.rank + if (color == PieceColor.white) 1 else -1
                    if (!supported && frontRank in 0..7) {
                        val front = Square.of(file, frontRank)
                        val contested = pieces.any { (candidateSquare, piece) ->
                            piece.kind == PieceKind.pawn && piece.color != color &&
                                pawnAttacks(candidateSquare, piece.color).contains(front)
                        }
                        if (contested) backward.add(square)
                    }
                }
            }

            return PawnStructure(isolated, doubled, passed, backward, own.sumOf { it.size })
        }

        fun kingSafety(
            color: PieceColor,
            pieces: List<Pair<Square, Piece>>,
            files: List<FileInfo>,
        ): KingSafety {
            val king = pieces.firstOrNull { it.second == Piece(color, PieceKind.king) }?.first
                ?: return KingSafety(null, 0.0, 0, false)

            var shield = 0.0
            for (fileOffset in -1..1) {
                val file = king.file + fileOffset
                if (file < 0 || file >= 8) continue
                for (step in 1..2) {
                    val rank = king.rank + if (color == PieceColor.white) step else -step
                    if (rank < 0 || rank >= 8) continue
                    val square = Square.of(file, rank)
                    if (pieces.any { it.first == square && it.second == Piece(color, PieceKind.pawn) }) {
                        shield += if (step == 1) 1.0 else 0.5
                        break
                    }
                }
            }

            var openNearby = 0
            for (fileOffset in -1..1) {
                val file = king.file + fileOffset
                if (file < 0 || file >= 8) continue
                if (files[file].isOpen || files[file].halfOpenFor == color.opponent) openNearby++
            }

            return KingSafety(
                square = king,
                shield = shield,
                openFilesNearby = openNearby,
                hasCastled = king.relativeRank(color) == 1 && (king.file <= 2 || king.file >= 6),
            )
        }

        /**
         * Legal moves for a colour, obtained by handing that side the move. Returns 0
         * when that would be an illegal position (the other side is giving check).
         */
        fun mobility(color: PieceColor, position: Position): Int {
            if (position.sideToMove == color) return position.legalMoves().size

            val fields = position.fen.split(" ").toMutableList()
            if (fields.size < 4) return 0
            fields[1] = if (color == PieceColor.white) "w" else "b"
            fields[3] = "-" // an en-passant square cannot be inherited by the other side
            val probe = Position.fromFen(fields.joinToString(" ")) ?: return 0
            return probe.legalMoves().size
        }
    }
}
