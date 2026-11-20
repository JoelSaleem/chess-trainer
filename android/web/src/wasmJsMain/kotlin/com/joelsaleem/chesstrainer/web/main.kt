package com.joelsaleem.chesstrainer.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.joelsaleem.chesstrainer.AppModel
import com.joelsaleem.chesstrainer.ProgressStore
import com.joelsaleem.chesstrainer.ui.RootView
import kotlinx.browser.document

// ponytail: in-memory store — progress is lost on reload. Swap for a localStorage-backed
// ProgressStore when web persistence matters.
private class InMemoryProgressStore : ProgressStore {
    private var data: String? = null
    override fun read(): String? = data
    override fun write(text: String) {
        data = text
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val app = AppModel(InMemoryProgressStore())
    ComposeViewport(document.body!!) {
        RootView(app)
    }
}
