package com.zbinfinn.tokenizer

class Tokenizer(
    val content: String,
) {
    private val oneCharTokens = mapOf(
        '@' to TokenType.AT,
        '.' to TokenType.DOT,
        ',' to TokenType.COMMA,
        ';' to TokenType.SEMI,
        ':' to TokenType.COLON,
        '(' to TokenType.LPAREN,
        ')' to TokenType.RPAREN,
        '{' to TokenType.LBRACE,
        '}' to TokenType.RBRACE,
        '=' to TokenType.EQ
    )

    private val keywords = mapOf(
        "fn" to TokenType.FN,
        "val" to TokenType.VAL,
        "var" to TokenType.VAR,
        "mod" to TokenType.MOD,
        "dict" to TokenType.DICT,
        "impl" to TokenType.IMPL,
        "with" to TokenType.WITH,
        "import" to TokenType.IMPORT,
        "package" to TokenType.PACKAGE,
        "if" to TokenType.IF,
        "else" to TokenType.ELSE,
        "return" to TokenType.RETURN,
        "true" to TokenType.TRUE,
        "false" to TokenType.FALSE,
    )

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
        val peeked = peek();

        if (peeked == '&' && canPeek(ahead = 1) && peek(ahead = 1) == '&') {
            consumeMultiple(2)
            addToken(TokenType.ANDAND, "&&")
            return
        }

        if (peeked == '|' && canPeek(ahead = 1) && peek(ahead = 1) == '|') {
            consumeMultiple(2)
            addToken(TokenType.OROR, "||")
            return
        }

        if (peeked == '=' && canPeek(ahead = 1) && peek(ahead = 1) == '=') {
            consumeMultiple(2)
            addToken(TokenType.EQEQ, "==")
            return
        }

        if (peeked == '!' && canPeek(ahead = 1) && peek(ahead = 1) == '=') {
            consumeMultiple(2)
            addToken(TokenType.NEQ, "!=")
            return
        }

        if (peeked == '!') {
            consume()
            addToken(TokenType.BANG, "!")
            return
        }

        if (oneCharTokens.contains(peeked)) {
            addTokenConsumeOne(oneCharTokens[peeked]!!, peeked.toString())
            return;
        }
        when (peeked) {
            ' ', '\n', '\r' -> consume()
            '"' -> {
                val buffer = StringBuffer()
                consume() // Consume leading "
                while (canPeek()) {
                    if (peek() == '"') {
                        break
                    }

                    if (peek() == '\\' && canPeek(ahead = 1) && peek(ahead = 1) == '"') {
                        consumeMultiple(2)
                        buffer.append('"')
                        println(peek())
                        continue
                    }

                    buffer.append(consume())
                }
                consume() // Consume trailing "

                addToken(TokenType.STRING_LIT, buffer.toString())
            }

            else -> {
                if (peeked.isDigit()) {
                    // TODO: Decimals
                    val buffer = StringBuffer()
                    while (canPeek() && peek().isDigit()) {
                        buffer.append(consume())
                    }

                    addToken(TokenType.NUMBER_LIT, buffer.toString())
                    return
                }

                val buffer = StringBuffer()
                while (canPeek() && (peek().isLetterOrDigit() || peek() == '_')) { // TODO properly care for identifier rules
                    buffer.append(consume())
                }

                if (buffer.isEmpty()) {
                    // Ensure we always advance; otherwise the tokenizer will infinite-loop on unexpected characters.
                    val unexpected = consume()
                    error("Unexpected character '$unexpected' at index $index")
                }

                val buffered = buffer.toString()
                if (keywords.containsKey(buffered)) {
                    addToken(keywords[buffered]!!, buffered)
                    return
                }
                when (buffered) {
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
        return content[index + ahead]
    }

    private fun consume(): Char {
        return content[index++]
    }

    private fun consumeMultiple(amount: Int) {
        index += amount
    }
}
