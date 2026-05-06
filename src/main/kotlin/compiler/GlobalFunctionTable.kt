package com.zbinfinn.compiler

import com.zbinfinn.ast.Ast
import com.zbinfinn.common.FunctionKind
import com.zbinfinn.common.functionKind
import kotlin.collections.set

data class FunctionSymbol(
    val qualifiedName: String,
    val simpleName: String,
    val modulePath: String,
    val kind: FunctionKind,
    val decl: Ast.FunctionDecl,
    val program: Ast.Program?,
    val memberOf: String? = null,
    val isStaticMember: Boolean = false,
)

class GlobalFunctionTable {
    private val byQualified = mutableMapOf<String, MutableList<FunctionSymbol>>()
    private val byModule = mutableMapOf<String, MutableMap<String, MutableList<FunctionSymbol>>>()
    private val byMember = mutableMapOf<String, MutableMap<String, FunctionSymbol>>()

    fun register(program: Ast.Program) {
        val modulePath = program.module.path
        val moduleMap = byModule.getOrPut(modulePath) { mutableMapOf() }

        for (fn in program.functions) {
            val kind = functionKind(fn)
            val existing = moduleMap.getOrPut(fn.name) { mutableListOf() }
            validateCanRegister(modulePath, fn.name, kind, existing)

            val qualifiedName = diamondFireName(modulePath, fn.name, kind)
            val symbol = FunctionSymbol(
                qualifiedName = qualifiedName,
                simpleName = fn.name,
                modulePath = modulePath,
                kind = kind,
                decl = fn,
                program = program
            )

            existing += symbol
            byQualified.getOrPut("$modulePath.${fn.name}") { mutableListOf() } += symbol
        }

        for (impl in program.impls) {
            registerImpl(modulePath, impl, program)
        }

        for (singleton in program.singletons) {
            registerSingleton(modulePath, singleton, program)
        }
    }

    fun registerFunction(
        modulePath: String,
        function: Ast.FunctionDecl,
    ) {
        val moduleMap = byModule.getOrPut(modulePath) {
            mutableMapOf()
        }

        val kind = functionKind(function)
        val existing = moduleMap.getOrPut(function.name) { mutableListOf() }
        validateCanRegister(modulePath, function.name, kind, existing)
        val qualifiedName = diamondFireName(modulePath, function.name, kind)

        val symbol = FunctionSymbol(
            qualifiedName = qualifiedName,
            simpleName = function.name,
            modulePath = modulePath,
            kind = kind,
            decl = function,
            program = null // stdlib
        )

        existing += symbol
        byQualified.getOrPut("$modulePath.${function.name}") { mutableListOf() } += symbol
    }

    fun functionsInModule(module: String): Map<String, List<FunctionSymbol>> =
        byModule[module] ?: emptyMap()

    fun resolveQualified(name: String): List<FunctionSymbol>? =
        byQualified[name]

    fun resolveInModule(module: String, name: String, kind: FunctionKind): FunctionSymbol? =
        byModule[module]?.get(name)?.singleOrNull { it.kind == kind }

    fun resolveMember(typeQualifiedName: String, name: String): FunctionSymbol? =
        byMember[typeQualifiedName]?.get(name)

    fun allFunctions(): Set<FunctionSymbol> =
        byQualified.values.flatten().plus(byMember.values.flatMap { it.values }).toSet()

    private fun registerImpl(modulePath: String, impl: Ast.ImplDecl, program: Ast.Program) {
        val typeQualifiedName = "$modulePath.${impl.typeName}"
        val memberMap = byMember.getOrPut(typeQualifiedName) { mutableMapOf() }

        for (fn in impl.functions) {
            if (memberMap.containsKey(fn.name)) {
                error("Duplicate member function '${fn.name}' for '$typeQualifiedName'")
            }
            if (functionKind(fn) != FunctionKind.Plain) {
                error("Member function '${fn.name}' may not use function role annotations")
            }

            val qualifiedName = "$typeQualifiedName.${fn.name}"
            val symbol = FunctionSymbol(
                qualifiedName = qualifiedName,
                simpleName = fn.name,
                modulePath = modulePath,
                kind = FunctionKind.Plain,
                decl = fn,
                program = program,
                memberOf = typeQualifiedName,
                isStaticMember = fn.parameters.firstOrNull()?.name != "this"
            )

            memberMap[fn.name] = symbol
        }
    }

    private fun registerSingleton(modulePath: String, singleton: Ast.SingletonDecl, program: Ast.Program) {
        val typeQualifiedName = "$modulePath.${singleton.name}"
        val memberMap = byMember.getOrPut(typeQualifiedName) { mutableMapOf() }

        for (fn in singleton.functions) {
            if (memberMap.containsKey(fn.name)) {
                error("Duplicate member function '${fn.name}' for '$typeQualifiedName'")
            }
            if (functionKind(fn) != FunctionKind.Plain) {
                error("Singleton member function '${fn.name}' may not use function role annotations")
            }

            val qualifiedName = "$typeQualifiedName.${fn.name}"
            val symbol = FunctionSymbol(
                qualifiedName = qualifiedName,
                simpleName = fn.name,
                modulePath = modulePath,
                kind = FunctionKind.Plain,
                decl = fn,
                program = program,
                memberOf = typeQualifiedName,
                isStaticMember = fn.parameters.firstOrNull()?.name != "this"
            )

            memberMap[fn.name] = symbol
        }
    }

    private fun validateCanRegister(
        modulePath: String,
        name: String,
        kind: FunctionKind,
        existing: List<FunctionSymbol>
    ) {
        if (existing.any { it.kind == kind }) {
            error("Duplicate $kind function '$name' in module '$modulePath'")
        }

        val hasPlain = existing.any { it.kind == FunctionKind.Plain }
        val hasSelectionHandler = existing.any {
            it.kind == FunctionKind.OnPlayerSelection || it.kind == FunctionKind.OnEntitySelection
        }
        val isSelectionHandler = kind == FunctionKind.OnPlayerSelection || kind == FunctionKind.OnEntitySelection

        if ((kind == FunctionKind.Plain && hasSelectionHandler) || (isSelectionHandler && hasPlain)) {
            error("Function '$name' in module '$modulePath' cannot mix an unannotated function with selection handlers")
        }
    }

    private fun diamondFireName(modulePath: String, name: String, kind: FunctionKind): String {
        val base = "$modulePath.$name"
        return kind.suffix?.let { "$base\$$it" } ?: base
    }
}
