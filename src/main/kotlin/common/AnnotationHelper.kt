package com.zbinfinn.common

import com.zbinfinn.ast.Ast
import com.zbinfinn.ir.LoweringContext

sealed interface EventAnnotation {
    val eventName: String
}

data class PlayerEventAnnotation(
    override val eventName: String,
) : EventAnnotation

data class EntityEventAnnotation(
    override val eventName: String,
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
    "PlayerEvent" to FunctionKind.PlayerEvent,
    "EntityEvent" to FunctionKind.EntityEvent,
)

fun functionKind(function: Ast.FunctionDecl): FunctionKind {
    val kinds = function.annotations.mapNotNull { functionKindAnnotations[it.name] }
    if (kinds.size > 1) {
        error("Function '${function.name}' may only have one function role annotation")
    }

    return kinds.singleOrNull() ?: FunctionKind.Plain
}

fun parseEventAnnotation(
    annotations: List<Ast.Annotation>
): EventAnnotation? {
    var result: EventAnnotation? = null

    for (annotation in annotations) {
        when (annotation.name) {
            "PlayerEvent" -> {
                if (result != null) {
                    error("Function may only have one event annotation")
                }
                if (annotation.args.size != 1) {
                    error("@PlayerEvent requires exactly one argument")
                }

                val arg = annotation.args.first()
                if (arg !is Ast.StringExpr) {
                    error("@PlayerEvent argument must be a string")
                }

                result = PlayerEventAnnotation(arg.value)
            }

            "EntityEvent" -> {
                if (result != null) {
                    error("Function may only have one event annotation")
                }
                if (annotation.args.size != 1) {
                    error("@EntityEvent requires exactly one argument")
                }

                val arg = annotation.args.first()
                if (arg !is Ast.StringExpr) {
                    error("@EntityEvent argument must be a string")
                }

                result = EntityEventAnnotation(arg.value)
            }
        }
    }
    return result
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
