package com.zbinfinn.typecheck

import com.zbinfinn.ast.Ast
import com.zbinfinn.compiler.DictSymbol
import com.zbinfinn.compiler.GlobalTypeTable
import com.zbinfinn.compiler.SingletonSymbol

class TypeResolver(
    private val typeTable: GlobalTypeTable,
) {
    fun resolve(
        type: Ast.Type,
        program: Ast.Program,
        diags: MutableList<Diagnostic>,
        function: String?,
        typeParameters: Set<String> = emptySet(),
    ): Type {
        val id = type.identifier
        if (id in typeParameters && type.args.isEmpty()) {
            return Type.TypeParameter(id)
        }
        return when (id) {
            "String" -> expectNoTypeArgs(type, program, diags, function) ?: Type.StringType
            "Number" -> expectNoTypeArgs(type, program, diags, function) ?: Type.NumberType
            "Boolean", "boolean" -> expectNoTypeArgs(type, program, diags, function) ?: Type.BooleanType
            "Any" -> expectNoTypeArgs(type, program, diags, function) ?: Type.AnyType
            "List" -> {
                if (type.args.size != 1) {
                    diags += Diagnostic(
                        message = "Type 'List' expects exactly 1 type argument.",
                        module = program.module.path,
                        function = function,
                    )
                    Type.Error
                } else {
                    Type.ListType(resolve(type.args.single(), program, diags, function, typeParameters))
                }
            }
            "Dictionary" -> {
                if (type.args.size != 1) {
                    diags += Diagnostic(
                        message = "Type 'Dictionary' expects exactly 1 type argument.",
                        module = program.module.path,
                        function = function,
                    )
                    Type.Error
                } else {
                    Type.DictionaryType(resolve(type.args.single(), program, diags, function, typeParameters))
                }
            }
            else -> {
                if (type.args.isNotEmpty()) {
                    diags += Diagnostic(
                        message = "Type '$id' does not accept type arguments.",
                        module = program.module.path,
                        function = function,
                    )
                    return Type.Error
                }
                val symbol = typeTable.resolve(id, program)
                if (symbol == null) {
                    diags += Diagnostic(
                        message = "Unknown type '$id'.",
                        module = program.module.path,
                        function = function,
                    )
                    Type.Error
                } else {
                    when (symbol) {
                        is DictSymbol -> Type.Dict(symbol.qualifiedName, symbol.decl)
                        is SingletonSymbol -> Type.Singleton(symbol.qualifiedName, symbol.decl)
                    }
                }
            }
        }
    }

    private fun expectNoTypeArgs(
        type: Ast.Type,
        program: Ast.Program,
        diags: MutableList<Diagnostic>,
        function: String?,
    ): Type? {
        if (type.args.isEmpty()) return null
        diags += Diagnostic(
            message = "Type '${type.identifier}' does not accept type arguments.",
            module = program.module.path,
            function = function,
        )
        return Type.Error
    }
}
