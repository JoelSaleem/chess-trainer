package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.Square
import com.joelsaleem.chesstrainer.core.isCheckmate
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.datetime.Instant

/**
 * The daily feed, as the app reads it.
 *
 * The three FeedLink/Invitation tests from the Swift suite (storyLinks, storyLinksAtAMove,
 * feedFolders) defer with FeedLink — see the note in Feed.kt.
 */
class FeedTests {
    /** One story as publish.mjs writes it, with the moves and key moment replaced to suit the test. */
    private fun storyJson(
        id: String = "2026-09-25-a-b-olympiad-r9",
        result: String = "0-1",
        moves: String = "f3 e5 g4 Qh4#",
        key: String? = null,
    ): String = """
        {"id":"$id","date":"2026-09-25",
         "event":{"name":"46th FIDE Chess Olympiad Samarkand 2026","short":"Olympiad","section":"Open","round":9,"location":"Samarkand, Uzbekistan","url":null},
         "white":{"name":"Anna White","short":"White","title":"GM","elo":2700,"team":null},
         "black":{"name":"Boris Black","short":"Black","title":"GM","elo":2710,"team":"Uzbekistan"},
         "result":"$result","opening":{"eco":"A00","name":"Barnes Opening"},
         "moves":"$moves","key":${key ?: "null"},
         "headline":"Black mates in two","body":"It was over on move two.",
         "url":"https://brasspawn.com/today/$id","source":{"name":"Lichess broadcast","url":null}}
    """.trimIndent()

    private fun fileJson(vararg stories: String, version: Int = 1): String = """
        {"version":$version,"generatedAt":"2026-09-26T08:00:00.000Z",
         "source":{"name":"Lichess broadcasts","url":"https://lichess.org/broadcast"},
         "stories":[${stories.joinToString(",")}]}
    """.trimIndent()

