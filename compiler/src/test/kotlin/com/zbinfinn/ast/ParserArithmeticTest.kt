package com.zbinfinn.ast

import com.zbinfinn.tokenizer.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ParserArithmeticTest {

    @Test
    fun `multiplication binds tighter than addition`() {
        val expr = parseDeclaredExpression("1 + 2 * 3")

        val add = assertIs<Ast.BinaryExpr>(expr)
        assertEquals(Ast.BinaryOp.Add, add.op)
        assertIs<Ast.NumberExpr>(add.left)
        val multiply = assertIs<Ast.BinaryExpr>(add.right)
        assertEquals(Ast.BinaryOp.Mul, multiply.op)
    }

    @Test
    fun `parentheses override arithmetic precedence`() {
        val expr = parseDeclaredExpression("(1 + 2) * 3")

        val multiply = assertIs<Ast.BinaryExpr>(expr)
        assertEquals(Ast.BinaryOp.Mul, multiply.op)
        val add = assertIs<Ast.BinaryExpr>(multiply.left)
        assertEquals(Ast.BinaryOp.Add, add.op)
        assertIs<Ast.NumberExpr>(multiply.right)
    }

    @Test
    fun `power is right associative`() {
        val expr = parseDeclaredExpression("2 ^ 3 ^ 2")

        val power = assertIs<Ast.BinaryExpr>(expr)
        assertEquals(Ast.BinaryOp.Pow, power.op)
        assertIs<Ast.NumberExpr>(power.left)
        val rightPower = assertIs<Ast.BinaryExpr>(power.right)
        assertEquals(Ast.BinaryOp.Pow, rightPower.op)
    }

    @Test
    fun `power binds tighter than unary minus`() {
        val expr = parseDeclaredExpression("-2 ^ 2")

        val negate = assertIs<Ast.UnaryExpr>(expr)
        assertEquals(Ast.UnaryOp.Negate, negate.op)
        val power = assertIs<Ast.BinaryExpr>(negate.expr)
        assertEquals(Ast.BinaryOp.Pow, power.op)
    }

    private fun parseDeclaredExpression(sourceExpr: String): Ast.Expr {
        val code = """
            mod main;
            fn f() {
                val x = $sourceExpr;
            }
        """.trimIndent()

        val stmt = Parser(Tokenizer(code).tokenize()).parseProgram().functions.single().body.statements.single()
        return assertIs<Ast.VariableDeclaration>(stmt).expression
    }
}
