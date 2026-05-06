package com.zbinfinn.stdlib.impl.selection

import com.zbinfinn.ir.Ir
import com.zbinfinn.stdlib.InternalStdlibProvider
import com.zbinfinn.stdlib.InternalStdlibRegistry
import com.zbinfinn.stdlib.impl.StdModules

object SelectDefaultPlayer : InternalStdlibProvider {
    fun body(args: List<Ir.Value>): List<Ir.Instr> {
        return listOf(
            Ir.SelectObject(
                actionName = "EventTarget",
                subAction = null,
                args = emptyList(),
                tags = listOf(Ir.Tag(26, "Event Target", "Default"))
            )
        )
    }

    override fun register(builder: InternalStdlibRegistry.Builder) {
        builder.playerSelector(StdModules.PLAYER_SELECTIONS, "defaultPlayer", ::body)
    }
}
