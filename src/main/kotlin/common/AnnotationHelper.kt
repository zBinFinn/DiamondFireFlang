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

// TODO: enforce max. 1 event annotation on a function
fun parseEventAnnotation(
    annotations: List<Ast.Annotation>
): EventAnnotation? {
    var result: EventAnnotation? = null

    for (annotation in annotations) {
        when (annotation.name) {
            "PlayerEvent" -> {
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
    return function.annotations.any {
        it.name == "OnPlayerSelection"
    }
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