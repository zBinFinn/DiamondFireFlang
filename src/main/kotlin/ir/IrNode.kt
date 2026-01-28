package com.zbinfinn.ir

import com.zbinfinn.common.VariableScope

object Ir {
    sealed interface Node

    data class Program(
        val entryPoints: List<EntryPoint>,
        val functions: List<Function>
    ) : Node

    sealed interface EntryPoint

    data class PlayerEvent(
        val eventName: String,
        val body: List<Instr>
    ) : EntryPoint

    data class EntityEvent(
        val eventName: String,
        val body: List<Instr>
    ) : EntryPoint

    data class Function(
        val name: String,
        val body: List<Instr>
    ) : Node

    sealed interface Instr

    data class Tag(
        val slot: Int,
        val name: String,
        val selectedOption: String
    )

    sealed class SimpleAction(
        val blockIrName: String, // for example "pa"
    ) : Instr {
        abstract val actionName: String
        open val subAction: String? = null
        open val args: List<Value> = emptyList()
        open val tags: List<Tag> = emptyList()
        open val target: Target? = null
    }

    data class InlineIr(
        val ir: String
    ) : Instr

    data class SelectObject(
        override val actionName: String,
        override val subAction: String?,
        override val args: List<Value>,
        override val tags: List<Tag>
    ) : SimpleAction("so")

    data class CallFunction(
        override val actionName: String,
    ) : SimpleAction("cf")

    data class SetVariableAction(
        override val actionName: String,
        override val args: List<Value>,
        override val tags: List<Tag>
    ) : SimpleAction("sv")

    data class PlayerAction(
        override val actionName: String,
        override val args: List<Value>,
        override val tags: List<Tag>,
        override val target: Target?
    ) : SimpleAction("pa")

    sealed interface Value

    data class StringValue(val value: String) : Value
    data class StyledText(val value: String) : Value
    data class NumberValue(val value: Number) : Value
    data class Variable(val name: String, val scope: VariableScope = VariableScope.LINE) : Value

    enum class Target {
        Selection,
        Default
    }
}