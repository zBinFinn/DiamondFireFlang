package com.zbinfinn.ast

import com.zbinfinn.tokenizer.Tokenizer
import kotlin.test.Test
import kotlin.test.assertTrue

class ParserIfTest {

    @Test
    fun `parses if with boolean expressions`() {
        val code = """
            mod main;
            fn f() {
                val a = true;
                val b = false;
                if (a && b) { }
                if (a || b) { }
                if (!a) { }
            }
        """.trimIndent()

        val program = Parser(Tokenizer(code).tokenize()).parseProgram()
        val stmts = program.functions.single().body.statements
        assertTrue(stmts.any { it is Ast.IfStmt })
    }
}

