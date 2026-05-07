package com.zbinfinn.tokenizer

enum class TokenType {
    IDENT,
    STRING_LIT,
    TEXT_LIT,
    NUMBER_LIT,

    FN,
    INTERNAL,
    VAL,
    VAR,
    MOD,
    DICT,
    ENUM,
    SINGLETON,
    IMPL,
    WITH,
    IMPORT,
    PACKAGE,
    IF,
    ELSE,
    RETURN,
    TRUE,
    FALSE,

    AT, // @
    EQ, // =
    EQEQ, // ==
    NEQ, // !=
    ANDAND, // &&
    OROR, // ||
    BANG, // !
    PLUS, // +
    MINUS, // -
    STAR, // *
    SLASH, // /
    CARET, // ^
    DOT, // .
    COMMA, // ,
    SEMI, // ;
    COLON, // :
    LT, // <
    GT, // >
    LPAREN, // (
    RPAREN, // )
    LBRACE, // {
    RBRACE, // }

    EOF,
}
