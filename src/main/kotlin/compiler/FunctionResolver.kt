package com.zbinfinn.compiler

import com.zbinfinn.ast.Ast
import com.zbinfinn.common.FunctionKind
import com.zbinfinn.ir.LoweringContext

class FunctionResolver(
    private val globals: GlobalFunctionTable
) {
    enum class Context {
        Normal,
        PlayerSelection,
        EntitySelection,
        Selector,
    }

    fun resolve(
        name: String,
        program: Ast.Program,
        context: Context = Context.Normal,
    ): FunctionSymbol {
        val module = program.module.path

        // Function present in module
        globals.functionsInModule(module)[name]?.let { return choose(name, it, context) }

        // Function imported
        val importScope = ImportScope(program)
        val qualified = importScope.resolve(name)
        if (qualified != null) {
            val candidates = globals.resolveQualified(qualified)
                ?: error("Imported function '$qualified' not found.")
            return choose(name, candidates, context)
        }

        error("Unresolved function '$name'")
    }

    fun contextForSelection(selection: LoweringContext.SelectionType?): Context {
        return when (selection) {
            LoweringContext.SelectionType.Player -> Context.PlayerSelection
            LoweringContext.SelectionType.Entity -> Context.EntitySelection
            null -> Context.Normal
        }
    }

    fun resolveMember(typeQualifiedName: String, name: String): FunctionSymbol {
        return globals.resolveMember(typeQualifiedName, name)
            ?: error("Unresolved member function '$typeQualifiedName.$name'")
    }

    private fun choose(
        name: String,
        candidates: List<FunctionSymbol>,
        context: Context
    ): FunctionSymbol {
        val preferredKinds = when (context) {
            Context.Normal -> listOf(FunctionKind.Plain)
            Context.PlayerSelection -> listOf(FunctionKind.OnPlayerSelection, FunctionKind.Plain)
            Context.EntitySelection -> listOf(FunctionKind.OnEntitySelection, FunctionKind.Plain)
            Context.Selector -> listOf(FunctionKind.PlayerSelector, FunctionKind.EntitySelector)
        }

        for (kind in preferredKinds) {
            val match = candidates.singleOrNull { it.kind == kind }
            if (match != null) {
                return match
            }
        }

        error("Unresolved function '$name' for $context context")
    }
}
