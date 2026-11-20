package com.joelsaleem.chesstrainer.engine

// wasmJs is single-threaded (Worker output arrives on the event loop), so no real lock is needed.
actual class EngineLock {
    actual fun <T> withLock(block: () -> T): T = block()
}
