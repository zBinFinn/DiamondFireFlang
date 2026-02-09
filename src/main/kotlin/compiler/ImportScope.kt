package com.zbinfinn.compiler

import com.zbinfinn.ast.Ast

class ImportScope(program: Ast.Program) {
    private val imported = mutableMapOf<String, String>()

    init {
        for (import in program.imports) {
            val parts = import.path.split(".")
            val simple = parts.last()
            imported[simple] = import.path
        }
    }

    fun resolve(simpleName: String): String? =
        imported[simpleName]
}