package com.example

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.domain.DocumentFormatter
import com.example.domain.SpreadsheetEngine
import com.example.domain.UndoRedoStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteOfficeEngineTest {

    @Test
    fun spreadsheetFormulasEvaluateAccurately() {
        val grid = listOf(
            listOf("10", "20", "=SUM(A1:B1)"),
            listOf("30", "40", "=AVERAGE(A1:B2)"),
            listOf("50", "", "=COUNT(A1:A3)"),
            listOf("=MIN(A1:A3)", "=MAX(A1:B2)", "=C1*2+5")
        )
        val evaluated = SpreadsheetEngine.evaluateAll(grid)

        assertEquals("30", evaluated[0][2])
        assertEquals("25", evaluated[1][2])
        assertEquals("3", evaluated[2][2])
        assertEquals("10", evaluated[3][0])
        assertEquals("40", evaluated[3][1])
        assertEquals("65", evaluated[3][2])
    }

    @Test
    fun spreadsheetHandlesCyclesAndDivByZero() {
        val grid = listOf(
            listOf("=B1", "=A1"),
            listOf("10", "=A2/0")
        )
        val evaluated = SpreadsheetEngine.evaluateAll(grid)
        assertEquals("#CYCLE!", evaluated[0][0])
        assertEquals("#DIV/0!", evaluated[1][1])
    }

    @Test
    fun csvRoundTripPreservesQuotesAndCommas() {
        val original = listOf(
            listOf("Item", "Notes", "Amount", "", "", ""),
            listOf("Coffee, Beans", "Said \"Great\"", "12.50", "", "", "")
        )
        val csv = SpreadsheetEngine.serializeCsv(original)
        val parsed = SpreadsheetEngine.parseCsv(csv)
        assertEquals("Coffee, Beans", parsed[1][0])
        assertEquals("Said \"Great\"", parsed[1][1])
        assertEquals("12.50", parsed[1][2])
    }

    @Test
    fun documentFormatterTogglesAndFindReplace() {
        val tfv = TextFieldValue("Hello world", TextRange(6, 11))
        val bolded = DocumentFormatter.toggleInlineWrap(tfv, "**", "**", "bold")
        assertEquals("Hello **world**", bolded.text)

        val unbolded = DocumentFormatter.toggleInlineWrap(bolded, "**", "**", "bold")
        assertEquals("Hello world", unbolded.text)

        val headed = DocumentFormatter.cycleHeading(TextFieldValue("Title", TextRange(2)))
        assertEquals("# Title", headed.text)

        val (replaced, count) = DocumentFormatter.replaceAll("foo bar Foo", "foo", "baz", matchCase = false)
        assertEquals("baz bar baz", replaced)
        assertEquals(2, count)

        val stats = DocumentFormatter.computeStats("One two\nthree")
        assertEquals(3, stats.words)
        assertEquals(13, stats.chars)
        assertEquals(2, stats.lines)
    }

    @Test
    fun undoRedoStackWorks() {
        val stack = UndoRedoStack<String>()
        stack.pushUndo("v1")
        stack.pushUndo("v2")
        assertTrue(stack.canUndo)
        assertFalse(stack.canRedo)

        val undone = stack.undo("v3")
        assertEquals("v2", undone)
        assertTrue(stack.canRedo)

        val redone = stack.redo("v2")
        assertEquals("v3", redone)
    }
}
