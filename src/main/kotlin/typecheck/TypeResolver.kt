package com.zbinfinn.typecheck

import com.zbinfinn.ast.Ast
import com.zbinfinn.compiler.GlobalTypeTable

class TypeResolver(
    private val typeTable: GlobalTypeTable,
) {
    fun resolve(type: Ast.Type, program: Ast.Program, diags: MutableList<Diagnostic>, function: String?): Type {
        val id = type.identifier
        return when (id) {
            "String" -> Type.StringType
            "Number" -> Type.NumberType
            "Any" -> Type.AnyType
            else -> {
                val symbol = typeTable.resolve(id, program)
                if (symbol == null) {
                    diags += Diagnostic(
                        message = "Unknown type '$id'.",
                        module = program.module.path,
                        function = function,
                    )
                    Type.Error
                } else {
                    Type.Dict(symbol.qualifiedName, symbol.decl)
                }
            }
        }
    }
}
