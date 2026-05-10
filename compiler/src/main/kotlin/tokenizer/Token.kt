package com.zbinfinn.tokenizer

import com.zbinfinn.source.SourceRange

data class Token(
    val type: TokenType,
    val lexeme: String,
    val position: Int,
    val endPosition: Int = position + lexeme.length,
) {
    val range: SourceRange get() = SourceRange(position, endPosition)
}
