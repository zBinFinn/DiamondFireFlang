package com.zbinfinn.tokenizer

enum class TokenType {
    IDENT,
    STRING_LIT,
    NUMBER_LIT,

    FN,
    VAL,
    WITH,
    IMPORT,
    PACKAGE,

    AT, // @
    EQ, // =
    DOT, // .
    COMMA, // ,
    SEMI, // ;
    LPAREN, // (
    RPAREN, // )
    LBRACE, // {

    RBRACE, // }
    EOF,
}