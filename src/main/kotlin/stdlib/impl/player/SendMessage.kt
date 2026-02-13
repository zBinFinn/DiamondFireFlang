package com.zbinfinn.stdlib.impl.player

import com.zbinfinn.ast.Ast
import com.zbinfinn.ir.Ir
import com.zbinfinn.stdlib.StdFunctionProvider
import com.zbinfinn.stdlib.StdlibAst
import com.zbinfinn.stdlib.dsl.function
import com.zbinfinn.stdlib.impl.StdModules

object SendMessage : StdFunctionProvider {
    override fun invoke() = function(
        StdModules.PLAYER,
        "sendMessage",
    ) {
        annotations {
            onPlayerSelection()
        }

        params {
            string("text")
        }

        body {
            playerAction("SendMessage") {
                variable("text")
                tag(26, "Alignment Mode", "Regular")
                tag(25, "Text Value Merging", "Add spaces")
                tag(24, "Inherit Styles", "False")
            }
        }
    }
}