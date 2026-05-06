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

        val assignment = assertIs<Ast.VariableDeclaration>(use.body.statements.single())
        assertEquals(false, assignment.mutable)
        assertIs<Ast.FunctionCallExpr>(assignment.expression)
    }

    @Test
    fun `parses mutable declaration and explicit mutable parameters`() {
        val code = """
            mod main;
            fn mutate(var mutable: Number, val immutable: Number) {
                var x = mutable;
                x = immutable;
            }
        """.trimIndent()

        val fn = Parser(Tokenizer(code).tokenize()).parseProgram().functions.single()

        assertEquals(true, fn.parameters[0].mutable)
        assertEquals(false, fn.parameters[1].mutable)
        val declaration = assertIs<Ast.VariableDeclaration>(fn.body.statements[0])
        assertEquals(true, declaration.mutable)
        assertIs<Ast.VariableAssignment>(fn.body.statements[1])
    }

    @Test
    fun `function parameters require val or var`() {
        val code = """
            mod main;
            fn old(x: Number) {}
        """.trimIndent()

        kotlin.test.assertFailsWith<IllegalStateException> {
            Parser(Tokenizer(code).tokenize()).parseProgram()
        }
    }
}
