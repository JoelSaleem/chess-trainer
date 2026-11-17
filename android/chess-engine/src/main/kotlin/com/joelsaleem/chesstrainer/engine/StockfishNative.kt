package com.joelsaleem.chesstrainer.engine

object StockfishNative {
    init {
        System.loadLibrary("stockfish")
    }

    external fun engineInfo(): String
}
