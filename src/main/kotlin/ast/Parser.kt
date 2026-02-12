package com.zbinfinn.ast

import com.zbinfinn.tokenizer.Token
import com.zbinfinn.tokenizer.TokenType

class Parser(
    private val tokens: List<Token>,
) {
    private var index = 0

    private fun peek(ahead: Int = 0): Token = tokens[index + ahead]
    private fun canPeek(ahead: UInt = 0u): Boolean = index + ahead.toInt() < tokens.size
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
        val module = parseModule();
        val imports = mutableListOf<Ast.Import>()
        val functions = mutableListOf<Ast.FunctionDecl>()
        val dicts = mutableListOf<Ast.DictDecl>()

        while (match(TokenType.IMPORT)) {
            imports += parseImport();
        }

        while (peek().type != TokenType.EOF) {
            when {
                peek().type == TokenType.FN || peek().type == TokenType.AT -> functions += parseFunction();
                peek().type == TokenType.DICT -> dicts += parseDict();
                else -> error("Unexpected token '${peek()}'")
            }
        }

        return Ast.Program(module, imports, dicts, functions)
    }

    private fun parseDict(): Ast.DictDecl {
        expect(TokenType.DICT, "Expected 'dict'")
        val dictName = expect(TokenType.IDENT, "Expected dict identifier").lexeme
        expect(TokenType.LBRACE, "Expected '{' after dict name")
        val fields = mutableListOf<Ast.Field>()
        do {
            fields += parseField();
        } while (match(TokenType.COMMA))
        expect(TokenType.RBRACE, "Expected '}' after dict fields")

        return Ast.DictDecl(dictName, fields)
    }

    private fun parseField(): Ast.Field {
        val name = expect(TokenType.IDENT, "Expected field name").lexeme
        expect(TokenType.COLON, "Expected ':' after field name")
        val type = parseType()
        return Ast.Field(name, type)
    }

    private fun parseType(): Ast.Type {
        val type = expect(TokenType.IDENT, "Expected type").lexeme
        return Ast.Type(type)
    }

    private fun parseModule(): Ast.ModuleDecl {
        expect(TokenType.MOD, "Expected module declaration: 'mod <path>;'")
        val path = mutableListOf<String>()
        do {
            path.add(expect(TokenType.IDENT, "Expected identifier as part of module declaration").lexeme)
        } while (match(TokenType.DOT))

        expect(TokenType.SEMI, "Expected ';' at the end of a module declaration")
        return Ast.ModuleDecl(
            path.joinToString(separator = ", ")
        )
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
            } while (peek().type != TokenType.RPAREN)
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
                val functionCall =
                    tryParseFunctionCall(expectSemi = false) ?: error("Expected selector function call after 'with'")
                val block = parseBlock()
                return Ast.WithBlock(functionCall, block)
            }

            TokenType.IDENT -> {
                if (canPeek(3u)
                    && peek(1).type == TokenType.DOT
                    && peek(2).type == TokenType.IDENT
                    && peek(3).type == TokenType.EQ
                ) {
                    val identifier = consume().lexeme
                    expect(TokenType.DOT, "Expected '.'")
                    val field = consume().lexeme
                    expect(TokenType.EQ, "Expected '='")
                    val expression = parseExpression()
                    expect(TokenType.SEMI, "Expected ';'")

                    Ast.FieldAssignment(
                        Ast.IdentifierExpr(identifier),
                        field,
                        expression
                    )
                }

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
        return parsePostfixExpression()
    }

    private fun parsePostfixExpression(): Ast.Expr {
        var expr = parsePrimaryExpression()

        while (match(TokenType.DOT)) {
            val fieldName = expect(TokenType.IDENT, "Expected field name after '.'").lexeme

            expr = Ast.FieldAccessExpr(
                receiver = expr,
                field = fieldName,
            )
        }

        return expr
    }

    private fun parsePrimaryExpression(): Ast.Expr {
        return when (peek().type) {
            TokenType.STRING_LIT -> Ast.StringExpr(consume().lexeme)
            TokenType.NUMBER_LIT -> Ast.NumberExpr(consume().lexeme.toDouble())
            TokenType.IDENT -> {
                val identifier = consume().lexeme
                if (match(TokenType.LBRACE)) {
                    val entries = mutableListOf<Ast.DictLiteralExpr.Entry>()
                    do {
                        entries += parseDictLiteralEntry();
                    } while (match(TokenType.COMMA))
                    expect(TokenType.RBRACE, "Expected '}'")

                    return Ast.DictLiteralExpr(identifier, entries)
                }

                return Ast.IdentifierExpr(identifier)
            }

            TokenType.LPAREN -> {
                consume()
                val expr = parseExpression()
                expect(TokenType.RPAREN, "Expected ')'")
                expr
            }

            else -> error("Unexpected Token ${peek()} for expression")
        }
    }

    private fun parseDictLiteralEntry(): Ast.DictLiteralExpr.Entry {
        val fieldName = expect(TokenType.IDENT, "Expected field name").lexeme
        expect(TokenType.COLON, "Expected ':'")
        val value = parseExpression()

        return Ast.DictLiteralExpr.Entry(fieldName, value)
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