package com.zbinfinn.analysis

import com.zbinfinn.ast.ParseDiagnosticException
import com.zbinfinn.ast.Parser
import com.zbinfinn.tokenizer.TokenType
import com.zbinfinn.tokenizer.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlangAnalyzerTest {
    @Test
    fun `tokenizer records token ranges`() {
        val tokens = Tokenizer("mod main;").tokenize()
        val moduleToken = tokens.first { it.type == TokenType.MOD }
        val nameToken = tokens.first { it.type == TokenType.IDENT }

        assertEquals(0, moduleToken.position)
        assertEquals(3, moduleToken.endPosition)
        assertEquals(4, nameToken.position)
        assertEquals(8, nameToken.endPosition)
    }

    @Test
    fun `parser error has source range`() {
        val ex = kotlin.test.assertFailsWith<ParseDiagnosticException> {
            Parser(Tokenizer("mod main").tokenize()).parseProgram()
        }

        assertNotNull(ex.diagnostic.range)
        assertTrue(ex.diagnostic.message.contains("Expected ';'"))
    }

    @Test
    fun `valid document has no diagnostics`() {
        val uri = "file:///valid.fl"
        val result = FlangAnalyzer.analyze(
            workspaceRoots = emptyList(),
            openDocuments = mapOf(
                uri to """
                    mod main;
                    fn ok() {}
                """.trimIndent()
            )
        )

        assertTrue(result.diagnosticsByUri[uri].orEmpty().isEmpty())
    }

    @Test
    fun `syntax error maps to a range`() {
        val uri = "file:///broken.fl"
        val result = FlangAnalyzer.analyze(
            workspaceRoots = emptyList(),
            openDocuments = mapOf(uri to "mod main")
        )

        val diagnostic = result.diagnosticsByUri[uri].orEmpty().single()
        assertTrue(diagnostic.message.contains("Expected ';'"))
        assertNotNull(diagnostic.range)
    }

    @Test
    fun `type error is reported for open document`() {
        val uri = "file:///type-error.fl"
        val result = FlangAnalyzer.analyze(
            workspaceRoots = emptyList(),
            openDocuments = mapOf(
                uri to """
                    mod main;
                    fn bad() {
                        val x = missing;
                    }
                """.trimIndent()
            )
        )

        assertTrue(result.diagnosticsByUri[uri].orEmpty().any { it.message.contains("not defined") })
    }

    @Test
    fun `cross file symbols resolve`() {
        val result = FlangAnalyzer.analyze(
            workspaceRoots = emptyList(),
            openDocuments = mapOf(
                "file:///lib.fl" to """
                    mod lib;
                    fn answer(): Number { return 1; }
                """.trimIndent(),
                "file:///main.fl" to """
                    mod main;
                    import lib.answer;
                    fn use() {
                        val x = answer();
                    }
                """.trimIndent(),
            )
        )

        assertTrue(result.diagnosticsByUri.values.flatten().isEmpty())
    }

    @Test
    fun `stdlib symbols resolve`() {
        val uri = "file:///stdlib-use.fl"
        val result = FlangAnalyzer.analyze(
            workspaceRoots = emptyList(),
            openDocuments = mapOf(
                uri to """
                    mod main;
                    import std.player.Player;
                    fn use(): Player {
                        return Player;
                    }
                """.trimIndent()
            )
        )

        assertTrue(result.diagnosticsByUri[uri].orEmpty().isEmpty())
    }
}
