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
        val impls = mutableListOf<Ast.ImplDecl>()

        while (match(TokenType.IMPORT)) {
            imports += parseImport();
        }

        while (peek().type != TokenType.EOF) {
            when {
                peek().type == TokenType.FN || peek().type == TokenType.AT -> functions += parseFunction();
                peek().type == TokenType.DICT -> dicts += parseDict();
                peek().type == TokenType.IMPL -> impls += parseImpl();
                else -> error("Unexpected token '${peek()}'")
            }
        }

        return Ast.Program(module, imports, dicts, impls, functions)
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
        val mutable = when {
            match(TokenType.VAL) -> false
            match(TokenType.VAR) -> true
            else -> error("Expected 'val' or 'var' before field name at ${peek().position}")
        }
        val name = expect(TokenType.IDENT, "Expected field name").lexeme
        expect(TokenType.COLON, "Expected ':' after field name")
        val type = parseType()
        return Ast.Field(name, type, mutable)
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

    private fun parseFunctionParameter(implicitThisType: String? = null): Ast.Parameter {
        val mutable = when {
            match(TokenType.VAL) -> false
            match(TokenType.VAR) -> true
            else -> error("Expected 'val' or 'var' before parameter name at ${peek().position}")
        }
        val identifier = expect(TokenType.IDENT, "Expected parameter name").lexeme
        if (identifier == "this") {
            if (implicitThisType == null) {
                error("'this' parameters are only allowed inside impl blocks")
            }
            if (peek().type == TokenType.COLON) {
                error("'this' parameter type is inferred from the impl block")
            }
            return Ast.Parameter(
                identifier,
                Ast.Type(implicitThisType),
                mutable
            )
        }
        expect(TokenType.COLON, "Expected ':' after parameter name")
        val type = expect(TokenType.IDENT, "Expected parameter type").lexeme
        return Ast.Parameter(
            identifier,
            Ast.Type(type),
            mutable
        )
    }

    private fun parseFunction(implicitThisType: String? = null): Ast.FunctionDecl {
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
                parameters.add(parseFunctionParameter(implicitThisType))
            } while (match(TokenType.COMMA))
        }
        expect(TokenType.RPAREN, "Expected ')'")

        val returnType = if (match(TokenType.COLON)) {
            parseType()
        } else {
            null
        }

        val block = parseBlock()

        return Ast.FunctionDecl(name, annotations, parameters, returnType, block)
    }

    private fun parseImpl(): Ast.ImplDecl {
        expect(TokenType.IMPL, "Expected 'impl'")
        val typeName = expect(TokenType.IDENT, "Expected impl type name").lexeme
        expect(TokenType.LBRACE, "Expected '{' after impl type name")
        val functions = mutableListOf<Ast.FunctionDecl>()
        while (peek().type != TokenType.RBRACE) {
            functions += parseFunction(typeName)
        }
        expect(TokenType.RBRACE, "Expected '}' after impl block")
        return Ast.ImplDecl(typeName, functions)
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
            TokenType.VAL -> return parseVariableDeclaration(mutable = false)
            TokenType.VAR -> return parseVariableDeclaration(mutable = true)

            TokenType.WITH -> {
                match(TokenType.WITH)
                val functionCall =
                    tryParseFunctionCall(expectSemi = false) ?: error("Expected selector function call after 'with'")
                val block = parseBlock()
                return Ast.WithBlock(functionCall, block)
            }

            TokenType.IF -> {
                return parseIfStmt()
            }

            TokenType.RETURN -> {
                consume()
                val expression = parseExpression()
                expect(TokenType.SEMI, "Expected ';'")
                return Ast.ReturnStmt(expression)
            }

            TokenType.IDENT -> {
                if (canPeek(1u) && peek(1).type == TokenType.EQ) {
                    val identifier = consume().lexeme
                    expect(TokenType.EQ, "Expected '='")
                    val expression = parseExpression()
                    expect(TokenType.SEMI, "Expected ';'")

                    return Ast.VariableAssignment(identifier, expression)
                }

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

                    return Ast.FieldAssignment(
                        Ast.IdentifierExpr(identifier),
                        field,
                        expression
                    )
                }

                val functionCall = tryParseFunctionCall(expectSemi = true)
                if (functionCall != null) {
                    return functionCall
                }

                val expr = parseExpression()
                expect(TokenType.SEMI, "Expected ';'")
                if (expr is Ast.MemberFunctionCall) {
                    return expr
                }
                error("Expected function call statement")
            }

            else -> error("Unexpected token ${peek()} for statement")
        }
    }

    private fun parseVariableDeclaration(mutable: Boolean): Ast.VariableDeclaration {
        consume()
        val identifier = expect(TokenType.IDENT, "Expected identifier").lexeme
        expect(TokenType.EQ, "Expected '='")
        val expression = parseExpression()
        expect(TokenType.SEMI, "Expected ';'")

        return Ast.VariableDeclaration(identifier, expression, mutable)
    }

    private fun parseExpression(): Ast.Expr {
        return parseOrExpression()
    }

    private fun parseOrExpression(): Ast.Expr {
        var expr = parseAndExpression()
        while (match(TokenType.OROR)) {
            val right = parseAndExpression()
            expr = Ast.BinaryExpr(expr, Ast.BinaryOp.OrOr, right)
        }
        return expr
    }

    private fun parseAndExpression(): Ast.Expr {
        var expr = parseEqualityExpression()
        while (match(TokenType.ANDAND)) {
            val right = parseEqualityExpression()
            expr = Ast.BinaryExpr(expr, Ast.BinaryOp.AndAnd, right)
        }
        return expr
    }

    private fun parseEqualityExpression(): Ast.Expr {
        var expr = parseAdditiveExpression()
        while (true) {
            expr = when {
                match(TokenType.EQEQ) -> Ast.BinaryExpr(expr, Ast.BinaryOp.EqEq, parseAdditiveExpression())
                match(TokenType.NEQ) -> Ast.BinaryExpr(expr, Ast.BinaryOp.Neq, parseAdditiveExpression())
                else -> return expr
            }
        }
    }

    private fun parseAdditiveExpression(): Ast.Expr {
        var expr = parseMultiplicativeExpression()
        while (true) {
            expr = when {
                match(TokenType.PLUS) -> Ast.BinaryExpr(expr, Ast.BinaryOp.Add, parseMultiplicativeExpression())
                match(TokenType.MINUS) -> Ast.BinaryExpr(expr, Ast.BinaryOp.Sub, parseMultiplicativeExpression())
                else -> return expr
            }
        }
    }

    private fun parseMultiplicativeExpression(): Ast.Expr {
        var expr = parseUnaryExpression()
        while (true) {
            expr = when {
                match(TokenType.STAR) -> Ast.BinaryExpr(expr, Ast.BinaryOp.Mul, parseUnaryExpression())
                match(TokenType.SLASH) -> Ast.BinaryExpr(expr, Ast.BinaryOp.Div, parseUnaryExpression())
                else -> return expr
            }
        }
    }

    private fun parseUnaryExpression(): Ast.Expr {
        if (match(TokenType.BANG)) {
            return Ast.UnaryExpr(Ast.UnaryOp.Not, parseUnaryExpression())
        }
        if (match(TokenType.MINUS)) {
            return Ast.UnaryExpr(Ast.UnaryOp.Negate, parseUnaryExpression())
        }
        return parsePowerExpression()
    }

    private fun parsePowerExpression(): Ast.Expr {
        val expr = parsePostfixExpression()
        if (match(TokenType.CARET)) {
            return Ast.BinaryExpr(expr, Ast.BinaryOp.Pow, parseUnaryExpression())
        }
        return expr
    }

    private fun parsePostfixExpression(): Ast.Expr {
        var expr = parsePrimaryExpression()

        while (match(TokenType.DOT)) {
            val fieldName = expect(TokenType.IDENT, "Expected field name after '.'").lexeme

            expr = if (match(TokenType.LPAREN)) {
                Ast.MemberFunctionCall(
                    receiver = expr,
                    name = fieldName,
                    args = parseCallArgumentsAfterOpenParen()
                )
            } else {
                Ast.FieldAccessExpr(
                    receiver = expr,
                    field = fieldName,
                )
            }
        }

        return expr
    }

    private fun parsePrimaryExpression(): Ast.Expr {
        return when (peek().type) {
            TokenType.STRING_LIT -> Ast.StringExpr(consume().lexeme)
            TokenType.NUMBER_LIT -> Ast.NumberExpr(consume().lexeme.toDouble())
            TokenType.TRUE -> {
                consume()
                Ast.BoolExpr(true)
            }
            TokenType.FALSE -> {
                consume()
                Ast.BoolExpr(false)
            }
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

                if (match(TokenType.LPAREN)) {
                    return Ast.FunctionCallExpr(identifier, parseCallArgumentsAfterOpenParen())
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

    private fun parseCallArgumentsAfterOpenParen(): List<Ast.Expr> {
        val arguments = mutableListOf<Ast.Expr>()
        if (!match(TokenType.RPAREN)) {
            do {
                arguments += parseExpression()
            } while (match(TokenType.COMMA))
            expect(TokenType.RPAREN, "Expected ')'")
        }
        return arguments
    }

    private fun parseIfStmt(): Ast.IfStmt {
        expect(TokenType.IF, "Expected 'if'")
        expect(TokenType.LPAREN, "Expected '(' after 'if'")
        val condition = parseExpression()
        expect(TokenType.RPAREN, "Expected ')' after if condition")
        val thenBlock = parseBlock()

        val elseBranch = if (match(TokenType.ELSE)) {
            if (peek().type == TokenType.IF) {
                Ast.IfStmt.ElseBranch.ElseIf(parseIfStmt())
            } else {
                Ast.IfStmt.ElseBranch.Else(parseBlock())
            }
        } else {
            null
        }

        return Ast.IfStmt(condition, thenBlock, elseBranch)
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
