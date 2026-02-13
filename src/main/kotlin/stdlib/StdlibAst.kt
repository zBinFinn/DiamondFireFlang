package com.zbinfinn.stdlib

import com.zbinfinn.ast.Ast
import com.zbinfinn.stdlib.impl.selection.SelectDefaultPlayer
import com.zbinfinn.stdlib.impl.player.SendMessage
import com.zbinfinn.stdlib.impl.player.ShowActionBarText

object StdlibAst {
    val functions: List<StdFunction> = listOf(
        // Player
        SendMessage, ShowActionBarText,

        // Player Selections
        SelectDefaultPlayer
    ).map { it.invoke() }

    data class StdFunction(
        val importPath: String,
        val decl: Ast.FunctionDecl
    )
}