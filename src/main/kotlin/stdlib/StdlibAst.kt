package com.zbinfinn.stdlib

import com.zbinfinn.ast.Ast
import com.zbinfinn.ir.Ir

object StdlibAst {
    val functions: List<StdFunction> = listOf(
        defaultPlayer(), sendMessage()
    )

    data class StdFunction(
        val importPath: String,
        val decl: Ast.FunctionDecl
    )

    private fun sendMessage(): StdFunction {
        return StdFunction(
            "std.player.sendMessage",
            Ast.FunctionDecl(
                name = "sendMessage",
                annotations = listOf(
                    Ast.Annotation("OnPlayerSelection", emptyList()),
                ),
                body = Ast.Block(
                    statements = listOf(
                        Ast.InlineIr(
                            Ir.PlayerAction(
                                actionName = "SendMessage",
                                args = listOf(
                                    Ir.StringValue("Hi!") // TODO
                                ),
                                tags = listOf(
                                    Ir.Tag(26, "Alignment Mode", "Regular"),
                                    Ir.Tag(25, "Text Value Merging", "Add spaces"),
                                    Ir.Tag(26, "Inherit Styles", "True")
                                ),
                                target = null
                            )
                        )
                    )
                )
            )
        )
    }

    private fun defaultPlayer(): StdFunction {
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
                )
            )
        )
    }
}