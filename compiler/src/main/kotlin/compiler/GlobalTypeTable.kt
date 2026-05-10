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

data class EnumSymbol(
    override val qualifiedName: String,
    override val simpleName: String,
    override val modulePath: String,
    val decl: Ast.EnumDecl
) : TypeSymbol

class GlobalTypeTable {
    private val byQualified = mutableMapOf<String, TypeSymbol>()
    private val byModule = mutableMapOf<String, MutableMap<String, TypeSymbol>>()

    fun register(program: Ast.Program) {
        val module = program.module.path
        val moduleMap = byModule.getOrPut(module) { mutableMapOf() }

        if (moduleMap.containsKey(GAME_VALUE_TARGET.simpleName)) {
            error("Duplicate type '${GAME_VALUE_TARGET.simpleName}' in module '$module'")
        }

        for (dict in program.dicts) {
            if (moduleMap.containsKey(dict.name)) {
                error("Duplicate type '${dict.name}' in module '$module'")
            }

            val qualified = "$module.${dict.name}"
            val symbol = DictSymbol(qualified, dict.name, module, dict)

            moduleMap[dict.name] = symbol
            byQualified[qualified] = symbol
        }

        for (enum in program.enums) {
            if (moduleMap.containsKey(enum.name)) {
                error("Duplicate type '${enum.name}' in module '$module'")
            }

            val qualified = "$module.${enum.name}"
            val symbol = EnumSymbol(qualified, enum.name, module, enum)

            moduleMap[enum.name] = symbol
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
        if (simpleName == "GameValueTarget") {
            return GAME_VALUE_TARGET
        }

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
        if (name == GAME_VALUE_TARGET.qualifiedName) GAME_VALUE_TARGET else byQualified[name]

    fun allTypes(): Set<TypeSymbol> =
        byQualified.values.plus(GAME_VALUE_TARGET).toSet()

    companion object {
        val GAME_VALUE_TARGET = EnumSymbol(
            qualifiedName = "std.builtin.GameValueTarget",
            simpleName = "GameValueTarget",
            modulePath = "std.builtin",
            decl = Ast.EnumDecl(
                "GameValueTarget",
                listOf(
                    Ast.EnumDecl.EnumCase("DEFAULT", "Default"),
                    Ast.EnumDecl.EnumCase("SELECTION", "Selection")
                )
            )
        )
    }
}
