package com.zbinfinn.stdlib

import kotlin.test.Test
import kotlin.test.assertTrue

class StdlibResourceTest {

    @Test
    fun `stdlib declarations load from resources`() {
        val programs = StdlibAst.programs

        assertTrue(programs.any { it.module.path == "std.player" && it.singletons.any { singleton -> singleton.name == "Player" && singleton.functions.any { fn -> fn.name == "sendMessage" && fn.internal } } })
        assertTrue(programs.any { it.module.path == "std.events" && it.singletons.any { singleton -> singleton.name == "PlayerJoinEvent" } })
    }
}
