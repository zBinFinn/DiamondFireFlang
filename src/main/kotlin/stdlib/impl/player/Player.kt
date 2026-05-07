package com.zbinfinn.stdlib.impl.player

import com.zbinfinn.ir.Ir
import com.zbinfinn.stdlib.InternalStdlibProvider
import com.zbinfinn.stdlib.InternalStdlibRegistry
import com.zbinfinn.stdlib.impl.StdModules

object Player : InternalStdlibProvider {
    private const val TYPE = "${StdModules.PLAYER}.Player"

    private fun sendMessage(args: List<Ir.Value>): List<Ir.Instr> {
        val text = args[0]
        return listOf(
            Ir.PlayerAction(
                actionName = "SendMessage",
                args = listOf(text),
                tags = listOf(
                    Ir.Tag(26, "Alignment Mode", "Regular"),
                    Ir.Tag(25, "Text Value Merging", "Add spaces"),
                    Ir.Tag(24, "Inherit Styles", "False"),
                ),
                target = null
            )
        )
    }

    private fun showActionBar(args: List<Ir.Value>): List<Ir.Instr> {
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
        builder.onPlayerSelectionMember(TYPE, "sendMessage", ::sendMessage)
        builder.onPlayerSelectionMember(TYPE, "showActionBar", ::showActionBar)
    }
}
