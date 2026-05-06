package com.zbinfinn.compiler

import com.zbinfinn.ast.Ast

sealed interface TypeSymbol {
    val qualifiedName: String
    val simpleName: String
    val modulePath: String
}

data class DictSymbol(
    override val qualifiedName: String,
    override val simpleName: String,
    override val modulePath: String,
    val decl: Ast.DictDecl
) : TypeSymbol

data class SingletonSymbol(
    override val qualifiedName: String,
    override val simpleName: String,
    override val modulePath: String,
    val decl: Ast.SingletonDecl
) : TypeSymbol

class GlobalTypeTable {
    private val byQualified = mutableMapOf<String, TypeSymbol>()
    private val byModule = mutableMapOf<String, MutableMap<String, TypeSymbol>>()

    fun register(program: Ast.Program) {
        val module = program.module.path
        val moduleMap = byModule.getOrPut(module) { mutableMapOf() }

        for (dict in program.dicts) {
            if (moduleMap.containsKey(dict.name)) {
                error("Duplicate type '${dict.name}' in module '$module'")
            }

            val qualified = "$module.${dict.name}"
            val symbol = DictSymbol(qualified, dict.name, module, dict)

            moduleMap[dict.name] = symbol
            byQualified[qualified] = symbol
        }

        for (singleton in program.singletons) {
            if (moduleMap.containsKey(singleton.name)) {
                error("Duplicate type '${singleton.name}' in module '$module'")
            }

            val qualified = "$module.${singleton.name}"
            val symbol = SingletonSymbol(qualified, singleton.name, module, singleton)

            moduleMap[singleton.name] = symbol
            byQualified[qualified] = symbol
        }
    }

    fun resolve(simpleName: String, program: Ast.Program): TypeSymbol? {
        val module = program.module.path

        byModule[module]?.get(simpleName)?.let { return it }

        val importScope = ImportScope(program)
        val qualified = importScope.resolve(simpleName)
        if (qualified != null) {
            return byQualified[qualified]
        }

        return null
    }

    fun resolveQualified(name: String): TypeSymbol? =
        byQualified[name]
}
