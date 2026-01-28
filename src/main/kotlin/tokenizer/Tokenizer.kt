package com.zbinfinn.tokenizer

class Tokenizer(
    val content: String,
) {
    private val allowedIdentifierLetters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        .toCharArray()

    var tokens = mutableListOf<Token>()
    var index: Int = 0;

    fun tokenize(): List<Token> {
        index = 0
        tokens = mutableListOf()

        while (canPeek()) {
            process()
        }

        addToken(TokenType.EOF, "")
        return tokens
    }

    private fun process() {
        when (peek()) {
            ' ', '\n', '\r' -> consume()
            '@' -> addTokenConsumeOne(TokenType.AT, "@")
            '.' -> addTokenConsumeOne(TokenType.DOT, ".")
            ',' -> addTokenConsumeOne(TokenType.COMMA, "/")
            ';' -> addTokenConsumeOne(TokenType.SEMI, ";")
            '(' -> addTokenConsumeOne(TokenType.LPAREN, "(")
            ')' -> addTokenConsumeOne(TokenType.RPAREN, ")")
            '{' -> addTokenConsumeOne(TokenType.LBRACE, "{")
            '}' -> addTokenConsumeOne(TokenType.RBRACE, "}")
            '=' -> addTokenConsumeOne(TokenType.EQ, "=")
            '"' -> {
                val buffer = StringBuffer()
                consume() // Consume leading "
                while (canPeek() && peek() != '"') {
                    buffer.append(consume())
                }
                consume() // Consume trailing "

                addToken(TokenType.STRING_LIT, buffer.toString())
            }

            else -> {
                if (peek().isDigit()) {
                    // TODO: Decimals
                    val buffer = StringBuffer()
                    while (canPeek() && peek().isDigit()) {
                        buffer.append(consume())
                    }

                    addToken(TokenType.NUMBER_LIT, buffer.toString())
                    return
                }

                val buffer = StringBuffer()
                while (canPeek() && allowedIdentifierLetters.contains(peek())) { // TODO properly care for identifier rules
                    buffer.append(consume())
                }
                when (buffer.toString()) {
                    "fn" -> {
                        addToken(TokenType.FN, "fn")
                        return
                    }

                    "val" -> {
                        addToken(TokenType.VAL, "val")
                        return
                    }

                    "import" -> {
                        addToken(TokenType.IMPORT, "import")
                        return
                    }

                    "package" -> {
                        addToken(TokenType.PACKAGE, "package")
                        return
                    }

                    else -> {
                        val string = buffer.toString()
                        // TODO: Actual Rules for what counts as an Identifier
                        addToken(TokenType.IDENT, string)
                    }
                }
            }
        }
    }

    private fun addToken(type: TokenType, lexeme: String) {
        tokens.add(Token(type, lexeme, index))
    }

    private fun addTokenConsumeOne(type: TokenType, lexeme: String) {
        consume()
        addToken(type, lexeme)
    }

    private fun canPeek(ahead: Int = 0): Boolean {
        return (index + ahead) >= 0 && (index + ahead) < content.length
    }

    private fun peek(ahead: Int = 0): Char {
        return content[index]
    }

    private fun consume(): Char {
        return content[index++]
    }

    private fun consumeMultiple(amount: Int) {
        index += amount
    }
}