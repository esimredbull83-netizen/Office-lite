package com.example.domain

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import java.io.OutputStream

object DocumentPdfExporter {

    private const val PAGE_WIDTH = 595 // Standard A4 width in PostScript points (72 DPI)
    private const val PAGE_HEIGHT = 842 // Standard A4 height in PostScript points (72 DPI)
    private const val MARGIN_HORIZONTAL = 48f
    private const val MARGIN_TOP = 54f
    private const val MARGIN_BOTTOM = 54f
    private const val CONTENT_WIDTH = (PAGE_WIDTH - MARGIN_HORIZONTAL * 2).toInt()
    private const val MAX_CONTENT_Y = PAGE_HEIGHT - MARGIN_BOTTOM

    private enum class BlockKind {
        H1,
        H2,
        H3,
        BULLET,
        NUMBERED,
        DIVIDER,
        BODY
    }

    private data class ParsedBlock(
        val kind: BlockKind,
        val text: CharSequence,
        val prefix: String = ""
    )

    fun exportToPdf(
        title: String,
        markdownContent: String,
        outputStream: OutputStream
    ): Int {
        val pdfDocument = PdfDocument()
        try {
            val baseTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#18181B")
                textSize = 18f
                typeface = Typeface.create(baseTypeface, Typeface.BOLD)
            }
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#18181B")
                textSize = 11f
                typeface = baseTypeface
            }
            val h1Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#18181B")
                textSize = 17f
                typeface = Typeface.create(baseTypeface, Typeface.BOLD)
            }
            val h2Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1D4ED8")
                textSize = 14f
                typeface = Typeface.create(baseTypeface, Typeface.BOLD)
            }
            val h3Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1D4ED8")
                textSize = 12f
                typeface = Typeface.create(baseTypeface, Typeface.BOLD)
            }
            val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D6D3C9")
                strokeWidth = 1f
            }
            val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#71717A")
                textSize = 8.5f
                typeface = baseTypeface
            }

            val cleanTitle = title.substringBeforeLast('.').ifBlank { "Document" }
            var pageNumber = 1
            var currentPage = startNewPage(pdfDocument, pageNumber)
            var canvas = currentPage.canvas
            var cursorY = MARGIN_TOP

            // Draw document header on page 1
            val headerLayout = StaticLayout.Builder
                .obtain(cleanTitle, 0, cleanTitle.length, titlePaint, CONTENT_WIDTH)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.15f)
                .build()
            canvas.save()
            canvas.translate(MARGIN_HORIZONTAL, cursorY)
            headerLayout.draw(canvas)
            canvas.restore()
            cursorY += headerLayout.height + 8f
            canvas.drawLine(MARGIN_HORIZONTAL, cursorY, PAGE_WIDTH - MARGIN_HORIZONTAL, cursorY, dividerPaint)
            cursorY += 16f

            val blocks = parseBlocks(markdownContent)
            for (block in blocks) {
                if (block.kind == BlockKind.DIVIDER) {
                    if (cursorY + 18f > MAX_CONTENT_Y) {
                        drawFooter(canvas, cleanTitle, pageNumber, footerPaint)
                        pdfDocument.finishPage(currentPage)
                        pageNumber++
                        currentPage = startNewPage(pdfDocument, pageNumber)
                        canvas = currentPage.canvas
                        cursorY = MARGIN_TOP
                    }
                    cursorY += 6f
                    canvas.drawLine(
                        MARGIN_HORIZONTAL,
                        cursorY,
                        PAGE_WIDTH - MARGIN_HORIZONTAL,
                        cursorY,
                        dividerPaint
                    )
                    cursorY += 12f
                    continue
                }

                val (paint, indentPx, spaceAfter) = when (block.kind) {
                    BlockKind.H1 -> Triple(h1Paint, 0f, 8f)
                    BlockKind.H2 -> Triple(h2Paint, 0f, 6f)
                    BlockKind.H3 -> Triple(h3Paint, 0f, 5f)
                    BlockKind.BULLET, BlockKind.NUMBERED -> Triple(bodyPaint, 18f, 4f)
                    BlockKind.BODY -> Triple(bodyPaint, 0f, 5f)
                    BlockKind.DIVIDER -> Triple(bodyPaint, 0f, 0f)
                }

                if (block.text.isEmpty()) {
                    cursorY += 10f
                    if (cursorY > MAX_CONTENT_Y) {
                        drawFooter(canvas, cleanTitle, pageNumber, footerPaint)
                        pdfDocument.finishPage(currentPage)
                        pageNumber++
                        currentPage = startNewPage(pdfDocument, pageNumber)
                        canvas = currentPage.canvas
                        cursorY = MARGIN_TOP
                    }
                    continue
                }

                val availWidth = (CONTENT_WIDTH - indentPx).toInt().coerceAtLeast(100)
                val layout = StaticLayout.Builder
                    .obtain(block.text, 0, block.text.length, paint, availWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1.25f)
                    .build()

                // Draw line-by-line so long paragraphs wrap cleanly across page breaks
                for (lineIdx in 0 until layout.lineCount) {
                    val lineTop = layout.getLineTop(lineIdx)
                    val lineBottom = layout.getLineBottom(lineIdx)
                    val lineHeight = (lineBottom - lineTop).toFloat()

                    if (cursorY + lineHeight > MAX_CONTENT_Y) {
                        drawFooter(canvas, cleanTitle, pageNumber, footerPaint)
                        pdfDocument.finishPage(currentPage)
                        pageNumber++
                        currentPage = startNewPage(pdfDocument, pageNumber)
                        canvas = currentPage.canvas
                        cursorY = MARGIN_TOP
                    }

                    if (lineIdx == 0 && block.prefix.isNotEmpty()) {
                        val baselineOffset = (layout.getLineBaseline(lineIdx) - lineTop).toFloat()
                        canvas.drawText(
                            block.prefix,
                            MARGIN_HORIZONTAL,
                            cursorY + baselineOffset,
                            paint
                        )
                    }

                    canvas.save()
                    canvas.translate(MARGIN_HORIZONTAL + indentPx, cursorY - lineTop)
                    canvas.clipRect(0f, lineTop.toFloat(), availWidth.toFloat(), lineBottom.toFloat())
                    layout.draw(canvas)
                    canvas.restore()

                    cursorY += lineHeight
                }
                cursorY += spaceAfter
            }

            drawFooter(canvas, cleanTitle, pageNumber, footerPaint)
            pdfDocument.finishPage(currentPage)
            pdfDocument.writeTo(outputStream)
            return pageNumber
        } finally {
            pdfDocument.close()
        }
    }

    private fun startNewPage(doc: PdfDocument, pageNumber: Int): PdfDocument.Page {
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        val page = doc.startPage(pageInfo)
        page.canvas.drawColor(Color.WHITE)
        return page
    }

    private fun drawFooter(
        canvas: Canvas,
        title: String,
        pageNumber: Int,
        paint: TextPaint
    ) {
        val footerY = PAGE_HEIGHT - 28f
        canvas.drawText(title, MARGIN_HORIZONTAL, footerY, paint)
        val pageText = "Page $pageNumber"
        val textWidth = paint.measureText(pageText)
        canvas.drawText(pageText, PAGE_WIDTH - MARGIN_HORIZONTAL - textWidth, footerY, paint)
    }

    private fun parseBlocks(markdown: String): List<ParsedBlock> {
        val numberedRegex = Regex("^(\\d+\\.)\\s+(.*)$")
        return markdown.split('\n').map { rawLine ->
            when {
                rawLine.trim() == "---" -> ParsedBlock(BlockKind.DIVIDER, "")
                rawLine.startsWith("### ") -> ParsedBlock(
                    BlockKind.H3,
                    parseInlineSpans(rawLine.removePrefix("### "))
                )
                rawLine.startsWith("## ") -> ParsedBlock(
                    BlockKind.H2,
                    parseInlineSpans(rawLine.removePrefix("## "))
                )
                rawLine.startsWith("# ") -> ParsedBlock(
                    BlockKind.H1,
                    parseInlineSpans(rawLine.removePrefix("# "))
                )
                rawLine.startsWith("- ") || rawLine.startsWith("* ") -> ParsedBlock(
                    kind = BlockKind.BULLET,
                    text = parseInlineSpans(rawLine.drop(2)),
                    prefix = "•"
                )
                else -> {
                    val match = numberedRegex.matchEntire(rawLine)
                    if (match != null) {
                        ParsedBlock(
                            kind = BlockKind.NUMBERED,
                            text = parseInlineSpans(match.groupValues[2]),
                            prefix = match.groupValues[1]
                        )
                    } else {
                        ParsedBlock(BlockKind.BODY, parseInlineSpans(rawLine))
                    }
                }
            }
        }
    }

    internal fun parseInlineSpans(text: String): CharSequence {
        val builder = SpannableStringBuilder()
        appendFormatted(builder, text)
        return builder
    }

    private fun appendFormatted(builder: SpannableStringBuilder, input: String) {
        var i = 0
        while (i < input.length) {
            when {
                input.startsWith("**", i) -> {
                    val end = input.indexOf("**", i + 2)
                    if (end != -1) {
                        val startIdx = builder.length
                        appendFormatted(builder, input.substring(i + 2, end))
                        builder.setSpan(
                            StyleSpan(Typeface.BOLD),
                            startIdx,
                            builder.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        i = end + 2
                    } else {
                        builder.append("**")
                        i += 2
                    }
                }
                input.startsWith("<u>", i) -> {
                    val end = input.indexOf("</u>", i + 3)
                    if (end != -1) {
                        val startIdx = builder.length
                        appendFormatted(builder, input.substring(i + 3, end))
                        builder.setSpan(
                            UnderlineSpan(),
                            startIdx,
                            builder.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        i = end + 4
                    } else {
                        builder.append("<u>")
                        i += 3
                    }
                }
                input.startsWith("*", i) -> {
                    val end = input.indexOf("*", i + 1)
                    if (end != -1 && end > i + 1) {
                        val startIdx = builder.length
                        appendFormatted(builder, input.substring(i + 1, end))
                        builder.setSpan(
                            StyleSpan(Typeface.ITALIC),
                            startIdx,
                            builder.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        i = end + 1
                    } else {
                        builder.append('*')
                        i++
                    }
                }
                else -> {
                    builder.append(input[i])
                    i++
                }
            }
        }
    }
}
