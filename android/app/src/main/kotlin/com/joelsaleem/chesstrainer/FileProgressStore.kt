package com.joelsaleem.chesstrainer

import java.io.File

/** Android [ProgressStore]: a single JSON file in filesDir. */
class FileProgressStore(private val file: File) : ProgressStore {
    override fun read(): String? = if (file.isFile) file.readText() else null
    override fun write(text: String) {
        file.writeText(text)
    }
}
