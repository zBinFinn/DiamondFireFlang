package com.zbinfinn.ir

import com.zbinfinn.common.VariableScope
import com.zbinfinn.ir.Ir.SetVariableAction
import com.zbinfinn.ir.Ir.StringValue
import com.zbinfinn.ir.Ir.Value
import com.zbinfinn.ir.Ir.Variable

object SetVars {
    fun createList(varName: String, elements: List<Value>) = SetVariableAction(
        actionName = "CreateList",
        args = arrayListOf<Value>(
            Variable(varName, VariableScope.LINE),
        ).let {
            it.addAll(elements)
            return@let it
        },
        tags = emptyList()
    )

    fun createDict(varName: String, keysVarName: String, valuesVarName: String) = SetVariableAction(
        actionName = "CreateDict",
        args = listOf(
            Variable(varName, VariableScope.LINE),
            Variable(keysVarName, VariableScope.LINE),
            Variable(valuesVarName, VariableScope.LINE),
        ),
        tags = emptyList()
    )

    fun getDictValue(varName: String, dictVarName: String, key: String) = SetVariableAction(
        actionName = "GetDictValue",
        args = listOf(
            Variable(varName, VariableScope.LINE),
            Variable(dictVarName, VariableScope.LINE),
            StringValue(key)
        ),
        tags = emptyList()
    )

    fun setDictValue(dictVarName: String, key: String, value: Ir.Value) = SetVariableAction(
        actionName = "SetDictValue",
        args = listOf(
            Variable(dictVarName, VariableScope.LINE),
            StringValue(key),
            value
        ),
        tags = emptyList()
    )
}