package com.zbinfinn.stdlib.impl.collections

import com.zbinfinn.ir.Ir
import com.zbinfinn.ir.SetVars
import com.zbinfinn.stdlib.InternalStdlibProvider
import com.zbinfinn.stdlib.InternalStdlibRegistry

object CollectionDictionary : InternalStdlibProvider {
    private const val OWNER = "std.collections.Dictionary"

    override fun register(builder: InternalStdlibRegistry.Builder) {
        builder.member(OWNER, "new", ::new)
        builder.member(OWNER, "set", ::set)
        builder.member(OWNER, "get", ::get)
        builder.member(OWNER, "remove", ::remove)
        builder.member(OWNER, "has", ::has)
        builder.member(OWNER, "size", ::size)
    }

    private fun new(args: List<Ir.Value>): List<Ir.Instr> {
        val (ret) = args
        return listOf(SetVars.createEmptyDict((ret as Ir.Variable).name))
    }

    private fun set(args: List<Ir.Value>): List<Ir.Instr> {
        val (self, key, value) = args
        return listOf(SetVars.setDictValue(self, key, value))
    }

    private fun get(args: List<Ir.Value>): List<Ir.Instr> {
        val (self, key, ret) = args
        return listOf(SetVars.getDictValue((ret as Ir.Variable).name, self, key))
    }

    private fun remove(args: List<Ir.Value>): List<Ir.Instr> {
        val (self, key) = args
        return listOf(SetVars.removeDictEntry(self, key))
    }

    private fun has(args: List<Ir.Value>): List<Ir.Instr> {
        val (self, key, ret) = args
        val returnVar = ret as Ir.Variable
        return listOf(
            Ir.SetVariableAction(
                actionName = "=",
                args = listOf(returnVar, Ir.NumberValue(0)),
                tags = emptyList()
            ),
            Ir.IfVarAction(
                actionName = "DictHasKey",
                args = listOf(self, key),
                negated = false
            ),
            Ir.OpenBracket,
            Ir.SetVariableAction(
                actionName = "=",
                args = listOf(returnVar, Ir.NumberValue(1)),
                tags = emptyList()
            ),
            Ir.CloseBracket,
        )
    }

    private fun size(args: List<Ir.Value>): List<Ir.Instr> {
        val (self, ret) = args
        return listOf(SetVars.getDictSize((ret as Ir.Variable).name, self))
    }
}
