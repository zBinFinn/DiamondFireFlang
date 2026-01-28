package com.zbinfinn.ast

import com.zbinfinn.tokenizer.Token
import com.zbinfinn.tokenizer.TokenType

class Parser(
    private val tokens: List<Token>,
) {
    private var index = 0

    private fun peek(): Token = tokens[index]
    private fun consume(): Token = tokens[index++]

    private fun match(type: TokenType): Boolean {
        if (peek().type == type) {
            consume()
            return true
        }
        return false
    }

    private fun expect(type: TokenType, msg: String): Token {
        if (peek().type != type) {
            error("$msg at ${peek().position}")
        }
        return consume()
    }

    fun parseProgram(): Ast.Program {
        val imports = mutableListOf<Ast.Import>()
        val functions = mutableListOf<Ast.FunctionDecl>()

        while (match(TokenType.IMPORT)) {
            imports += parseImport();
        }

        while (peek().type != TokenType.EOF) {
            functions += parseFunction();
        }

        return Ast.Program(imports, functions)
    }

    private fun parseFunctionParameter(): Ast.Parameter {
        val mutable = match(TokenType.MUT)
        val identifier = expect(TokenType.IDENT, "Expected parameter name").lexeme
        expect(TokenType.COLON, "Expected ':' after parameter name")
        val type = expect(TokenType.IDENT, "Expected parameter type").lexeme
        return Ast.Parameter(
            identifier,
            Ast.Type(type),
            mutable
        )
    }

    private fun parseFunction(): Ast.FunctionDecl {
        val annotations = mutableListOf<Ast.Annotation>()

        while (match(TokenType.AT)) {
            annotations += parseAnnotation()
        }

        expect(TokenType.FN, "Expected 'fn'")
        val name = expect(TokenType.IDENT, "Expected function name").lexeme

        expect(TokenType.LPAREN, "Expected '(")
        val parameters = mutableListOf<Ast.Parameter>()
        if (peek().type != TokenType.RPAREN) {
            do {
                parameters.add(parseFunctionParameter())
            } while(peek().type != TokenType.RPAREN)
        }
        expect(TokenType.RPAREN, "Expected ')'")

        val block = parseBlock()

        return Ast.FunctionDecl(name, annotations, parameters, block)
    }

    private fun parseBlock(): Ast.Block {
        val statements = mutableListOf<Ast.Statement>()
        expect(TokenType.LBRACE, "Expected '{'")
        while (peek().type != TokenType.RBRACE) {
            val statement = parseStatement()
            statements += statement
        }
        expect(TokenType.RBRACE, "Expected '}'")
        return Ast.Block(statements)
    }

    private fun tryParseFunctionCall(expectSemi: Boolean): Ast.FunctionCall? {
        val identifier = expect(TokenType.IDENT, "Expected identifier").lexeme
        if (match(TokenType.LPAREN)) {
            val arguments = mutableListOf<Ast.Expr>()
            if (!match(TokenType.RPAREN)) {
                do {
                    arguments += parseExpression()
                } while (match(TokenType.COMMA))
                expect(TokenType.RPAREN, "Expected ')'")
            }
            if (expectSemi) {
                expect(TokenType.SEMI, "Expected ';'")
            }
            return Ast.FunctionCall(identifier, arguments)
        }
        unconsume(1)
        return null
    }

    private fun unconsume(amount: Int) {
        index -= amount
    }

    private fun parseStatement(): Ast.Statement {
        when (peek().type) {
            TokenType.VAL -> {
                match(TokenType.VAL)
                val identifier = expect(TokenType.IDENT, "Expected identifier").lexeme
                expect(TokenType.EQ, "Expected '='")
                val expression = parseExpression()
                expect(TokenType.SEMI, "Expected ';'")

                return Ast.ImmutableAssignment(identifier, expression)
            }

            TokenType.WITH -> {
                match(TokenType.WITH)
                val functionCall = tryParseFunctionCall(expectSemi = false) ?: error("Expected selector function call after 'with'")
                val block = parseBlock()
                return Ast.WithBlock(functionCall, block)
            }

            TokenType.IDENT -> {
                val functionCall = tryParseFunctionCall(expectSemi = true)
                if (functionCall != null) {
                    return functionCall
                }
                TODO()
            }

            else -> error("Unexpected token ${peek()} for statement")
        }
    }

    private fun parseExpression(): Ast.Expr {
        return when (peek().type) {
            TokenType.STRING_LIT -> Ast.StringExpr(consume().lexeme)
            TokenType.NUMBER_LIT -> Ast.NumberExpr(consume().lexeme.toDouble())
            TokenType.IDENT -> Ast.IdentifierExpr(consume().lexeme)
            else -> error("Unexpected Token ${peek()} for expression")
        }
    }

    private fun parseAnnotation(): Ast.Annotation {
        val identifier = expect(TokenType.IDENT, "Expected annotation identifier").lexeme
        val arguments = mutableListOf<Ast.Expr>()
        if (match(TokenType.LPAREN)) {
            do {
                val expression = parseExpression()
                arguments.add(expression)
            } while (match(TokenType.COMMA))
            expect(TokenType.RPAREN, "Expected ')'")
        }

        return Ast.Annotation(identifier, arguments)
    }

    private fun parseImport(): Ast.Import {
        val path = StringBuilder()
        while (peek().type == TokenType.IDENT) {
            val identifier = expect(TokenType.IDENT, "Expected identifier").lexeme
            path.append(identifier)
            if (!match(TokenType.DOT)) {
                break
            }
            path.append('.')
        }

        expect(TokenType.SEMI, "Missing semicolon terminating import statement")
        return Ast.Import(path.toString())
    }
}