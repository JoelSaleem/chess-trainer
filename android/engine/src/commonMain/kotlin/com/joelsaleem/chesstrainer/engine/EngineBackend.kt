package com.joelsaleem.chesstrainer.engine

/**
 * The platform engine surface [StockfishEngine] drives: in-process JNI on Android,
 * a stockfish.wasm Web Worker (UCI) on web. The engine is an opaque handle (Long);
 * search output arrives via [SearchListener].
 */
interface EngineBackend {
    fun engineInfo(): String
    fun globalInit()
    fun create(): Long
    fun destroy(handle: Long)
    fun loadNetworks(handle: Long, bigPath: String, smallPath: String): Boolean
    fun setOption(handle: Long, name: String, value: String)
    fun newGame(handle: Long)
    fun setPosition(handle: Long, fen: String): Boolean
    fun go(handle: Long, depth: Int, movetimeMs: Int, listener: SearchListener)
    fun stop(handle: Long)
    fun waitForSearch(handle: Long)

    interface SearchListener {
        fun onInfo(
            depth: Int, selDepth: Int, multiPV: Int, scoreCp: Int, scoreMate: Int,
            isMate: Boolean, nodes: Long, timeMs: Long, pv: String,
        )
        fun onBestMove(bestMove: String?, ponder: String?)
    }
}
