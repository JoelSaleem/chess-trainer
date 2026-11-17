package com.joelsaleem.chesstrainer.engine

import com.joelsaleem.chesstrainer.core.MoveValues
import com.joelsaleem.chesstrainer.core.Position
import com.joelsaleem.chesstrainer.core.legalMoves
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Stockfish, driven in-process.
 *
 * A UCI engine holds exactly one position and one search, so every operation is
 * serialised through [searchMutex] — a correctness requirement, not an
 * optimisation: sending a new position while a search is unwinding corrupts the
 * single callback context the C side holds. Mirrors the iOS `actor StockfishEngine`.
 */
class StockfishEngine : AutoCloseable {
    private val handle: Long
    private var isReady = false
    private val searchMutex = Mutex()

    init {
        StockfishNative.globalInit()
        handle = StockfishNative.create()
        require(handle != 0L) { "Stockfish could not be created" }
    }

    /** Stockfish limits strength through UCI_Elo, a real rating limiter, so the whole ladder is available. */
    val capabilities: EngineCapabilities =
        EngineCapabilities(name = shortDescription, limitsStrength = true)

    /** Point the engine at its two network files. Must succeed before searching. */
    suspend fun loadNetworks(bigPath: String, smallPath: String) = searchMutex.withLock {
        if (!StockfishNative.loadNetworks(handle, bigPath, smallPath)) {
            throw EngineException.NetworksMissing(bigPath)
        }
        isReady = true
    }

    suspend fun setOption(name: String, value: String) = searchMutex.withLock {
        StockfishNative.setOption(handle, name, value)
    }

    suspend fun newGame() = searchMutex.withLock { StockfishNative.newGame(handle) }

    /** Search a position. Both a depth and a time cap: "this deep, but never longer than this". */
    suspend fun analyse(fen: String, depth: Int = 14, movetimeMs: Int = 0, multiPV: Int = 1): Analysis =
        searchMutex.withLock { analyseLocked(fen, depth, movetimeMs, multiPV) }

    /** Search under a named budget, which is how the app asks for all of them. */
    suspend fun analyse(fen: String, budget: SearchBudget, multiPV: Int = 1): Analysis =
        analyse(fen, budget.depth, budget.movetimeMs, multiPV)

    /**
     * Pick a move, optionally at a limited strength. Faithful to iOS, including that
     * [analyse] re-sets UCI_LimitStrength to "false" — so today the elo limit does
     * not take hold during the search. Kept as-is rather than silently diverging.
     */
    suspend fun chooseMove(fen: String, elo: Int? = null, depth: Int = 12, movetimeMs: Int = 0): String? =
        searchMutex.withLock {
            if (elo != null) {
                StockfishNative.setOption(handle, "UCI_LimitStrength", "true")
                StockfishNative.setOption(handle, "UCI_Elo", elo.coerceIn(1320, 3190).toString())
            } else {
                StockfishNative.setOption(handle, "UCI_LimitStrength", "false")
            }
            val analysis = analyseLocked(fen, depth, movetimeMs, 1)
            StockfishNative.setOption(handle, "UCI_LimitStrength", "false")
            analysis.bestMove
        }

    suspend fun chooseMove(fen: String, elo: Int? = null, budget: SearchBudget): String? =
        chooseMove(fen, elo, budget.depth, budget.movetimeMs)

    /**
     * Evaluate every legal move at once: one wide-MultiPV search rather than a
     * search per move. Runs shallower than analysis — the aim is to rank moves.
     */
    suspend fun valueEveryMove(fen: String, depth: Int = 10, movetimeMs: Int = 900): MoveValues {
        val position = Position.fromFen(fen) ?: throw EngineException.InvalidPosition(fen)
        val legal = position.legalMoves()
        if (legal.isEmpty()) return MoveValues(byMove = emptyMap(), best = 0)
        val analysis = analyse(fen, depth, movetimeMs, multiPV = legal.size)
        val byMove = HashMap<String, Int>()
        for (line in analysis.lines) {
            val move = line.bestMove ?: continue
            byMove[move] = line.score.comparable
        }
        // UCI reports for the side to move, which is the mover — no conversion here.
        return MoveValues(byMove = byMove, best = byMove.values.maxOrNull() ?: 0)
    }

