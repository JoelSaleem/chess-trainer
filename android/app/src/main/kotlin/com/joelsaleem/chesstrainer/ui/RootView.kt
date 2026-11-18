package com.joelsaleem.chesstrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ponytail: state-based navigation for two screens — no nav library for a home + one screen.
// A real nav graph lands as the screen count grows (M5/M6).
private enum class Screen { Home, Tactics }

@Composable
fun RootView() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            var screen by remember { mutableStateOf(Screen.Home) }
            when (screen) {
                Screen.Home -> HomeScreen(onTactics = { screen = Screen.Tactics })
                Screen.Tactics -> TacticsScreen(onBack = { screen = Screen.Home })
            }
        }
    }
}

@Composable
private fun HomeScreen(onTactics: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Brass Pawn", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onTactics) { Text("Tactics") }
    }
}
