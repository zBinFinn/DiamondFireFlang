package com.zbinfinn.lsp

import com.zbinfinn.analysis.FlangAnalyzer
import com.zbinfinn.analysis.FlangDiagnostic
import com.zbinfinn.analysis.uriToPath
import com.zbinfinn.source.SourceDocument
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensRangeParams
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class FlangLanguageServer : LanguageServer, LanguageClientAware {
    private var client: LanguageClient? = null
    private val textDocuments = FlangTextDocumentService(this)
    private val workspace = FlangWorkspaceService()
    internal val workspaceState = FlangWorkspaceState()
    private val shutdownRequested = AtomicBoolean(false)
    private var workspaceRoots: List<Path> = emptyList()

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        workspaceRoots = workspaceRoots(params)
        workspaceState.initialize(workspaceRoots)
        val capabilities = ServerCapabilities().apply {
            textDocumentSync = Either.forRight(
                TextDocumentSyncOptions().apply {
                    openClose = true
                    change = TextDocumentSyncKind.Full
                    save = Either.forLeft(true)
                }
            )
            completionProvider = CompletionOptions(false, listOf(".", "@", "<", ":"))
            semanticTokensProvider = SemanticTokensWithRegistrationOptions(
                SemanticTokensLegend(
                    FlangSemanticTokensProvider.tokenTypes,
                    FlangSemanticTokensProvider.tokenModifiers,
                )
            ).apply {
                setFull(true)
                setRange(true)
            }
        }
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
    }

    override fun shutdown(): CompletableFuture<Any> {
        shutdownRequested.set(true)
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        if (shutdownRequested.get()) {
            kotlin.system.exitProcess(0)
        }
        kotlin.system.exitProcess(1)
    }

    override fun getTextDocumentService(): TextDocumentService = textDocuments

    override fun getWorkspaceService(): WorkspaceService = workspace

    fun analyzeAndPublish(openDocuments: Map<String, String>) {
        val result = FlangAnalyzer.analyze(workspaceRoots, openDocuments)
        val knownUris = result.diagnosticsByUri.keys + openDocuments.keys
        for (uri in knownUris) {
            val text = openDocuments[uri] ?: readUri(uri)
            val document = SourceDocument(uri, text ?: "")
            val diagnostics = result.diagnosticsByUri[uri].orEmpty().map { it.toLsp(document) }
            client?.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
        }
    }

    private fun FlangDiagnostic.toLsp(document: SourceDocument): Diagnostic {
        val sourceRange = range ?: document.wholeDocumentRange()
        return Diagnostic(
            Range(
                document.offsetToPosition(sourceRange.start).toLsp(),
                document.offsetToPosition(sourceRange.end).toLsp(),
            ),
            message,
            when (severity) {
                com.zbinfinn.typecheck.DiagnosticSeverity.Error -> DiagnosticSeverity.Error
                com.zbinfinn.typecheck.DiagnosticSeverity.Warning -> DiagnosticSeverity.Warning
            },
            "diamondfire-flang",
        )
    }

    private fun com.zbinfinn.source.SourcePosition.toLsp(): Position =
        Position(line, character)

    private fun workspaceRoots(params: InitializeParams): List<Path> {
        val folders = params.workspaceFolders.orEmpty()
            .mapNotNull { uriToPath(it.uri) }
        if (folders.isNotEmpty()) return folders

        return listOfNotNull(params.rootUri?.let { uriToPath(it) })
    }

    private fun readUri(uri: String): String? {
        val path = uriToPath(uri) ?: return null
        return if (Files.isRegularFile(path)) Files.readString(path) else null
    }
}

class FlangTextDocumentService(
    private val server: FlangLanguageServer,
) : TextDocumentService {
    private val openDocuments = linkedMapOf<String, String>()

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<org.eclipse.lsp4j.CompletionItem>, org.eclipse.lsp4j.CompletionList>> {
        val items = FlangCompletionProvider.complete(
            server.workspaceState.view(),
            params.textDocument.uri,
            params.position,
        )
        return CompletableFuture.completedFuture(Either.forLeft(items))
    }

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> {
        return CompletableFuture.completedFuture(
            FlangSemanticTokensProvider.full(server.workspaceState.view(), params.textDocument.uri)
        )
    }

    override fun semanticTokensRange(params: SemanticTokensRangeParams): CompletableFuture<SemanticTokens> {
        return CompletableFuture.completedFuture(
            FlangSemanticTokensProvider.range(server.workspaceState.view(), params.textDocument.uri, params.range)
        )
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        openDocuments[params.textDocument.uri] = params.textDocument.text
        server.workspaceState.open(params.textDocument.uri, params.textDocument.text)
        server.analyzeAndPublish(openDocuments)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val text = params.contentChanges.lastOrNull()?.text ?: return
        openDocuments[params.textDocument.uri] = text
        server.workspaceState.change(params.textDocument.uri, text)
        server.analyzeAndPublish(openDocuments)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        openDocuments.remove(params.textDocument.uri)
        server.workspaceState.close(params.textDocument.uri)
        server.analyzeAndPublish(openDocuments)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        params.text?.let { openDocuments[params.textDocument.uri] = it }
        server.workspaceState.save(params.textDocument.uri, params.text)
        server.analyzeAndPublish(openDocuments)
    }
}

class FlangWorkspaceService : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) = Unit

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) = Unit
}
