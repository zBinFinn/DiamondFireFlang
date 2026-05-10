package com.zbinfinn.analysis

import com.zbinfinn.ast.ParseDiagnosticException
import com.zbinfinn.ast.Parser
import com.zbinfinn.compiler.FunctionResolver
import com.zbinfinn.compiler.GlobalFunctionTable
import com.zbinfinn.compiler.GlobalTypeTable
import com.zbinfinn.source.SourceDocument
import com.zbinfinn.source.SourceRange
import com.zbinfinn.stdlib.StdlibAst
import com.zbinfinn.tokenizer.Tokenizer
import com.zbinfinn.typecheck.Diagnostic
import com.zbinfinn.typecheck.DiagnosticSeverity
import com.zbinfinn.typecheck.TypeChecker
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.toList

data class FlangDiagnostic(
    val message: String,
    val severity: DiagnosticSeverity,
    val range: SourceRange?,
    val module: String,
    val function: String? = null,
)

data class AnalysisResult(
    val diagnosticsByUri: Map<String, List<FlangDiagnostic>>,
)

object FlangAnalyzer {
    fun analyze(
        workspaceRoots: List<Path>,
        openDocuments: Map<String, String>,
    ): AnalysisResult {
        val sources = collectSources(workspaceRoots, openDocuments)
        val diagnosticsByUri = linkedMapOf<String, MutableList<FlangDiagnostic>>()
        val parsed = mutableListOf<ParsedSource>()

        for ((uri, text) in sources) {
            val document = SourceDocument(uri, text)
            try {
                val program = Parser(Tokenizer(text).tokenize(), moduleHint = uri).parseProgram()
                parsed += ParsedSource(uri, document, program)
            } catch (e: ParseDiagnosticException) {
                diagnosticsByUri.getOrPut(uri) { mutableListOf() } += e.diagnostic.toFlang(document)
            } catch (t: Throwable) {
                diagnosticsByUri.getOrPut(uri) { mutableListOf() } += FlangDiagnostic(
                    message = t.message ?: "Failed to parse source.",
                    severity = DiagnosticSeverity.Error,
                    range = document.wholeDocumentRange(),
                    module = uri,
                )
            }
        }

        if (parsed.isEmpty()) {
            return AnalysisResult(diagnosticsByUri)
        }

        val globals = GlobalFunctionTable()
        val typeTable = GlobalTypeTable()

        try {
            for (program in StdlibAst.programs) {
                globals.register(program)
                typeTable.register(program)
            }
            for (source in parsed) {
                globals.register(source.program)
                typeTable.register(source.program)
            }
        } catch (t: Throwable) {
            val first = parsed.first()
            diagnosticsByUri.getOrPut(first.uri) { mutableListOf() } += FlangDiagnostic(
                message = t.message ?: "Failed to register symbols.",
                severity = DiagnosticSeverity.Error,
                range = first.document.wholeDocumentRange(),
                module = first.program.module.path,
            )
            return AnalysisResult(diagnosticsByUri)
        }

        val checker = TypeChecker(
            globals = globals,
            functionResolver = FunctionResolver(globals),
            typeTable = typeTable,
        )

        for (source in parsed) {
            val diagnostics = try {
                checker.check(source.program)
            } catch (t: Throwable) {
                listOf(
                    Diagnostic(
                        message = t.message ?: "Failed to typecheck source.",
                        module = source.program.module.path,
                        range = source.document.wholeDocumentRange(),
                    )
                )
            }
            diagnosticsByUri.getOrPut(source.uri) { mutableListOf() } += diagnostics.map {
                it.toFlang(source.document)
            }
        }

        return AnalysisResult(diagnosticsByUri)
    }

    private fun collectSources(
        workspaceRoots: List<Path>,
        openDocuments: Map<String, String>,
    ): Map<String, String> {
        val sources = linkedMapOf<String, String>()

        for (root in workspaceRoots.distinct()) {
            if (!Files.exists(root)) continue
            Files.walk(root).use { paths ->
                paths.toList()
                    .filter { Files.isRegularFile(it) && it.extension == "fl" }
                    .filterNot { it.isExcluded(root) }
                    .forEach { path -> sources[path.toUri().toString()] = path.readText() }
            }
        }

        for ((uri, text) in openDocuments) {
            sources[uri] = text
        }

        return sources
    }

    private fun Path.isExcluded(root: Path): Boolean {
        val relative = root.relativize(this)
        return relative.any { part ->
            val name = part.name
            name == "build" ||
                name == ".gradle" ||
                name == ".gradle-user-home" ||
                name == ".idea" ||
                name == ".kotlin"
        }
    }

    private fun Diagnostic.toFlang(document: SourceDocument): FlangDiagnostic =
        FlangDiagnostic(
            message = message,
            severity = severity,
            range = range ?: document.wholeDocumentRange(),
            module = module,
            function = function,
        )

    private data class ParsedSource(
        val uri: String,
        val document: SourceDocument,
        val program: com.zbinfinn.ast.Ast.Program,
    )
}

fun uriToPath(uri: String): Path? =
    runCatching { Path.of(URI(uri)) }.getOrNull()
