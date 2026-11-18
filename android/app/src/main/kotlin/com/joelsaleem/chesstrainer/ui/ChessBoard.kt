package com.joelsaleem.chesstrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joelsaleem.chesstrainer.core.Piece
import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.PieceKind
import com.joelsaleem.chesstrainer.core.Position
import com.joelsaleem.chesstrainer.core.Square

// ponytail: the lamplight square colours, hardcoded. The full BoardStyle palette + LightTone
// shading (deferred from Appearance) and piece-art image sets land with the settings UI (M5);
// Unicode glyphs need no assets, so the board works now.
private val lightSquare = Color(0xFFE0BB8E)
private val darkSquare = Color(0xFF80684D)
private val selectedTint = Color(0x9957C7A8)
private val lastMoveTint = Color(0x80E6C64B)

/** The flat 2D board: renders a position and reports a tapped from→to move. */
@Composable
fun ChessBoard(
    position: Position,
    orientation: PieceColor = PieceColor.white,
    legalDestinations: Map<Square, List<Square>> = emptyMap(),
    lastMove: Pair<Square, Square>? = null,
    enabled: Boolean = true,
    onMove: (from: Square, to: Square) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<Square?>(null) }

    fun tap(square: Square) {
        if (!enabled) return
        val from = selected
        if (from == null) {
            if (position[square] != null) selected = square
            return
        }
        if (square == from) {
            selected = null
            return
        }
        // Tapping another of your own pieces re-selects rather than tries an illegal move.
        val moving = position[from]
        val target = position[square]
        if (target != null && moving != null && target.color == moving.color) {
            selected = square
            return
        }
        selected = null
        onMove(from, square)
    }

    BoxWithConstraints(modifier.aspectRatio(1f)) {
        Column(Modifier.fillMaxSize()) {
            for (row in 0 until 8) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (col in 0 until 8) {
                        val file = if (orientation == PieceColor.white) col else 7 - col
                        val rank = if (orientation == PieceColor.white) 7 - row else row
                        val square = Square.of(file, rank)
                        SquareCell(
                            piece = position[square],
                            isDark = (file + rank) % 2 == 0,
                            isSelected = selected == square,
                            isLastMove = lastMove != null && (square == lastMove.first || square == lastMove.second),
                            isLegalTarget = selected?.let { legalDestinations[it]?.contains(square) } == true,
                            onClick = { tap(square) },
                            modifier = Modifier.weight(1f).fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SquareCell(
    piece: Piece?,
    isDark: Boolean,
    isSelected: Boolean,
    isLastMove: Boolean,
    isLegalTarget: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(if (isDark) darkSquare else lightSquare)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isLastMove) Box(Modifier.fillMaxSize().background(lastMoveTint))
        if (isSelected) Box(Modifier.fillMaxSize().background(selectedTint))
        if (piece != null) {
            Text(
                text = glyph(piece.kind),
                style = TextStyle(fontSize = 34.sp),
                color = if (piece.color == PieceColor.white) Color.White else Color.Black,
            )
        }
        if (isLegalTarget) {
            Box(
                Modifier.size(14.dp).clip(CircleShape)
                    .background(if (piece != null) Color(0x80D13B3B) else Color(0x66000000)),
            )
        }
    }
}

// Filled glyphs for both sides, tinted by colour — the legible trick for a glyph board.
private fun glyph(kind: PieceKind): String = when (kind) {
    PieceKind.king -> "♚"
    PieceKind.queen -> "♛"
    PieceKind.rook -> "♜"
    PieceKind.bishop -> "♝"
    PieceKind.knight -> "♞"
    PieceKind.pawn -> "♟"
}
