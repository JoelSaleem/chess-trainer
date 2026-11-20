package com.joelsaleem.chesstrainer.engine

/**
 * STUB. The real web backend drives a stockfish.wasm Web Worker over UCI and parses
 * `info`/`bestmove` lines back into [EngineBackend.SearchListener].
 */
// TODO(human): real stockfish.wasm / UCI / Web Worker driver.
class WasmEngineBackend : EngineBackend {
    override fun engineInfo(): String = TODO("web engine backend")
    override fun globalInit(): Unit = TODO("web engine backend")
    override fun create(): Long = TODO("web engine backend")
    override fun destroy(handle: Long): Unit = TODO("web engine backend")
    override fun loadNetworks(handle: Long, bigPath: String, smallPath: String): Boolean = TODO("web engine backend")
    override fun setOption(handle: Long, name: String, value: String): Unit = TODO("web engine backend")
    override fun newGame(handle: Long): Unit = TODO("web engine backend")
    override fun setPosition(handle: Long, fen: String): Boolean = TODO("web engine backend")
    override fun go(handle: Long, depth: Int, movetimeMs: Int, listener: EngineBackend.SearchListener): Unit =
        TODO("web engine backend")
    override fun stop(handle: Long): Unit = TODO("web engine backend")
    override fun waitForSearch(handle: Long): Unit = TODO("web engine backend")
}
