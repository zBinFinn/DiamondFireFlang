package com.zbinfinn.nbt

import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateNameTest {

    @Test
    fun `template name uses function identifier`() {
        val source = """
            fn "main.join"
            end
        """.trimIndent()

        val generated = TemplateNbtGenerator(source).generate().single()
        assertEquals("Flang - main.join", generated["name"].asString)
    }
}

