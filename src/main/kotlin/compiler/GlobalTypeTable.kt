package com.zbinfinn.compiler

import com.zbinfinn.ast.Ast

data class DictSymbol(
    val qualifiedName: String,
    val simpleName: String,
    val modulePath: String,
    val decl: Ast.DictDecl
)

class GlobalTypeTable {
    private val byQualified = mutableMapOf<String, DictSymbol>()
    private val byModule = mutableMapOf<String, MutableMap<String, DictSymbol>>()

    fun register(program: Ast.Program) {
        val module = program.module.path
        val moduleMap = byModule.getOrPut(module) { mutableMapOf() }

        for (dict in program.dicts) {
            if (moduleMap.containsKey(dict.name)) {
                error("Duplicate dict '${dict.name}' in module '$module'")
            }

            val qualified = "$module.${dict.name}"
            val symbol = DictSymbol(
                qualified,
                dict.name,
                module,
                dict
            )

            moduleMap[dict.name] = symbol
            byQualified[qualified] = symbol
        }
    }

    fun resolve(simpleName: String, program: Ast.Program): DictSymbol? {
        val module = program.module.path

        byModule[module]?.get(simpleName)?.let { return it }

        val importScope = ImportScope(program)
        val qualified = importScope.resolve(simpleName)
        if (qualified != null) {
            return byQualified[qualified]
        }

        return null
    }
}