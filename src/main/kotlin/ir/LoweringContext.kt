package com.zbinfinn.ir

class LoweringContext {
    val selectionStack = ArrayDeque<SelectionType>()

    fun currentSelection(): SelectionType? = selectionStack.firstOrNull()

    enum class SelectionType {
        Player,
        Entity
    }
}