    private val key = """
        {"kind":"mistake","ply":3,"played":"g4","better":"Nc3","reply":"Qh4#","replyPlayed":true,
         "before":{"cp":-150,"mate":null},"after":{"cp":null,"mate":-1}}
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a file as publish mjs writes it is read`() {
        val feed = FeedFile.decode(fileJson(storyJson(key = key)))
        assertEquals(1, feed.stories.size)
        val story = feed.stories.first()
        assertEquals(PieceColor.black, story.winner)
        assertEquals(listOf("f3", "e5", "g4", "Qh4#"), story.sans)
        assertEquals(3, story.key?.ply)
        assertEquals(9, story.event.round)
    }

    @Test
    fun `a story whose moves do not play is left out`() {
        val feed = FeedFile.decode(fileJson(storyJson(id = "good"), storyJson(id = "bad", moves = "e4 e5 Ke3")))
        assertEquals(listOf("good"), feed.stories.map { it.id })
    }

    @Test
    fun `the first file lists the days behind it`() {
        val data = """
            {"version":1,"generatedAt":"2026-09-26T08:00:00Z",
             "days":[{"date":"2026-09-25","count":1},{"date":"2026-09-24","count":7}],
             "stories":[${storyJson()}]}
        """.trimIndent()
        val feed = FeedFile.decode(data)
        assertEquals(listOf("2026-09-25", "2026-09-24"), feed.days?.map { it.date })
        assertEquals(7, feed.days?.last()?.count)
    }

    @Test
    fun `a day's file is read, and a story in it whose moves do not play is left out`() {
        val data = """
            {"date":"2026-09-24","stories":[${storyJson(id = "good")},${storyJson(id = "bad", moves = "e4 e5 Ke3")}]}
        """.trimIndent()
        val day = FeedDay.decode(data)
        assertEquals("2026-09-24", day.date)
        assertEquals(listOf("good"), day.stories.map { it.id })
    }

    @Test
    fun `fields the app does not know are ignored`() {
        val extra = storyJson().replace(
            "\"id\":",
            "\"status\":\"auto\",\"rank\":0,\"fen\":\"8/8/8/8/8/8/8/8 w - - 0 1\",\"id\":",
        )
        assertEquals(1, FeedFile.decode(fileJson(extra)).stories.size)
    }

    @Test
    fun `a version this build does not know is refused rather than half read`() {
        val ex = assertFailsWith<FeedError.UnsupportedVersion> {
            FeedFile.decode(fileJson(storyJson(), version = 2))
        }
        assertEquals(2, ex.version)
    }

    @Test
    fun `the board shown is the one after the key move`() {
        val story = FeedFile.decode(fileJson(storyJson(key = key))).stories.first()
        val focus = story.focus
        assertEquals(Square.of(6, 1), focus.lastMove?.from)
        assertEquals(Square.of(6, 3), focus.lastMove?.to)
        assertEquals(PieceColor.black, focus.position.sideToMove)
    }

    @Test
    fun `a stored position is used as it is`() {
        val withBoard = storyJson(key = key).replace(
            "\"id\":",
            "\"fen\":\"rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2\",\"last\":{\"from\":\"g2\",\"to\":\"g4\"},\"id\":",
        )
        val story = FeedFile.decode(fileJson(withBoard)).stories.first()
        val focus = story.focus
        assertTrue(focus.position.fen.startsWith("rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2"))
        assertEquals(Square.fromName("g2"), focus.lastMove?.from)
        assertEquals(Square.fromName("g4"), focus.lastMove?.to)
    }

    @Test
    fun `without a key moment, the board is the final one`() {
        val story = FeedFile.decode(fileJson(storyJson())).stories.first()
        assertEquals(4, story.focusPly)
        assertTrue(story.focus.position.isCheckmate)
    }

    @Test
    fun `a story is the last day's until a whole day after its round's day, in UTC`() {
        val story = FeedFile.decode(fileJson(storyJson())).stories.first()
        // Played on 25 September: all of the 25th and the 26th are its day.
        assertTrue(story.isRecent(Instant.parse("2026-09-25T18:00:00Z")))
        assertTrue(story.isRecent(Instant.parse("2026-09-26T23:59:59Z")))
        assertFalse(story.isRecent(Instant.parse("2026-09-27T00:00:00Z")))
        assertFalse(story.isRecent(Instant.parse("2026-10-10T12:00:00Z")))
    }

    @Test
    fun `a story whose date cannot be read is never locked`() {
        val odd = storyJson().replace("\"date\":\"2026-09-25\"", "\"date\":\"soon\"")
        val story = FeedFile.decode(fileJson(odd)).stories.first()
        assertTrue(story.isRecent(Instant.DISTANT_FUTURE))
    }

    @Test
    fun `moves are labelled the way the commentary writes them`() {
        assertEquals("2.g4", FeedStory.label(ply = 3, san = "g4"))
        assertEquals("40...Rxb3", FeedStory.label(ply = 80, san = "Rxb3"))
    }

    @Test
    fun `the headline the app builds names the winner first`() {
        val black = FeedFile.decode(fileJson(storyJson(result = "0-1"))).stories.first()
        assertEquals("Black beat White", black.title)
        val white = FeedFile.decode(fileJson(storyJson(result = "1-0", moves = "e4"))).stories.first()
        assertEquals("White beat Black", white.title)
        val draw = FeedFile.decode(fileJson(storyJson(result = "1/2-1/2", moves = "e4"))).stories.first()
        assertEquals("White and Black drew", draw.title)
        assertEquals("Olympiad · Open · Round 9", draw.occasion)
    }

    @Test
    fun `decodes optional PremiumArticle sections and resolves section diagrams`() {
        val plain = FeedFile.decode(fileJson(storyJson(key = key))).stories.first()
        assertNull(plain.premium)

        val premiumJson = """
            {"tier":"pro","subtitle":"Why weakening the kingside diagonal decided the game","readingMinutes":4,
             "sections":[
               {"heading":"The Fatal Weakening","ply":3,"move":"2.g4",
                "fen":"rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2",
                "last":{"from":"g2","to":"g4"},"evalBefore":"−1.50","evalAfter":"#−1",
                "engineLine":"2...Qh4#","body":"Pushing the g-pawn opens the e1-h4 diagonal.",
                "takeaways":["Never open f3 and g4 before developing pieces."]},
               {"heading":"Checkmate on h4","ply":4,"move":"2...Qh4#",
                "fen":null,"last":null,"evalBefore":null,"evalAfter":"#−1",
                "engineLine":null,"body":"The queen delivers immediate mate.","takeaways":null}
             ]}
        """.trimIndent()
        val enriched = storyJson(key = key).replace("\"id\":", "\"premium\":$premiumJson,\"id\":")
        val decoded = FeedFile.decode(fileJson(enriched)).stories.first()
        val premium = decoded.premium!!
        assertEquals(4, premium.readingMinutes)
        assertEquals(2, premium.sections.size)
        assertEquals("The Fatal Weakening", premium.sections[0].heading)
        assertEquals(1, premium.sections[0].takeaways?.size)

        val diag0 = decoded.sectionBoard(premium.sections[0])!!
        assertEquals(Square.fromName("g2"), diag0.lastMove?.from)
        assertEquals(Square.fromName("g4"), diag0.lastMove?.to)

        val diag1 = decoded.sectionBoard(premium.sections[1])!!
        assertTrue(diag1.position.isCheckmate)
    }

    @Test
    fun `a story in another language's copy says which language its words are in`() {
        val text = """{"id":"x","date":"2026-09-26","event":{"name":"E","short":"E","section":null,"round":1,"location":null,"url":null},"white":{"name":"A B","short":"B","title":null,"elo":null,"team":null},"black":{"name":"C D","short":"D","title":null,"elo":null,"team":null},"result":"1-0","opening":{"eco":null,"name":null},"moves":"e4 e5","key":null,"headline":"B – D 1–0: Sieg für Weiß","body":"…","lang":"de","url":"https://brasspawn.com/today/x","source":{"name":"Lichess broadcast","url":null}}"""
        val story = json.decodeFromString<FeedStory>(text)
        assertEquals("de", story.lang)
        val english = json.decodeFromString<FeedStory>(text.replace(",\"lang\":\"de\"", ""))
        assertNull(english.lang)
    }

    @Test
    fun `an engine score reads as a number from White's side`() {
        assertTrue(FeedStory.Score(cp = 108, mate = null).text.startsWith("+1"))
        assertTrue(FeedStory.Score(cp = -440, mate = null).text.startsWith("−4"))
        assertEquals("#3", FeedStory.Score(cp = null, mate = 3).text)
        assertEquals("#−2", FeedStory.Score(cp = null, mate = -2).text)
    }
}
