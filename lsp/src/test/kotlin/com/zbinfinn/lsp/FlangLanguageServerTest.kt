package com.zbinfinn.lsp

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensRangeParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlangLanguageServerTest {
    @Test
    fun `initialize advertises full document sync`() {
        val server = FlangLanguageServer()
        val result = server.initialize(InitializeParams()).get()

        assertNotNull(result.capabilities.textDocumentSync)
    }

    @Test
    fun `initialize advertises completion and semantic tokens`() {
        val server = FlangLanguageServer()
        val result = server.initialize(InitializeParams()).get()

        assertNotNull(result.capabilities.completionProvider)
        assertNotNull(result.capabilities.semanticTokensProvider)
    }

    @Test
    fun `didOpen publishes diagnostics`() {
        val client = RecordingClient()
        val server = FlangLanguageServer()
        server.connect(client)
        server.initialize(InitializeParams()).get()

        server.getTextDocumentService().didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem("file:///broken.fl", "flang", 1, "mod main")
            )
        )

        assertTrue(client.published.single().diagnostics.isNotEmpty())
    }

    @Test
    fun `didChange clears stale diagnostics`() {
        val client = RecordingClient()
        val server = FlangLanguageServer()
        server.connect(client)
        server.initialize(InitializeParams()).get()
        server.getTextDocumentService().didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem("file:///fixed.fl", "flang", 1, "mod main")
            )
        )

        server.getTextDocumentService().didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier("file:///fixed.fl", 2),
                listOf(TextDocumentContentChangeEvent("mod main;\nfn ok() {}"))
            )
        )

        assertEquals(0, client.published.last().diagnostics.size)
    }

    @Test
    fun `completion returns context appropriate keywords types and functions`() {
        val server = initializedServer()
        val text = """
            mod main;
            fn helper() {}
            fn test() {
                
            }
        """.trimIndent()
        server.open("file:///complete.fl", text)

        val labels = server.complete("file:///complete.fl", Position(3, 4)).map { it.label }

        assertTrue("val" in labels)
        assertTrue("String" in labels)
        assertTrue("helper" in labels)
        assertFalse("fn" in labels)
    }

    @Test
    fun `completion returns local parameters and variables in current function`() {
        val server = initializedServer()
        val text = """
            mod main;
            fn test(val input: Number) {
                val count = 1;
                cou
            }
        """.trimIndent()
        server.open("file:///locals.fl", text)

        val labels = server.complete("file:///locals.fl", Position(3, 7)).map { it.label }

        assertTrue("input" in labels)
        assertTrue("count" in labels)
    }

    @Test
    fun `completion after receiver dot returns obvious dict fields`() {
        val server = initializedServer()
        val text = """
            mod main;
            dict Foo { val bar: Number }
            fn test(): Number {
                val foo = Foo { bar: 1 };
                return foo.bar;
            }
        """.trimIndent()
        server.open("file:///members.fl", text)

        val labels = server.complete("file:///members.fl", Position(4, 15)).map { it.label }

        assertTrue("bar" in labels)
    }

    @Test
    fun `completion on broken source still returns fallback suggestions`() {
        val server = initializedServer()
        server.open("file:///broken-complete.fl", "mod main;\nfn test() {\n    @")

        val labels = server.complete("file:///broken-complete.fl", Position(2, 5)).map { it.label }

        assertTrue("Event" in labels)
        assertTrue("PlayerJoinEvent" in labels)
    }

    @Test
    fun `completion does not rebuild workspace index per request`() {
        val server = initializedServer()
        server.open("file:///cached.fl", "mod main;\nfn helper() {}\nfn test() {\n    \n}")
        val rebuildsAfterOpen = server.workspaceState.indexRebuildCount

        server.complete("file:///cached.fl", Position(3, 4))
        server.complete("file:///cached.fl", Position(3, 4))

        assertEquals(rebuildsAfterOpen, server.workspaceState.indexRebuildCount)
    }

    @Test
    fun `changing one open document updates cached parse version`() {
        val server = initializedServer()
        server.open("file:///one.fl", "mod one;\nfn a() {}")
        server.open("file:///two.fl", "mod two;\nfn b() {}")
        val oneVersion = server.workspaceState.cachedFile("file:///one.fl")?.version
        val twoVersion = server.workspaceState.cachedFile("file:///two.fl")?.version

        server.change("file:///one.fl", "mod one;\nfn a() {}\nfn c() {}")

        assertTrue((server.workspaceState.cachedFile("file:///one.fl")?.version ?: 0) > (oneVersion ?: 0))
        assertEquals(twoVersion, server.workspaceState.cachedFile("file:///two.fl")?.version)
    }

    @Test
    fun `type position completion excludes ordinary functions`() {
        val server = initializedServer()
        val text = """
            mod main;
            dict Foo { val bar: Number }
            fn helper() {}
            fn test(val value: ) {}
        """.trimIndent()
        server.open("file:///types.fl", text)

        val labels = server.complete("file:///types.fl", Position(3, 19)).map { it.label }

        assertTrue("Foo" in labels)
        assertTrue("Number" in labels)
        assertFalse("helper" in labels)
    }

    @Test
    fun `dict literal completion suggests missing fields`() {
        val server = initializedServer()
        val text = """
            mod main;
            dict Foo { val first: Number, val second: String }
            fn test() {
                val foo = Foo { first: 1,  };
            }
        """.trimIndent()
        server.open("file:///dict-fields.fl", text)

        val labels = server.complete("file:///dict-fields.fl", Position(3, 31)).map { it.label }

        assertTrue("second" in labels)
        assertFalse("first" in labels)
    }

    @Test
    fun `enum shorthand completion suggests enum cases from function argument context`() {
        val server = initializedServer()
        val text = """
            mod main;
            enum Direction { NORTH, SOUTH }
            fn turn(val direction: Direction) {}
            fn test() {
                turn(.);
            }
        """.trimIndent()
        server.open("file:///enum.fl", text)

        val labels = server.complete("file:///enum.fl", Position(4, 10)).map { it.label }

        assertTrue("NORTH" in labels)
        assertTrue("SOUTH" in labels)
    }

    @Test
    fun `unimported stdlib completion adds import edit`() {
        val server = initializedServer()
        val text = """
            mod main;
            fn test() {
                
            }
        """.trimIndent()
        server.open("file:///auto-stdlib.fl", text)

        val item = server.complete("file:///auto-stdlib.fl", Position(2, 4))
            .first { it.label == "defaultPlayer" }

        assertEquals("import std.player.selection.defaultPlayer;\n", item.additionalTextEdits.single().newText)
    }

    @Test
    fun `unimported workspace completion adds import edit`() {
        val server = initializedServer()
        server.open("file:///lib.fl", "mod lib;\nfn answer(): Number { return 1; }")
        server.open("file:///main.fl", "mod main;\nfn test() {\n    \n}")

        val item = server.complete("file:///main.fl", Position(2, 4))
            .first { it.label == "answer" }

        assertEquals("import lib.answer;\n", item.additionalTextEdits.single().newText)
    }

    @Test
    fun `already imported completion does not add duplicate import`() {
        val server = initializedServer()
        server.open("file:///lib-imported.fl", "mod lib;\nfn answer(): Number { return 1; }")
        server.open("file:///main-imported.fl", "mod main;\nimport lib.answer;\nfn test() {\n    \n}")

        val item = server.complete("file:///main-imported.fl", Position(3, 4))
            .first { it.label == "answer" }

        assertTrue(item.additionalTextEdits.isNullOrEmpty())
    }

    @Test
    fun `semantic tokens classify core token types`() {
        val server = initializedServer()
        val text = """
            mod main;
            dict Foo { val bar: Number }
            fn helper(): String {
                return "ok";
            }
        """.trimIndent()
        server.open("file:///semantic.fl", text)

        val tokenTypeIndexes = server.semanticTokens("file:///semantic.fl")
            .chunked(5)
            .map { it[3] }
            .toSet()

        assertTrue(FlangSemanticTokensProvider.tokenTypes.indexOf("keyword") in tokenTypeIndexes)
        assertTrue(FlangSemanticTokensProvider.tokenTypes.indexOf("function") in tokenTypeIndexes)
        assertTrue(FlangSemanticTokensProvider.tokenTypes.indexOf("type") in tokenTypeIndexes)
        assertTrue(FlangSemanticTokensProvider.tokenTypes.indexOf("string") in tokenTypeIndexes)
    }

    @Test
    fun `range semantic tokens only include requested range`() {
        val server = initializedServer()
        val text = """
            mod main;
            fn one() {}
            fn two() {}
        """.trimIndent()
        server.open("file:///range.fl", text)

        val lines = decodeTokenLines(
            server.semanticTokensRange("file:///range.fl", Range(Position(1, 0), Position(2, 0)))
        )

        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it == 1 })
    }

    private fun initializedServer(): FlangLanguageServer {
        val server = FlangLanguageServer()
        server.connect(RecordingClient())
        server.initialize(InitializeParams()).get()
        return server
    }

    private fun FlangLanguageServer.open(uri: String, text: String) {
        getTextDocumentService().didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "flang", 1, text))
        )
    }

    private fun FlangLanguageServer.change(uri: String, text: String) {
        getTextDocumentService().didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent(text))
            )
        )
    }

    private fun FlangLanguageServer.complete(uri: String, position: Position): List<CompletionItem> =
        getTextDocumentService().completion(
            CompletionParams(TextDocumentIdentifier(uri), position)
        ).get().left

    private fun FlangLanguageServer.semanticTokens(uri: String): List<Int> =
        getTextDocumentService().semanticTokensFull(
            SemanticTokensParams(TextDocumentIdentifier(uri))
        ).get().data

    private fun FlangLanguageServer.semanticTokensRange(uri: String, range: Range): List<Int> =
        getTextDocumentService().semanticTokensRange(
            SemanticTokensRangeParams(TextDocumentIdentifier(uri), range)
        ).get().data

    private fun decodeTokenLines(data: List<Int>): List<Int> {
        val lines = mutableListOf<Int>()
        var line = 0
        for (chunk in data.chunked(5)) {
            line += chunk[0]
            lines += line
        }
        return lines
    }

    private class RecordingClient : LanguageClient {
        val published = mutableListOf<PublishDiagnosticsParams>()

        override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
            published += diagnostics
        }

        override fun telemetryEvent(`object`: Any?) = Unit

        override fun showMessage(messageParams: org.eclipse.lsp4j.MessageParams?) = Unit

        override fun showMessageRequest(requestParams: org.eclipse.lsp4j.ShowMessageRequestParams?): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(null)

        override fun logMessage(message: org.eclipse.lsp4j.MessageParams?) = Unit
    }
}
