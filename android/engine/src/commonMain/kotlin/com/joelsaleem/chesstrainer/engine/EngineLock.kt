package com.joelsaleem.chesstrainer.engine

/**
 * A mutual-exclusion lock for the search-result buffer, which the backend may fill
 * from another thread (Android: Stockfish's search thread). A real monitor on the
 * JVM; a no-op on single-threaded wasmJs.
 */
expect class EngineLock() {
    fun <T> withLock(block: () -> T): T
}
