package com.zbinfinn.ast

import com.zbinfinn.ir.Ir

object Ast {
    sealed interface AstNode

    data class Program(
        val module: ModuleDecl,
        val imports: List<Import>,
        val dicts: List<DictDecl>,
        val enums: List<EnumDecl>,
        val singletons: List<SingletonDecl>,
        val impls: List<ImplDecl>,
        val functions: List<FunctionDecl>
    ) : AstNode

    data class ModuleDecl(
        val path: String,
    ) : AstNode

    data class DictDecl(
        val name: String,
        val fields: List<Field>
    ) : AstNode

    data class EnumDecl(
        val name: String,
        val cases: List<EnumCase>
    ) : AstNode {
        data class EnumCase(
            val name: String,
            val value: String?
        )
    }

    data class SingletonDecl(
        val name: String,
        val annotations: List<Annotation>,
        val functions: List<FunctionDecl>
    ) : AstNode

    data class Field(
        val name: String,
        val type: Type,
        val mutable: Boolean,
    )

    data class ImplDecl(
        val type: Type,
        val functions: List<FunctionDecl>
    ) : AstNode {
        val typeName: String get() = type.identifier
    }

    data class FunctionDecl(
        val name: String,
        val annotations: List<Annotation>,
        val parameters: List<Parameter>,
        val returnType: Type?,
        val body: Block,
        val internal: Boolean = false,
    ) : AstNode

    data class Parameter(
        val name: String,
        val type: Type,
        val mutable: Boolean
    )

    data class Type(
        val identifier: String,
        val args: List<Type> = emptyList()
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

    data class MemberFunctionCall(
        val receiver: Expr,
        val name: String,
        val args: List<Expr>,
    ) : Statement, Expr

    data class WithBlock(
        val selectorFunction: FunctionCall,
        val body: Block,
    ) : Statement

    data class VariableDeclaration(
        val identifier: String,
        val expression: Expr,
        val mutable: Boolean,
    ) : Statement

    data class VariableAssignment(
        val identifier: String,
        val expression: Expr,
    ) : Statement

    data class FieldAssignment(
        val receiver: Expr,
        val field: String,
        val value: Expr
    ) : Statement

    data class IfStmt(
        val condition: Expr,
        val thenBlock: Block,
        val elseBranch: ElseBranch?
    ) : Statement {
        sealed interface ElseBranch {
            data class ElseIf(val stmt: IfStmt) : ElseBranch
            data class Else(val block: Block) : ElseBranch
        }
    }

    data class ReturnStmt(
        val expression: Expr,
    ) : Statement

    sealed interface Expr : AstNode

    data class StringExpr(
        val value: String
    ) : Expr

    data class TextExpr(
        val value: String
    ) : Expr

    data class NumberExpr(
        val value: Number
    ) : Expr

    data class IdentifierExpr(
        val name: String,
    ) : Expr

    data class InferredEnumCaseExpr(
        val caseName: String,
    ) : Expr

    data class TypeExpr(
        val type: Type,
    ) : Expr

    data class BoolExpr(
        val value: Boolean
    ) : Expr

    enum class UnaryOp { Not, Negate }

    data class UnaryExpr(
        val op: UnaryOp,
        val expr: Expr
    ) : Expr

    enum class BinaryOp { EqEq, Neq, AndAnd, OrOr, Add, Sub, Mul, Div, Pow }

    data class BinaryExpr(
        val left: Expr,
        val op: BinaryOp,
        val right: Expr
    ) : Expr

    data class DictLiteralExpr(
        val typeName: String,
        val entries: List<Entry>
    ) : Expr {
        data class Entry(
            val field: String,
            val value: Expr
        )
    }

    data class FieldAccessExpr(
        val receiver: Expr,
        val field: String,
    ) : Expr

    data class FunctionCallExpr(
        val name: String,
        val args: List<Expr>,
    ) : Expr

    data class GameValueExpr(
        val name: String,
        val target: Expr?,
    ) : Expr
}
