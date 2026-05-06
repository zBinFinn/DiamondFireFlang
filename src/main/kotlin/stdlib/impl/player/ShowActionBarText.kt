package com.zbinfinn.stdlib.impl.player

import com.zbinfinn.ir.Ir
import com.zbinfinn.stdlib.InternalStdlibProvider
import com.zbinfinn.stdlib.InternalStdlibRegistry
import com.zbinfinn.stdlib.impl.StdModules

object ShowActionBarText : InternalStdlibProvider {
    fun body(args: List<Ir.Value>): List<Ir.Instr> {
        val text = args[0]
        return listOf(
            Ir.PlayerAction(
                actionName = "ActionBar",
                args = listOf(text),
                tags = listOf(
                    Ir.Tag(26, "Text Value Merging", "No spaces"),
                    Ir.Tag(25, "Inherit Styles", "False"),
                ),
                target = null
            )
        )
    }

    override fun register(builder: InternalStdlibRegistry.Builder) {
        builder.onPlayerSelection(StdModules.PLAYER, "showActionBar", ::body)
    }
}
