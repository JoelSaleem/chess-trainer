package com.joelsaleem.chesstrainer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.joelsaleem.chesstrainer.engine.StockfishNative

// Placeholder until the root view lands (milestone M4).
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { Text(StockfishNative.engineInfo()) }
            }
        }
    }
}
