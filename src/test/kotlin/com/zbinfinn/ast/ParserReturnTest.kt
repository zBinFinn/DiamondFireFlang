package com.zbinfinn.ast

import com.zbinfinn.tokenizer.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ParserReturnTest {

    @Test
    fun `parses function return type return statement and call expression`() {
        val code = """
            mod main;
            fn get5(): Number {
                return 5;
            }
            fn use() {
                val x = get5();
            }
        """.trimIndent()

        val program = Parser(Tokenizer(code).tokenize()).parseProgram()
        val get5 = program.functions.first { it.name == "get5" }
        val use = program.functions.first { it.name == "use" }

        assertEquals("Number", get5.returnType?.identifier)
        assertIs<Ast.ReturnStmt>(get5.body.statements.single())

        val assignment = assertIs<Ast.ImmutableAssignment>(use.body.statements.single())
        assertIs<Ast.FunctionCallExpr>(assignment.expression)
    }
}
