package com.zbinfinn.typecheck

import com.zbinfinn.ast.Ast

sealed interface Type {
    data object StringType : Type
    data object TextType : Type
    data object NumberType : Type
    data object BooleanType : Type
    data object AnyType : Type

    data class ListType(
        val element: Type,
    ) : Type

    data class DictionaryType(
        val value: Type,
    ) : Type

    data class TypeParameter(
        val name: kotlin.String,
    ) : Type

    data class Dict(
        val qualifiedName: kotlin.String,
        val decl: Ast.DictDecl,
    ) : Type

    data class Singleton(
        val qualifiedName: kotlin.String,
        val decl: Ast.SingletonDecl,
    ) : Type

    data class Enum(
        val qualifiedName: kotlin.String,
        val decl: Ast.EnumDecl,
    ) : Type

    /**
     * for error-recovery, so we can keep type-checking and report multiple problems.
     */
    data object Error : Type
}

internal fun isAssignable(from: Type, to: Type): Boolean {
    if (from == Type.Error || to == Type.Error) return true
    if (to == Type.AnyType) return true
    if (from is Type.ListType && to is Type.ListType) return isAssignable(from.element, to.element) && isAssignable(to.element, from.element)
    if (from is Type.DictionaryType && to is Type.DictionaryType) return isAssignable(from.value, to.value) && isAssignable(to.value, from.value)
    return from == to
}
