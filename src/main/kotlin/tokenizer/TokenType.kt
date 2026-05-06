package com.zbinfinn.tokenizer

enum class TokenType {
    IDENT,
    STRING_LIT,
    NUMBER_LIT,

    FN,
    VAL,
    MUT,
    MOD,
    DICT,
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
    DOT, // .
    COMMA, // ,
    SEMI, // ;
    COLON, // :
    LPAREN, // (
    RPAREN, // )
    LBRACE, // {
    RBRACE, // }

    EOF,
}
