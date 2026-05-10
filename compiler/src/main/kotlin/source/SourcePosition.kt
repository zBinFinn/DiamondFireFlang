package com.zbinfinn.source

data class SourcePosition(
    val line: Int,
    val character: Int,
)

data class SourceRange(
    val start: Int,
    val end: Int,
)

class SourceDocument(
    val uri: String,
    val text: String,
) {
    private val lineStarts: List<Int> = buildList {
        add(0)
        text.forEachIndexed { index, char ->
            if (char == '\n') {
                add(index + 1)
            }
        }
    }

    fun offsetToPosition(offset: Int): SourcePosition {
        val safeOffset = offset.coerceIn(0, text.length)
        val lineIndex = lineStarts.binarySearch(safeOffset).let {
            if (it >= 0) it else -it - 2
        }.coerceAtLeast(0)
        return SourcePosition(
            line = lineIndex,
            character = safeOffset - lineStarts[lineIndex],
        )
    }

    fun positionToOffset(line: Int, character: Int): Int {
        val safeLine = line.coerceIn(0, lineStarts.lastIndex)
        val lineStart = lineStarts[safeLine]
        val lineEnd = if (safeLine + 1 < lineStarts.size) {
            (lineStarts[safeLine + 1] - 1).coerceAtLeast(lineStart)
        } else {
            text.length
        }
        return (lineStart + character.coerceAtLeast(0)).coerceIn(lineStart, lineEnd)
    }

    fun wholeDocumentRange(): SourceRange =
        SourceRange(0, text.length.coerceAtLeast(1))
}
