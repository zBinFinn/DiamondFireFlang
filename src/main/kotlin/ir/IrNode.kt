package com.zbinfinn.ir

import com.zbinfinn.common.VariableScope

object Ir {
    sealed interface IrNode

    data class Program(
        val entryPoints: List<EntryPoint>,
        val functions: List<Function>
    ) : IrNode

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
    ) : IrNode

    sealed interface Instr

    data class CallFunction(
        val name: String
    ) : Instr

    data class SetVariableAction(
        val actionName: String,
        val args: List<Value>
    ) : Instr

    data class PlayerAction(
        val actionName: String,
        val args: List<Value>,
        val target: Target
    ) : Instr

    sealed interface Value

    data class StringValue(val value: String): Value
    data class StyledText(val value: String): Value
    data class NumberValue(val value: Number): Value
    data class Variable(val name: String, val scope: VariableScope = VariableScope.LINE): Value

    enum class Target {
        Selection,
        Default
    }
}