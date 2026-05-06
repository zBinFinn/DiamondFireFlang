package com.zbinfinn.common

import com.zbinfinn.ast.Ast
import com.zbinfinn.compiler.GlobalTypeTable
import com.zbinfinn.compiler.SingletonSymbol
import com.zbinfinn.ir.LoweringContext

sealed interface EventAnnotation {
    val eventName: String
    val singletonQualifiedName: String
}

data class PlayerEventAnnotation(
    override val eventName: String,
    override val singletonQualifiedName: String,
) : EventAnnotation

data class EntityEventAnnotation(
    override val eventName: String,
    override val singletonQualifiedName: String,
) : EventAnnotation

enum class FunctionKind(
    val suffix: String?
) {
    Plain(null),
    PlayerSelector("playerSelector"),
    EntitySelector("entitySelector"),
    OnPlayerSelection("onPlayerSelection"),
    OnEntitySelection("onEntitySelection"),
    PlayerEvent("playerEvent"),
    EntityEvent("entityEvent"),
}

private val functionKindAnnotations = mapOf(
    "PlayerSelector" to FunctionKind.PlayerSelector,
    "EntitySelector" to FunctionKind.EntitySelector,
    "OnPlayerSelection" to FunctionKind.OnPlayerSelection,
    "OnEntitySelection" to FunctionKind.OnEntitySelection,
)

fun functionKind(function: Ast.FunctionDecl): FunctionKind {
    val kinds = function.annotations.mapNotNull { functionKindAnnotations[it.name] }
    if (kinds.size > 1) {
        error("Function '${function.name}' may only have one function role annotation")
    }

    return kinds.singleOrNull() ?: FunctionKind.Plain
}

fun parseEventAnnotation(
    function: Ast.FunctionDecl,
    program: Ast.Program,
    typeTable: GlobalTypeTable,
): EventAnnotation? {
    val annotations = function.annotations
    var result: EventAnnotation? = null

    for (annotation in annotations) {
        when (annotation.name) {
            "PlayerEvent", "EntityEvent" -> {
                error("@${annotation.name} is no longer supported; use @Event(EventSingleton)")
            }

            "Event" -> {
                if (result != null) {
                    error("Function may only have one event annotation")
                }
                if (annotation.args.size != 1) {
                    error("@Event requires exactly one argument")
                }

                val arg = annotation.args.first()
                if (arg !is Ast.IdentifierExpr) {
                    error("@Event argument must be a singleton type identifier")
                }

                val symbol = typeTable.resolve(arg.name, program) as? SingletonSymbol
                    ?: error("@Event argument '${arg.name}' must resolve to a singleton type")
                val provider = parseEventProvider(symbol.decl)
                    ?: error("Singleton '${arg.name}' is not an event provider")

                result = when (provider) {
                    is EventProvider.Player -> PlayerEventAnnotation(provider.eventName, symbol.qualifiedName)
                    is EventProvider.Entity -> EntityEventAnnotation(provider.eventName, symbol.qualifiedName)
                }
            }
        }
    }
    return result
}

sealed interface EventProvider {
    val eventName: String

    data class Player(override val eventName: String) : EventProvider
    data class Entity(override val eventName: String) : EventProvider
}

fun parseEventProvider(singleton: Ast.SingletonDecl): EventProvider? {
    var result: EventProvider? = null
    for (annotation in singleton.annotations) {
        val provider = when (annotation.name) {
            "PlayerEventProvider" -> EventProvider.Player(parseProviderName(annotation))
            "EntityEventProvider" -> EventProvider.Entity(parseProviderName(annotation))
            else -> null
        } ?: continue

        if (result != null) {
            error("Singleton '${singleton.name}' may only have one event provider annotation")
        }
        result = provider
    }
    return result
}

private fun parseProviderName(annotation: Ast.Annotation): String {
    if (annotation.args.size != 1) {
        error("@${annotation.name} requires exactly one argument")
    }
    val arg = annotation.args.first()
    if (arg !is Ast.StringExpr) {
        error("@${annotation.name} argument must be a string")
    }
    return arg.value
}

fun requiresSelection(function: Ast.FunctionDecl): Boolean {
    return requiredSelectionType(function) != null
}

fun selectorType(fn: Ast.FunctionDecl): LoweringContext.SelectionType? {
    return when {
        fn.annotations.any { it.name == "PlayerSelector" } ->
            LoweringContext.SelectionType.Player
        fn.annotations.any { it.name == "EntitySelector" } ->
            LoweringContext.SelectionType.Entity
        else -> null
    }
}

fun requiredSelectionType(fn: Ast.FunctionDecl): LoweringContext.SelectionType? {
    return when {
        fn.annotations.any { it.name == "OnPlayerSelection" } ->
            LoweringContext.SelectionType.Player
        fn.annotations.any { it.name == "OnEntitySelection" } ->
            LoweringContext.SelectionType.Entity
        else -> null
    }
}
