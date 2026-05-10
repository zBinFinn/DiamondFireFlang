package com.zbinfinn.nbt

import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateNbtGeneratorTest {

    @Test
    fun `parses iv NOT prefix and sets inverted`() {
        val source = """
            pe "Join"
            iv NOT "=" args(n"0", n"1")
            {
            }
            end
        """.trimIndent()

        val raw = TemplateNbtGenerator(source).generateRaw().single()
        val blocks = raw.getAsJsonArray("blocks")

        val ifVar = blocks[1].asJsonObject
        assertEquals("block", ifVar["id"].asString)
        assertEquals("if_var", ifVar["block"].asString)
        assertEquals("=", ifVar["action"].asString)
        assertEquals("NOT", ifVar["attribute"].asString)

        val open = blocks[2].asJsonObject
        assertEquals("bracket", open["id"].asString)
        assertEquals("open", open["direct"].asString)

        val close = blocks[3].asJsonObject
        assertEquals("bracket", close["id"].asString)
        assertEquals("close", close["direct"].asString)
    }
}
