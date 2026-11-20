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
 * single callback context the backend holds. Mirrors the iOS `actor StockfishEngine`.
 *
 * The platform [backend] is the only per-target part: JNI on Android, a
 * stockfish.wasm Worker on web.
 */
class StockfishEngine(private val backend: EngineBackend) : AutoCloseable {
    private val handle: Long
    private var isReady = false
    private val searchMutex = Mutex()

    init {
        backend.globalInit()
        handle = backend.create()
        require(handle != 0L) { "Stockfish could not be created" }
    }

    /** The full UCI id string, e.g. "Stockfish 18 by the Stockfish developers (see AUTHORS file)". */
    val engineDescription: String get() = backend.engineInfo()

    /** The name and version without the authorship engine_info appends, for Settings. */
    val shortDescription: String
        get() {
            val full = engineDescription
            val idx = full.indexOf(" by ")
            return if (idx < 0) full else full.substring(0, idx)
        }

    /** Stockfish limits strength through UCI_Elo, a real rating limiter, so the whole ladder is available. */
    val capabilities: EngineCapabilities =
        EngineCapabilities(name = shortDescription, limitsStrength = true)

    /** Point the engine at its two network files. Must succeed before searching. */
    suspend fun loadNetworks(bigPath: String, smallPath: String) = searchMutex.withLock {
        if (!backend.loadNetworks(handle, bigPath, smallPath)) {
            throw EngineException.NetworksMissing(bigPath)
        }
        isReady = true
    }

    suspend fun setOption(name: String, value: String) = searchMutex.withLock {
        backend.setOption(handle, name, value)
    }

    suspend fun newGame() = searchMutex.withLock { backend.newGame(handle) }

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
                backend.setOption(handle, "UCI_LimitStrength", "true")
                backend.setOption(handle, "UCI_Elo", elo.coerceIn(1320, 3190).toString())
            } else {
                backend.setOption(handle, "UCI_LimitStrength", "false")
            }
            val analysis = analyseLocked(fen, depth, movetimeMs, 1)
            backend.setOption(handle, "UCI_LimitStrength", "false")
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
    fun stop() = backend.stop(handle)

    override fun close() {
        backend.stop(handle)
        backend.waitForSearch(handle)
        backend.destroy(handle)
    }

    private suspend fun analyseLocked(fen: String, depth: Int, movetimeMs: Int, multiPV: Int): Analysis {
        if (!isReady) throw EngineException.NetworksMissing("networks not loaded")
        backend.setOption(handle, "MultiPV", multiPV.toString())
        backend.setOption(handle, "UCI_LimitStrength", "false")
        if (!backend.setPosition(handle, fen)) throw EngineException.InvalidPosition(fen)

        val session = Session()
        return coroutineScope {
            // A search that overruns its budget is stopped. Stockfish honours movetime
            // itself and this should never fire, but a hung search must not hang the app.
            val watchdog = launch {
                delay(ceilingMs(movetimeMs).toLong())
                runCatching { backend.stop(handle) }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    if (!session.park(cont)) {
                        cont.resume(Unit)
                        return@suspendCancellableCoroutine
                    }
                    cont.invokeOnCancellation { runCatching { backend.stop(handle) } }
                    backend.go(handle, depth, movetimeMs, session)
                }
            } finally {
                watchdog.cancel()
            }
            val (lines, best) = session.results()
            Analysis(lines = lines, bestMove = best)
        }
    }

    /** Collects one search's output across the backend callback boundary (may be another thread). */
    private class Session : EngineBackend.SearchListener {
        private val lock = EngineLock()
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
            lock.withLock { lines[line.rank] = line }
        }

        override fun onBestMove(bestMove: String?, ponder: String?) {
            val resume: CancellableContinuation<Unit>? = lock.withLock {
                best = bestMove?.takeUnless { it == "(none)" || it.isEmpty() }
                finished = true
                val c = cont
                cont = null
                c
            }
            resume?.resume(Unit)
        }

        /** Returns false if the search already finished, so the caller resumes at once. */
        fun park(continuation: CancellableContinuation<Unit>): Boolean = lock.withLock {
            if (finished) {
                false
            } else {
                cont = continuation
                true
            }
        }

        fun results(): Pair<List<EngineLine>, String?> = lock.withLock {
            lines.values.sortedBy { it.rank } to best
        }
    }

    companion object {
        /** The longest any one search may run, whatever it was asked for. */
        const val HARD_CEILING_MS = 10_000

        private fun ceilingMs(movetimeMs: Int): Int =
            if (movetimeMs <= 0) HARD_CEILING_MS else minOf(movetimeMs + 1500, HARD_CEILING_MS)
    }
}
