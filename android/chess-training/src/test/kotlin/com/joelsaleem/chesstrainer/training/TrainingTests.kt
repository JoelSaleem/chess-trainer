package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.PieceColor
import com.joelsaleem.chesstrainer.core.Position
import com.joelsaleem.chesstrainer.core.Square
import com.joelsaleem.chesstrainer.core.make
import com.joelsaleem.chesstrainer.core.move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FeatureTests {
    @Test
    fun `Open and half-open files are identified`() {
        // White has no d-pawn, Black has no e-pawn; the c-file is empty of both.
        val position = Position.fromFen("r3k2r/pp1p1ppp/8/8/8/8/PP2PPPP/R3K2R w KQkq - 0 1")!!
        val features = PositionFeatures(position)

        val byName = features.files.associateBy { it.name }
        assertEquals(true, byName["c"]?.isOpen)
        assertEquals(PieceColor.white, byName["d"]?.halfOpenFor)
        assertEquals(false, byName["a"]?.isOpen)
    }

    @Test
    fun `A rook on an open file is recognised`() {
        val position = Position.fromFen("4k3/pp4pp/8/8/8/8/PP4PP/3RK3 w - - 0 1")!!
        val features = PositionFeatures(position)
        val rook = features.heavyPieces.first { it.square == Square.fromName("d1")!! }
        assertTrue(rook.onOpenFile)
        assertFalse(rook.onSeventhRank)
    }

    @Test
    fun `An outpost needs a defender and no pawn that can chase it`() {
        // Knight on e5, defended by the d4 pawn. Black has no d- or f-pawn left
        // to drive it away, so it is a genuine outpost.
        val secure = Position.fromFen("4k3/pp4pp/8/4N3/3P4/8/PP4PP/4K3 w - - 0 1")!!
        assertEquals(true, PositionFeatures(secure).knights.firstOrNull()?.isOutpost)

        // Same knight, but now a black f-pawn can play f6 and evict it.
        val contested = Position.fromFen("4k3/pp3ppp/8/4N3/3P4/8/PP4PP/4K3 w - - 0 1")!!
        assertEquals(false, PositionFeatures(contested).knights.firstOrNull()?.isOutpost)

        // Undefended knight is not an outpost however deep it sits.
        val loose = Position.fromFen("4k3/pp4pp/8/4N3/8/8/PP4PP/4K3 w - - 0 1")!!
        assertEquals(false, PositionFeatures(loose).knights.firstOrNull()?.isOutpost)
    }

    @Test
    fun `Pawn structure faults are found`() {
        // White a-pawn is isolated; the White d-pawn on d5 is passed.
        val position = Position.fromFen("4k3/1p4pp/8/3P4/8/8/P5PP/4K3 w - - 0 1")!!
        val structure = PositionFeatures(position).structure[PieceColor.white]!!
        assertTrue(structure.isolated.contains(Square.fromName("a2")!!))
        assertTrue(structure.passed.contains(Square.fromName("d5")!!))
    }

    @Test
    fun `Mobility is measured for both sides`() {
        val position = Position.fromFen(Position.startFEN)!!
        val features = PositionFeatures(position)
        assertEquals(20, features.mobility[PieceColor.white])
        assertEquals(20, features.mobility[PieceColor.black])
    }
}

class MoveDescriptionTests {
    private fun describe(fen: String, san: String): List<String> {
        val position = Position.fromFen(fen)!!
        val move = position.move(san)!!
        val resulting = position.copy()
        resulting.make(move)
        return MoveDescription.clauses(
            before = PositionFeatures(position),
            after = PositionFeatures(resulting),
            move = move,
            position = position,
            resulting = resulting,
        )
    }

    @Test
    fun `Checkmate is described on its own`() {
        val clauses = describe("6k1/5ppp/8/8/8/8/8/R3K3 w - - 0 1", "Ra8#")
        assertEquals(listOf("delivers checkmate"), clauses)
    }

