package com.zbinfinn.ir

import com.zbinfinn.ast.Ast
import com.zbinfinn.common.EntityEventAnnotation
import com.zbinfinn.common.EventAnnotation
import com.zbinfinn.common.PlayerEventAnnotation
import com.zbinfinn.common.functionKind
import com.zbinfinn.common.parseEventAnnotation
import com.zbinfinn.common.requiredSelectionType
import com.zbinfinn.common.requiresSelection
import com.zbinfinn.common.selectorType
import com.zbinfinn.compiler.FunctionResolver
import com.zbinfinn.compiler.GlobalFunctionTable
import com.zbinfinn.compiler.GlobalTypeTable
import com.zbinfinn.stdlib.ImportContext
import com.zbinfinn.stdlib.StdlibAst

class IrLowerer(
    private val astProgram: Ast.Program,
    private val globals: GlobalFunctionTable,
    private val functionResolver: FunctionResolver,
    private val typeTable: GlobalTypeTable? = null,
) {
    private companion object {
        const val RETURN_PARAMETER_NAME = "\$return"
    }

    private val importContext = ImportContext(astProgram.imports.map { it.path })
    private val functionTable: Map<String, FunctionInfo> = buildFunctionTable()

    private fun buildFunctionTable(): Map<String, FunctionInfo> {
        val table = mutableMapOf<String, FunctionInfo>()

        for (fn in astProgram.functions) {
            table[fn.name] = FunctionInfo(fn, astProgram.module.path, FunctionSource.User)
        }

        for (stdFn in StdlibAst.functions) {
            val fn = stdFn.decl
            val import = stdFn.importPath

            if (importContext.isImported(import)) {
                table[fn.name] = FunctionInfo(fn, import.substringBeforeLast("."), FunctionSource.Std)
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
                functions += lowerFunction(function, astProgram.module.path, context)
            }
        }

        for (fn in functionTable.values.filter { it.source == FunctionSource.Std }) {
            functions += lowerFunction(fn.decl, fn.modulePath, context)
        }

        for (impl in astProgram.impls) {
            val typeQualifiedName = "${astProgram.module.path}.${impl.typeName}"
            for (function in impl.functions) {
                val symbol = globals.resolveMember(typeQualifiedName, function.name)
                    ?: error("Registered member function '$typeQualifiedName.${function.name}' not found")
                functions += lowerFunction(function, astProgram.module.path, context, symbol)
            }
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

    private fun lowerFunction(
        function: Ast.FunctionDecl,
        modulePath: String,
        context: LoweringContext,
        symbolOverride: com.zbinfinn.compiler.FunctionSymbol? = null
    ): Ir.Function {
        context.resetTempVariableIndex()

        val requiresSelection = requiresSelection(function)
        if (requiresSelection && function.body.statements.isEmpty()) {
            error("Selection handler function '${function.name}' must have a body")
        }

        val symbols = SymbolTable()
        val body = mutableListOf<Ir.Instr>()

        val parameters = mutableListOf<Ir.Parameter>()
        for (param in function.parameters) {
            parameters += lowerParameter(param)
            symbols.define(param.name, mutable = param.mutable, typeQualifiedName = resolveDictType(param.type))
        }
        if (function.returnType != null) {
            parameters += Ir.Parameter(RETURN_PARAMETER_NAME, mutable = true)
            symbols.define(RETURN_PARAMETER_NAME, mutable = true)
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

        val functionSymbol = symbolOverride ?: globals.resolveInModule(modulePath, function.name, functionKind(function))
            ?: error("Registered symbol for function '${function.name}' not found")
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
            is Ast.VariableDeclaration -> {
                val name = stmt.identifier
                val value = lowerExpr(stmt.expression, symbols, out, context)

                symbols.define(
                    name,
                    mutable = stmt.mutable,
                    typeQualifiedName = expressionDictType(stmt.expression, symbols)
                )
                out += Ir.SetVariableAction(
                    actionName = "=",
                    args = listOf(
                        Ir.Variable(name),
                        value
                    ),
                    tags = emptyList(),
                )
            }

            is Ast.VariableAssignment -> {
                symbols.assign(stmt.identifier)
                out += Ir.SetVariableAction(
                    actionName = "=",
                    args = listOf(
                        Ir.Variable(stmt.identifier),
                        lowerExpr(stmt.expression, symbols, out, context)
                    ),
                    tags = emptyList(),
                )
            }

            is Ast.WithBlock -> {
                val selectorCall = stmt.selectorFunction

                val selectorFunction = functionResolver.resolve(
                    selectorCall.name,
                    astProgram,
                    FunctionResolver.Context.Selector
                )

                val type = selectorType(selectorFunction.decl) ?: error("Unknown selector '${selectorCall.name}'")

                lowerFunctionCall(
                    stmt.selectorFunction,
                    out,
                    symbols,
                    context,
                    FunctionResolver.Context.Selector
                )

                context.selectionStack.addLast(type)
                for (stmt in stmt.body.statements) {
                    lowerStatement(stmt, symbols, out, context)
                }
                context.selectionStack.removeLast()

                emitSelectionReset(out)
            }

            is Ast.FunctionCall -> {
                val symbol = functionResolver.resolve(
                    stmt.name,
                    astProgram,
                    functionResolver.contextForSelection(context.currentSelection())
                )
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

            is Ast.MemberFunctionCall -> {
                lowerMemberFunctionCall(stmt, out, symbols, context, requireReturn = false)
            }

            is Ast.InlineIr -> {
                out.addAll(stmt.ir)
            }

            is Ast.FieldAssignment -> {
                if (stmt.receiver is Ast.IdentifierExpr) {
                    symbols.assign(stmt.receiver.name)
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

            is Ast.ReturnStmt -> {
                out += Ir.SetVariableAction(
                    actionName = "=",
                    args = listOf(
                        Ir.Variable(RETURN_PARAMETER_NAME),
                        lowerExpr(stmt.expression, symbols, out, context)
                    ),
                    tags = emptyList(),
                )
                out += Ir.ControlAction("Return")
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
            is Ast.BoolExpr -> lowerBoolExprToValue(expr, symbols, out, context)
            is Ast.UnaryExpr -> when (expr.op) {
                Ast.UnaryOp.Not -> lowerBoolExprToValue(expr, symbols, out, context)
                Ast.UnaryOp.Negate -> lowerNumericExprToValue(expr, symbols, out, context)
            }

            is Ast.BinaryExpr -> if (expr.isBooleanBinary()) {
                lowerBoolExprToValue(expr, symbols, out, context)
            } else {
                lowerNumericExprToValue(expr, symbols, out, context)
            }

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

            is Ast.FunctionCallExpr,
            is Ast.MemberFunctionCall -> lowerExpr(expr, symbols, out, context)

            is Ast.IdentifierExpr -> {
                symbols.resolve(expr.name)
                Ir.Variable(expr.name)
            }

            is Ast.UnaryExpr -> {
                val inner = lowerBoolExprToValue(expr.expr, symbols, out, context)
                when (expr.op) {
                    Ast.UnaryOp.Not -> comparisonToTemp("=", Ir.NumberValue(0), inner, out, context)
                    Ast.UnaryOp.Negate -> error("Unsupported boolean expression $expr")
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

                    Ast.BinaryOp.Add,
                    Ast.BinaryOp.Sub,
                    Ast.BinaryOp.Mul,
                    Ast.BinaryOp.Div,
                    Ast.BinaryOp.Pow -> error("Unsupported boolean expression $expr")
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

    private fun lowerNumericExprToValue(
        expr: Ast.Expr,
        symbols: SymbolTable,
        out: MutableList<Ir.Instr>,
        context: LoweringContext
    ): Ir.Value {
        return when (expr) {
            is Ast.UnaryExpr -> when (expr.op) {
                Ast.UnaryOp.Negate -> {
                    val value = lowerExpr(expr.expr, symbols, out, context)
                    arithmeticToTemp("-", Ir.NumberValue(0), value, out, context)
                }

                Ast.UnaryOp.Not -> error("Unsupported numeric expression $expr")
            }

            is Ast.BinaryExpr -> {
                if (!expr.isArithmeticBinary()) {
                    error("Unsupported numeric expression $expr")
                }
                arithmeticToTemp(
                    actionName = expr.arithmeticActionName(),
                    left = lowerExpr(expr.left, symbols, out, context),
                    right = lowerExpr(expr.right, symbols, out, context),
                    out = out,
                    context = context
                )
            }

            else -> lowerExpr(expr, symbols, out, context)
        }
    }

    private fun arithmeticToTemp(
        actionName: String,
        left: Ir.Value,
        right: Ir.Value,
        out: MutableList<Ir.Instr>,
        context: LoweringContext
    ): Ir.Variable {
        val temp = context.newTempVariableName()
        out += Ir.SetVariableAction(
            actionName = actionName,
            args = listOf(Ir.Variable(temp), left, right),
            tags = emptyList()
        )
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
        context: LoweringContext,
        contextOverride: FunctionResolver.Context? = null,
    ) {
        val functionSymbol = functionResolver.resolve(
            stmt.name,
            astProgram,
            contextOverride ?: functionResolver.contextForSelection(context.currentSelection())
        )
        val returnArgs = if (functionSymbol.decl.returnType != null) {
            listOf(Ir.Variable(context.newTempVariableName()))
        } else {
            emptyList()
        }
        out += Ir.CallFunction(
            functionSymbol.qualifiedName,
            stmt.args.map { lowerExpr(it, symbols, out, context) } + returnArgs
        )
    }

    private fun lowerMemberFunctionCall(
        call: Ast.MemberFunctionCall,
        out: MutableList<Ir.Instr>,
        symbols: SymbolTable,
        context: LoweringContext,
        requireReturn: Boolean,
    ): Ir.Variable? {
        val resolution = resolveMemberCall(call, symbols)
        val returnTemp = if (resolution.symbol.decl.returnType != null) {
            Ir.Variable(context.newTempVariableName())
        } else {
            if (requireReturn) {
                error("Member function '${call.name}' does not return a value")
            }
            null
        }

        val receiverArgs = resolution.receiver?.let { listOf(lowerExpr(it, symbols, out, context)) } ?: emptyList()
        val args = receiverArgs + call.args.map { lowerExpr(it, symbols, out, context) } + listOfNotNull(returnTemp)
        out += Ir.CallFunction(resolution.symbol.qualifiedName, args)
        return returnTemp
    }

    private data class MemberResolution(
        val symbol: com.zbinfinn.compiler.FunctionSymbol,
        val receiver: Ast.Expr?,
    )

    private fun resolveMemberCall(call: Ast.MemberFunctionCall, symbols: SymbolTable): MemberResolution {
        val staticReceiver = call.receiver as? Ast.IdentifierExpr
        val staticType = staticReceiver
            ?.takeIf { runCatching { symbols.resolve(it.name) }.getOrNull() == null }
            ?.let { requireTypeTable().resolve(it.name, astProgram) }

        if (staticType != null) {
            val symbol = functionResolver.resolveMember(staticType.qualifiedName, call.name)
            if (!symbol.isStaticMember) {
                error("Member function '${call.name}' requires an instance")
            }
            return MemberResolution(symbol, null)
        }

        val receiverType = expressionDictType(call.receiver, symbols)
            ?: error("Cannot resolve receiver type for member function '${call.name}'")
        val symbol = functionResolver.resolveMember(receiverType, call.name)
        if (symbol.isStaticMember) {
            error("Static member function '${call.name}' must be called on a type")
        }
        val thisParam = symbol.decl.parameters.firstOrNull()
        if (thisParam?.mutable == true && call.receiver is Ast.IdentifierExpr) {
            symbols.assign(call.receiver.name)
        }
        return MemberResolution(symbol, call.receiver)
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
            is Ast.BoolExpr -> lowerBoolExprToValue(expr, symbols, out, context)
            is Ast.UnaryExpr -> when (expr.op) {
                Ast.UnaryOp.Not -> lowerBoolExprToValue(expr, symbols, out, context)
                Ast.UnaryOp.Negate -> lowerNumericExprToValue(expr, symbols, out, context)
            }

            is Ast.BinaryExpr -> if (expr.isBooleanBinary()) {
                lowerBoolExprToValue(expr, symbols, out, context)
            } else {
                lowerNumericExprToValue(expr, symbols, out, context)
            }
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

            is Ast.MemberFunctionCall -> {
                lowerMemberFunctionCall(expr, out, symbols, context, requireReturn = true)
                    ?: error("Member function '${expr.name}' does not return a value")
            }

            is Ast.FunctionCallExpr -> {
                val functionSymbol = functionResolver.resolve(
                    expr.name,
                    astProgram,
                    functionResolver.contextForSelection(context.currentSelection())
                )
                if (functionSymbol.decl.returnType == null) {
                    error("Function '${expr.name}' does not return a value")
                }

                val temp = context.newTempVariableName()
                out += Ir.CallFunction(
                    functionSymbol.qualifiedName,
                    expr.args.map { lowerExpr(it, symbols, out, context) } + Ir.Variable(temp)
                )
                Ir.Variable(temp)
            }
        }
    }

    private fun expressionDictType(expr: Ast.Expr, symbols: SymbolTable): String? {
        return when (expr) {
            is Ast.IdentifierExpr -> symbols.resolve(expr.name).typeQualifiedName
            is Ast.DictLiteralExpr -> requireTypeTable().resolve(expr.typeName, astProgram)?.qualifiedName
            is Ast.FieldAccessExpr -> {
                val receiverTypeName = expressionDictType(expr.receiver, symbols) ?: return null
                val receiverDecl = requireTypeTable().resolveQualified(receiverTypeName)?.decl ?: return null
                val field = receiverDecl.fields.firstOrNull { it.name == expr.field } ?: return null
                resolveDictType(field.type)
            }
            is Ast.FunctionCallExpr -> {
                val symbol = functionResolver.resolve(
                    expr.name,
                    astProgram,
                    functionResolver.contextForSelection(null)
                )
                symbol.decl.returnType?.let { resolveDictType(it) }
            }
            is Ast.MemberFunctionCall -> {
                val symbol = resolveMemberCall(expr, symbols).symbol
                symbol.decl.returnType?.let { resolveDictType(it) }
            }
            else -> null
        }
    }

    private fun resolveDictType(type: Ast.Type): String? {
        if (type.identifier in setOf("String", "Number", "Boolean", "boolean", "Any")) {
            return null
        }
        return requireTypeTable().resolve(type.identifier, astProgram)?.qualifiedName
    }

    private fun requireTypeTable(): GlobalTypeTable {
        return typeTable ?: error("Type table is required for member function lowering")
    }

    enum class FunctionSource {
        User,
        Std
    }

    data class FunctionInfo(
        val decl: Ast.FunctionDecl,
        val modulePath: String,
        val source: FunctionSource
    )
}

private fun Ast.BinaryExpr.isBooleanBinary(): Boolean {
    return op in setOf(
        Ast.BinaryOp.EqEq,
        Ast.BinaryOp.Neq,
        Ast.BinaryOp.AndAnd,
        Ast.BinaryOp.OrOr
    )
}

private fun Ast.BinaryExpr.isArithmeticBinary(): Boolean {
    return op in setOf(
        Ast.BinaryOp.Add,
        Ast.BinaryOp.Sub,
        Ast.BinaryOp.Mul,
        Ast.BinaryOp.Div,
        Ast.BinaryOp.Pow
    )
}

private fun Ast.BinaryExpr.arithmeticActionName(): String {
    return when (op) {
        Ast.BinaryOp.Add -> "+"
        Ast.BinaryOp.Sub -> "-"
        Ast.BinaryOp.Mul -> "*"
        Ast.BinaryOp.Div -> "/"
        Ast.BinaryOp.Pow -> "Exponent"
        else -> error("Unexpected arithmetic operator $op")
    }
}
