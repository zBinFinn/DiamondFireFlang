package com.zbinfinn.stdlib.impl.events

import com.zbinfinn.ir.Ir
import com.zbinfinn.stdlib.InternalStdlibProvider
import com.zbinfinn.stdlib.InternalStdlibRegistry

object PlayerJoinEvent : InternalStdlibProvider {
    private const val TYPE = "std.events.PlayerJoinEvent"

    override fun register(builder: InternalStdlibRegistry.Builder) {
        builder.member(TYPE, "setCancelled", ::setCancelled)
    }

    fun setCancelled(args: List<Ir.Value>): List<Ir.Instr> {
        val cancelled = args[1]
        return listOf(
            Ir.IfVarAction(
                actionName = "=",
                args = listOf(Ir.NumberValue(1), cancelled),
            ),
            Ir.OpenBracket,
            Ir.GameAction("CancelEvent"),
            Ir.CloseBracket,
            Ir.Else,
            Ir.OpenBracket,
            Ir.GameAction("UncancelEvent"),
            Ir.CloseBracket,
        )
    }
}
