package com.zbinfinn.tokenizer

import kotlin.test.Test
import kotlin.test.assertTrue

class TokenizerTest {

    @Test
    fun `tokenizer handles boolean operators without looping`() {
        val code = """
            mod main;
            fn f() {
                val a = true;
                val b = false;
                if (a && b || !a) { }
            }
        """.trimIndent()

        val tokens = Tokenizer(code).tokenize()
        assertTrue(tokens.any { it.type == TokenType.ANDAND })
        assertTrue(tokens.any { it.type == TokenType.OROR })
        assertTrue(tokens.any { it.type == TokenType.BANG })
    }

    @Test
    fun `tokenizer handles arithmetic operators`() {
        val code = """
            mod main;
            fn f() {
                val x = 1 + 2 - 3 * 4 / 5 ^ 6;
            }
        """.trimIndent()

        val tokenTypes = Tokenizer(code).tokenize().map { it.type }
        assertTrue(TokenType.PLUS in tokenTypes)
        assertTrue(TokenType.MINUS in tokenTypes)
        assertTrue(TokenType.STAR in tokenTypes)
        assertTrue(TokenType.SLASH in tokenTypes)
        assertTrue(TokenType.CARET in tokenTypes)
    }
}
