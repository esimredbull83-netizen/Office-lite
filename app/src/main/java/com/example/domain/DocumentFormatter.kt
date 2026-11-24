package com.example.domain

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

data class TextStats(
    val words: Int,
    val chars: Int,
    val lines: Int
)

class UndoRedoStack<T>(private val maxCapacity: Int = 80) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun pushUndo(snapshot: T) {
        if (undoStack.lastOrNull() == snapshot) return
        if (undoStack.size >= maxCapacity) {
            undoStack.removeFirst()
        }
        undoStack.addLast(snapshot)
        redoStack.clear()
    }

    fun undo(current: T): T? {
        if (undoStack.isEmpty()) return null
        redoStack.addLast(current)
        return undoStack.removeLast()
    }

    fun redo(current: T): T? {
        if (redoStack.isEmpty()) return null
        undoStack.addLast(current)
        return redoStack.removeLast()
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}

object DocumentFormatter {

    fun computeStats(text: String): TextStats {
        if (text.isEmpty()) return TextStats(words = 0, chars = 0, lines = 1)
        var words = 0
        var inWord = false
        var lines = 1
        for (ch in text) {
            if (ch == '\n') lines++
            if (ch.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                words++
            }
        }
        return TextStats(words = words, chars = text.length, lines = lines)
    }

    fun toggleInlineWrap(
        value: TextFieldValue,
        prefix: String,
        suffix: String,
        placeholder: String
    ): TextFieldValue {
        val text = value.text
        val start = minOf(value.selection.start, value.selection.end).coerceIn(0, text.length)
        val end = maxOf(value.selection.start, value.selection.end).coerceIn(0, text.length)

        // Check if selection is already immediately wrapped by prefix/suffix
        if (start >= prefix.length && end + suffix.length <= text.length) {
            val before = text.substring(start - prefix.length, start)
            val after = text.substring(end, end + suffix.length)
            if (before == prefix && after == suffix) {
                val updated = text.removeRange(end, end + suffix.length)
                    .removeRange(start - prefix.length, start)
                return TextFieldValue(
                    text = updated,
                    selection = TextRange(start - prefix.length, end - prefix.length)
                )
            }
        }

        return if (start == end) {
            val inserted = "$prefix$placeholder$suffix"
            val updated = text.substring(0, start) + inserted + text.substring(end)
            val selStart = start + prefix.length
            val selEnd = selStart + placeholder.length
            TextFieldValue(text = updated, selection = TextRange(selStart, selEnd))
        } else {
            val selected = text.substring(start, end)
            val updated = text.substring(0, start) + prefix + selected + suffix + text.substring(end)
            TextFieldValue(
                text = updated,
                selection = TextRange(start + prefix.length, end + prefix.length)
            )
        }
    }

    fun cycleHeading(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val cursor = value.selection.start.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let {
            if (it == -1 || cursor == 0) 0 else it + 1
        }
        val lineEnd = text.indexOf('\n', cursor).let { if (it == -1) text.length else it }
        val line = text.substring(lineStart, lineEnd)

        val (oldPrefixLen, newPrefix) = when {
            line.startsWith("### ") -> 4 to ""
            line.startsWith("## ") -> 3 to "### "
            line.startsWith("# ") -> 2 to "## "
            else -> 0 to "# "
        }

        val updatedLine = newPrefix + line.drop(oldPrefixLen)
        val updatedText = text.substring(0, lineStart) + updatedLine + text.substring(lineEnd)
        val delta = newPrefix.length - oldPrefixLen
        val newCursor = (cursor + delta).coerceIn(lineStart, lineStart + updatedLine.length)
        return TextFieldValue(text = updatedText, selection = TextRange(newCursor))
    }

    fun toggleLinePrefix(value: TextFieldValue, bullet: Boolean): TextFieldValue {
        val text = value.text
        val selStart = minOf(value.selection.start, value.selection.end).coerceIn(0, text.length)
        val selEnd = maxOf(value.selection.start, value.selection.end).coerceIn(0, text.length)

        val blockStart = if (selStart == 0) 0 else text.lastIndexOf('\n', selStart - 1).let {
            if (it == -1) 0 else it + 1
        }
        val blockEnd = text.indexOf('\n', selEnd).let { if (it == -1) text.length else it }

        val lines = text.substring(blockStart, blockEnd).split('\n')
        val regexNumbered = Regex("^\\d+\\.\\s+")

        val allPrefixed = lines.all { line ->
            if (bullet) line.startsWith("- ") else regexNumbered.containsMatchIn(line)
        }

        val transformedLines = lines.mapIndexed { idx, line ->
            if (allPrefixed) {
                if (bullet) line.removePrefix("- ") else regexNumbered.replaceFirst(line, "")
            } else {
                val stripped = line.removePrefix("- ").let { regexNumbered.replaceFirst(it, "") }
                if (bullet) "- $stripped" else "${idx + 1}. $stripped"
            }
        }

        val replacement = transformedLines.joinToString("\n")
        val updatedText = text.substring(0, blockStart) + replacement + text.substring(blockEnd)
        val newCursor = (blockStart + replacement.length).coerceIn(0, updatedText.length)
        return TextFieldValue(text = updatedText, selection = TextRange(newCursor))
    }

