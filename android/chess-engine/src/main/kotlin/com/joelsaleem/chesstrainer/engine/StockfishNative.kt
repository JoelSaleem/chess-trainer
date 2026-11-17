package com.joelsaleem.chesstrainer.engine

/**
 * Thin JNI surface over the flat C Stockfish bridge (sfbridge.h).
 *
 * The engine is an opaque native handle (a `Long`). Search output arrives on
 * Stockfish's own search thread via [SearchListener]; callers must keep the
 * listener referenced for the duration of the search.
 */
object StockfishNative {
    init {
        System.loadLibrary("stockfish")
    }

    /** Receives one search's output, called from Stockfish's search thread. */
    interface SearchListener {
        fun onInfo(
            depth: Int,
            selDepth: Int,
            multiPV: Int,
            scoreCp: Int,
            scoreMate: Int,
            isMate: Boolean,
            nodes: Long,
            timeMs: Long,
            pv: String,
        )

        /** Final callback of a search. [bestMove] is null when there is none. */
        fun onBestMove(bestMove: String?, ponder: String?)
    }

    external fun engineInfo(): String

    /** Initialise global tables. Safe to call more than once; must precede [create]. */
    external fun globalInit()

    /** Create an engine, returning its handle (0 on failure). */
    external fun create(): Long

    external fun destroy(handle: Long)

    /** Load both networks from explicit paths. False if either file is unreadable. */
    external fun loadNetworks(handle: Long, bigPath: String, smallPath: String): Boolean

    external fun setOption(handle: Long, name: String, value: String)

    external fun newGame(handle: Long)

    /** Set the position from a FEN. False if the FEN is unusable. */
    external fun setPosition(handle: Long, fen: String): Boolean

    /**
     * Start searching. Non-blocking; [listener] is called from the search thread.
     * Pass 0 for a limit that should not apply; the search stops at whichever of
     * [depth]/[movetimeMs] is reached first.
     */
    external fun go(handle: Long, depth: Int, movetimeMs: Int, listener: SearchListener)

    external fun stop(handle: Long)

    external fun waitForSearch(handle: Long)
}
