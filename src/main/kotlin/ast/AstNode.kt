package com.zbinfinn.ast

object Ast {
    sealed interface AstNode

    data class Program(
        val imports: List<Import>,
        val functions: List<FunctionDecl>
    ) : AstNode

    data class FunctionDecl(
        val name: String,
        val annotations: List<Annotation>,
        val body: Block,
    ) : AstNode

    data class Block(
        val statements: List<Statement>
    ) : AstNode

    data class Import(
        val path: String,
    ) : AstNode

    sealed interface Annotation

    data class NamedAnnotation(
        val name: String,
        val args: List<Expr>
    ) : Annotation

    sealed interface Selection

    data class EventTargetSelection(
        val target: Expr,
        // i.e., Default or Killer or Victim or Selection
    ) : Selection

    sealed interface Statement : AstNode

    data class FunctionCall(
        val name: String,
        val args: List<Expr>,
    ) : Statement

    data class SelectionBlock(
        val selection: Selection,
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
}