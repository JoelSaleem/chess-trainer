package com.joelsaleem.chesstrainer.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joelsaleem.chesstrainer.AppModel
import com.joelsaleem.chesstrainer.SamplePuzzles
import com.joelsaleem.chesstrainer.training.L
import com.joelsaleem.chesstrainer.training.TrainingMode
import kotlinx.coroutines.launch

@Composable
fun TacticsScreen(app: AppModel, onBack: () -> Unit) {
    val model = remember { TacticsModel() }
    val scope = rememberCoroutineScope()
    // Tactics grants one completion per puzzle, not per attempt: replaying via Retry is free
    // practice and must not move the rating again. Resets only on a fresh puzzle (a new screen
    // entry today, a "next" button in M6), never on retry — mirrors iOS TacticsScreen.
    var hasCountedCompletion by remember { mutableStateOf(false) }
    model.onComplete = { solved, usedHint ->
        if (!hasCountedCompletion) {
            hasCountedCompletion = true
            model.puzzle?.let { p ->
                app.update {
                    it.record(
                        mode = TrainingMode.tactics, itemID = p.id, itemRating = p.rating,
                        correct = solved, themes = p.themes, usedHint = usedHint,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (model.puzzle == null) model.load(SamplePuzzles.all.first())
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Tactics", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        val prompt = when {
            model.isReplying -> "…"
            model.isFinished -> model.feedback ?: ""
            else -> "${L.color(model.orientation)} to play"
        }
        Text(prompt, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        if (model.puzzle != null) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ChessBoard(
                    position = model.position,
                    orientation = model.orientation,
                    legalDestinations = model.legalDestinations,
                    lastMove = model.lastMove,
                    enabled = !model.isFinished && !model.isReplying,
                    onMove = { from, to -> scope.launch { model.play(from, to) } },
                    modifier = Modifier.size(minOf(maxWidth, maxHeight)),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        if (!model.isFinished) model.feedback?.let { Text(it) }

        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = onBack) { Text("Back") }
            if (model.isFinished) {
                Spacer(Modifier.width(12.dp))
                Button(onClick = { model.retry() }) { Text("Retry") }
            }
        }
    }
}
