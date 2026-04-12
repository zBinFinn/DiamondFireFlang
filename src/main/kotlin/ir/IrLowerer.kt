package com.zbinfinn.ir

import com.zbinfinn.ast.Ast
import com.zbinfinn.common.EntityEventAnnotation
import com.zbinfinn.common.EventAnnotation
import com.zbinfinn.common.PlayerEventAnnotation
import com.zbinfinn.common.parseEventAnnotation
import com.zbinfinn.common.requiredSelectionType
import com.zbinfinn.common.requiresSelection
import com.zbinfinn.common.selectorType
import com.zbinfinn.compiler.FunctionResolver
import com.zbinfinn.compiler.GlobalFunctionTable
import com.zbinfinn.stdlib.ImportContext
import com.zbinfinn.stdlib.StdlibAst

class IrLowerer(
    private val astProgram: Ast.Program,
    private val globals: GlobalFunctionTable,
    private val functionResolver: FunctionResolver
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
        context.resetTempVariableIndex()
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
        context.resetTempVariableIndex()
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

        val requiredSelection = requiredSelectionType(function)
        if (requiredSelection != null) {
            context.selectionStack.addLast(requiredSelection)
        }

        for (stmt in function.body.statements) {
            lowerStatement(stmt, symbols, body, context)
        }

        if (requiredSelection != null) {
            context.selectionStack.removeLast()
        }

        val functionSymbol = functionResolver.resolve(function.name, astProgram)
        return Ir.Function(functionSymbol.qualifiedName, parameters, body)
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
                        lowerExpr(stmt.expression, symbols, out, context)
                    ),
                    tags = emptyList(),
                )
            }

            is Ast.WithBlock -> {
                val selectorCall = stmt.selectorFunction

                val selectorFunction = functionResolver.resolve(selectorCall.name, astProgram)

                val type = selectorType(selectorFunction.decl) ?: error("Unknown selector '${selectorCall.name}'")

                lowerFunctionCall(stmt.selectorFunction, out, symbols, context)

                context.selectionStack.addLast(type)
                for (stmt in stmt.body.statements) {
                    lowerStatement(stmt, symbols, out, context)
                }
                context.selectionStack.removeLast()

                emitSelectionReset(out)
            }

            is Ast.FunctionCall -> {
                val symbol = functionResolver.resolve(stmt.name, astProgram)
                val targetFunction = symbol.decl

                if (selectorType(targetFunction) != null) {
                    error(
                        "Selector function '${stmt.name}' may only be used in a 'with' block"
                    )
                }

                val required = requiredSelectionType(targetFunction)
                val active = context.currentSelection()

                if (required != null) {
                    if (active == null || required != active) {
                        error("Function '${stmt.name}' requires $required selection")
                    }
                }

                lowerFunctionCall(stmt, out, symbols, context)
            }

            is Ast.InlineIr -> {
                out.addAll(stmt.ir)
            }

            is Ast.FieldAssignment -> {
                if (stmt.receiver is Ast.IdentifierExpr) {
                    out += SetVars.setDictValue(
                        stmt.receiver.name,
                        stmt.field,
                        lowerExpr(stmt.value, symbols, out, context)
                    )
                } else {
                    error("Only simple dict field assignment supported for now")
                }
            }

            is Ast.IfStmt -> {
                emitIf(stmt, symbols, out, context)
            }
        }
    }

    private fun emitIf(
        stmt: Ast.IfStmt,
        symbols: SymbolTable,
        out: MutableList<Ir.Instr>,
        context: LoweringContext
    ) {
        val condition = stmt.condition

        val gate = buildIfGate(condition, symbols, out, context)
        out += gate
        out += Ir.OpenBracket
        for (inner in stmt.thenBlock.statements) {
            lowerStatement(inner, symbols, out, context)
        }
        out += Ir.CloseBracket

        when (val elseBranch = stmt.elseBranch) {
            null -> {}

            is Ast.IfStmt.ElseBranch.Else -> {
                out += Ir.ElseMarker
                out += Ir.OpenBracket
                for (inner in elseBranch.block.statements) {
                    lowerStatement(inner, symbols, out, context)
                }
                out += Ir.CloseBracket
            }

            is Ast.IfStmt.ElseBranch.ElseIf -> {
                out += Ir.ElseMarker
                out += Ir.OpenBracket
                emitIf(elseBranch.stmt, symbols, out, context)
                out += Ir.CloseBracket
            }
        }
    }

    private fun buildIfGate(
        condition: Ast.Expr,
        symbols: SymbolTable,
        out: MutableList<Ir.Instr>,
        context: LoweringContext
    ): Ir.IfVarAction {
        return when (condition) {
            is Ast.BinaryExpr -> when (condition.op) {
                Ast.BinaryOp.OrOr -> {
                    val terms = flatten(condition, Ast.BinaryOp.OrOr)
                    val values = terms.map { lowerBoolExprToValue(it, symbols, out, context) }
                    Ir.IfVarAction(
                        actionName = "=",
                        args = listOf(Ir.NumberValue(1)).plus(values),
                        negated = false
                    )
                }

                Ast.BinaryOp.AndAnd -> {
                    val terms = flatten(condition, Ast.BinaryOp.AndAnd)
                    val values = terms.map { lowerBoolExprToValue(it, symbols, out, context) }
                    Ir.IfVarAction(
                        actionName = "=",
                        args = listOf(Ir.NumberValue(0)).plus(values),
                        negated = true
                    )
                }

                else -> {
                    val value = lowerBoolExprToValue(condition, symbols, out, context)
                    Ir.IfVarAction(
                        actionName = "=",
                        args = listOf(Ir.NumberValue(0), value),
                        negated = true
                    )
                }
            }

            else -> {
                val value = lowerBoolExprToValue(condition, symbols, out, context)
                Ir.IfVarAction(
                    actionName = "=",
                    args = listOf(Ir.NumberValue(0), value),
                    negated = true
                )
            }
        }
    }

    private fun flatten(expr: Ast.Expr, op: Ast.BinaryOp): List<Ast.Expr> {
        if (expr is Ast.BinaryExpr && expr.op == op) {
            return flatten(expr.left, op) + flatten(expr.right, op)
        }
        return listOf(expr)
    }

    private fun lowerValueExpr(
        expr: Ast.Expr,
        symbols: SymbolTable,
        out: MutableList<Ir.Instr>,
        context: LoweringContext
    ): Ir.Value {
        return when (expr) {
            is Ast.BoolExpr,
            is Ast.UnaryExpr,
            is Ast.BinaryExpr -> lowerBoolExprToValue(expr, symbols, out, context)

            else -> lowerExpr(expr, symbols, out, context)
        }
    }

    private fun lowerBoolExprToValue(
        expr: Ast.Expr,
        symbols: SymbolTable,
        out: MutableList<Ir.Instr>,
        context: LoweringContext
    ): Ir.Value {
        return when (expr) {
            is Ast.BoolExpr -> Ir.NumberValue(if (expr.value) 1 else 0)

            is Ast.IdentifierExpr -> {
                symbols.resolve(expr.name)
                Ir.Variable(expr.name)
            }

            is Ast.UnaryExpr -> {
                val inner = lowerBoolExprToValue(expr.expr, symbols, out, context)
                when (expr.op) {
                    Ast.UnaryOp.Not -> comparisonToTemp("=", Ir.NumberValue(0), inner, out, context)
                }
            }

            is Ast.BinaryExpr -> {
                when (expr.op) {
                    Ast.BinaryOp.EqEq -> comparisonToTemp("=", lowerValueExpr(expr.left, symbols, out, context), lowerValueExpr(expr.right, symbols, out, context), out, context)
                    Ast.BinaryOp.Neq -> comparisonToTemp("!=", lowerValueExpr(expr.left, symbols, out, context), lowerValueExpr(expr.right, symbols, out, context), out, context)
                    Ast.BinaryOp.AndAnd -> {
                        val terms = flatten(expr, Ast.BinaryOp.AndAnd)
                        val values = terms.map { lowerBoolExprToValue(it, symbols, out, context) }
                        gateToTemp(isAnd = true, values = values, out = out, context = context)
                    }

                    Ast.BinaryOp.OrOr -> {
                        val terms = flatten(expr, Ast.BinaryOp.OrOr)
                        val values = terms.map { lowerBoolExprToValue(it, symbols, out, context) }
                        gateToTemp(isAnd = false, values = values, out = out, context = context)
                    }
                }
            }

            else -> error("Unsupported boolean expression $expr")
        }
    }

    private fun gateToTemp(
        isAnd: Boolean,
        values: List<Ir.Value>,
        out: MutableList<Ir.Instr>,
        context: LoweringContext
    ): Ir.Variable {
        val temp = context.newTempVariableName()
        out += Ir.SetVariableAction(
            actionName = "=",
            args = listOf(Ir.Variable(temp), Ir.NumberValue(0)),
            tags = emptyList()
        )

        val gate = if (isAnd) {
            Ir.IfVarAction(
                actionName = "=",
                args = listOf(Ir.NumberValue(0)).plus(values),
                negated = true
            )
        } else {
            Ir.IfVarAction(
                actionName = "=",
                args = listOf(Ir.NumberValue(1)).plus(values),
                negated = false
            )
        }

        out += gate
        out += Ir.OpenBracket
        out += Ir.SetVariableAction(
            actionName = "=",
            args = listOf(Ir.Variable(temp), Ir.NumberValue(1)),
            tags = emptyList()
        )
        out += Ir.CloseBracket
        return Ir.Variable(temp)
    }

    private fun comparisonToTemp(
        actionName: String,
        left: Ir.Value,
        right: Ir.Value,
        out: MutableList<Ir.Instr>,
        context: LoweringContext
    ): Ir.Variable {
        val temp = context.newTempVariableName()
        out += Ir.SetVariableAction(
            actionName = "=",
            args = listOf(Ir.Variable(temp), Ir.NumberValue(0)),
            tags = emptyList()
        )
        out += Ir.IfVarAction(
            actionName = actionName,
            args = listOf(left, right),
            negated = false
        )
        out += Ir.OpenBracket
        out += Ir.SetVariableAction(
            actionName = "=",
            args = listOf(Ir.Variable(temp), Ir.NumberValue(1)),
            tags = emptyList()
        )
        out += Ir.CloseBracket
        return Ir.Variable(temp)
    }

    private fun lowerFunctionCall(
        stmt: Ast.FunctionCall,
        out: MutableList<Ir.Instr>,
        symbols: SymbolTable,
        context: LoweringContext
    ) {
        val functionSymbol = functionResolver.resolve(stmt.name, astProgram)
        out += Ir.CallFunction(
            functionSymbol.qualifiedName,
            stmt.args.map { lowerExpr(it, symbols, out, context) }
        )
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

    private fun lowerExpr(
        expr: Ast.Expr,
        symbols: SymbolTable,
        out: MutableList<Ir.Instr>,
        context: LoweringContext
    ): Ir.Value {
        return when (expr) {
            is Ast.StringExpr -> Ir.StringValue(expr.value)
            is Ast.NumberExpr -> Ir.NumberValue(expr.value)
            is Ast.BoolExpr,
            is Ast.UnaryExpr,
            is Ast.BinaryExpr -> lowerBoolExprToValue(expr, symbols, out, context)
            is Ast.IdentifierExpr -> {
                symbols.resolve(expr.name)
                Ir.Variable(expr.name)
            }

            is Ast.DictLiteralExpr -> {
                val dictTemp = context.newTempVariableName()
                val keysTemp = context.newTempVariableName()
                val valuesTemp = context.newTempVariableName()

                val keys = expr.entries.map {
                    Ir.StringValue(it.field)
                }

                val values = expr.entries.map {
                    lowerExpr(it.value, symbols, out, context)
                }

                out += SetVars.createList(keysTemp, keys)
                out += SetVars.createList(valuesTemp, values)
                out += SetVars.createDict(dictTemp, keysTemp, valuesTemp)

                Ir.Variable(dictTemp)
            }

            is Ast.FieldAccessExpr -> {
                if (expr.receiver is Ast.IdentifierExpr) {

                    val dictVar = expr.receiver.name
                    val temp = context.newTempVariableName()

                    out += SetVars.getDictValue(temp, dictVar, expr.field)
                    Ir.Variable(temp)
                } else {
                    error("Only simple dict access supported for now")
                }
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
