package com.zbinfinn.compiler

import com.zbinfinn.ast.Ast
import kotlin.collections.set

data class FunctionSymbol(
    val qualifiedName: String,
    val simpleName: String,
    val modulePath: String,
    val decl: Ast.FunctionDecl,
    val program: Ast.Program?,
)

class GlobalFunctionTable {
    private val byQualified = mutableMapOf<String, FunctionSymbol>()
    private val byModule = mutableMapOf<String, MutableMap<String, FunctionSymbol>>()

    fun register(program: Ast.Program) {
        val modulePath = program.module.path
        val moduleMap = byModule.getOrPut(modulePath) { mutableMapOf() }

        for (fn in program.functions) {
            if (moduleMap.containsKey(fn.name)) {
                error(
                    "Duplicate function '${fn.name}' in module '$modulePath'"
                )
            }

            val qualifiedName = "$modulePath.${fn.name}"
            val symbol = FunctionSymbol(
                qualifiedName = qualifiedName,
                simpleName = fn.name,
                modulePath = modulePath,
                decl = fn,
                program = program
            )

            moduleMap[fn.name] = symbol
            byQualified[qualifiedName] = symbol
        }
    }

    fun registerFunction(
        modulePath: String,
        function: Ast.FunctionDecl,
        qualifiedName: String,
    ) {
        val moduleMap = byModule.getOrPut(modulePath) {
            mutableMapOf()
        }

        if (moduleMap.containsKey(function.name)) {
            error("Duplicate function '${function.name}' in module '$modulePath'")
        }

        val symbol = FunctionSymbol(
            qualifiedName = qualifiedName,
            simpleName = function.name,
            modulePath = modulePath,
            decl = function,
            program = null // stdlib
        )

        moduleMap[function.name] = symbol
        byQualified[qualifiedName] = symbol
    }

    fun functionsInModule(module: String): Map<String, FunctionSymbol> =
        byModule[module] ?: emptyMap()

    fun resolveQualified(name: String): FunctionSymbol? =
        byQualified[name]

    fun allFunctions(): Set<FunctionSymbol> =
        byQualified.values.toSet()
}