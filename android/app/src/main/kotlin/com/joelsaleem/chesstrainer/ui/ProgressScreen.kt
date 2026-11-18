package com.joelsaleem.chesstrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joelsaleem.chesstrainer.AppModel
import com.joelsaleem.chesstrainer.training.LadderRung
import com.joelsaleem.chesstrainer.training.TrainingMode

@Composable
fun ProgressScreen(app: AppModel, onBack: () -> Unit) {
    val progress = app.progress
    val rung = LadderRung.current(progress.overallRating)

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Progress", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text("${progress.overallRating}", style = MaterialTheme.typography.displaySmall)
        Text(rung.name, style = MaterialTheme.typography.titleMedium)
        Text(rung.focus, style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(20.dp))
        RatingRow("Tactics", progress.rating(TrainingMode.tactics))
        RatingRow("Positional", progress.rating(TrainingMode.positional))
        RatingRow("Endgames", progress.rating(TrainingMode.endgame))

        Spacer(Modifier.height(20.dp))
        Text("Current streak: ${progress.currentStreak}", style = MaterialTheme.typography.bodyLarge)
        Text("Best streak: ${progress.bestStreak}", style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.height(28.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun RatingRow(label: String, rating: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text("$rating", style = MaterialTheme.typography.bodyLarge)
    }
}
