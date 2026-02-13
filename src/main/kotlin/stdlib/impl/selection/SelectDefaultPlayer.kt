package com.zbinfinn.stdlib.impl.selection

import com.zbinfinn.ast.Ast
import com.zbinfinn.ir.Ir
import com.zbinfinn.stdlib.StdFunctionProvider
import com.zbinfinn.stdlib.StdlibAst
import com.zbinfinn.stdlib.dsl.function
import com.zbinfinn.stdlib.impl.StdModules

object SelectDefaultPlayer : StdFunctionProvider {
    override fun invoke(): StdlibAst.StdFunction = function(
        StdModules.PLAYER_SELECTIONS,
        "defaultPlayer"
    ) {
        annotations {
            playerSelector()
        }
        body {
            selectObject("EventTarget") {
                tag(26, "Event Target", "Default")
            }
        }
    }

    /*
    function(
       mod = "std.selection.player",
       name = "defaultPlayer"
    ) {
       annotations {
          playerSelector()
          // or onPlayerSelection() or onEntitySelection() etc... premade functions to make annotations easier
       }
       params {
          // In this case nothing but like you could do
          // string("argName") or num("argName") or dict("TypeName", "argName")
       }
       body {
          selectObject(
             name = "EventTarget",
             sub = null,
             args = emptyList(),
          ) {
             tag(slot = 26, name = "Event Target", option = "Default")
          }
       }
    }
     */

}