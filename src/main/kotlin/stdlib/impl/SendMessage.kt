package com.zbinfinn.stdlib.impl

import com.zbinfinn.ast.Ast
import com.zbinfinn.ir.Ir
import com.zbinfinn.stdlib.StdFunctionProvider
import com.zbinfinn.stdlib.StdlibAst.StdFunction

object SendMessage : StdFunctionProvider {
    override fun invoke(): StdFunction {
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
                                    Ir.Variable("text")
                                ),
                                tags = listOf(
                                    Ir.Tag(26, "Alignment Mode", "Regular"),
                                    Ir.Tag(25, "Text Value Merging", "Add spaces"),
                                    Ir.Tag(24, "Inherit Styles", "True")
                                ),
                                target = null
                            )
                        )
                    )
                ),
                parameters = listOf(
                    Ast.Parameter(
                        name = "text",
                        type = Ast.Type("String"),
                        mutable = false
                    )
                )
            )
        )
    }
}