    @Test
    fun `Taking the open file is named`() {
        val clauses = describe("4k3/pp4pp/8/8/8/8/PP4PP/R3K3 w - - 0 1", "Rd1")
        assertTrue(clauses.any { it.contains("open d-file") })
    }

    @Test
    fun `Winning material is distinguished from an even trade`() {
        val clauses = describe("4k3/8/8/8/3q4/8/8/3RK3 w - - 0 1", "Rxd4")
        assertTrue(clauses.any { it.contains("wins material") })
    }

    @Test
    fun `Compact notation is expanded into a readable move`() {
        val position = Position.fromFen("3kq3/pQ6/8/3p4/3P2p1/2P5/PP4PP/4rNK1 b - - 2 37")!!
        val move = position.move("Rxf1+")!!
        val resulting = position.copy()
        resulting.make(move)
        val sentence = MoveDescription.detailedSentence(
            san = "Rxf1+", move = move, position = position, resulting = resulting,
        )!!

        assertTrue(sentence.contains("rook from e1 to f1"))
        assertTrue(sentence.contains("capturing the knight"))
        assertTrue(sentence.contains("giving check"))
    }

    @Test
    fun `A summary reads the position`() {
        val position = Position.fromFen("4k3/pp4pp/8/4N3/3P4/8/PP4PP/3RK3 w - - 0 1")!!
        val points = MoveDescription.summary(PositionFeatures(position))
        assertTrue(points.any { it.contains("secure knight on e5") })
        assertTrue(points.any { it.contains("Material") })
    }
}

class ProgressTests {
    @Test
    fun `Rating rises on a win and falls on a loss`() {
        val progress = TrainingProgress()
        val start = progress.rating(TrainingMode.tactics)

        progress.record(mode = TrainingMode.tactics, itemID = "a", itemRating = 1500, correct = true)
        assertTrue(progress.rating(TrainingMode.tactics) > start)

        val losing = TrainingProgress()
        losing.record(mode = TrainingMode.tactics, itemID = "a", itemRating = 900, correct = false)
        assertTrue(losing.rating(TrainingMode.tactics) < start)
    }

    @Test
    fun `A hint means the attempt does not count towards rating`() {
        val progress = TrainingProgress()
        progress.record(mode = TrainingMode.tactics, itemID = "a", itemRating = 1500, correct = true, usedHint = true)
        assertTrue(progress.rating(TrainingMode.tactics) < 1200) // scored as a miss for rating purposes
    }

    @Test
    fun `A missed puzzle returns tomorrow, a solved one later`() {
        val now = Instant.now()
        val progress = TrainingProgress()

        progress.record(mode = TrainingMode.tactics, itemID = "missed", itemRating = 1200, correct = false, now = now)
        assertEquals(1, progress.cards["missed"]!!.intervalDays)

        progress.record(mode = TrainingMode.tactics, itemID = "solved", itemRating = 1200, correct = true, now = now)
        progress.record(
            mode = TrainingMode.tactics, itemID = "solved", itemRating = 1200, correct = true,
            now = now.plusSeconds(86_400L * 2),
        )
        assertTrue(progress.cards["solved"]!!.intervalDays >= 4)
    }

    @Test
    fun `Descriptive themes are not counted as skills`() {
        val progress = TrainingProgress()
        progress.record(
            mode = TrainingMode.tactics, itemID = "a", itemRating = 1200, correct = true,
            themes = listOf("pin", "short", "middlegame", "crushing"),
        )
        assertNotNull(progress.themes["pin"])
        assertNull(progress.themes["short"])
        assertNull(progress.themes["middlegame"])
    }

    @Test
    fun `Confidence, not raw accuracy, ranks weaknesses`() {
        val oneMiss = ThemeRecord(seen = 1, solved = 0)
        val manyMisses = ThemeRecord(seen = 20, solved = 5)
        // Both are 0% and 25%, but only the second is evidence of a weakness.
        assertTrue(manyMisses.ceiling < oneMiss.ceiling)
    }

