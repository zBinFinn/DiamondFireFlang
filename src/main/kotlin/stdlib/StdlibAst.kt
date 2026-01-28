package com.zbinfinn.stdlib

import com.zbinfinn.ast.Ast
import com.zbinfinn.stdlib.impl.SelectDefaultPlayer
import com.zbinfinn.stdlib.impl.SendMessage

object StdlibAst {
    val functions: List<StdFunction> = listOf(
        SelectDefaultPlayer, SendMessage
    ).map { it.invoke() }

    data class StdFunction(
        val importPath: String,
        val decl: Ast.FunctionDecl
    )
}