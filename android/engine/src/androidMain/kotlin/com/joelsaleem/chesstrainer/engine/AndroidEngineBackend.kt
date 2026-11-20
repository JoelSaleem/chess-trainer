package com.joelsaleem.chesstrainer.engine

/** [EngineBackend] backed by the in-process JNI Stockfish (chess-engine's [StockfishNative]). */
class AndroidEngineBackend : EngineBackend {
    override fun engineInfo(): String = StockfishNative.engineInfo()
    override fun globalInit() = StockfishNative.globalInit()
    override fun create(): Long = StockfishNative.create()
    override fun destroy(handle: Long) = StockfishNative.destroy(handle)
    override fun loadNetworks(handle: Long, bigPath: String, smallPath: String): Boolean =
        StockfishNative.loadNetworks(handle, bigPath, smallPath)
    override fun setOption(handle: Long, name: String, value: String) =
        StockfishNative.setOption(handle, name, value)
    override fun newGame(handle: Long) = StockfishNative.newGame(handle)
    override fun setPosition(handle: Long, fen: String): Boolean =
        StockfishNative.setPosition(handle, fen)

    override fun go(handle: Long, depth: Int, movetimeMs: Int, listener: EngineBackend.SearchListener) =
        StockfishNative.go(handle, depth, movetimeMs, object : StockfishNative.SearchListener {
            override fun onInfo(
                depth: Int, selDepth: Int, multiPV: Int, scoreCp: Int, scoreMate: Int,
                isMate: Boolean, nodes: Long, timeMs: Long, pv: String,
            ) = listener.onInfo(depth, selDepth, multiPV, scoreCp, scoreMate, isMate, nodes, timeMs, pv)

            override fun onBestMove(bestMove: String?, ponder: String?) =
                listener.onBestMove(bestMove, ponder)
        })

    override fun stop(handle: Long) = StockfishNative.stop(handle)
    override fun waitForSearch(handle: Long) = StockfishNative.waitForSearch(handle)
}
