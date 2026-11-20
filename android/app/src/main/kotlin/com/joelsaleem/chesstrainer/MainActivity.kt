package com.joelsaleem.chesstrainer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.joelsaleem.chesstrainer.ui.RootView
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = AppModel(FileProgressStore(File(filesDir, "progress.json")))
        setContent { RootView(app) }
    }
}
