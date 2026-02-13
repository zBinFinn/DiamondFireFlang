package com.zbinfinn.stdlib.impl.player

import com.zbinfinn.stdlib.StdFunctionProvider
import com.zbinfinn.stdlib.dsl.function
import com.zbinfinn.stdlib.impl.StdModules

object ShowActionBarText : StdFunctionProvider {
    override fun invoke() = function(
        StdModules.PLAYER,
        "showActionBar"
    ) {
        annotations {
            onPlayerSelection()
        }

        params {
            string("text")
        }

        body {
            playerAction("ActionBar") {
                variable("text")
                tag(26, "Text Value Merging", "No spaces")
                tag(25, "Inherit Styles", "False")
            }
        }
    }
}