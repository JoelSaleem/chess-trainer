package com.joelsaleem.chesstrainer.engine

import com.joelsaleem.chesstrainer.core.Position
import org.w3c.dom.MessageEvent
import org.w3c.dom.Worker

/**
 * Web engine backend: drives a single-threaded stockfish.wasm Web Worker over UCI.
 *
 * The [EngineBackend] API is synchronous (JNI-shaped), but the worker is async — so
 * commands issued before the engine reports `uciok` are queued and flushed on ready,
 * and worker output is parsed from `onmessage` into [EngineBackend.SearchListener].
 *
 * Needs the single-threaded `stockfish-*-lite-single.js` worker script (and its
 * matching `.wasm`, same directory) served next to the app; [workerUrl] points at it.
 * lite-single needs no COOP/COEP headers, so it runs on any static host.
 */
class WasmEngineBackend(private val workerUrl: String = "stockfish.js") : EngineBackend {
    private var worker: Worker? = null
    private var ready = false
    private val pending = ArrayDeque<String>()
    private var listener: EngineBackend.SearchListener? = null
    private var idName = "Stockfish"

    override fun globalInit() {
        if (worker != null) return
        val w = Worker(workerUrl)
        w.onmessage = { event -> onLine(messageText(event)) }
        worker = w
        rawSend("uci")
    }

    override fun create(): Long = 1L // single engine per worker; the handle is nominal

    override fun destroy(handle: Long) {
        worker?.terminate()
        worker = null
        ready = false
        listener = null
        pending.clear()
    }

    override fun engineInfo(): String = idName

    // The lite-single build embeds its NNUE; the Android filesystem paths don't apply.
    override fun loadNetworks(handle: Long, bigPath: String, smallPath: String): Boolean = true

    override fun setOption(handle: Long, name: String, value: String) = send("setoption name $name value $value")

    override fun newGame(handle: Long) = send("ucinewgame")

    override fun setPosition(handle: Long, fen: String): Boolean {
        // UCI doesn't reject a position synchronously; validate with chess-core so the
        // engine layer's InvalidPosition contract holds on web too.
        if (Position.fromFen(fen) == null) return false
        send("position fen $fen")
        return true
    }

    override fun go(handle: Long, depth: Int, movetimeMs: Int, listener: EngineBackend.SearchListener) {
        this.listener = listener
        val cmd = buildString {
            append("go")
            if (depth > 0) append(" depth ").append(depth)
            if (movetimeMs > 0) append(" movetime ").append(movetimeMs)
        }
        send(cmd)
    }

    override fun stop(handle: Long) = send("stop")

    // Single-threaded worker: the search resolves asynchronously via the listener,
    // so there is nothing to block on here.
    override fun waitForSearch(handle: Long) {}

    private fun send(cmd: String) {
        if (worker == null || !ready) pending.addLast(cmd) else rawSend(cmd)
    }

    private fun rawSend(cmd: String) {
        worker?.postMessage(cmd.toJsString())
    }

    private fun onLine(line: String) {
        when {
            line.startsWith("id name ") -> idName = line.substring(8).trim()
            line == "uciok" -> if (!ready) {
                ready = true
                while (pending.isNotEmpty()) rawSend(pending.removeFirst())
            }
            line.startsWith("info ") && " pv " in line -> parseInfo(line)
            line.startsWith("bestmove ") -> parseBestMove(line)
        }
    }

    /** Parse `info depth D seldepth S multipv M score cp N|mate K nodes X time T pv ...`. */
    private fun parseInfo(line: String) {
        val l = listener ?: return
        val t = line.split(" ")
        var depth = 0
        var selDepth = 0
        var multiPV = 1
        var scoreCp = 0
        var scoreMate = 0
        var isMate = false
        var nodes = 0L
        var timeMs = 0L
        var pv = ""
        var i = 1
        while (i < t.size) {
            when (t[i]) {
                "depth" -> { depth = t.getOrNull(i + 1)?.toIntOrNull() ?: 0; i += 2 }
                "seldepth" -> { selDepth = t.getOrNull(i + 1)?.toIntOrNull() ?: 0; i += 2 }
                "multipv" -> { multiPV = t.getOrNull(i + 1)?.toIntOrNull() ?: 1; i += 2 }
                "nodes" -> { nodes = t.getOrNull(i + 1)?.toLongOrNull() ?: 0L; i += 2 }
                "time" -> { timeMs = t.getOrNull(i + 1)?.toLongOrNull() ?: 0L; i += 2 }
                "score" -> {
                    when (t.getOrNull(i + 1)) {
                        "cp" -> { scoreCp = t.getOrNull(i + 2)?.toIntOrNull() ?: 0; isMate = false }
                        "mate" -> { scoreMate = t.getOrNull(i + 2)?.toIntOrNull() ?: 0; isMate = true }
                    }
                    i += 3
                }
                "pv" -> { pv = t.subList(i + 1, t.size).joinToString(" "); i = t.size }
                else -> i += 1
            }
        }
        l.onInfo(depth, selDepth, multiPV, scoreCp, scoreMate, isMate, nodes, timeMs, pv)
    }

    private fun parseBestMove(line: String) {
        val t = line.split(" ")
        val best = t.getOrNull(1)?.takeUnless { it == "(none)" || it.isEmpty() }
        val ponder = if (t.size >= 4 && t[2] == "ponder") t[3] else null
        listener?.onBestMove(best, ponder)
    }

    private fun messageText(event: MessageEvent): String =
        (event.data as? JsString)?.toString() ?: ""
}