    fun findMatchRanges(text: String, query: String, matchCase: Boolean): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val matches = mutableListOf<IntRange>()
        var index = 0
        while (index <= text.length - query.length) {
            val found = text.indexOf(query, startIndex = index, ignoreCase = !matchCase)
            if (found < 0) break
            matches.add(found until (found + query.length))
            index = found + query.length.coerceAtLeast(1)
        }
        return matches
    }

    fun replaceSingle(text: String, range: IntRange, replacement: String): String {
        if (range.first < 0 || range.last >= text.length) return text
        return text.substring(0, range.first) + replacement + text.substring(range.last + 1)
    }

    fun replaceAll(
        text: String,
        query: String,
        replacement: String,
        matchCase: Boolean
    ): Pair<String, Int> {
        val matches = findMatchRanges(text, query, matchCase)
        if (matches.isEmpty()) return text to 0
        val sb = StringBuilder(text.length)
        var cursor = 0
        for (range in matches) {
            sb.append(text, cursor, range.first)
            sb.append(replacement)
            cursor = range.last + 1
        }
        sb.append(text, cursor, text.length)
        return sb.toString() to matches.size
    }

    fun renderRichPreview(
        markdown: String,
        accentColor: Color,
        mutedColor: Color
    ): AnnotatedString {
        return buildAnnotatedString {
            val lines = markdown.split('\n')
            lines.forEachIndexed { lineIdx, rawLine ->
                when {
                    rawLine.startsWith("### ") -> {
                        withStyle(
                            SpanStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = accentColor
                            )
                        ) {
                            appendInlineFormatted(rawLine.removePrefix("### "))
                        }
                    }
                    rawLine.startsWith("## ") -> {
                        withStyle(
                            SpanStyle(
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                        ) {
                            appendInlineFormatted(rawLine.removePrefix("## "))
                        }
                    }
                    rawLine.startsWith("# ") -> {
                        withStyle(
                            SpanStyle(
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold
                            )
                        ) {
                            appendInlineFormatted(rawLine.removePrefix("# "))
                        }
                    }
                    rawLine.startsWith("- ") || rawLine.startsWith("* ") -> {
                        withStyle(SpanStyle(color = accentColor, fontWeight = FontWeight.Bold)) {
                            append("  •  ")
                        }
                        appendInlineFormatted(rawLine.drop(2))
                    }
                    rawLine.matches(Regex("^\\d+\\.\\s+.*")) -> {
                        val dotIdx = rawLine.indexOf('.')
                        val numPart = rawLine.substring(0, dotIdx + 1)
                        withStyle(SpanStyle(color = accentColor, fontWeight = FontWeight.SemiBold)) {
                            append("  $numPart ")
                        }
                        appendInlineFormatted(rawLine.substring(dotIdx + 2))
                    }
                    rawLine.trim() == "---" -> {
                        withStyle(SpanStyle(color = mutedColor)) {
                            append("────────────────────")
                        }
                    }
                    else -> {
                        appendInlineFormatted(rawLine)
                    }
                }
                if (lineIdx < lines.lastIndex) {
                    append('\n')
                }
            }
        }
    }

    private fun AnnotatedString.Builder.appendInlineFormatted(line: String) {
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("**", i) -> {
                    val end = line.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendInlineFormatted(line.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append("**")
                        i += 2
                    }
                }
                line.startsWith("<u>", i) -> {
                    val end = line.indexOf("</u>", i + 3)
                    if (end != -1) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                            appendInlineFormatted(line.substring(i + 3, end))
                        }
                        i = end + 4
                    } else {
                        append("<u>")
                        i += 3
                    }
                }
                line.startsWith("*", i) -> {
                    val end = line.indexOf("*", i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            appendInlineFormatted(line.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append('*')
                        i++
                    }
                }
                else -> {
                    append(line[i])
                    i++
                }
            }
        }
    }
}
