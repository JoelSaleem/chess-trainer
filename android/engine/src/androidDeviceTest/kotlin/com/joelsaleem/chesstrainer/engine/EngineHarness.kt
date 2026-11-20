package com.joelsaleem.chesstrainer.engine

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Builds a ready Stockfish engine for instrumented tests: copies the bundled
 * networks out of assets into the app's files dir (sf_load_networks needs real
 * file paths, not asset handles) and loads them. Mirrors iOS StockfishTests.makeEngine.
 */
object EngineHarness {
    suspend fun makeEngine(): StockfishEngine {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "networks").apply { mkdirs() }
        val assets = context.assets

        // `nn-` is Stockfish's own prefix; only its two nets live here.
        val names = assets.list("networks")
            ?.filter { it.startsWith("nn-") && it.endsWith(".nnue") }
            ?: emptyList()
        require(names.size == 2) { "expected 2 Stockfish networks in assets/networks, found $names" }

        val files = names.map { name ->
            val out = File(dir, name)
            if (!out.exists() || out.length() == 0L) {
                assets.open("networks/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            out
        }.sortedByDescending { it.length() }  // the big network is the larger file

        val engine = StockfishEngine(AndroidEngineBackend())
        engine.loadNetworks(files[0].absolutePath, files[1].absolutePath)
        engine.setOption("Threads", "1")
        engine.setOption("Hash", "64")
        return engine
    }
}
