package com.zbinfinn.ast

import com.zbinfinn.tokenizer.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParserSingletonTest {

    @Test
    fun `parses singleton with provider annotation and internal members`() {
        val code = """
            mod main;
            @PlayerEventProvider("Join")
            singleton PlayerJoinEvent {
                internal fn setCancelled(var this, cancelled: Boolean);
                internal fn isCancelled(val this): Boolean;
            }
        """.trimIndent()

        val singleton = Parser(Tokenizer(code).tokenize()).parseProgram().singletons.single()

        assertEquals("PlayerJoinEvent", singleton.name)
        assertEquals("PlayerEventProvider", singleton.annotations.single().name)
        assertEquals("setCancelled", singleton.functions[0].name)
        assertTrue(singleton.functions[0].internal)
        assertEquals(true, singleton.functions[0].parameters.first().mutable)
        assertEquals(false, singleton.functions[1].parameters.first().mutable)
    }

    @Test
    fun `parses Event annotation with singleton identifier`() {
        val code = """
            mod main;
            @Event(PlayerJoinEvent)
            fn join(var event: PlayerJoinEvent) {}
        """.trimIndent()

        val annotation = Parser(Tokenizer(code).tokenize()).parseProgram().functions.single().annotations.single()

        assertEquals("Event", annotation.name)
        assertEquals("PlayerJoinEvent", (annotation.args.single() as Ast.IdentifierExpr).name)
    }
}
