package com.example.domain

import kotlin.math.abs

object SpreadsheetEngine {

    fun createEmptyGrid(rows: Int = 12, cols: Int = 6): List<List<String>> {
        return List(rows) { List(cols) { "" } }
    }

    fun columnLabel(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.append(('A' + (n % 26)))
            n = (n / 26) - 1
        }
        return sb.reverse().toString()
    }

    fun cellName(row: Int, col: Int): String = "${columnLabel(col)}${row + 1}"

    fun parseCellRef(token: String): Pair<Int, Int>? {
        val cleaned = token.trim().uppercase()
        var i = 0
        while (i < cleaned.length && cleaned[i] in 'A'..'Z') i++
        if (i == 0 || i == cleaned.length) return null
        val colPart = cleaned.substring(0, i)
        val rowPart = cleaned.substring(i).toIntOrNull() ?: return null
        if (rowPart <= 0) return null

        var col = 0
        for (ch in colPart) {
            col = col * 26 + (ch - 'A' + 1)
        }
        return (rowPart - 1) to (col - 1)
    }

    fun parseCsv(csv: String): List<List<String>> {
        if (csv.isEmpty()) return createEmptyGrid(rows = 12, cols = 6)

        val rows = mutableListOf<MutableList<String>>()
        var currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < csv.length) {
            val ch = csv[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < csv.length && csv[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow.add(field.toString())
                        field.clear()
                    }
                    '\r' -> {
                        if (i + 1 < csv.length && csv[i + 1] == '\n') i++
                        currentRow.add(field.toString())
                        field.clear()
                        rows.add(currentRow)
                        currentRow = mutableListOf()
                    }
                    '\n' -> {
                        currentRow.add(field.toString())
                        field.clear()
                        rows.add(currentRow)
                        currentRow = mutableListOf()
                    }
                    else -> field.append(ch)
                }
            }
            i++
        }

        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(field.toString())
            rows.add(currentRow)
        }

        if (rows.isEmpty()) return createEmptyGrid(rows = 12, cols = 6)

        val maxCols = maxOf(6, rows.maxOf { it.size })
        val normalized = rows.map { row ->
            if (row.size < maxCols) row + List(maxCols - row.size) { "" } else row
        }.toMutableList()

        while (normalized.size < 8) {
            normalized.add(List(maxCols) { "" })
        }
        return normalized
    }

    fun serializeCsv(grid: List<List<String>>): String {
        return grid.joinToString("\n") { row ->
            row.joinToString(",") { cell ->
                if (cell.contains(',') || cell.contains('"') || cell.contains('\n') || cell.contains('\r')) {
                    "\"${cell.replace("\"", "\"\"")}\""
                } else {
                    cell
                }
            }
        }
    }

    fun evaluateAll(grid: List<List<String>>): List<List<String>> {
        val cache = HashMap<Pair<Int, Int>, String>()
        return List(grid.size) { r ->
            List(grid[r].size) { c ->
                evaluateCell(r, c, grid, mutableSetOf(), cache)
            }
        }
    }

    fun evaluateCell(
        row: Int,
        col: Int,
        grid: List<List<String>>,
        visiting: MutableSet<Pair<Int, Int>> = mutableSetOf(),
        cache: MutableMap<Pair<Int, Int>, String> = mutableMapOf()
    ): String {
        val key = row to col
        cache[key]?.let { return it }

        val raw = grid.getOrNull(row)?.getOrNull(col)?.trim().orEmpty()
        if (!raw.startsWith("=")) {
            cache[key] = raw
            return raw
        }

        if (!visiting.add(key)) return "#CYCLE!"

        val result = try {
            val expr = raw.drop(1).trim()
            if (expr.isEmpty()) "" else {
                val value = FormulaParser(expr, grid, visiting, cache).parseExpression()
                formatNumber(value)
            }
        } catch (e: FormulaException) {
            e.message ?: "#ERR!"
        } catch (_: Exception) {
            "#ERR!"
        } finally {
            visiting.remove(key)
        }

        cache[key] = result
        return result
    }

    fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "#ERR!"
        val roundedLong = value.toLong()
        return if (abs(value - roundedLong.toDouble()) < 1e-9) {
            roundedLong.toString()
        } else {
            val formatted = String.format(java.util.Locale.US, "%.4f", value)
            formatted.trimEnd('0').trimEnd('.')
        }
    }

    private class FormulaException(code: String) : RuntimeException(code)

    private class FormulaParser(
        private val input: String,
        private val grid: List<List<String>>,
        private val visiting: MutableSet<Pair<Int, Int>>,
        private val cache: MutableMap<Pair<Int, Int>, String>
    ) {
        private var pos = 0

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipWhitespace()
                when {
                    match('+') -> value += parseTerm()
                    match('-') -> value -= parseTerm()
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                skipWhitespace()
                when {
                    match('*') -> value *= parseFactor()
                    match('/') -> {
                        val denom = parseFactor()
                        if (abs(denom) < 1e-12) throw FormulaException("#DIV/0!")
                        value /= denom
                    }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            skipWhitespace()
            if (match('+')) return parseFactor()
            if (match('-')) return -parseFactor()
            if (match('(')) {
                val inside = parseExpression()
                skipWhitespace()
                if (!match(')')) throw FormulaException("#ERR!")
                return inside
            }

            // Number literal
            val start = pos
            if (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
                while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
                return input.substring(start, pos).toDoubleOrNull() ?: throw FormulaException("#ERR!")
            }

            // Identifier: Function or Cell Reference
            if (pos < input.length && input[pos].isLetter()) {
                while (pos < input.length && (input[pos].isLetterOrDigit())) pos++
                val ident = input.substring(start, pos).uppercase()
                skipWhitespace()
                return if (match('(')) {
                    evaluateFunction(ident)
                } else {
                    val ref = parseCellRef(ident) ?: throw FormulaException("#REF!")
                    cellAsDouble(ref.first, ref.second) ?: 0.0
                }
            }

            throw FormulaException("#ERR!")
        }

        private fun evaluateFunction(name: String): Double {
            val values = mutableListOf<Double>()
            skipWhitespace()
            if (!match(')')) {
                while (true) {
                    skipWhitespace()
                    // Check if argument is a range like A1:B5
                    val rangeStart = pos
                    val maybeRange = readRangeOrArg()
                    if (maybeRange != null) {
                        values.addAll(maybeRange)
                    } else {
                        pos = rangeStart
                        values.add(parseExpression())
                    }
                    skipWhitespace()
                    if (match(',')) continue
                    if (match(')')) break
                    throw FormulaException("#ERR!")
                }
            }

            return when (name) {
                "SUM" -> values.sum()
                "AVERAGE", "AVG" -> if (values.isEmpty()) throw FormulaException("#DIV/0!") else values.average()
                "COUNT" -> values.size.toDouble()
                "MIN" -> values.minOrNull() ?: 0.0
                "MAX" -> values.maxOrNull() ?: 0.0
                else -> throw FormulaException("#NAME?")
            }
        }

        private fun readRangeOrArg(): List<Double>? {
            val saved = pos
            val firstToken = readIdentToken() ?: return null
            skipWhitespace()
            if (!match(':')) {
                pos = saved
                return null
            }
            skipWhitespace()
            val secondToken = readIdentToken() ?: run {
                pos = saved
                return null
            }
            val startRef = parseCellRef(firstToken) ?: throw FormulaException("#REF!")
            val endRef = parseCellRef(secondToken) ?: throw FormulaException("#REF!")
            val rMin = minOf(startRef.first, endRef.first)
            val rMax = maxOf(startRef.first, endRef.first)
            val cMin = minOf(startRef.second, endRef.second)
            val cMax = maxOf(startRef.second, endRef.second)

            val collected = mutableListOf<Double>()
            for (r in rMin..rMax) {
                for (c in cMin..cMax) {
                    cellAsDouble(r, c)?.let { collected.add(it) }
                }
            }
            return collected
        }

        private fun readIdentToken(): String? {
            skipWhitespace()
            val start = pos
            while (pos < input.length && input[pos].isLetterOrDigit()) pos++
            return if (pos > start) input.substring(start, pos) else null
        }

        private fun cellAsDouble(row: Int, col: Int): Double? {
            if (row !in grid.indices || col !in grid[row].indices) return null
            val evaluated = evaluateCell(row, col, grid, visiting, cache)
            if (evaluated.startsWith("#")) throw FormulaException(evaluated)
            if (evaluated.isBlank()) return null
            return evaluated.toDoubleOrNull()
        }

        private fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        private fun match(expected: Char): Boolean {
            if (pos < input.length && input[pos] == expected) {
                pos++
                return true
            }
            return false
        }
    }
}
