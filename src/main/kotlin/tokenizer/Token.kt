package com.zbinfinn.tokenizer

data class Token(
    val type: TokenType,
    val lexeme: String,
    val position: Int
)
