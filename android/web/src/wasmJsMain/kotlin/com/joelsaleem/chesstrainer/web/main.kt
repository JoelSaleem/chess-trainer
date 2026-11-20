package com.joelsaleem.chesstrainer.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import com.joelsaleem.chesstrainer.core.Piece
import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.PieceKind
import com.joelsaleem.chesstrainer.core.Position
import com.joelsaleem.chesstrainer.core.Square
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        MaterialTheme {
            App()
        }
    }
}

/** First web screen: a board rendered from chess-core's position model. */
@Composable
fun App() {
    val position = remember { Position() }
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Chess Trainer — web", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Board(position)
    }
}

@Composable
private fun Board(position: Position) {
    val lightSquare = Color(0xFFEEEED2)
    val darkSquare = Color(0xFF769656)
    Column {
        for (rank in 7 downTo 0) {
            Row {
                for (file in 0..7) {
                    val square = Square(rank * 8 + file)
                    val isLight = (rank + file) % 2 == 1
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(if (isLight) lightSquare else darkSquare),
                        contentAlignment = Alignment.Center,
                    ) {
                        position[square]?.let { Text(glyph(it), fontSize = 38.sp) }
                    }
                }
            }
        }
    }
}

private fun glyph(piece: Piece): String = when (piece.color) {
    PieceColor.white -> when (piece.kind) {
        PieceKind.king -> "♔"
        PieceKind.queen -> "♕"
        PieceKind.rook -> "♖"
        PieceKind.bishop -> "♗"
        PieceKind.knight -> "♘"
        PieceKind.pawn -> "♙"
    }
    PieceColor.black -> when (piece.kind) {
        PieceKind.king -> "♚"
        PieceKind.queen -> "♛"
        PieceKind.rook -> "♜"
        PieceKind.bishop -> "♝"
        PieceKind.knight -> "♞"
        PieceKind.pawn -> "♟"
    }
}
