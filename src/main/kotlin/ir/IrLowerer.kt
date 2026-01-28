package com.zbinfinn.ir

import com.zbinfinn.ast.Ast
import com.zbinfinn.common.EntityEventAnnotation
import com.zbinfinn.common.EventAnnotation
import com.zbinfinn.common.PlayerEventAnnotation
import com.zbinfinn.common.parseEventAnnotation

class IrLowerer(
    val astProgram: Ast.Program
) {

    fun lowerProgram(): Ir.Program {
        val entryPoints = mutableListOf<Ir.EntryPoint>()
        val functions = mutableListOf<Ir.Function>()

        for (function in astProgram.functions) {
            val event = parseEventAnnotation(function.annotations)

            if (event != null) {
                entryPoints += lowerEvent(function, event)
            } else {
                functions += lowerFunction(function)
            }
        }

        return Ir.Program(entryPoints, functions)
    }

    private fun lowerEvent(function: Ast.FunctionDecl, event: EventAnnotation): Ir.EntryPoint {
        val symbols = SymbolTable()
        val body = mutableListOf<Ir.Instr>()

        for (stmt in function.body.statements) {
            lowerStatement(stmt, symbols, body)
        }

        return when (event) {
            is PlayerEventAnnotation -> Ir.PlayerEvent(event.eventName, body)
            is EntityEventAnnotation -> Ir.EntityEvent(event.eventName, body)
        }
    }

    private fun lowerFunction(function: Ast.FunctionDecl): Ir.Function {
        val symbols = SymbolTable()
        val body = mutableListOf<Ir.Instr>()

        for (stmt in function.body.statements) {
            lowerStatement(stmt, symbols, body)
        }
        return Ir.Function(function.name, body)
    }

    private fun lowerStatement(stmt: Ast.Statement, symbols: SymbolTable, out: MutableList<Ir.Instr>) {
        when (stmt) {
            is Ast.ImmutableAssignment -> {
                val name = stmt.identifier
                symbols.define(name, mutable = false)

                val value = lowerExpr(stmt.expression, symbols)

                out += Ir.SetVariableAction(
                    actionName = "=",
                    args = listOf(
                        Ir.StringValue(name),
                        lowerExpr(stmt.expression, symbols)
                    ),
                )
            }

            is Ast.FunctionCall -> TODO()
            is Ast.SelectionBlock -> TODO()
        }
    }

    private fun lowerExpr(expr: Ast.Expr, symbols: SymbolTable): Ir.Value {
        return when(expr) {
            is Ast.StringExpr -> Ir.StringValue(expr.value)
            is Ast.NumberExpr -> Ir.NumberValue(expr.value)

            /*
            is Ast.IdentifierExpr -> {
                symbols.resolve(expr.name)
                IrVarRef(expr.name)
            }
             */
        }
    }
}