package com.zbinfinn.ast

import com.zbinfinn.ir.Ir

object Ast {
    sealed interface AstNode

    data class Program(
        val imports: List<Import>,
        val functions: List<FunctionDecl>
    ) : AstNode

    data class FunctionDecl(
        val name: String,
        val annotations: List<Annotation>,
        val parameters: List<Parameter>,
        val body: Block,
    ) : AstNode

    data class Parameter(
        val name: String,
        val type: Type,
        val mutable: Boolean
    )

    data class Type(
        val identifier: String
    )

    data class Block(
        val statements: List<Statement>
    ) : AstNode

    data class Import(
        val path: String,
    ) : AstNode

    data class Annotation(
        val name: String,
        val args: List<Expr>
    )

    data class Selection(
        val expr: Expr // inline IR
    )

    sealed interface Statement : AstNode

    data class InlineIr(
        val ir: List<Ir.Instr>
    ) : Statement {
        constructor(vararg ir: Ir.Instr) : this(ir.toList())
    }

    data class FunctionCall(
        val name: String,
        val args: List<Expr>,
    ) : Statement

    data class WithBlock(
        val selectorFunction: FunctionCall,
        val body: Block,
    ) : Statement

    data class ImmutableAssignment(
        val identifier: String,
        val expression: Expr,
    ) : Statement

    sealed interface Expr : AstNode

    data class StringExpr(
        val value: String
    ) : Expr

    data class NumberExpr(
        val value: Number
    ) : Expr

    data class IdentifierExpr(
        val name: String,
    ) : Expr
}