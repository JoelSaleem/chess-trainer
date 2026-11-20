package com.joelsaleem.chesstrainer.engine

actual class EngineLock {
    private val monitor = Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}
