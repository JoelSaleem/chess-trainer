package com.joelsaleem.chesstrainer

/**
 * Where [AppModel] persists the serialized TrainingProgress. The platform supplies the impl:
 * a file on Android, localStorage/in-memory on web.
 */
interface ProgressStore {
    fun read(): String?
    fun write(text: String)
}
