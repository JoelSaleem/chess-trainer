package com.joelsaleem.chesstrainer.training

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTests {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `System is the default typeface`() {
        assertEquals(AppTypeface.system, Appearance().typeface)
    }

    @Test
    fun `The selected typeface persists`() {
        val appearance = Appearance()
        appearance.typeface = AppTypeface.jetBrainsMono

        val data = json.encodeToString(appearance)
        val decoded = json.decodeFromString<Appearance>(data)

        assertEquals(AppTypeface.jetBrainsMono, decoded.typeface)
    }

    @Test
    fun `Legacy appearance data defaults to the system typeface`() {
        val input = """{"pieces":"ebony","board":"wood","soundsOn":true,"volume":0.7,"dimension":"flat","carving":"banded"}"""
        val decoded = json.decodeFromString<Appearance>(input)

        assertEquals(AppTypeface.system, decoded.typeface)
    }
}

class EngineChoiceTests {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `Stockfish is the default engine`() {
        assertEquals(EngineChoice.stockfish, Appearance().engine)
    }

    @Test
    fun `The selected engine persists`() {
        val appearance = Appearance()
        appearance.engine = EngineChoice.reckless

        val data = json.encodeToString(appearance)
        val decoded = json.decodeFromString<Appearance>(data)

        assertEquals(EngineChoice.reckless, decoded.engine)
    }

    @Test
    fun `A save from before the choice existed still loads, as Stockfish`() {
        val input = """{"pieces":"ebony","board":"wood","soundsOn":true,"volume":0.7,"dimension":"flat","carving":"banded","showsCoordinates":true}"""
        val decoded = json.decodeFromString<Appearance>(input)

        assertEquals(EngineChoice.stockfish, decoded.engine)
        assertTrue("shown online unless they say otherwise", decoded.showsOnline)
    }

    @Test
    fun `Hiding from the online list is remembered`() {
        val appearance = Appearance()
        assertTrue(appearance.showsOnline)
        appearance.showsOnline = false
        val decoded = json.decodeFromString<Appearance>(json.encodeToString(appearance))
        assertFalse(decoded.showsOnline)
    }

    @Test
    fun `Only Stockfish claims to limit its strength`() {
        assertTrue(EngineChoice.stockfish.limitsStrength)
        assertFalse(EngineChoice.reckless.limitsStrength)
    }
}
