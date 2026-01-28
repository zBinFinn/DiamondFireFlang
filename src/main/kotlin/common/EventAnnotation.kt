package com.zbinfinn.common

import com.zbinfinn.ast.Ast

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
        if (annotation !is Ast.NamedAnnotation) continue

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