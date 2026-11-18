package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.Move
import com.joelsaleem.chesstrainer.core.Piece
import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.PieceKind
import com.joelsaleem.chesstrainer.core.Position
import com.joelsaleem.chesstrainer.core.Square
import com.joelsaleem.chesstrainer.core.isCheckmate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Turns a move into the reasons a coach would give for it. */
object MoveDescription {
    /**
     * Compare the positions either side of a move and name what it achieved.
     * Returns short clauses meant to be joined into one sentence.
     */
    fun clauses(
        before: PositionFeatures,
        after: PositionFeatures,
        move: Move,
        position: Position,
        resulting: Position,
    ): List<String> {
        val piece = position[move.from] ?: return emptyList()
        val mover = piece.color
        val them = mover.opponent
        val captured = capturedPiece(move, position)

        // Checkmate ends the game, so nothing else about the move matters.
        // Listing pawn structure beside "delivers checkmate" reads as noise.
        if (resulting.isCheckmate) {
            return if (captured != null)
                listOf(L.t("coach.mateCapturing", "delivers checkmate, capturing the %s", name(captured.kind)))
            else listOf(L.t("coach.mate", "delivers checkmate"))
        }

        val clauses = ArrayList<String>()

        when (move.kind) {
            Move.Kind.kingsideCastle -> clauses.add(L.t("coach.castlesKingside", "castles kingside, tucking the king away"))
            Move.Kind.queensideCastle -> clauses.add(L.t("coach.castlesQueenside", "castles queenside"))
            else -> {}
        }

        if (resulting.isCheck) clauses.add(L.t("coach.givesCheck", "gives check"))

        if (captured != null) {
            val gained = captured.kind.value
            val risked = piece.kind.value
            clauses.add(
                if (gained > risked + 50) L.t("coach.winsMaterial", "wins material — takes the %s", name(captured.kind))
                else L.t("coach.captures", "captures the %s", name(captured.kind))
            )
        }

        move.promotion?.let { clauses.add(L.t("coach.promotes", "promotes to a %s", name(it))) }

        if (piece.kind == PieceKind.rook) {
            val landed = after.heavyPieces.firstOrNull { it.square == move.to }
            val departed = before.heavyPieces.firstOrNull { it.square == move.from }
            if (landed?.onOpenFile == true && departed?.onOpenFile != true) {
                clauses.add(L.t("coach.takesOpenFile", "takes the open %s-file", fileName(move.to.file)))
            } else if (landed?.onHalfOpenFile == true && departed?.onHalfOpenFile != true) {
                clauses.add(L.t("coach.halfOpenFile", "puts the rook on the half-open %s-file", fileName(move.to.file)))
            }
            if (landed?.onSeventhRank == true && departed?.onSeventhRank != true) {
                clauses.add(L.t("coach.seventhRank", "lands the rook on the seventh rank"))
            }
            val stacked = after.heavyPieces.filter {
                it.color == mover && it.kind == PieceKind.rook && it.square.file == move.to.file
            }
            if (stacked.size > 1) clauses.add(L.t("coach.doublesRooks", "doubles the rooks"))
        }

        if (piece.kind == PieceKind.knight &&
            after.knights.firstOrNull { it.square == move.to }?.isOutpost == true
        ) {
            clauses.add(L.t("coach.outpost", "plants the knight on the %s outpost, where no pawn can dislodge it", "${move.to}"))
        }

        val ours = after.structure[mover]
        val wasOurs = before.structure[mover]
        if (ours != null && wasOurs != null && ours.passed.size > wasOurs.passed.size) {
            clauses.add(L.t("coach.passedPawn", "creates a passed pawn"))
        }

        val theirs = after.structure[them]
        val wasTheirs = before.structure[them]
        if (theirs != null && wasTheirs != null && theirs.isolated.size > wasTheirs.isolated.size) {
            clauses.add(L.t("coach.isolatesOpponent", "leaves the opponent with an isolated pawn"))
        }

        if (before.hasBishopPair[them] == true && after.hasBishopPair[them] == false) {
            clauses.add(L.t("coach.breaksBishopPair", "breaks up the enemy bishop pair"))
        }

        after.files.forEachIndexed { index, file ->
            if (file.isOpen && !before.files[index].isOpen) {
                clauses.add(L.t("coach.opensFile", "opens the %s-file", file.name))
            }
        }

        val nowMobility = after.mobility[mover]
        val wasMobility = before.mobility[mover]
        if (nowMobility != null && wasMobility != null && nowMobility - wasMobility >= 6) {
            clauses.add(L.t("coach.moreActivity", "sharply increases the activity of the pieces"))
        }

        val nowOpen = after.kingSafety[them]?.openFilesNearby
        val wasOpen = before.kingSafety[them]?.openFilesNearby
        if (nowOpen != null && wasOpen != null && nowOpen > wasOpen) {
            clauses.add(L.t("coach.opensKingLines", "pries open lines toward the enemy king"))
        }

        return clauses
    }

    /** A full sentence, or null when the move achieved nothing worth naming. */
    fun sentence(
        san: String,
        before: PositionFeatures,
        after: PositionFeatures,
        move: Move,
        position: Position,
        resulting: Position,
    ): String? {
        val parts = clauses(before, after, move, position, resulting)
        if (parts.isEmpty()) return null
        return "$san ${join(parts)}."
    }