    /** Cut the current search short. Safe to call when nothing is running. */
    fun stop() = StockfishNative.stop(handle)

    override fun close() {
        StockfishNative.stop(handle)
        StockfishNative.waitForSearch(handle)
        StockfishNative.destroy(handle)
    }

    private suspend fun analyseLocked(fen: String, depth: Int, movetimeMs: Int, multiPV: Int): Analysis {
        if (!isReady) throw EngineException.NetworksMissing("networks not loaded")
        StockfishNative.setOption(handle, "MultiPV", multiPV.toString())
        StockfishNative.setOption(handle, "UCI_LimitStrength", "false")
        if (!StockfishNative.setPosition(handle, fen)) throw EngineException.InvalidPosition(fen)

        val session = Session()
        return coroutineScope {
            // A search that overruns its budget is stopped. Stockfish honours movetime
            // itself and this should never fire, but a hung search must not hang the app.
            val watchdog = launch {
                delay(ceilingMs(movetimeMs).toLong())
                runCatching { StockfishNative.stop(handle) }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    if (!session.park(cont)) {
                        cont.resume(Unit)
                        return@suspendCancellableCoroutine
                    }
                    cont.invokeOnCancellation { runCatching { StockfishNative.stop(handle) } }
                    StockfishNative.go(handle, depth, movetimeMs, session)
                }
            } finally {
                watchdog.cancel()
            }
            val (lines, best) = session.results()
            Analysis(lines = lines, bestMove = best)
        }
    }

    /** Collects one search's output across the JNI callback boundary (called from the search thread). */
    private class Session : StockfishNative.SearchListener {
        private val lock = Any()
        private val lines = LinkedHashMap<Int, EngineLine>()
        private var best: String? = null
        private var cont: CancellableContinuation<Unit>? = null
        private var finished = false

        override fun onInfo(
            depth: Int, selDepth: Int, multiPV: Int, scoreCp: Int, scoreMate: Int,
            isMate: Boolean, nodes: Long, timeMs: Long, pv: String,
        ) {
            val score = if (isMate) EngineScore.Mate(scoreMate) else EngineScore.Centipawns(scoreCp)
            val line = EngineLine(
                rank = multiPV,
                depth = depth,
                score = score,
                moves = if (pv.isEmpty()) emptyList() else pv.split(" "),
            )
            // A deeper report for the same rank supersedes the earlier one.
            synchronized(lock) { lines[line.rank] = line }
        }

        override fun onBestMove(bestMove: String?, ponder: String?) {
            val resume: CancellableContinuation<Unit>?
            synchronized(lock) {
                best = bestMove?.takeUnless { it == "(none)" || it.isEmpty() }
                finished = true
                resume = cont
                cont = null
            }
            resume?.resume(Unit)
        }

        /** Returns false if the search already finished, so the caller resumes at once. */
        fun park(continuation: CancellableContinuation<Unit>): Boolean = synchronized(lock) {
            if (finished) {
                false
            } else {
                cont = continuation
                true
            }
        }

        fun results(): Pair<List<EngineLine>, String?> = synchronized(lock) {
            lines.values.sortedBy { it.rank } to best
        }
    }

    companion object {
        /** The full UCI id string, e.g. "Stockfish 18 by the Stockfish developers (see AUTHORS file)". */
        val engineDescription: String get() = StockfishNative.engineInfo()

        /** The name and version without the authorship engine_info appends, for Settings. */
        val shortDescription: String
            get() {
                val full = engineDescription
                val idx = full.indexOf(" by ")
                return if (idx < 0) full else full.substring(0, idx)
            }

        /** The longest any one search may run, whatever it was asked for. */
        const val HARD_CEILING_MS = 10_000

        private fun ceilingMs(movetimeMs: Int): Int =
            if (movetimeMs <= 0) HARD_CEILING_MS else minOf(movetimeMs + 1500, HARD_CEILING_MS)
    }
}
