package com.zbinfinn.compiler

import com.zbinfinn.ast.Ast

class FunctionResolver(
    private val globals: GlobalFunctionTable
) {
    fun resolve(
        name: String,
        program: Ast.Program,
    ): FunctionSymbol {
        val module = program.module.path

        // Function present in module
        globals.functionsInModule(module)[name]?.let { return it }

        // Function imported
        val importScope = ImportScope(program)
        val qualified = importScope.resolve(name)
        if (qualified != null) {
            return globals.resolveQualified(qualified)
                ?: error("Imported function '$qualified' not found.")
        }

        error("Unresolved function '$name'")
    }
}