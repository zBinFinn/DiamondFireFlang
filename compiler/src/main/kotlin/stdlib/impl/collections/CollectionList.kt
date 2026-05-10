package com.zbinfinn.stdlib.impl.collections

import com.zbinfinn.ir.Ir
import com.zbinfinn.ir.SetVars
import com.zbinfinn.stdlib.InternalStdlibProvider
import com.zbinfinn.stdlib.InternalStdlibRegistry

object CollectionList : InternalStdlibProvider {
    private const val OWNER = "std.collections.List"

    override fun register(builder: InternalStdlibRegistry.Builder) {
        builder.member(OWNER, "add", ::add)
        builder.member(OWNER, "get", ::get)
        builder.member(OWNER, "set", ::set)
        builder.member(OWNER, "remove", ::remove)
        builder.member(OWNER, "size", ::size)
    }

    private fun new(args: List<Ir.Value>): List<Ir.Instr> {
        val (ret) = args;
        return listOf(
            SetVars.createList((ret as Ir.Variable).name, emptyList())
        )
    }

    private fun add(args: List<Ir.Value>): List<Ir.Instr> {
        val (self, value) = args
        return listOf(SetVars.appendValue(self, value))
    }

    private fun get(args: List<Ir.Value>): List<Ir.Instr> {
        val (self, index, ret) = args
        val translatedIndex = translatedIndex(index)
        return listOf(
            translatedIndex.instr,
            SetVars.getListValue((ret as Ir.Variable).name, self, translatedIndex.value)
        )
    }

    private fun set(args: List<Ir.Value>): List<Ir.Instr> {
        val (self, index, value) = args
        val translatedIndex = translatedIndex(index)
        return listOf(
            translatedIndex.instr,
            SetVars.setListValue(self, translatedIndex.value, value)
        )
    }

    private fun remove(args: List<Ir.Value>): List<Ir.Instr> {
        val (self, index) = args
        val translatedIndex = translatedIndex(index)
        return listOf(
            translatedIndex.instr,
            SetVars.removeListIndex(self, translatedIndex.value)
        )
    }

    private fun size(args: List<Ir.Value>): List<Ir.Instr> {
        val (self, ret) = args
        return listOf(SetVars.getListLength((ret as Ir.Variable).name, self))
    }

    private data class TranslatedIndex(
        val value: Ir.Variable,
        val instr: Ir.Instr,
    )

    private fun translatedIndex(index: Ir.Value): TranslatedIndex {
        val temp = Ir.Variable("\$translatedIndex")
        return TranslatedIndex(
            value = temp,
            instr = Ir.SetVariableAction(
                actionName = "+",
                args = listOf(temp, index, Ir.NumberValue(1)),
                tags = emptyList(),
            )
        )
    }
}
