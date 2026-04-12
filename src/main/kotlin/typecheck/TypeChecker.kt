package com.zbinfinn.typecheck

import com.zbinfinn.ast.Ast
import com.zbinfinn.common.selectorType
import com.zbinfinn.compiler.FunctionResolver
import com.zbinfinn.compiler.GlobalFunctionTable
import com.zbinfinn.compiler.GlobalTypeTable

class TypeChecker(
    private val globals: GlobalFunctionTable,
    private val functionResolver: FunctionResolver,
    private val typeTable: GlobalTypeTable,
) {
    private val resolver = TypeResolver(typeTable)

    fun check(program: Ast.Program): List<Diagnostic> {
        val diags = mutableListOf<Diagnostic>()

        for (dict in program.dicts) {
            for (field in dict.fields) {
                resolver.resolve(field.type, program, diags, function = null)
            }
        }

        for (fn in program.functions) {
            checkFunction(fn, program, diags)
        }

        return diags
    }

    private fun checkFunction(fn: Ast.FunctionDecl, program: Ast.Program, diags: MutableList<Diagnostic>) {
        val env = mutableMapOf<String, Type>()
        val functionName = fn.name

        for (param in fn.parameters) {
            val t = resolver.resolve(param.type, program, diags, function = functionName)
            env[param.name] = t
        }

        for (stmt in fn.body.statements) {
            checkStatement(stmt, program, functionName, env, diags)
        }
    }

    private fun checkStatement(
        stmt: Ast.Statement,
        program: Ast.Program,
        functionName: String,
        env: MutableMap<String, Type>,
        diags: MutableList<Diagnostic>,
    ) {
        when (stmt) {
            is Ast.ImmutableAssignment -> {
                if (env.containsKey(stmt.identifier)) {
                    diags += Diagnostic(
                        message = "Variable '${stmt.identifier}' already defined.",
                        module = program.module.path,
                        function = functionName,
                    )
                    checkExpr(stmt.expression, program, functionName, env, diags)
                } else {
                    val t = checkExpr(stmt.expression, program, functionName, env, diags)
                    env[stmt.identifier] = t
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
                    checkExpr(stmt.value, program, functionName, env, diags)
                    return
                }

                val recvType = env[recv.name]
                if (recvType == null) {
                    diags += Diagnostic(
                        message = "Variable '${recv.name}' not defined.",
                        module = program.module.path,
                        function = functionName,
                    )
                    checkExpr(stmt.value, program, functionName, env, diags)
                    return
                }

                when (recvType) {
                    Type.AnyType -> {
                        diags += Diagnostic(
                            message = "Cannot assign field '${stmt.field}' on value of type Any.",
                            module = program.module.path,
                            function = functionName,
                        )
                        checkExpr(stmt.value, program, functionName, env, diags)
                    }

                    is Type.Dict -> {
                        val fieldDecl = recvType.decl.fields.firstOrNull { it.name == stmt.field }
                        if (fieldDecl == null) {
                            diags += Diagnostic(
                                message = "Unknown field '${stmt.field}' on dict '${recvType.qualifiedName}'.",
                                module = program.module.path,
                                function = functionName,
                            )
                            checkExpr(stmt.value, program, functionName, env, diags)
                            return
                        }

                        val expected = resolver.resolve(fieldDecl.type, program, diags, functionName)
                        val actual = checkExpr(stmt.value, program, functionName, env, diags)
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
                        checkExpr(stmt.value, program, functionName, env, diags)
                    }

                    Type.Error -> {
                        checkExpr(stmt.value, program, functionName, env, diags)
                    }
                }
            }

            is Ast.FunctionCall -> checkFunctionCall(stmt, program, functionName, env, diags)

            is Ast.WithBlock -> {
                val symbol = resolveFunction(stmt.selectorFunction.name, program, functionName, diags)
                if (symbol != null && selectorType(symbol.decl) == null) {
                    diags += Diagnostic(
                        message = "Function '${stmt.selectorFunction.name}' is not a selector and cannot be used in a 'with' block.",
                        module = program.module.path,
                        function = functionName,
                    )
                }

                checkFunctionCall(stmt.selectorFunction, program, functionName, env, diags)
                for (inner in stmt.body.statements) {
                    checkStatement(inner, program, functionName, env, diags)
                }
            }

            is Ast.InlineIr -> {
                // No type information to check inside inline IR
            }

            is Ast.IfStmt -> {
                val condType = checkExpr(stmt.condition, program, functionName, env, diags)
                if (condType != Type.BooleanType && condType != Type.Error) {
                    diags += Diagnostic(
                        message = "If condition must be Boolean but got ${render(condType)}.",
                        module = program.module.path,
                        function = functionName,
                    )
                }

                for (inner in stmt.thenBlock.statements) {
                    checkStatement(inner, program, functionName, env, diags)
                }

                when (val elseBranch = stmt.elseBranch) {
                    null -> {}
                    is Ast.IfStmt.ElseBranch.Else -> {
                        for (inner in elseBranch.block.statements) {
                            checkStatement(inner, program, functionName, env, diags)
                        }
                    }

                    is Ast.IfStmt.ElseBranch.ElseIf -> {
                        checkStatement(elseBranch.stmt, program, functionName, env, diags)
                    }
                }
            }
        }
    }

    private fun checkFunctionCall(
        call: Ast.FunctionCall,
        program: Ast.Program,
        functionName: String,
        env: MutableMap<String, Type>,
        diags: MutableList<Diagnostic>,
    ) {
        val symbol = resolveFunction(call.name, program, functionName, diags) ?: run {
            for (arg in call.args) {
                checkExpr(arg, program, functionName, env, diags)
            }
            return
        }

        val params = symbol.decl.parameters
        if (call.args.size != params.size) {
            diags += Diagnostic(
                message = "Function '${call.name}' expects ${params.size} argument(s) but got ${call.args.size}.",
                module = program.module.path,
                function = functionName,
            )
        }

        val pairs = call.args.zip(params)
        for ((argExpr, param) in pairs) {
            val actual = checkExpr(argExpr, program, functionName, env, diags)
            val expected = resolver.resolve(param.type, program, diags, function = functionName)
            if (!isAssignable(actual, expected)) {
                diags += Diagnostic(
                    message = "Argument '${param.name}' expects ${render(expected)} but got ${render(actual)}.",
                    module = program.module.path,
                    function = functionName,
                )
            }
        }

        if (call.args.size > params.size) {
            for (argExpr in call.args.drop(params.size)) {
                checkExpr(argExpr, program, functionName, env, diags)
            }
        }
    }

    private fun checkExpr(
        expr: Ast.Expr,
        program: Ast.Program,
        functionName: String,
        env: MutableMap<String, Type>,
        diags: MutableList<Diagnostic>,
    ): Type {
        return when (expr) {
            is Ast.StringExpr -> Type.StringType
            is Ast.NumberExpr -> Type.NumberType
            is Ast.BoolExpr -> Type.BooleanType

            is Ast.IdentifierExpr -> {
                env[expr.name] ?: run {
                    diags += Diagnostic(
                        message = "Variable '${expr.name}' not defined.",
                        module = program.module.path,
                        function = functionName,
                    )
                    Type.Error
                }
            }

            is Ast.DictLiteralExpr -> checkDictLiteral(expr, program, functionName, env, diags)

            is Ast.FieldAccessExpr -> {
                val recvType = checkExpr(expr.receiver, program, functionName, env, diags)
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

                    Type.Error -> Type.Error
                }
            }

            is Ast.UnaryExpr -> {
                val t = checkExpr(expr.expr, program, functionName, env, diags)
                if (expr.op == Ast.UnaryOp.Not && t != Type.BooleanType && t != Type.Error) {
                    diags += Diagnostic(
                        message = "Operator '!' expects Boolean but got ${render(t)}.",
                        module = program.module.path,
                        function = functionName,
                    )
                }
                Type.BooleanType
            }

            is Ast.BinaryExpr -> {
                val left = checkExpr(expr.left, program, functionName, env, diags)
                val right = checkExpr(expr.right, program, functionName, env, diags)
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
                }
            }
        }
    }

    private fun checkDictLiteral(
        expr: Ast.DictLiteralExpr,
        program: Ast.Program,
        functionName: String,
        env: MutableMap<String, Type>,
        diags: MutableList<Diagnostic>,
    ): Type {
        val dictSymbol = typeTable.resolve(expr.typeName, program)
        if (dictSymbol == null) {
            diags += Diagnostic(
                message = "Unknown dict type '${expr.typeName}'.",
                module = program.module.path,
                function = functionName,
            )
            // Still walk entries for further issues.
            for (entry in expr.entries) {
                checkExpr(entry.value, program, functionName, env, diags)
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
                checkExpr(entry.value, program, functionName, env, diags)
                continue
            }

            val expected = resolver.resolve(fieldDecl.type, program, diags, functionName)
            val actual = checkExpr(entry.value, program, functionName, env, diags)
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
        diags: MutableList<Diagnostic>,
    ): com.zbinfinn.compiler.FunctionSymbol? {
        return try {
            functionResolver.resolve(name, program)
        } catch (t: Throwable) {
            diags += Diagnostic(
                message = t.message ?: "Unresolved function '$name'.",
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
        }
    }
}
