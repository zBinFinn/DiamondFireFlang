package com.zbinfinn.ir

import com.zbinfinn.ast.Ast
import com.zbinfinn.common.EntityEventAnnotation
import com.zbinfinn.common.EventAnnotation
import com.zbinfinn.common.PlayerEventAnnotation
import com.zbinfinn.common.parseEventAnnotation
import com.zbinfinn.common.requiredSelectionType
import com.zbinfinn.common.requiresSelection
import com.zbinfinn.common.selectorType
import com.zbinfinn.stdlib.ImportContext
import com.zbinfinn.stdlib.StdlibAst

class IrLowerer(
    val astProgram: Ast.Program
) {
    private val importContext = ImportContext(astProgram.imports.map { it.path })
    private val functionTable: Map<String, FunctionInfo> = buildFunctionTable()

    private fun buildFunctionTable(): Map<String, FunctionInfo> {
        val table = mutableMapOf<String, FunctionInfo>()

        for (fn in astProgram.functions) {
            table[fn.name] = FunctionInfo(fn, FunctionSource.User)
        }

        for (stdFn in StdlibAst.functions) {
            val fn = stdFn.decl
            val import = stdFn.importPath

            if (importContext.isImported(import)) {
                table[fn.name] = FunctionInfo(fn, FunctionSource.Std)
            }
        }

        return table
    }

    fun lowerProgram(): Ir.Program {
        val entryPoints = mutableListOf<Ir.EntryPoint>()
        val functions = mutableListOf<Ir.Function>()
        val context = LoweringContext()

        for (function in astProgram.functions) {
            val event = parseEventAnnotation(function.annotations)

            if (event != null) {
                entryPoints += lowerEvent(function, event, context)
            } else {
                functions += lowerFunction(function, context)
            }
        }

        for (fn in functionTable.values.filter { it.source == FunctionSource.Std }.map { it.decl }) {
            functions += lowerFunction(fn, context)
        }

        return Ir.Program(entryPoints, functions)
    }

    private fun lowerEvent(
        function: Ast.FunctionDecl,
        event: EventAnnotation,
        context: LoweringContext
    ): Ir.EntryPoint {
        val symbols = SymbolTable()
        val body = mutableListOf<Ir.Instr>()

        if (function.annotations.any {
                setOf("OnPlayerSelection", "OnEntitySelection", "PlayerSelector", "EntitySelector").contains(it.name)
            }) {
            error("Events may not be annotated with selection annotations")
        }

        for (stmt in function.body.statements) {
            lowerStatement(stmt, symbols, body, context)
        }

        return when (event) {
            is PlayerEventAnnotation -> Ir.PlayerEvent(event.eventName, body)
            is EntityEventAnnotation -> Ir.EntityEvent(event.eventName, body)
        }
    }

    private fun lowerFunction(function: Ast.FunctionDecl, context: LoweringContext): Ir.Function {
        if (function.annotations.any {
                setOf("OnPlayerSelection", "OnEntitySelection").contains(it.name) &&
                        setOf("PlayerSelector", "EntitySelector").contains(it.name)
            }) {

            error("Functions may not be both @On<Something>Selection and @<Something>Selector")
        }

        val requiresSelection = requiresSelection(function)
        if (requiresSelection && function.body.statements.isEmpty()) {
            error("@OnPlayerSelection function '${function.name}' must have a body")
        }

        val symbols = SymbolTable()
        val body = mutableListOf<Ir.Instr>()

        val parameters = mutableListOf<Ir.Parameter>()
        for (param in function.parameters) {
            parameters += lowerParameter(param)
        }

        for (stmt in function.body.statements) {
            lowerStatement(stmt, symbols, body, context)
        }
        return Ir.Function(function.name, parameters, body)
    }

    private fun lowerParameter(param: Ast.Parameter): Ir.Parameter {
        return Ir.Parameter(
            name = param.name,
            mutable = param.mutable,
        )
    }

    private fun lowerStatement(
        stmt: Ast.Statement,
        symbols: SymbolTable,
        out: MutableList<Ir.Instr>,
        context: LoweringContext
    ) {
        when (stmt) {
            is Ast.ImmutableAssignment -> {
                val name = stmt.identifier
                symbols.define(name, mutable = false)

                out += Ir.SetVariableAction(
                    actionName = "=",
                    args = listOf(
                        Ir.Variable(name),
                        lowerExpr(stmt.expression, symbols, context)
                    ),
                    tags = emptyList(),
                )
            }

            is Ast.WithBlock -> {
                val selectorCall = stmt.selectorFunction

                val selectorFunction = resolveFunction(selectorCall.name) { function ->
                    function.annotations.any {
                        it.name == "PlayerSelector" || it.name == "EntitySelector"
                    }
                } ?: error("Unknown selector '${selectorCall.name}'")

                val type = selectorType(selectorFunction)!!

                lowerFunctionCall(stmt.selectorFunction, out, symbols, context)

                context.selectionStack.addLast(type)
                for (stmt in stmt.body.statements) {
                    lowerStatement(stmt, symbols, out, context)
                }
                context.selectionStack.removeLast()

                emitSelectionReset(out)
            }

            is Ast.FunctionCall -> {
                val targetFunction = resolveFunction(stmt.name)

                if (targetFunction != null && selectorType(targetFunction) != null) {
                    error(
                        "Selector function '${stmt.name}' may only be used in a 'with' block"
                    )
                }

                if (targetFunction != null) {
                    val required = requiredSelectionType(targetFunction)
                    val active = context.currentSelection()

                    if (required != null) {
                        if (active == null) {
                            error("Function '${stmt.name}' requires $required selection")
                        }
                        if (required != active) {
                            error("Function '${stmt.name}' requires $required selection")
                        }
                    }
                }

                lowerFunctionCall(stmt, out, symbols, context)
            }

            is Ast.InlineIr -> {
                out.addAll(stmt.ir)
            }
        }
    }

    private fun lowerFunctionCall(
        stmt: Ast.FunctionCall,
        out: MutableList<Ir.Instr>,
        symbols: SymbolTable,
        context: LoweringContext
    ) {
        out += Ir.CallFunction(stmt.name, stmt.args.map { lowerExpr(it, symbols, context) })
    }

    private fun emitSelectionReset(out: MutableList<Ir.Instr>) {
        out.add(
            Ir.SelectObject(
                actionName = "Reset",
                subAction = null,
                args = emptyList(),
                tags = emptyList()
            )
        )
    }

    private fun resolveFunction(name: String, filter: (Ast.FunctionDecl) -> Boolean = { true }): Ast.FunctionDecl? {
        val info = functionTable[name] ?: return null
        return info.decl.takeIf(filter)
    }

    private fun lowerExpr(expr: Ast.Expr, symbols: SymbolTable, context: LoweringContext): Ir.Value {
        return when (expr) {
            is Ast.StringExpr -> Ir.StringValue(expr.value)
            is Ast.NumberExpr -> Ir.NumberValue(expr.value)
            is Ast.IdentifierExpr -> {
                symbols.resolve(expr.name)
                Ir.Variable(expr.name)
            }
        }
    }

    enum class FunctionSource {
        User,
        Std
    }

    data class FunctionInfo(
        val decl: Ast.FunctionDecl,
        val source: FunctionSource
    )
}