    @Test
    fun `Targets are motifs below your own baseline`() {
        val progress = TrainingProgress()
        progress.themes = mapOf(
            "pin" to ThemeRecord(seen = 12, solved = 3),
            "deflection" to ThemeRecord(seen = 9, solved = 3),
            "fork" to ThemeRecord(seen = 14, solved = 12),
            "discoveredAttack" to ThemeRecord(seen = 10, solved = 9),
        )
        val names = progress.trainingTargets().map { it.name }.toSet()
        assertTrue(names.contains("pin"))
        assertTrue(names.contains("deflection"))
        assertFalse(names.contains("fork"))
    }

    @Test
    fun `Streaks count consecutive days only`() {
        val progress = TrainingProgress()
        val day = Instant.ofEpochSecond(1_700_000_000)
        progress.record(mode = TrainingMode.tactics, itemID = "a", itemRating = 1200, correct = true, now = day)
        progress.record(
            mode = TrainingMode.tactics, itemID = "b", itemRating = 1200, correct = true,
            now = day.plusSeconds(86_400L),
        )
        assertEquals(2, progress.currentStreak)

        // Skipping a day resets it.
        progress.record(
            mode = TrainingMode.tactics, itemID = "c", itemRating = 1200, correct = true,
            now = day.plusSeconds(86_400L * 4),
        )
        assertEquals(1, progress.currentStreak)
        assertEquals(2, progress.bestStreak)
    }
}

class SelectionTests {
    private fun puzzle(id: String, rating: Int, themes: List<String>): Puzzle =
        Puzzle(
            id = id, fen = Position.startFEN, solution = listOf("e2e4"), themes = themes,
            rating = rating, mate = false, source = null,
        )

    @Test
    fun `Due reviews come before anything else`() {
        val progress = TrainingProgress()
        val now = Instant.now()
        progress.record(
            mode = TrainingMode.tactics, itemID = "old", itemRating = 1200, correct = false,
            now = now.minusSeconds(86_400L * 3),
        )

        val puzzles = listOf(
            puzzle("old", rating = 1200, themes = listOf("pin")),
            puzzle("new", rating = 1250, themes = listOf("fork")),
        )
        val selection = ItemSelector.nextPuzzle(from = puzzles, progress = progress, now = now)!!
        assertEquals("old", selection.item.id)
        assertEquals(SelectionReason.Review, selection.reason)
    }

    @Test
    fun `Weak motifs are targeted most of the time`() {
        val progress = TrainingProgress()
        progress.pools = mapOf(RatedPool.training(TrainingMode.tactics).id to PoolRating(rating = 1500))
        progress.themes = mapOf(
            "pin" to ThemeRecord(seen = 12, solved = 3),
            "fork" to ThemeRecord(seen = 14, solved = 12),
            "discoveredAttack" to ThemeRecord(seen = 10, solved = 9),
        )

        val puzzles = (0 until 40).map { index ->
            puzzle("p$index", rating = 1500, themes = if (index % 2 == 0) listOf("pin") else listOf("fork"))
        }

        // Force the targeting branch; the share itself is a product decision, not a test's business.
        val selection = ItemSelector.nextPuzzle(
            from = puzzles, progress = progress, random = { 0 }, chance = { 0.0 },
        )!!
        assertTrue(selection.item.themes.contains("pin"))
        val reason = selection.reason
        assertTrue(reason is SelectionReason.Weakness)
        assertEquals("pin", (reason as SelectionReason.Weakness).motif)
    }

    @Test
    fun `Without enough evidence it just tracks your level`() {
        val progress = TrainingProgress()
        progress.pools = mapOf(RatedPool.training(TrainingMode.tactics).id to PoolRating(rating = 1500))
        val puzzles = listOf(
            puzzle("near", rating = 1540, themes = listOf("pin")),
            puzzle("far", rating = 2400, themes = listOf("pin")),
        )
        val selection = ItemSelector.nextPuzzle(
            from = puzzles, progress = progress, random = { 0 }, chance = { 0.0 },
        )!!
        assertEquals("near", selection.item.id)
        assertEquals(SelectionReason.Level, selection.reason)
    }
}
