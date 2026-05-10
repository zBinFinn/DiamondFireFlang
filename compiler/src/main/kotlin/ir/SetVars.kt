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

    fun appendValue(list: Value, value: Value) = SetVariableAction(
        actionName = "AppendValue",
        args = listOf(list, value),
        tags = emptyList()
    )

    fun getListValue(varName: String, list: Value, index: Value) = SetVariableAction(
        actionName = "GetListValue",
        args = listOf(
            Variable(varName, VariableScope.LINE),
            list,
            index,
        ),
        tags = emptyList()
    )

    fun setListValue(list: Value, index: Value, value: Value) = SetVariableAction(
        actionName = "SetListValue",
        args = listOf(list, index, value),
        tags = emptyList()
    )

    fun removeListIndex(list: Value, index: Value) = SetVariableAction(
        actionName = "RemoveListIndex",
        args = listOf(list, index),
        tags = emptyList()
    )

    fun getListLength(varName: String, list: Value) = SetVariableAction(
        actionName = "GetListLength",
        args = listOf(Variable(varName, VariableScope.LINE), list),
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

    fun createEmptyDict(varName: String) = SetVariableAction(
        actionName = "CreateDict",
        args = listOf(
            Variable(varName, VariableScope.LINE),
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

    fun getDictValue(varName: String, dict: Value, key: Value) = SetVariableAction(
        actionName = "GetDictValue",
        args = listOf(
            Variable(varName, VariableScope.LINE),
            dict,
            key
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

    fun setDictValue(dict: Value, key: Value, value: Ir.Value) = SetVariableAction(
        actionName = "SetDictValue",
        args = listOf(
            dict,
            key,
            value
        ),
        tags = emptyList()
    )

    fun removeDictEntry(dict: Value, key: Value) = SetVariableAction(
        actionName = "RemoveDictEntry",
        args = listOf(dict, key),
        tags = emptyList()
    )

    fun getDictSize(varName: String, dict: Value) = SetVariableAction(
        actionName = "GetDictSize",
        args = listOf(Variable(varName, VariableScope.LINE), dict),
        tags = emptyList()
    )
}
