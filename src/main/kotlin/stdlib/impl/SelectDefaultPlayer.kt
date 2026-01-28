package com.zbinfinn.stdlib.impl

import com.zbinfinn.ast.Ast
import com.zbinfinn.ir.Ir
import com.zbinfinn.stdlib.StdFunctionProvider
import com.zbinfinn.stdlib.StdlibAst
import com.zbinfinn.stdlib.StdlibAst.StdFunction

object SelectDefaultPlayer : StdFunctionProvider {
    override fun invoke(): StdFunction {
        return StdFunction(
            "std.selection.player.defaultPlayer",
            Ast.FunctionDecl(
                name = "defaultPlayer",
                annotations = listOf(
                    Ast.Annotation("PlayerSelector", emptyList())
                ),
                body = Ast.Block(
                    statements = listOf(
                        Ast.InlineIr(
                            Ir.SelectObject(
                                actionName = "EventTarget",
                                subAction = null,
                                args = emptyList(),
                                tags = listOf(
                                    Ir.Tag(26, "Event Target", "Default")
                                )
                            )
                        )
                    )
                ),
                parameters = emptyList()
            )
        )
    }

}