    /**
     * Expands compact algebraic notation into a sentence that can be read by
     * someone who does not yet know that `Rxf1+` means a rook capture on f1
     * with check. SAN is retained first so the explanation also teaches the
     * notation rather than replacing it.
     */
    fun detailedSentence(san: String, move: Move, position: Position, resulting: Position): String? {
        val piece = position[move.from] ?: return null

        val side = L.color(piece.color)
        var sentence = when (move.kind) {
            Move.Kind.kingsideCastle -> L.t("coach.detailedCastleKingside", "%s — %s castles kingside", san, side)
            Move.Kind.queensideCastle -> L.t("coach.detailedCastleQueenside", "%s — %s castles queenside", san, side)
            else -> {
                val captured = capturedPiece(move, position)
                if (captured != null)
                    L.t(
                        "coach.detailedCapture",
                        "%s — %s moves the %s from %s to %s, capturing the %s",
                        san, side, name(piece.kind), "${move.from}", "${move.to}", name(captured.kind),
                    )
                else
                    L.t(
                        "coach.detailedMove",
                        "%s — %s moves the %s from %s to %s",
                        san, side, name(piece.kind), "${move.from}", "${move.to}",
                    )
            }
        }

        move.promotion?.let { sentence += L.t("coach.detailedPromotion", ", promoting to a %s", name(it)) }

        if (resulting.isCheckmate) sentence += L.t("coach.detailedCheckmate", ", delivering checkmate")
        else if (resulting.isCheck) sentence += L.t("coach.detailedCheck", ", giving check")
        else if (position.isCheck) sentence += L.t("coach.detailedEscape", ", escaping the check")

        return "$sentence."
    }

    /** A static read of a position, for the judgement exercises. */
    fun summary(features: PositionFeatures): List<String> {
        val points = ArrayList<String>()

        val openFiles = features.files.filter { it.isOpen }.map { it.name }
        if (openFiles.isNotEmpty()) {
            points.add(
                if (openFiles.size > 1) L.t("summary.openFiles", "Open files: %s.", openFiles.joinToString(", "))
                else L.t("summary.openFile", "Open file: %s.", openFiles.joinToString(", "))
            )
        }

        for (color in listOf(PieceColor.white, PieceColor.black)) {
            val side = L.color(color)

            val activeRooks = features.heavyPieces.filter {
                it.color == color && it.kind == PieceKind.rook && (it.onOpenFile || it.onHalfOpenFile)
            }
            if (activeRooks.isNotEmpty()) {
                val squares = activeRooks.joinToString(" and ") { "${it.square}" }
                points.add(
                    if (activeRooks.size > 1) L.t("summary.rooksOpenLines", "%s rooks on %s control open lines.", side, squares)
                    else L.t("summary.rookOpenLines", "%s rook on %s controls open lines.", side, squares)
                )
            }

            val outposts = features.knights.filter { it.color == color && it.isOutpost }
            if (outposts.isNotEmpty()) {
                points.add(
                    L.t("summary.secureKnight", "%s has a secure knight on %s.",
                        side, outposts.joinToString(", ") { "${it.square}" })
                )
            }

            features.structure[color]?.let { structure ->
                if (structure.passed.isNotEmpty()) {
                    points.add(
                        L.t("summary.passedPawn", "%s has a passed pawn on %s.",
                            side, structure.passed.joinToString(", ") { "$it" })
                    )
                }
                if (structure.isolated.isNotEmpty()) {
                    val squares = structure.isolated.joinToString(", ") { "$it" }
                    points.add(
                        if (structure.isolated.size > 1) L.t("summary.isolatedPawns", "%s's pawns on %s are isolated.", side, squares)
                        else L.t("summary.isolatedPawn", "%s's pawn on %s is isolated.", side, squares)
                    )
                }
                if (structure.backward.isNotEmpty()) {
                    points.add(
                        L.t("summary.backwardPawn", "%s has a backward pawn on %s.",
                            side, structure.backward.joinToString(", ") { "$it" })
                    )
                }
            }
        }

        if (features.hasBishopPair[PieceColor.white] != features.hasBishopPair[PieceColor.black]) {
            points.add(
                L.t("summary.bishopPair", "%s holds the bishop pair.",
                    L.color(if (features.hasBishopPair[PieceColor.white] == true) PieceColor.white else PieceColor.black))
            )
        }

        val balance = features.materialBalance
        if (abs(balance) >= 75) {
            val pawns = abs(balance).toDouble() / 100
            points.add(
                L.t("summary.material", "Material: %s is up roughly %s points.",
                    L.color(if (balance > 0) PieceColor.white else PieceColor.black),
                    String.format(Locale.ROOT, "%.2f", pawns))
            )
        } else {
            points.add(L.t("summary.materialLevel", "Material is level."))
        }

        val white = features.mobility[PieceColor.white]
        val black = features.mobility[PieceColor.black]
        if (white != null && black != null && abs(white - black) >= 6) {
            points.add(
                L.t("summary.moreActive", "%s has noticeably more active pieces (%d versus %d legal moves).",
                    L.color(if (white > black) PieceColor.white else PieceColor.black), max(white, black), min(white, black))
            )
        }

        return points
    }

    // MARK: - Helpers

    fun capturedPiece(move: Move, position: Position): Piece? {
        if (move.kind == Move.Kind.enPassant) return position[Square.of(move.to.file, move.from.rank)]
        return position[move.to]
    }

    fun name(kind: PieceKind): String = when (kind) {
        PieceKind.pawn -> L.t("piece.pawn", "pawn")
        PieceKind.knight -> L.t("piece.knight", "knight")
        PieceKind.bishop -> L.t("piece.bishop", "bishop")
        PieceKind.rook -> L.t("piece.rook", "rook")
        PieceKind.queen -> L.t("piece.queen", "queen")
        PieceKind.king -> L.t("piece.king", "king")
    }

    fun fileName(index: Int): String = ('a' + index).toString()

    fun join(clauses: List<String>): String {
        if (clauses.size <= 1) return clauses.firstOrNull() ?: ""
        return L.t("coach.joinClauses", "%s and %s", clauses.dropLast(1).joinToString(", "), clauses.last())
    }
}
