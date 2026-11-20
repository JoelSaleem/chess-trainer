package com.joelsaleem.chesstrainer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import com.joelsaleem.chesstrainer.training.TrainingProgress
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The app's shared state: the player's [TrainingProgress], loaded once and persisted on change.
 *
 * ponytail: the deferred iOS ProgressStorage, now shared-UI-layer — a small JSON blob rewritten whole
 * on change via [ProgressStore] (a file on Android, localStorage/in-memory on web). The heavy game
 * notation lives in GameHistory's own store, not here. [neverEqualPolicy] because TrainingProgress
 * mutates in place (its record/settle methods edit the held instance), so a set must notify Compose
 * readers even though the reference is unchanged.
 */
class AppModel(private val store: ProgressStore) {
    private val json = Json { ignoreUnknownKeys = true }

    var progress by mutableStateOf(load(), neverEqualPolicy())
        private set

    /** Mutate the progress and persist it, notifying Compose readers. */
    fun update(mutate: (TrainingProgress) -> Unit) {
        mutate(progress)
        progress = progress
        save()
    }

    private fun load(): TrainingProgress = runCatching {
        store.read()?.let { json.decodeFromString<TrainingProgress>(it) } ?: TrainingProgress()
    }.getOrDefault(TrainingProgress())

    private fun save() {
        runCatching { store.write(json.encodeToString(progress)) }
    }
}
