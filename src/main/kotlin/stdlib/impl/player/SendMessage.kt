package com.zbinfinn.stdlib.impl.player

import com.zbinfinn.stdlib.StdFunctionProvider
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
            any("text")
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
