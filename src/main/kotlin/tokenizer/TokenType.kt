package com.zbinfinn.tokenizer

enum class TokenType {
    IDENT,
    STRING_LIT,
    NUMBER_LIT,

    FN,
    VAL,
    MUT,
    MOD,
    WITH,
    IMPORT,
    PACKAGE,

    AT, // @
    EQ, // =
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