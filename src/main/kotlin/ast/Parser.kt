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

    private fun parseFunction(): Ast.FunctionDecl {
        val annotations = mutableListOf<Ast.Annotation>()

        while (match(TokenType.AT)) {
            annotations += parseAnnotation()
        }

        expect(TokenType.FN, "Expected 'fn'")
        val name = expect(TokenType.IDENT, "Expected function name").lexeme

        expect(TokenType.LPAREN, "Expected '(")
        // TODO: Arguments
        expect(TokenType.RPAREN, "Expected ')")

        val block = parseBlock()

        return Ast.FunctionDecl(name, annotations, block)
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

    private fun parseStatement(): Ast.Statement {
        when(peek().type) {
            TokenType.VAL -> {
                match(TokenType.VAL)
                val identifier = expect(TokenType.IDENT, "Expected identifier").lexeme
                expect(TokenType.EQ, "Expected '='")
                val expression = parseExpression()
                expect(TokenType.SEMI, "Expected ';'")

                return Ast.ImmutableAssignment(identifier, expression)
            }
            else -> error("Unexpected token ${peek()} for statement")
        }
    }

    private fun parseExpression(): Ast.Expr {
        when (peek().type) {
            TokenType.STRING_LIT -> return Ast.StringExpr(consume().lexeme)
            TokenType.NUMBER_LIT -> return Ast.NumberExpr(consume().lexeme.toInt()) // TODO: toDouble or something depending on decimal
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
            } while(match(TokenType.COMMA))
            expect(TokenType.RPAREN, "Expected ')'")
        }

        return Ast.NamedAnnotation(identifier, arguments)
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