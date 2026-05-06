package com.zbinfinn.typecheck

import com.zbinfinn.ast.Ast
import com.zbinfinn.common.EntityEventAnnotation
import com.zbinfinn.common.PlayerEventAnnotation
import com.zbinfinn.common.parseEventAnnotation
import com.zbinfinn.common.requiredSelectionType
import com.zbinfinn.common.selectorType
import com.zbinfinn.compiler.DictSymbol
import com.zbinfinn.compiler.FunctionResolver
import com.zbinfinn.compiler.GlobalFunctionTable
import com.zbinfinn.compiler.GlobalTypeTable
import com.zbinfinn.compiler.SingletonSymbol
import com.zbinfinn.ir.LoweringContext

class TypeChecker(
    private val globals: GlobalFunctionTable,
    private val functionResolver: FunctionResolver,
    private val typeTable: GlobalTypeTable,
) {
    private val resolver = TypeResolver(typeTable)

    private data class VariableInfo(
        val type: Type,
        val mutable: Boolean
    )

    fun check(program: Ast.Program): List<Diagnostic> {
        val diags = mutableListOf<Diagnostic>()

        for (dict in program.dicts) {
            for (field in dict.fields) {
                resolver.resolve(field.type, program, diags, function = null)
            }
        }

        for (singleton in program.singletons) {
            for (annotation in singleton.annotations) {
                if (annotation.name == "PlayerEventProvider" || annotation.name == "EntityEventProvider") {
                    if (annotation.args.size != 1 || annotation.args.first() !is Ast.StringExpr) {
                        diags += Diagnostic(
                            message = "@${annotation.name} requires exactly one string argument.",
                            module = program.module.path,
                            function = null,
                        )
                    }
                }
            }
            for (fn in singleton.functions) {
                checkFunction(fn, program, diags, ownerType = singleton.name)
            }
        }

        for (impl in program.impls) {
            if (typeTable.resolve(impl.typeName, program) == null) {
                diags += Diagnostic(
                    message = "Unknown impl type '${impl.typeName}'.",
                    module = program.module.path,
                    function = null,
                )
            }
            for (fn in impl.functions) {
                checkFunction(fn, program, diags, ownerType = impl.typeName)
            }
        }

        for (fn in program.functions) {
            checkFunction(fn, program, diags)
        }

        return diags
    }

    private fun checkFunction(
        fn: Ast.FunctionDecl,
        program: Ast.Program,
        diags: MutableList<Diagnostic>,
        ownerType: String? = null
    ) {
        val env = mutableMapOf<String, VariableInfo>()
        val functionName = ownerType?.let { "$it.${fn.name}" } ?: fn.name
        val declaredReturnType = fn.returnType?.let {
            resolver.resolve(it, program, diags, function = functionName)
        }

        validateEventFunction(fn, program, functionName, diags)

        for (param in fn.parameters) {
            val t = resolver.resolve(param.type, program, diags, function = functionName)
            env[param.name] = VariableInfo(t, param.mutable)
        }

        if (fn.internal) {
            return
        }

        val activeSelection = requiredSelectionType(fn)
        for (stmt in fn.body.statements) {
            checkStatement(stmt, program, functionName, declaredReturnType, env, activeSelection, diags)
        }

        if (declaredReturnType != null && !blockAlwaysReturns(fn.body)) {
            diags += Diagnostic(
                message = "Function '${fn.name}' must return a value on every path.",
                module = program.module.path,
                function = functionName,
            )
        }
    }

    private fun checkStatement(
        stmt: Ast.Statement,
        program: Ast.Program,
        functionName: String,
        expectedReturnType: Type?,
        env: MutableMap<String, VariableInfo>,
        activeSelection: LoweringContext.SelectionType?,
        diags: MutableList<Diagnostic>,
    ) {
        when (stmt) {
            is Ast.VariableDeclaration -> {
                if (env.containsKey(stmt.identifier)) {
                    diags += Diagnostic(
                        message = "Variable '${stmt.identifier}' already defined.",
                        module = program.module.path,
                        function = functionName,
                    )
                    checkExpr(stmt.expression, program, functionName, env, diags, activeSelection)
                } else {
                    val t = checkExpr(stmt.expression, program, functionName, env, diags, activeSelection)
                    env[stmt.identifier] = VariableInfo(t, stmt.mutable)
                }
            }

            is Ast.VariableAssignment -> {
                val variable = env[stmt.identifier]
                if (variable == null) {
                    diags += Diagnostic(
                        message = "Variable '${stmt.identifier}' not defined.",
                        module = program.module.path,
                        function = functionName,
                    )
                    checkExpr(stmt.expression, program, functionName, env, diags, activeSelection)
                } else {
                    val actual = checkExpr(stmt.expression, program, functionName, env, diags, activeSelection)
                    if (!variable.mutable) {
                        diags += Diagnostic(
                            message = "Cannot reassign immutable variable '${stmt.identifier}'.",
                            module = program.module.path,
                            function = functionName,
                        )
                    }
                    if (!isAssignable(actual, variable.type)) {
                        diags += Diagnostic(
                            message = "Cannot assign value of type ${render(actual)} to variable '${stmt.identifier}' of type ${render(variable.type)}.",
                            module = program.module.path,
                            function = functionName,
                        )
                    }
                }
            }

            is Ast.FieldAssignment -> {
                val recv = stmt.receiver
                if (recv !is Ast.IdentifierExpr) {
                    diags += Diagnostic(
                        message = "Only simple dict field assignment supported (identifier receiver).",
                        module = program.module.path,
                        function = functionName,
                    )
                    checkExpr(stmt.value, program, functionName, env, diags, activeSelection)
                    return
                }

                val recvInfo = env[recv.name]
                if (recvInfo == null) {
                    diags += Diagnostic(
                        message = "Variable '${recv.name}' not defined.",
                        module = program.module.path,
                        function = functionName,
                    )
                    checkExpr(stmt.value, program, functionName, env, diags, activeSelection)
                    return
                }
                if (!recvInfo.mutable) {
                    diags += Diagnostic(
                        message = "Cannot assign field '${stmt.field}' on immutable variable '${recv.name}'.",
                        module = program.module.path,
                        function = functionName,
                    )
                }

                when (val recvType = recvInfo.type) {
                    Type.AnyType -> {
                        diags += Diagnostic(
                            message = "Cannot assign field '${stmt.field}' on value of type Any.",
                            module = program.module.path,
                            function = functionName,
                        )
                        checkExpr(stmt.value, program, functionName, env, diags, activeSelection)
                    }

                    is Type.Dict -> {
                        val fieldDecl = recvType.decl.fields.firstOrNull { it.name == stmt.field }
                        if (fieldDecl == null) {
                            diags += Diagnostic(
                                message = "Unknown field '${stmt.field}' on dict '${recvType.qualifiedName}'.",
                                module = program.module.path,
                                function = functionName,
                            )
                            checkExpr(stmt.value, program, functionName, env, diags, activeSelection)
                            return
                        }
                        if (!fieldDecl.mutable) {
                            diags += Diagnostic(
                                message = "Cannot assign immutable field '${stmt.field}' on dict '${recvType.qualifiedName}'.",
                                module = program.module.path,
                                function = functionName,
                            )
                        }

                        val expected = resolver.resolve(fieldDecl.type, program, diags, functionName)
                        val actual = checkExpr(stmt.value, program, functionName, env, diags, activeSelection)
                        if (!isAssignable(actual, expected)) {
                            diags += Diagnostic(
                                message = "Cannot assign value of type ${render(actual)} to field '${stmt.field}' of type ${render(expected)}.",
                                module = program.module.path,
                                function = functionName,
                            )
                        }
                    }

                    Type.StringType, Type.NumberType, Type.BooleanType -> {
                        diags += Diagnostic(
                            message = "Cannot assign field '${stmt.field}' on non-dict type ${render(recvType)}.",
                            module = program.module.path,
                            function = functionName,
                        )
                        checkExpr(stmt.value, program, functionName, env, diags, activeSelection)
                    }

                    is Type.Singleton -> {
                        diags += Diagnostic(
                            message = "Cannot assign field '${stmt.field}' on singleton type ${render(recvType)}.",
                            module = program.module.path,
                            function = functionName,
                        )
                        checkExpr(stmt.value, program, functionName, env, diags, activeSelection)
                    }

                    Type.Error -> {
                        checkExpr(stmt.value, program, functionName, env, diags, activeSelection)
                    }
                }
            }

            is Ast.MemberFunctionCall -> {
                checkMemberFunctionCall(stmt, program, functionName, env, activeSelection, diags, requireReturn = false)
            }

            is Ast.FunctionCall -> checkFunctionCall(stmt, program, functionName, env, activeSelection, diags)

            is Ast.WithBlock -> {
                val symbol = resolveFunction(
                    stmt.selectorFunction.name,
                    program,
                    functionName,
                    FunctionResolver.Context.Selector,
                    diags
                )
                if (symbol != null && selectorType(symbol.decl) == null) {
                    diags += Diagnostic(
                        message = "Function '${stmt.selectorFunction.name}' is not a selector and cannot be used in a 'with' block.",
                        module = program.module.path,
                        function = functionName,
                    )
                }

                checkFunctionCall(
                    stmt.selectorFunction,
                    program,
                    functionName,
                    env,
                    activeSelection,
                    diags,
                    FunctionResolver.Context.Selector
                )
                val selected = symbol?.decl?.let { selectorType(it) } ?: activeSelection
                for (inner in stmt.body.statements) {
                    checkStatement(inner, program, functionName, expectedReturnType, env, selected, diags)
                }
            }

            is Ast.InlineIr -> {
                // No type information to check inside inline IR
            }

            is Ast.IfStmt -> {
                val condType = checkExpr(stmt.condition, program, functionName, env, diags, activeSelection)
                if (condType != Type.BooleanType && condType != Type.Error) {
                    diags += Diagnostic(
                        message = "If condition must be Boolean but got ${render(condType)}.",
                        module = program.module.path,
                        function = functionName,
                    )
                }

                for (inner in stmt.thenBlock.statements) {
                    checkStatement(inner, program, functionName, expectedReturnType, env, activeSelection, diags)
                }

                when (val elseBranch = stmt.elseBranch) {
                    null -> {}
                    is Ast.IfStmt.ElseBranch.Else -> {
                        for (inner in elseBranch.block.statements) {
                            checkStatement(inner, program, functionName, expectedReturnType, env, activeSelection, diags)
                        }
                    }

                    is Ast.IfStmt.ElseBranch.ElseIf -> {
                        checkStatement(elseBranch.stmt, program, functionName, expectedReturnType, env, activeSelection, diags)
                    }
                }
            }

            is Ast.ReturnStmt -> {
                val actual = checkExpr(stmt.expression, program, functionName, env, diags, activeSelection)
                if (expectedReturnType == null) {
                    diags += Diagnostic(
                        message = "Cannot return a value from function '$functionName' because it has no return type.",
                        module = program.module.path,
                        function = functionName,
                    )
                } else if (!isAssignable(actual, expectedReturnType)) {
                    diags += Diagnostic(
                        message = "Cannot return ${render(actual)} from function '$functionName' with return type ${render(expectedReturnType)}.",
                        module = program.module.path,
                        function = functionName,
                    )
                }
            }
        }
    }

    private fun validateEventFunction(
        fn: Ast.FunctionDecl,
        program: Ast.Program,
        functionName: String,
        diags: MutableList<Diagnostic>
    ) {
        for (annotation in fn.annotations) {
            if (annotation.name == "PlayerEvent" || annotation.name == "EntityEvent") {
                diags += Diagnostic(
                    message = "@${annotation.name} is no longer supported; use @Event(EventSingleton).",
                    module = program.module.path,
                    function = functionName,
                )
                return
            }
        }

        val event = try {
            parseEventAnnotation(fn, program, typeTable)
        } catch (t: Throwable) {
            if (fn.annotations.any { it.name == "Event" }) {
                diags += Diagnostic(
                    message = t.message ?: "Invalid @Event annotation.",
                    module = program.module.path,
                    function = functionName,
                )
            }
            return
        } ?: return

        if (fn.parameters.size != 1) {
            diags += Diagnostic(
                message = "Event function '$functionName' must declare exactly one event parameter.",
                module = program.module.path,
                function = functionName,
            )
            return
        }

        val parameterType = resolver.resolve(fn.parameters.single().type, program, diags, functionName)
        val expected = event.singletonQualifiedName
        if (parameterType !is Type.Singleton || parameterType.qualifiedName != expected) {
            val kind = when (event) {
                is PlayerEventAnnotation -> "player"
                is EntityEventAnnotation -> "entity"
            }
            diags += Diagnostic(
                message = "@Event $kind function parameter must be ${expected.substringAfterLast('.')} but got ${render(parameterType)}.",
                module = program.module.path,
                function = functionName,
            )
        }
    }

    private fun blockAlwaysReturns(block: Ast.Block): Boolean {
        for (stmt in block.statements) {
            if (stmtAlwaysReturns(stmt)) {
                return true
            }
        }
        return false
    }

    private fun stmtAlwaysReturns(stmt: Ast.Statement): Boolean {
        return when (stmt) {
            is Ast.ReturnStmt -> true
            is Ast.IfStmt -> {
                val elseBranch = stmt.elseBranch ?: return false
                blockAlwaysReturns(stmt.thenBlock) && when (elseBranch) {
                    is Ast.IfStmt.ElseBranch.Else -> blockAlwaysReturns(elseBranch.block)
                    is Ast.IfStmt.ElseBranch.ElseIf -> stmtAlwaysReturns(elseBranch.stmt)
                }
            }
            else -> false
        }
    }

    private fun checkMemberFunctionCall(
        call: Ast.MemberFunctionCall,
        program: Ast.Program,
        functionName: String,
        env: MutableMap<String, VariableInfo>,
        activeSelection: LoweringContext.SelectionType?,
        diags: MutableList<Diagnostic>,
        requireReturn: Boolean,
    ): Type {
        val staticReceiver = call.receiver as? Ast.IdentifierExpr
        val staticType = staticReceiver
            ?.takeIf { !env.containsKey(it.name) }
            ?.let { typeTable.resolve(it.name, program) }

        val symbol = if (staticType != null) {
            resolveMember(staticType.qualifiedName, call.name, program, functionName, diags)?.also {
                if (!it.isStaticMember) {
                    diags += Diagnostic(
                        message = "Member function '${call.name}' requires an instance of '${staticType.simpleName}'.",
                        module = program.module.path,
                        function = functionName,
                    )
                }
            }
        } else {
            val receiverType = checkExpr(call.receiver, program, functionName, env, diags, activeSelection)
            if (receiverType !is Type.Dict && receiverType !is Type.Singleton) {
                if (receiverType != Type.Error) {
                    diags += Diagnostic(
                        message = "Cannot call member function '${call.name}' on ${render(receiverType)}.",
                        module = program.module.path,
                        function = functionName,
                    )
                }
                null
            } else {
                val receiverTypeName = when (receiverType) {
                    is Type.Dict -> receiverType.qualifiedName
                    is Type.Singleton -> receiverType.qualifiedName
                    else -> error("Unexpected receiver type $receiverType")
                }
                val receiverSimpleName = when (receiverType) {
                    is Type.Dict -> receiverType.decl.name
                    is Type.Singleton -> receiverType.decl.name
                    else -> error("Unexpected receiver type $receiverType")
                }
                resolveMember(receiverTypeName, call.name, program, functionName, diags)?.also {
                    if (it.isStaticMember) {
                        diags += Diagnostic(
                            message = "Static member function '${call.name}' must be called on type '$receiverSimpleName'.",
                            module = program.module.path,
                            function = functionName,
                        )
                    }

                    val thisParam = it.decl.parameters.firstOrNull()
                    if (thisParam?.mutable == true) {
                        val receiverInfo = (call.receiver as? Ast.IdentifierExpr)?.let { receiver -> env[receiver.name] }
                        if (receiverInfo?.mutable != true) {
                            diags += Diagnostic(
                                message = "Member function '${call.name}' requires a mutable receiver.",
                                module = program.module.path,
                                function = functionName,
                            )
                        }
                    }
                }
            }
        } ?: run {
            for (arg in call.args) {
                checkExpr(arg, program, functionName, env, diags, activeSelection)
            }
            return Type.Error
        }

        val params = if (symbol.isStaticMember) {
            symbol.decl.parameters
        } else {
            symbol.decl.parameters.drop(1)
        }
        checkArguments(call.name, call.args, params, program, functionName, env, activeSelection, diags)

        val returnType = symbol.decl.returnType
        if (returnType == null) {
            if (requireReturn) {
                diags += Diagnostic(
                    message = "Member function '${call.name}' does not return a value.",
                    module = program.module.path,
                    function = functionName,
                )
            }
            return Type.Error
        }

        return resolver.resolve(returnType, program, diags, functionName)
    }

    private fun checkFunctionCall(
        call: Ast.FunctionCall,
        program: Ast.Program,
        functionName: String,
        env: MutableMap<String, VariableInfo>,
        activeSelection: LoweringContext.SelectionType?,
        diags: MutableList<Diagnostic>,
        contextOverride: FunctionResolver.Context? = null,
    ): com.zbinfinn.compiler.FunctionSymbol? {
        val context = contextOverride ?: functionResolver.contextForSelection(activeSelection)
        val symbol = resolveFunction(call.name, program, functionName, context, diags) ?: run {
            for (arg in call.args) {
                checkExpr(arg, program, functionName, env, diags, activeSelection)
            }
            return null
        }

        checkArguments(call.name, call.args, symbol.decl.parameters, program, functionName, env, activeSelection, diags)

        return symbol
    }

    private fun checkArguments(
        callName: String,
        args: List<Ast.Expr>,
        params: List<Ast.Parameter>,
        program: Ast.Program,
        functionName: String,
        env: MutableMap<String, VariableInfo>,
        activeSelection: LoweringContext.SelectionType?,
        diags: MutableList<Diagnostic>,
    ) {
        if (args.size != params.size) {
            diags += Diagnostic(
                message = "Function '$callName' expects ${params.size} argument(s) but got ${args.size}.",
                module = program.module.path,
                function = functionName,
            )
        }

        val pairs = args.zip(params)
        for ((argExpr, param) in pairs) {
            val actual = checkExpr(argExpr, program, functionName, env, diags, activeSelection)
            val expected = resolver.resolve(param.type, program, diags, function = functionName)
            if (!isAssignable(actual, expected)) {
                diags += Diagnostic(
                    message = "Argument '${param.name}' expects ${render(expected)} but got ${render(actual)}.",
                    module = program.module.path,
                    function = functionName,
                )
            }
        }

        if (args.size > params.size) {
            for (argExpr in args.drop(params.size)) {
                checkExpr(argExpr, program, functionName, env, diags, activeSelection)
            }
        }
    }

    private fun checkExpr(
        expr: Ast.Expr,
        program: Ast.Program,
        functionName: String,
        env: MutableMap<String, VariableInfo>,
        diags: MutableList<Diagnostic>,
        activeSelection: LoweringContext.SelectionType? = null,
    ): Type {
        return when (expr) {
            is Ast.StringExpr -> Type.StringType
            is Ast.NumberExpr -> Type.NumberType
            is Ast.BoolExpr -> Type.BooleanType

            is Ast.IdentifierExpr -> {
                env[expr.name]?.type ?: run {
                    when (val symbol = typeTable.resolve(expr.name, program)) {
                        is SingletonSymbol -> Type.Singleton(symbol.qualifiedName, symbol.decl)
                        else -> {
                            diags += Diagnostic(
                                message = "Variable '${expr.name}' not defined.",
                                module = program.module.path,
                                function = functionName,
                            )
                            Type.Error
                        }
                    }
                }
            }

            is Ast.DictLiteralExpr -> checkDictLiteral(expr, program, functionName, env, diags, activeSelection)

            is Ast.FunctionCallExpr -> {
                val symbol = checkFunctionCall(
                    Ast.FunctionCall(expr.name, expr.args),
                    program,
                    functionName,
                    env,
                    activeSelection,
                    diags
                ) ?: return Type.Error

                val returnType = symbol.decl.returnType
                if (returnType == null) {
                    diags += Diagnostic(
                        message = "Function '${expr.name}' does not return a value.",
                        module = program.module.path,
                        function = functionName,
                    )
                    Type.Error
                } else {
                    resolver.resolve(returnType, program, diags, functionName)
                }
            }

            is Ast.MemberFunctionCall -> {
                checkMemberFunctionCall(expr, program, functionName, env, activeSelection, diags, requireReturn = true)
            }

            is Ast.FieldAccessExpr -> {
                val recvType = checkExpr(expr.receiver, program, functionName, env, diags, activeSelection)
                when (recvType) {
                    Type.AnyType -> {
                        diags += Diagnostic(
                            message = "Cannot access field '${expr.field}' on value of type Any.",
                            module = program.module.path,
                            function = functionName,
                        )
                        Type.Error
                    }

                    is Type.Dict -> {
                        val fieldDecl = recvType.decl.fields.firstOrNull { it.name == expr.field }
                        if (fieldDecl == null) {
                            diags += Diagnostic(
                                message = "Unknown field '${expr.field}' on dict '${recvType.qualifiedName}'.",
                                module = program.module.path,
                                function = functionName,
                            )
                            Type.Error
                        } else {
                            resolver.resolve(fieldDecl.type, program, diags, functionName)
                        }
                    }

                    Type.StringType, Type.NumberType, Type.BooleanType -> {
                        diags += Diagnostic(
                            message = "Cannot access field '${expr.field}' on non-dict type ${render(recvType)}.",
                            module = program.module.path,
                            function = functionName,
                        )
                        Type.Error
                    }

                    is Type.Singleton -> {
                        diags += Diagnostic(
                            message = "Cannot access field '${expr.field}' on singleton type ${render(recvType)}.",
                            module = program.module.path,
                            function = functionName,
                        )
                        Type.Error
                    }

                    Type.Error -> Type.Error
                }
            }

            is Ast.UnaryExpr -> {
                val t = checkExpr(expr.expr, program, functionName, env, diags, activeSelection)
                when (expr.op) {
                    Ast.UnaryOp.Not -> {
                        if (t != Type.BooleanType && t != Type.Error) {
                            diags += Diagnostic(
                                message = "Operator '!' expects Boolean but got ${render(t)}.",
                                module = program.module.path,
                                function = functionName,
                            )
                        }
                        Type.BooleanType
                    }

                    Ast.UnaryOp.Negate -> {
                        if (t != Type.NumberType && t != Type.Error) {
                            diags += Diagnostic(
                                message = "Operator '-' expects Number but got ${render(t)}.",
                                module = program.module.path,
                                function = functionName,
                            )
                        }
                        Type.NumberType
                    }
                }
            }

            is Ast.BinaryExpr -> {
                val left = checkExpr(expr.left, program, functionName, env, diags, activeSelection)
                val right = checkExpr(expr.right, program, functionName, env, diags, activeSelection)
                when (expr.op) {
                    Ast.BinaryOp.EqEq, Ast.BinaryOp.Neq -> {
                        if (!isAssignable(left, right) && !isAssignable(right, left) && left != Type.Error && right != Type.Error) {
                            diags += Diagnostic(
                                message = "Cannot compare ${render(left)} and ${render(right)}.",
                                module = program.module.path,
                                function = functionName,
                            )
                        }
                        Type.BooleanType
                    }

                    Ast.BinaryOp.AndAnd, Ast.BinaryOp.OrOr -> {
                        if (left != Type.BooleanType && left != Type.Error) {
                            diags += Diagnostic(
                                message = "Operator '${if (expr.op == Ast.BinaryOp.AndAnd) "&&" else "||"}' expects Boolean on left but got ${render(left)}.",
                                module = program.module.path,
                                function = functionName,
                            )
                        }
                        if (right != Type.BooleanType && right != Type.Error) {
                            diags += Diagnostic(
                                message = "Operator '${if (expr.op == Ast.BinaryOp.AndAnd) "&&" else "||"}' expects Boolean on right but got ${render(right)}.",
                                module = program.module.path,
                                function = functionName,
                            )
                        }
                        Type.BooleanType
                    }

                    Ast.BinaryOp.Add, Ast.BinaryOp.Sub, Ast.BinaryOp.Mul, Ast.BinaryOp.Div, Ast.BinaryOp.Pow -> {
                        val op = when (expr.op) {
                            Ast.BinaryOp.Add -> "+"
                            Ast.BinaryOp.Sub -> "-"
                            Ast.BinaryOp.Mul -> "*"
                            Ast.BinaryOp.Div -> "/"
                            Ast.BinaryOp.Pow -> "^"
                            else -> error("Unexpected arithmetic operator ${expr.op}")
                        }
                        if (left != Type.NumberType && left != Type.Error) {
                            diags += Diagnostic(
                                message = "Operator '$op' expects Number on left but got ${render(left)}.",
                                module = program.module.path,
                                function = functionName,
                            )
                        }
                        if (right != Type.NumberType && right != Type.Error) {
                            diags += Diagnostic(
                                message = "Operator '$op' expects Number on right but got ${render(right)}.",
                                module = program.module.path,
                                function = functionName,
                            )
                        }
                        Type.NumberType
                    }
                }
            }
        }
    }

    private fun checkDictLiteral(
        expr: Ast.DictLiteralExpr,
        program: Ast.Program,
        functionName: String,
        env: MutableMap<String, VariableInfo>,
        diags: MutableList<Diagnostic>,
        activeSelection: LoweringContext.SelectionType? = null,
    ): Type {
        val dictSymbol = typeTable.resolve(expr.typeName, program) as? DictSymbol
        if (dictSymbol == null) {
            diags += Diagnostic(
                message = "Unknown dict type '${expr.typeName}'.",
                module = program.module.path,
                function = functionName,
            )
            // Still walk entries for further issues.
            for (entry in expr.entries) {
                checkExpr(entry.value, program, functionName, env, diags, activeSelection)
            }
            return Type.Error
        }

        val dictType = Type.Dict(dictSymbol.qualifiedName, dictSymbol.decl)
        val declaredFields = dictSymbol.decl.fields.associateBy { it.name }

        val seen = mutableSetOf<String>()
        for (entry in expr.entries) {
            if (!seen.add(entry.field)) {
                diags += Diagnostic(
                    message = "Duplicate field '${entry.field}' in ${expr.typeName} literal.",
                    module = program.module.path,
                    function = functionName,
                )
            }

            val fieldDecl = declaredFields[entry.field]
            if (fieldDecl == null) {
                diags += Diagnostic(
                    message = "Unknown field '${entry.field}' for dict '${dictType.qualifiedName}'.",
                    module = program.module.path,
                    function = functionName,
                )
                checkExpr(entry.value, program, functionName, env, diags, activeSelection)
                continue
            }

            val expected = resolver.resolve(fieldDecl.type, program, diags, functionName)
            val actual = checkExpr(entry.value, program, functionName, env, diags, activeSelection)
            if (!isAssignable(actual, expected)) {
                diags += Diagnostic(
                    message = "Field '${entry.field}' expects ${render(expected)} but got ${render(actual)}.",
                    module = program.module.path,
                    function = functionName,
                )
            }
        }

        for (decl in dictSymbol.decl.fields) {
            if (!seen.contains(decl.name)) {
                diags += Diagnostic(
                    message = "Missing required field '${decl.name}' for dict '${dictType.qualifiedName}'.",
                    module = program.module.path,
                    function = functionName,
                )
            }
        }

        return dictType
    }

    private fun resolveFunction(
        name: String,
        program: Ast.Program,
        functionName: String,
        context: FunctionResolver.Context,
        diags: MutableList<Diagnostic>,
    ): com.zbinfinn.compiler.FunctionSymbol? {
        return try {
            functionResolver.resolve(name, program, context)
        } catch (t: Throwable) {
            diags += Diagnostic(
                message = t.message ?: "Unresolved function '$name'.",
                module = program.module.path,
                function = functionName,
            )
            null
        }
    }

    private fun resolveMember(
        typeQualifiedName: String,
        name: String,
        program: Ast.Program,
        functionName: String,
        diags: MutableList<Diagnostic>,
    ): com.zbinfinn.compiler.FunctionSymbol? {
        return try {
            functionResolver.resolveMember(typeQualifiedName, name)
        } catch (t: Throwable) {
            diags += Diagnostic(
                message = t.message ?: "Unresolved member function '$typeQualifiedName.$name'.",
                module = program.module.path,
                function = functionName,
            )
            null
        }
    }

    private fun render(t: Type): String {
        return when (t) {
            Type.StringType -> "String"
            Type.NumberType -> "Number"
            Type.BooleanType -> "Boolean"
            Type.AnyType -> "Any"
            Type.Error -> "<error>"
            is Type.Dict -> t.qualifiedName
            is Type.Singleton -> t.qualifiedName
        }
    }
}
