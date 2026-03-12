package com.zbinfinn.typecheck

import com.zbinfinn.ast.Ast

sealed interface Type {
    data object StringType : Type
    data object NumberType : Type
    data object AnyType : Type

    data class Dict(
        val qualifiedName: kotlin.String,
        val decl: Ast.DictDecl,
    ) : Type

    /**
     * for error-recovery, so we can keep type-checking and report multiple problems.
     */
    data object Error : Type
}

internal fun isAssignable(from: Type, to: Type): Boolean {
    if (from == Type.Error || to == Type.Error) return true
    if (to == Type.AnyType) return true
    return from == to
}
