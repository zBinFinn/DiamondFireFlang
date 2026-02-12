package com.zbinfinn.ir

class LoweringContext {
    private var tempVariableIndex = 0
    val selectionStack = ArrayDeque<SelectionType>()

    fun currentSelection(): SelectionType? = selectionStack.firstOrNull()

    fun newTempVariableName(): String {
        return "$\$temp_${tempVariableIndex++}"
    }

    fun resetTempVariableIndex() {
        tempVariableIndex = 0
    }

    enum class SelectionType {
        Player,
        Entity
    }
}