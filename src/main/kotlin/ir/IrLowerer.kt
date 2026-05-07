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
import com.zbinfinn.compiler.DictSymbol
import com.zbinfinn.compiler.EnumSymbol
import com.zbinfinn.compiler.SingletonSymbol
import com.zbinfinn.stdlib.ImportContext
import com.zbinfinn.stdlib.InternalStdlib
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

        for (stdProgram in StdlibAst.programs) {
            for (fn in stdProgram.functions) {
                val import = "${stdProgram.module.path}.${fn.name}"

                if (importContext.isImported(import)) {
                    table[fn.name] = FunctionInfo(fn, stdProgram.module.path, FunctionSource.Std)
                }
            }
        }

        return table
    }

    fun lowerProgram(): Ir.Program {
        val entryPoints = mutableListOf<Ir.EntryPoint>()
        val functions = mutableListOf<Ir.Function>()
        val context = LoweringContext()

        for (function in astProgram.functions) {
            val event = if (function.annotations.any { it.name == "Event" || it.name == "PlayerEvent" || it.name == "EntityEvent" }) {
                parseEventAnnotation(function, astProgram, requireTypeTable())
            } else {
                null
            }

            if (event != null) {
                entryPoints += lowerEvent(function, event, context)
            } else {
                functions += lowerFunction(function, astProgram.module.path, context)
            }
        }

        for (fn in functionTable.values.filter { it.source == FunctionSource.Std }) {
            functions += lowerFunction(fn.decl, fn.modulePath, context)
        }

        for (stdProgram in StdlibAst.programs) {
            for (singleton in stdProgram.singletons) {
                val import = "${stdProgram.module.path}.${singleton.name}"
                if (!importContext.isImported(import)) {
                    continue
                }

                val typeQualifiedName = import
                for (function in singleton.functions.filterNot { it.internal }) {
                    val symbol = globals.resolveMember(typeQualifiedName, function.name)
                        ?: error("Registered member function '$typeQualifiedName.${function.name}' not found")
                    functions += lowerFunction(function, stdProgram.module.path, context, symbol)
                }
            }
        }

        for (impl in astProgram.impls) {
            val typeQualifiedName = memberOwnerQualifiedName(astProgram.module.path, impl.type)
            for (function in impl.functions) {
                val symbol = globals.resolveMember(typeQualifiedName, function.name)
                    ?: error("Registered member function '$typeQualifiedName.${function.name}' not found")
                functions += lowerFunction(function, astProgram.module.path, context, symbol)
            }
        }

        for (singleton in astProgram.singletons) {
            val typeQualifiedName = "${astProgram.module.path}.${singleton.name}"
            for (function in singleton.functions) {
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

        val eventParam = function.parameters.singleOrNull()
        if (eventParam != null) {
            symbols.define(
                eventParam.name,
                mutable = eventParam.mutable,
                typeQualifiedName = event.singletonQualifiedName,
            )
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
        if (requiresSelection && function.body.statements.isEmpty() && !function.internal) {
            error("Selection handler function '${function.name}' must have a body")
        }

        val symbols = SymbolTable()
        val body = mutableListOf<Ir.Instr>()

        val parameters = mutableListOf<Ir.Parameter>()
        for (param in function.parameters) {
            val typeQualifiedName = resolveTypeQualifiedName(param.type)
            if (!isSingletonType(typeQualifiedName)) {
                parameters += lowerParameter(param)
            }
            symbols.define(param.name, mutable = param.mutable, typeQualifiedName = typeQualifiedName)
        }
        val returnTypeQualifiedName = function.returnType?.let { resolveTypeQualifiedName(it) }
        if (function.returnType != null && !isSingletonType(returnTypeQualifiedName)) {
            parameters += Ir.Parameter(RETURN_PARAMETER_NAME, mutable = true)
            symbols.define(RETURN_PARAMETER_NAME, mutable = true)
        }

        val requiredSelection = requiredSelectionType(function)
        if (requiredSelection != null) {
            context.selectionStack.addLast(requiredSelection)
        }

        if (function.internal) {
            val functionSymbol = symbolOverride ?: globals.resolveInModule(modulePath, function.name, functionKind(function))
                ?: error("Registered symbol for function '${function.name}' not found")
            body += InternalStdlib.functionBody(functionSymbol.qualifiedName, function.parameters.map { Ir.Variable(it.name) })
                ?: error("Missing internal stdlib provider for '${functionSymbol.qualifiedName}'")
        } else {
            for (stmt in function.body.statements) {
                lowerStatement(stmt, symbols, body, context)
            }
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
                    typeQualifiedName = expressionTypeQualifiedName(stmt.expression, symbols)
                )
                if (value !is Ir.SingletonValue) {
                    out += Ir.SetVariableAction(
                        actionName = "=",
                        args = listOf(
                            Ir.Variable(name),
                            value
                        ),
                        tags = emptyList(),
                    )
                }
            }

            is Ast.VariableAssignment -> {
                symbols.assign(stmt.identifier)
                val expectedType = symbols.resolve(stmt.identifier).typeQualifiedName?.let { qualifiedNameToAstType(it) }
                val value = lowerExpr(stmt.expression, symbols, out, context, expectedType)
                if (value !is Ir.SingletonValue) {
                    out += Ir.SetVariableAction(
                        actionName = "=",
                        args = listOf(
                            Ir.Variable(stmt.identifier),
                            value
                        ),
                        tags = emptyList(),
                    )
                }
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
                val value = lowerExpr(stmt.expression, symbols, out, context)
                if (value !is Ir.SingletonValue) {
                    out += Ir.SetVariableAction(
                        actionName = "=",
                        args = listOf(
                            Ir.Variable(RETURN_PARAMETER_NAME),
                            value
                        ),
                        tags = emptyList(),
                    )
                }
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
                out += Ir.Else
                out += Ir.OpenBracket
                for (inner in elseBranch.block.statements) {
                    lowerStatement(inner, symbols, out, context)
                }
                out += Ir.CloseBracket
            }

            is Ast.IfStmt.ElseBranch.ElseIf -> {
                out += Ir.Else
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
        val returnTypeQualifiedName = functionSymbol.decl.returnType?.let { resolveTypeQualifiedName(it) }
        val returnArgs = if (functionSymbol.decl.returnType != null && !isSingletonType(returnTypeQualifiedName)) {
            listOf(Ir.Variable(context.newTempVariableName()))
        } else {
            emptyList()
        }
        val runtimeArgs = stmt.args
            .zip(functionSymbol.decl.parameters)
            .map { (arg, param) -> lowerExpr(arg, symbols, out, context, param.type) }
            .filterNot { it is Ir.SingletonValue }
        out += Ir.CallFunction(
            functionSymbol.qualifiedName,
            runtimeArgs + returnArgs
        )
    }

    private fun lowerMemberFunctionCall(
        call: Ast.MemberFunctionCall,
        out: MutableList<Ir.Instr>,
        symbols: SymbolTable,
        context: LoweringContext,
        requireReturn: Boolean,
    ): Ir.Value? {
        val resolution = resolveMemberCall(call, symbols)
        val returnTypeQualifiedName = resolution.symbol.decl.returnType?.let { resolveTypeQualifiedName(it) }
        val returnTemp = if (resolution.symbol.decl.returnType != null && !isSingletonType(returnTypeQualifiedName)) {
            Ir.Variable(context.newTempVariableName())
        } else {
            if (requireReturn) {
                if (isSingletonType(returnTypeQualifiedName)) {
                    null
                } else {
                    error("Member function '${call.name}' does not return a value")
                }
            } else {
                null
            }
        }

        val receiverArgs = resolution.receiver?.let { listOf(lowerExpr(it, symbols, out, context)) } ?: emptyList()
        val params = if (resolution.symbol.isStaticMember) resolution.symbol.decl.parameters else resolution.symbol.decl.parameters.drop(1)
        val args = receiverArgs + call.args.zip(params).map { (arg, param) -> lowerExpr(arg, symbols, out, context, param.type) } + listOfNotNull(returnTemp)
        val internalBody = InternalStdlib.memberBody(resolution.symbol.qualifiedName, args)
        if (internalBody != null) {
            out += internalBody
        } else {
            out += Ir.CallFunction(resolution.symbol.qualifiedName, args.filterNot { it is Ir.SingletonValue })
        }
        return returnTemp ?: returnTypeQualifiedName
            ?.takeIf { isSingletonType(it) }
            ?.let { Ir.SingletonValue(it) }
    }

    private data class MemberResolution(
        val symbol: com.zbinfinn.compiler.FunctionSymbol,
        val receiver: Ast.Expr?,
    )

    private fun resolveMemberCall(call: Ast.MemberFunctionCall, symbols: SymbolTable): MemberResolution {
        if (call.receiver is Ast.TypeExpr) {
            val receiverType = resolveTypeQualifiedName(call.receiver.type)
                ?: error("Cannot resolve type receiver for member function '${call.name}'")
            val symbol = functionResolver.resolveMember(receiverType, call.name)
            if (!symbol.isStaticMember) {
                error("Member function '${call.name}' requires an instance")
            }
            return MemberResolution(symbol, null)
        }

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

        val receiverType = expressionTypeQualifiedName(call.receiver, symbols)
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
        context: LoweringContext,
        expectedType: Ast.Type? = null,
    ): Ir.Value {
        return when (expr) {
            is Ast.StringExpr -> Ir.StringValue(expr.value)
            is Ast.TextExpr -> Ir.StyledText(expr.value)
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
                val symbol = runCatching { symbols.resolve(expr.name) }.getOrNull()
                if (symbol != null) {
                    if (isSingletonType(symbol.typeQualifiedName)) {
                        Ir.SingletonValue(symbol.typeQualifiedName!!)
                    } else {
                        Ir.Variable(expr.name)
                    }
                } else {
                    val typeSymbol = requireTypeTable().resolve(expr.name, astProgram)
                    if (typeSymbol is SingletonSymbol) {
                        Ir.SingletonValue(typeSymbol.qualifiedName)
                    } else {
                        error("Variable ${expr.name} not defined")
                    }
                }
            }

            is Ast.InferredEnumCaseExpr -> {
                val enum = expectedType?.let { resolveEnum(it) }
                    ?: error("Enum shorthand '.${expr.caseName}' has no enum context")
                val case = enum.decl.cases.firstOrNull { it.name == expr.caseName }
                    ?: error("Enum '${enum.simpleName}' has no case '${expr.caseName}'")
                Ir.StringValue(case.runtimeValue())
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
                val staticEnum = (expr.receiver as? Ast.IdentifierExpr)
                    ?.let { requireTypeTable().resolve(it.name, astProgram) as? EnumSymbol }
                if (staticEnum != null) {
                    val case = staticEnum.decl.cases.firstOrNull { it.name == expr.field }
                        ?: error("Enum '${staticEnum.simpleName}' has no case '${expr.field}'")
                    return Ir.StringValue(case.runtimeValue())
                }

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
                if (expr.name == "name") {
                    val enum = expressionEnumSymbol(expr, symbols)
                    if (enum != null) {
                        return lowerEnumName(expr.receiver, enum, symbols, out, context)
                    }
                }
                if (expr.name == "ordinal") {
                    val enum = expressionEnumSymbol(expr, symbols)
                    if (enum != null) {
                        return lowerEnumOrdinal(expr.receiver, enum, symbols, out, context)
                    }
                }
                lowerMemberFunctionCall(expr, out, symbols, context, requireReturn = true)
                    ?: error("Member function '${expr.name}' does not return a value")
            }

            is Ast.TypeExpr -> error("Type '${expr.type.identifier}' has no runtime value")

            is Ast.GameValueExpr -> Ir.GameValue(expr.name, expr.target?.let { enumTargetName(it, symbols, out, context) })

            is Ast.FunctionCallExpr -> {
                val functionSymbol = functionResolver.resolve(
                    expr.name,
                    astProgram,
                    functionResolver.contextForSelection(context.currentSelection())
                )
                if (functionSymbol.decl.returnType == null) {
                    error("Function '${expr.name}' does not return a value")
                }

                val returnTypeQualifiedName = functionSymbol.decl.returnType?.let { resolveTypeQualifiedName(it) }
                if (isSingletonType(returnTypeQualifiedName)) {
                    return Ir.SingletonValue(returnTypeQualifiedName!!)
                }

                val temp = context.newTempVariableName()
                val runtimeArgs = expr.args
                    .zip(functionSymbol.decl.parameters)
                    .map { (arg, param) -> lowerExpr(arg, symbols, out, context, param.type) }
                    .filterNot { it is Ir.SingletonValue }
                out += Ir.CallFunction(
                    functionSymbol.qualifiedName,
                    runtimeArgs + Ir.Variable(temp)
                )
                Ir.Variable(temp)
            }
        }
    }

    private fun enumTargetName(
        expr: Ast.Expr,
        symbols: SymbolTable,
        out: MutableList<Ir.Instr>,
        context: LoweringContext,
    ): String {
        val value = lowerExpr(expr, symbols, out, context, Ast.Type("GameValueTarget"))
        return (value as? Ir.StringValue)?.value
            ?: error("gval target must be a compile-time enum case")
    }

    private fun lowerEnumName(
        receiver: Ast.Expr,
        enum: EnumSymbol,
        symbols: SymbolTable,
        out: MutableList<Ir.Instr>,
        context: LoweringContext,
    ): Ir.Value {
        if (receiver is Ast.FieldAccessExpr && receiver.receiver is Ast.IdentifierExpr) {
            val case = enum.decl.cases.firstOrNull { it.name == receiver.field }
            if (case != null) return Ir.StringValue(case.name)
        }

        val enumValue = lowerExpr(receiver, symbols, out, context, Ast.Type(enum.simpleName))
        val temp = Ir.Variable(context.newTempVariableName())
        out += Ir.SetVariableAction("=", listOf(temp, Ir.StringValue("<invalid>")), emptyList())
        for (case in enum.decl.cases) {
            out += Ir.IfVarAction("=", listOf(enumValue, Ir.StringValue(case.runtimeValue())))
            out += Ir.OpenBracket
            out += Ir.SetVariableAction("=", listOf(temp, Ir.StringValue(case.name)), emptyList())
            out += Ir.CloseBracket
        }
        return temp
    }

    private fun lowerEnumOrdinal(
        receiver: Ast.Expr,
        enum: EnumSymbol,
        symbols: SymbolTable,
        out: MutableList<Ir.Instr>,
        context: LoweringContext,
    ): Ir.Value {
        if (receiver is Ast.FieldAccessExpr && receiver.receiver is Ast.IdentifierExpr) {
            val ordinal = enum.decl.cases.indexOfFirst { it.name == receiver.field }
            if (ordinal >= 0) return Ir.NumberValue(ordinal)
        }

        val enumValue = lowerExpr(receiver, symbols, out, context, Ast.Type(enum.simpleName))
        val temp = Ir.Variable(context.newTempVariableName())
        out += Ir.SetVariableAction("=", listOf(temp, Ir.NumberValue(-1)), emptyList())
        for ((ordinal, case) in enum.decl.cases.withIndex()) {
            out += Ir.IfVarAction("=", listOf(enumValue, Ir.StringValue(case.runtimeValue())))
            out += Ir.OpenBracket
            out += Ir.SetVariableAction("=", listOf(temp, Ir.NumberValue(ordinal)), emptyList())
            out += Ir.CloseBracket
        }
        return temp
    }

    private fun expressionEnumSymbol(expr: Ast.Expr, symbols: SymbolTable): EnumSymbol? {
        return when (expr) {
            is Ast.MemberFunctionCall -> expressionEnumSymbol(expr.receiver, symbols)
            is Ast.IdentifierExpr -> runCatching { symbols.resolve(expr.name).typeQualifiedName }.getOrNull()
                ?.let { requireTypeTable().resolveQualified(it) as? EnumSymbol }
            is Ast.FieldAccessExpr -> (expr.receiver as? Ast.IdentifierExpr)
                ?.let { requireTypeTable().resolve(it.name, astProgram) as? EnumSymbol }
            else -> null
        }
    }

    private fun resolveEnum(type: Ast.Type): EnumSymbol? {
        return requireTypeTable().resolve(type.identifier, astProgram) as? EnumSymbol
    }

    private fun qualifiedNameToAstType(qualifiedName: String): Ast.Type? {
        val symbol = requireTypeTable().resolveQualified(qualifiedName)
        return symbol?.simpleName?.let { Ast.Type(it) }
    }

    private fun expressionTypeQualifiedName(expr: Ast.Expr, symbols: SymbolTable): String? {
        return when (expr) {
            is Ast.IdentifierExpr -> runCatching { symbols.resolve(expr.name).typeQualifiedName }.getOrNull()
                ?: (requireTypeTable().resolve(expr.name, astProgram) as? SingletonSymbol)?.qualifiedName
            is Ast.DictLiteralExpr -> (requireTypeTable().resolve(expr.typeName, astProgram) as? DictSymbol)?.qualifiedName
            is Ast.FieldAccessExpr -> {
                val staticEnum = (expr.receiver as? Ast.IdentifierExpr)
                    ?.let { requireTypeTable().resolve(it.name, astProgram) as? EnumSymbol }
                if (staticEnum != null) return staticEnum.qualifiedName

                val receiverTypeName = expressionTypeQualifiedName(expr.receiver, symbols) ?: return null
                val receiverDecl = (requireTypeTable().resolveQualified(receiverTypeName) as? DictSymbol)?.decl ?: return null
                val field = receiverDecl.fields.firstOrNull { it.name == expr.field } ?: return null
                resolveTypeQualifiedName(field.type)
            }
            is Ast.FunctionCallExpr -> {
                val symbol = functionResolver.resolve(
                    expr.name,
                    astProgram,
                    functionResolver.contextForSelection(null)
                )
                symbol.decl.returnType?.let { resolveTypeQualifiedName(it) }
            }
            is Ast.MemberFunctionCall -> {
                if ((expr.name == "name" || expr.name == "ordinal") && expressionEnumSymbol(expr, symbols) != null) {
                    return null
                }
                val symbol = resolveMemberCall(expr, symbols).symbol
                symbol.decl.returnType?.let { resolveTypeQualifiedName(it) }
            }
            is Ast.TypeExpr -> resolveTypeQualifiedName(expr.type)
            is Ast.GameValueExpr -> null
            else -> null
        }
    }

    private fun resolveTypeQualifiedName(type: Ast.Type): String? {
        if (type.identifier in setOf("String", "Text", "Number", "Boolean", "boolean", "Any")) {
            return null
        }
        if (type.identifier == "List") {
            return "std.collections.List"
        }
        if (type.identifier == "Dictionary") {
            return "std.collections.Dictionary"
        }
        return requireTypeTable().resolve(type.identifier, astProgram)?.qualifiedName
    }

    private fun memberOwnerQualifiedName(modulePath: String, type: Ast.Type): String {
        return when (type.identifier) {
            "List" -> "std.collections.List"
            "Dictionary" -> "std.collections.Dictionary"
            else -> "$modulePath.${type.identifier}"
        }
    }

    private fun isSingletonType(typeQualifiedName: String?): Boolean {
        return typeQualifiedName != null && requireTypeTable().resolveQualified(typeQualifiedName) is SingletonSymbol
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

private fun Ast.EnumDecl.EnumCase.runtimeValue(): String = value ?: name
