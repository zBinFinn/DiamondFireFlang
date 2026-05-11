package com.zbinfinn.ir

class LoweringContext {
    private var tempVariableIndex = 0
    private var selectionFrameIndex = 0
    val selectionStack = ArrayDeque<SelectionType>()
    private val selectionFrames = ArrayDeque<SelectionFrame>()
    var functionEntrySelection: SelectionRestore? = null

    fun currentSelection(): SelectionType? = selectionStack.lastOrNull()

    fun beginSelectionFrame(type: SelectionType): SelectionFrame {
        val index = selectionFrameIndex++
        val frame = SelectionFrame(
            index = index,
            type = type,
            listVarName = "\$selection_$index",
            uuidVarName = "\$selection_uuid_$index",
        )
        selectionFrames.addLast(frame)
        return frame
    }

    fun endSelectionFrame() {
        selectionFrames.removeLast()
    }

    fun activeSelectionFrame(): SelectionFrame? = selectionFrames.lastOrNull()

    fun newTempVariableName(): String {
        return "\$temp_${tempVariableIndex++}"
    }

    fun resetTempVariableIndex() {
        tempVariableIndex = 0
        selectionFrameIndex = 0
        selectionFrames.clear()
        functionEntrySelection = null
    }

    data class SelectionFrame(
        val index: Int,
        val type: SelectionType,
        val listVarName: String,
        val uuidVarName: String,
    )

    data class SelectionRestore(
        val type: SelectionType,
        val value: Ir.Value,
    )

    enum class SelectionType {
        Player,
        Entity
    }
}
