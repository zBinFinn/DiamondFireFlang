package com.zbinfinn.lsp

import com.zbinfinn.analysis.uriToPath
import com.zbinfinn.ast.Ast
import com.zbinfinn.ast.Parser
import com.zbinfinn.common.FunctionKind
import com.zbinfinn.compiler.DictSymbol
import com.zbinfinn.compiler.EnumSymbol
import com.zbinfinn.compiler.FunctionSymbol
import com.zbinfinn.compiler.GlobalFunctionTable
import com.zbinfinn.compiler.GlobalTypeTable
import com.zbinfinn.compiler.SingletonSymbol
import com.zbinfinn.compiler.TypeSymbol
import com.zbinfinn.source.SourceDocument
import com.zbinfinn.source.SourceRange
import com.zbinfinn.stdlib.StdlibAst
import com.zbinfinn.tokenizer.Token
import com.zbinfinn.tokenizer.TokenType
import com.zbinfinn.tokenizer.Tokenizer
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.TextEdit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.toList

internal class FlangWorkspaceState {
    private val files = linkedMapOf<String, CachedFile>()
    private val openUris = mutableSetOf<String>()
    private val stdlibPrograms = StdlibAst.programs
    private var workspaceRoots: List<Path> = emptyList()
    private var globals = GlobalFunctionTable()
    private var typeTable = GlobalTypeTable()
    var workspaceScanCount: Int = 0
        private set
    var indexRebuildCount: Int = 0
        private set

    fun initialize(roots: List<Path>) {
        workspaceRoots = roots
        scanWorkspace()
        rebuildIndex()
    }

    fun open(uri: String, text: String) {
        openUris += uri
        update(uri, text)
    }

    fun change(uri: String, text: String) {
        openUris += uri
        update(uri, text)
    }

    fun save(uri: String, text: String?) {
        if (text != null) {
            update(uri, text)
        }
    }

    fun close(uri: String) {
        openUris -= uri
        val path = uriToPath(uri)
        if (path != null && Files.isRegularFile(path)) {
            update(uri, path.readText())
        } else {
            files.remove(uri)
            rebuildIndex()
        }
    }

    fun source(uri: String): String? =
        files[uri]?.text ?: uriToPath(uri)?.takeIf { Files.isRegularFile(it) }?.readText()

    fun cachedFile(uri: String): CachedFile? = files[uri]

    fun view(): FlangWorkspaceView =
        FlangWorkspaceView(files.toMap(), globals, typeTable, indexRebuildCount)

    private fun scanWorkspace() {
        workspaceScanCount++
        files.clear()
        for (root in workspaceRoots.distinct()) {
            if (!Files.exists(root)) continue
            Files.walk(root).use { paths ->
                paths.toList()
                    .filter { Files.isRegularFile(it) && it.extension == "fl" }
                    .filterNot { it.isExcluded(root) }
                    .forEach { path -> files[path.toUri().toString()] = parse(path.toUri().toString(), path.readText(), 0) }
            }
        }
    }

    private fun update(uri: String, text: String) {
        val version = (files[uri]?.version ?: 0) + 1
        files[uri] = parse(uri, text, version)
        rebuildIndex()
    }

    private fun rebuildIndex() {
        indexRebuildCount++
        val newGlobals = GlobalFunctionTable()
        val newTypeTable = GlobalTypeTable()
        for (program in stdlibPrograms) {
            runCatching {
                newGlobals.register(program)
                newTypeTable.register(program)
            }
        }
        for (file in files.values) {
            val program = file.program ?: continue
            runCatching {
                newGlobals.register(program)
                newTypeTable.register(program)
            }
        }
        globals = newGlobals
        typeTable = newTypeTable
    }

    private fun parse(uri: String, text: String, version: Int): CachedFile {
        val tokens = runCatching { Tokenizer(text).tokenize() }.getOrElse { emptyList() }
        val program = runCatching { Parser(tokens, moduleHint = uri).parseProgram() }.getOrNull()
        return CachedFile(uri, text, tokens, program, version)
    }

    private fun Path.isExcluded(root: Path): Boolean {
        val relative = root.relativize(this)
        return relative.any { part ->
            when (part.name) {
                "build", ".gradle", ".gradle-user-home", ".idea", ".kotlin" -> true
                else -> false
            }
        }
    }
}

internal data class CachedFile(
    val uri: String,
    val text: String,
    val tokens: List<Token>,
    val program: Ast.Program?,
    val version: Int,
)

internal data class FlangWorkspaceView(
    val files: Map<String, CachedFile>,
    val globals: GlobalFunctionTable,
    val typeTable: GlobalTypeTable,
    val indexVersion: Int,
) {
    fun file(uri: String): CachedFile? = files[uri]
}

internal object FlangCompletionProvider {
    private val statementKeywords = listOf("val", "var", "if", "return", "with")
    private val topLevelKeywords = listOf("import", "fn", "internal", "dict", "enum", "singleton", "impl")
    private val annotationNames = listOf("Event", "PlayerEventProvider", "EntityEventProvider")
    private val builtinTypes = listOf("String", "Text", "Number", "Boolean", "Any", "List", "Dictionary")

    fun complete(view: FlangWorkspaceView, uri: String, position: Position): List<CompletionItem> {
        val file = view.file(uri) ?: return fallbackItems()
        val document = SourceDocument(uri, file.text)
        val offset = document.positionToOffset(position.line, position.character)
        val context = classifyContext(file, offset, view)
        val locals = localScope(file.tokens, offset, view, file.program)
        val items = linkedMapOf<String, CompletionItem>()

        fun add(item: CompletionItem) {
            val key = "${item.kind}:${item.label}:${item.detail}:${item.additionalTextEdits?.joinToString { it.newText }.orEmpty()}"
            items.putIfAbsent(key, item)
        }

        when (context) {
            is CompletionContext.ImportPath -> importableSymbols(view).forEach {
                add(it.toCompletion(importEdit = null, rank = "10"))
            }
            is CompletionContext.TypePosition -> {
                builtinTypes.forEach { add(simpleItem(it, CompletionItemKind.Class, "built-in type", "10")) }
                localTypes(file).forEach { add(simpleItem(it, CompletionItemKind.Class, "local type", "15")) }
                visibleTypes(file.program, view).forEach { add(typeItem(it, file, null, "20")) }
                importableTypes(file.program, view).forEach { add(typeItem(it, file, importEdit(file, it.qualifiedName), "40")) }
            }
            is CompletionContext.AnnotationPosition -> {
                annotationNames.forEach { add(simpleItem(it, CompletionItemKind.Property, "annotation", "10")) }
                visibleTypes(file.program, view).forEach { add(typeItem(it, file, null, "20")) }
                importableTypes(file.program, view).forEach { add(typeItem(it, file, importEdit(file, it.qualifiedName), "40")) }
            }
            is CompletionContext.MemberAccess -> {
                receiverCompletions(context.receiver, locals, file.program, view).forEach(::add)
            }
            is CompletionContext.DictLiteralField -> {
                context.fields.forEach {
                    add(simpleItem(it.name, CompletionItemKind.Field, "${if (it.mutable) "var" else "val"} ${it.name}: ${render(it.type)}", "10"))
                }
            }
            is CompletionContext.EnumShorthand -> {
                context.enumSymbol.decl.cases.forEach {
                    add(simpleItem(it.name, CompletionItemKind.EnumMember, context.enumSymbol.qualifiedName, "10"))
                }
            }
            CompletionContext.ExpressionOrStatement -> {
                locals.symbols.values.forEach { add(simpleItem(it.name, CompletionItemKind.Variable, it.detail, "00")) }
                statementKeywords.forEach { add(simpleItem(it, CompletionItemKind.Keyword, "keyword", "10")) }
                builtinTypes.forEach { add(simpleItem(it, CompletionItemKind.Class, "built-in type", "18")) }
                visibleFunctions(file.program, view).forEach { add(functionItem(it, null, "20")) }
                visibleTypes(file.program, view).forEach { add(typeItem(it, file, null, "25")) }
                localTypes(file).forEach { add(simpleItem(it, CompletionItemKind.Class, "local type", "26")) }
                importableFunctions(file.program, view).forEach { add(functionItem(it.symbol, importEdit(file, it.importQualifiedName), "40")) }
                importableTypes(file.program, view).forEach { add(typeItem(it, file, importEdit(file, it.qualifiedName), "45")) }
                if (!isInsideFunction(file.tokens, offset)) {
                    topLevelKeywords.forEach { add(simpleItem(it, CompletionItemKind.Keyword, "keyword", "15")) }
                }
            }
        }

        return items.values.sortedWith(compareBy<CompletionItem> { it.sortText ?: it.label }.thenBy { it.label }.thenBy { it.detail })
    }

    private fun fallbackItems(): List<CompletionItem> =
        (statementKeywords + topLevelKeywords).map { simpleItem(it, CompletionItemKind.Keyword, "keyword", "10") } +
            builtinTypes.map { simpleItem(it, CompletionItemKind.Class, "built-in type", "20") }

    private fun classifyContext(file: CachedFile, offset: Int, view: FlangWorkspaceView): CompletionContext {
        val token = tokenBefore(file.tokens, offset)
        val linePrefix = file.text.substring(lineStart(file.text, offset), offset)
        expectedEnumForDot(file, offset, view)?.let { return CompletionContext.EnumShorthand(it) }
        receiverBeforeDot(file.text, offset)?.let { receiver ->
            return CompletionContext.MemberAccess(receiver)
        }
        if (linePrefix.trimStart().startsWith("import ")) return CompletionContext.ImportPath
        if (token?.type == TokenType.AT || linePrefix.trimEnd().endsWith("@")) return CompletionContext.AnnotationPosition
        dictLiteralContext(file, offset, view)?.let { return it }
        if (isTypePosition(file.tokens, offset)) return CompletionContext.TypePosition
        return CompletionContext.ExpressionOrStatement
    }

    private fun isTypePosition(tokens: List<Token>, offset: Int): Boolean {
        val before = tokens.filter { it.position < offset && it.type != TokenType.EOF }
        val last = before.lastOrNull() ?: return false
        val prev = before.getOrNull(before.lastIndex - 1)
        return last.type == TokenType.COLON ||
            last.type == TokenType.LT ||
            last.type == TokenType.COMMA && before.takeLast(8).any { it.type == TokenType.LT } ||
            prev?.type == TokenType.IMPL
    }

    private fun dictLiteralContext(file: CachedFile, offset: Int, view: FlangWorkspaceView): CompletionContext.DictLiteralField? {
        val before = file.tokens.filter { it.position < offset && it.type != TokenType.EOF }
        for (i in before.indices.reversed()) {
            if (before[i].type == TokenType.SEMI || before[i].type == TokenType.RBRACE) break
            if (before[i].type == TokenType.LBRACE && i > 0 && before[i - 1].type == TokenType.IDENT) {
                val typeName = before[i - 1].lexeme
                val program = file.program
                val type = if (program != null) view.typeTable.resolve(typeName, program) else view.typeTable.allTypes().firstOrNull { it.simpleName == typeName }
                val dict = type as? DictSymbol
                if (dict != null) {
                    val used = before.drop(i + 1)
                        .filterIndexed { index, token -> token.type == TokenType.IDENT && before.getOrNull(i + 1 + index + 1)?.type == TokenType.COLON }
                        .mapTo(mutableSetOf()) { it.lexeme }
                    return CompletionContext.DictLiteralField(dict.decl.fields.filterNot { it.name in used })
                }
            }
        }
        dictLiteralTypeName(file.tokens, offset)?.let { typeName ->
            val used = usedFieldsInCurrentLiteral(file.tokens, offset)
            fieldsForLocalDict(file, typeName)?.let { fields ->
                return CompletionContext.DictLiteralField(fields.filterNot { it.name in used })
            }
        }
        return null
    }

    private fun expectedEnumForDot(file: CachedFile, offset: Int, view: FlangWorkspaceView): EnumSymbol? {
        val tokens = file.tokens
        val dot = tokens.indexOfLast { it.position < offset && it.type == TokenType.DOT }
        if (dot < 0) return null
        val before = tokens.take(dot)
        val program = file.program
        val paren = before.indexOfLast { it.type == TokenType.LPAREN }
        if (paren >= 1 && before[paren - 1].type == TokenType.IDENT) {
            val fn = program?.let { resolveFunction(before[paren - 1].lexeme, it, view) }
            val argIndex = before.drop(paren + 1).count { it.type == TokenType.COMMA }
            val typeName = fn?.decl?.parameters?.getOrNull(argIndex)?.type?.identifier
                ?: parameterTypeForLocalFunction(file, before[paren - 1].lexeme, argIndex)
            val type = typeName?.let {
                if (program != null) view.typeTable.resolve(it, program) else null
            } ?: typeName?.let { enumForLocalType(file, it) }
            if (type is EnumSymbol) return type
        }
        val eq = before.indexOfLast { it.type == TokenType.EQ }
        if (eq >= 2 && before[eq - 2].type == TokenType.COLON && before[eq - 1].type == TokenType.IDENT) {
            val type = if (program != null) view.typeTable.resolve(before[eq - 1].lexeme, program) else enumForLocalType(file, before[eq - 1].lexeme)
            if (type is EnumSymbol) return type
        }
        return null
    }

    private fun parameterTypeForLocalFunction(file: CachedFile, functionName: String, parameterIndex: Int): String? {
        val tokens = file.tokens
        for (i in tokens.indices) {
            if (tokens[i].type == TokenType.FN && tokens.getOrNull(i + 1)?.lexeme == functionName) {
                val open = findNext(tokens, i, TokenType.LPAREN) ?: return null
                val close = matchingParen(tokens, open) ?: return null
                var seen = 0
                var j = open + 1
                while (j < close) {
                    if (tokens[j].type == TokenType.IDENT && tokens.getOrNull(j + 1)?.type == TokenType.COLON) {
                        if (seen == parameterIndex) return tokens.getOrNull(j + 2)?.takeIf { it.type == TokenType.IDENT }?.lexeme
                        seen++
                    }
                    j++
                }
            }
        }
        return null
    }

    private fun enumForLocalType(file: CachedFile, typeName: String): EnumSymbol? {
        val cases = enumCasesForLocalType(file, typeName) ?: return null
        return EnumSymbol("${file.program?.module?.path ?: "local"}.$typeName", typeName, file.program?.module?.path ?: "local", Ast.EnumDecl(typeName, cases.map { Ast.EnumDecl.EnumCase(it, null) }))
    }

    private fun enumCasesForLocalType(file: CachedFile, typeName: String): List<String>? {
        val tokens = file.tokens
        for (i in tokens.indices) {
            if (tokens[i].type == TokenType.ENUM && tokens.getOrNull(i + 1)?.lexeme == typeName) {
                val open = findNext(tokens, i, TokenType.LBRACE) ?: return null
                val close = matchingBrace(tokens, open) ?: tokens.indexOfFirst { it.position > tokens[open].position && it.type == TokenType.FN }.takeIf { it > open } ?: tokens.size
                return tokens.subList(open + 1, close).filter { it.type == TokenType.IDENT }.map { it.lexeme }
            }
        }
        return null
    }

    private fun localTypes(file: CachedFile): List<String> =
        file.tokens.mapIndexedNotNull { index, token ->
            if (token.type in setOf(TokenType.DICT, TokenType.ENUM, TokenType.SINGLETON)) {
                file.tokens.getOrNull(index + 1)?.takeIf { it.type == TokenType.IDENT }?.lexeme
            } else {
                null
            }
        }.distinct()

    private fun dictLiteralTypeName(tokens: List<Token>, offset: Int): String? {
        val before = tokens.filter { it.position < offset && it.type != TokenType.EOF }
        for (i in before.indices.reversed()) {
            if (before[i].type == TokenType.SEMI || before[i].type == TokenType.RBRACE) return null
            if (before[i].type == TokenType.LBRACE && i > 0 && before[i - 1].type == TokenType.IDENT) {
                return before[i - 1].lexeme
            }
        }
        return null
    }

    private fun usedFieldsInCurrentLiteral(tokens: List<Token>, offset: Int): Set<String> {
        val before = tokens.filter { it.position < offset && it.type != TokenType.EOF }
        val open = before.indexOfLast { it.type == TokenType.LBRACE }
        if (open < 0) return emptySet()
        return before.drop(open + 1)
            .filterIndexed { index, token -> token.type == TokenType.IDENT && before.getOrNull(open + 1 + index + 1)?.type == TokenType.COLON }
            .mapTo(mutableSetOf()) { it.lexeme }
    }

    private fun fieldsForLocalDict(file: CachedFile, typeName: String): List<Ast.Field>? {
        val tokens = file.tokens
        val dictIndex = tokens.indices.firstOrNull { index ->
            tokens[index].type == TokenType.DICT && tokens.getOrNull(index + 1)?.lexeme == typeName
        } ?: -1
        if (dictIndex < 0) return null
        val open = findNext(tokens, dictIndex, TokenType.LBRACE) ?: return null
        val close = matchingBrace(tokens, open) ?: tokens.indexOfFirst { it.type == TokenType.FN }.takeIf { it > open } ?: tokens.size
        val fields = mutableListOf<Ast.Field>()
        var i = open + 1
        while (i < close) {
            val mutable = when (tokens.getOrNull(i)?.type) {
                TokenType.VAL -> false
                TokenType.VAR -> true
                else -> {
                    i++
                    continue
                }
            }
            val name = tokens.getOrNull(i + 1)?.takeIf { it.type == TokenType.IDENT }?.lexeme
            val type = tokens.getOrNull(i + 3)?.takeIf { tokens.getOrNull(i + 2)?.type == TokenType.COLON && it.type == TokenType.IDENT }?.lexeme
            if (name != null && type != null) {
                fields += Ast.Field(name, Ast.Type(type), mutable)
            }
            i++
        }
        return fields
    }

    private fun receiverCompletions(
        receiver: String,
        locals: LocalScope,
        program: Ast.Program?,
        view: FlangWorkspaceView,
    ): List<CompletionItem> {
        val typeName = locals.symbols[receiver]?.typeName ?: receiver
        val items = mutableListOf<CompletionItem>()
        val typeSymbol = if (program != null) {
            view.typeTable.resolve(typeName, program) ?: view.typeTable.allTypes().firstOrNull { it.simpleName == typeName || it.qualifiedName == typeName }
        } else {
            view.typeTable.allTypes().firstOrNull { it.simpleName == typeName || it.qualifiedName == typeName }
        }
        if (typeSymbol is DictSymbol) {
            typeSymbol.decl.fields.forEach { field ->
                items += simpleItem(field.name, CompletionItemKind.Field, "${if (field.mutable) "var" else "val"} ${field.name}: ${render(field.type)}", "10")
            }
        }
        if (typeSymbol is EnumSymbol) {
            typeSymbol.decl.cases.forEach { case ->
                items += simpleItem(case.name, CompletionItemKind.EnumMember, typeSymbol.qualifiedName, "10")
            }
        }
        val qualifiedType = when (typeName) {
            "List" -> "std.collections.List"
            "Dictionary" -> "std.collections.Dictionary"
            else -> typeSymbol?.qualifiedName
        }
        if (qualifiedType != null) {
            view.globals.membersFor(qualifiedType).values.flatten()
                .forEach { items += functionItem(it, null, "20", CompletionItemKind.Method) }
        }
        return items
    }

    private fun localScope(tokens: List<Token>, offset: Int, view: FlangWorkspaceView, program: Ast.Program?): LocalScope {
        val fnIndex = tokens.indexOfLast { it.type == TokenType.FN && it.position < offset }
        if (fnIndex < 0) return LocalScope(emptyMap())
        val bodyStart = findNext(tokens, fnIndex, TokenType.LBRACE) ?: return LocalScope(emptyMap())
        val bodyEnd = matchingBrace(tokens, bodyStart) ?: tokens.lastIndex
        if (offset !in tokens[bodyStart].position..tokens[bodyEnd].endPosition) return LocalScope(emptyMap())

        val symbols = linkedMapOf<String, LocalSymbol>()
        val paramsStart = findNext(tokens, fnIndex, TokenType.LPAREN)
        val paramsEnd = paramsStart?.let { matchingParen(tokens, it) }
        if (paramsStart != null && paramsEnd != null) {
            var i = paramsStart + 1
            while (i < paramsEnd) {
                if (tokens[i].type == TokenType.IDENT && i + 1 < paramsEnd && tokens[i + 1].type == TokenType.COLON) {
                    val typeName = readTypeName(tokens, i + 2, paramsEnd)
                    symbols[tokens[i].lexeme] = LocalSymbol(tokens[i].lexeme, typeName, "parameter${typeName?.let { ": $it" } ?: ""}")
                }
                i++
            }
        }

        var i = bodyStart + 1
        while (i < bodyEnd && tokens[i].position < offset) {
            if ((tokens[i].type == TokenType.VAL || tokens[i].type == TokenType.VAR) && tokens.getOrNull(i + 1)?.type == TokenType.IDENT) {
                val mutable = tokens[i].type == TokenType.VAR
                val name = tokens[i + 1].lexeme
                val explicitType = if (tokens.getOrNull(i + 2)?.type == TokenType.COLON) readTypeName(tokens, i + 3, bodyEnd) else null
                val eq = findNext(tokens, i + 2, TokenType.EQ, stopAt = TokenType.SEMI)
                val typeName = explicitType ?: eq?.let { inferExpressionType(tokens, it + 1, view, program, symbols) }
                symbols[name] = LocalSymbol(name, typeName, "${if (mutable) "var" else "val"} $name${typeName?.let { ": $it" } ?: ""}")
            }
            i++
        }
        return LocalScope(symbols)
    }

    private fun inferExpressionType(
        tokens: List<Token>,
        start: Int,
        view: FlangWorkspaceView,
        program: Ast.Program?,
        locals: Map<String, LocalSymbol>,
    ): String? {
        val first = tokens.getOrNull(start) ?: return null
        return when (first.type) {
            TokenType.STRING_LIT -> "String"
            TokenType.TEXT_LIT -> "Text"
            TokenType.NUMBER_LIT -> "Number"
            TokenType.TRUE, TokenType.FALSE -> "Boolean"
            TokenType.IDENT -> when {
                tokens.getOrNull(start + 1)?.type == TokenType.LBRACE -> first.lexeme
                tokens.getOrNull(start + 1)?.type == TokenType.DOT -> first.lexeme
                tokens.getOrNull(start + 1)?.type == TokenType.LPAREN && program != null ->
                    resolveFunction(first.lexeme, program, view)?.decl?.returnType?.identifier
                locals[first.lexeme]?.typeName != null -> locals[first.lexeme]?.typeName
                program != null && view.typeTable.resolve(first.lexeme, program) is SingletonSymbol -> first.lexeme
                else -> null
            }
            else -> null
        }
    }

    private fun resolveFunction(name: String, program: Ast.Program, view: FlangWorkspaceView): FunctionSymbol? {
        view.globals.functionsInModule(program.module.path)[name]?.firstOrNull()?.let { return it }
        for (import in program.imports) {
            if (import.path.substringAfterLast('.') == name) {
                return view.globals.resolveQualified(import.path)?.firstOrNull()
            }
        }
        return null
    }

    private fun visibleFunctions(program: Ast.Program?, view: FlangWorkspaceView): List<FunctionSymbol> {
        if (program == null) return emptyList()
        val imported = program.imports.flatMap { view.globals.resolveQualified(it.path).orEmpty() }
        val current = view.globals.functionsInModule(program.module.path).values.flatten()
        return (current + imported).distinctBy { it.qualifiedName }
    }

    private fun visibleTypes(program: Ast.Program?, view: FlangWorkspaceView): List<TypeSymbol> {
        if (program == null) return emptyList()
        val imports = program.imports.mapNotNull { view.typeTable.resolveQualified(it.path) }
        val current = view.typeTable.allTypes().filter { it.modulePath == program.module.path }
        return (current + imports).distinctBy { it.qualifiedName }
    }

    private fun importableFunctions(program: Ast.Program?, view: FlangWorkspaceView): List<ImportableFunction> {
        val currentModule = program?.module?.path
        val imported = program?.imports?.mapTo(mutableSetOf()) { it.path } ?: emptySet()
        return view.globals.allFunctions()
            .filter { it.memberOf == null }
            .map { ImportableFunction(it, "${it.modulePath}.${it.simpleName}") }
            .filter { it.symbol.modulePath != currentModule && it.importQualifiedName !in imported }
            .distinctBy { "${it.importQualifiedName}:${it.symbol.kind}" }
    }

    private fun importableTypes(program: Ast.Program?, view: FlangWorkspaceView): List<TypeSymbol> {
        val currentModule = program?.module?.path
        val imported = program?.imports?.mapTo(mutableSetOf()) { it.path } ?: emptySet()
        return view.typeTable.allTypes()
            .filter { it.modulePath != currentModule && it.qualifiedName !in imported && !isBuiltinType(it.simpleName) }
            .distinctBy { it.qualifiedName }
    }

    private fun importableSymbols(view: FlangWorkspaceView): List<ImportableCompletion> {
        val functions = view.globals.allFunctions()
            .filter { it.memberOf == null }
            .map { ImportableCompletion(it.simpleName, CompletionItemKind.Function, "${it.modulePath}.${it.simpleName}", renderSignature(it), null) }
        val types = view.typeTable.allTypes()
            .filterNot { isBuiltinType(it.simpleName) }
            .map { ImportableCompletion(it.simpleName, CompletionItemKind.Class, it.qualifiedName, it.qualifiedName, null) }
        return (functions + types).distinctBy { "${it.qualifiedName}:${it.kind}" }
    }

    private fun importEdit(file: CachedFile, qualifiedName: String): TextEdit? {
        val program = file.program
        if (program != null && (qualifiedName in program.imports.map { it.path } || qualifiedName.substringBeforeLast('.') == program.module.path)) {
            return null
        }
        val insertOffset = importInsertOffset(file)
        val position = SourceDocument(file.uri, file.text).offsetToPosition(insertOffset)
        return TextEdit(Range(Position(position.line, position.character), Position(position.line, position.character)), "import $qualifiedName;\n")
    }

    private fun importInsertOffset(file: CachedFile): Int {
        val tokens = file.tokens.filter { it.type != TokenType.EOF }
        var lastImportSemi: Token? = null
        var moduleSemi: Token? = null
        var i = 0
        while (i < tokens.size) {
            if (tokens[i].type == TokenType.MOD) {
                moduleSemi = findToken(tokens, i, TokenType.SEMI)
            }
            if (tokens[i].type == TokenType.IMPORT) {
                lastImportSemi = findToken(tokens, i, TokenType.SEMI)
            }
            i++
        }
        val token = lastImportSemi ?: moduleSemi
        return token?.endPosition?.let { if (it < file.text.length && file.text[it] == '\r') it + 2 else if (it < file.text.length && file.text[it] == '\n') it + 1 else it + 1 }
            ?: 0
    }

    private fun typeItem(symbol: TypeSymbol, file: CachedFile, importEdit: TextEdit?, rank: String): CompletionItem =
        simpleItem(symbol.simpleName, CompletionItemKind.Class, symbol.qualifiedName, rank).apply {
            importEdit?.let { additionalTextEdits = listOf(it) }
        }

    private fun functionItem(symbol: FunctionSymbol, importEdit: TextEdit?, rank: String, kind: CompletionItemKind = CompletionItemKind.Function): CompletionItem =
        simpleItem(symbol.simpleName, kind, renderSignature(symbol), rank).apply {
            importEdit?.let { additionalTextEdits = listOf(it) }
        }

    private fun ImportableCompletion.toCompletion(importEdit: TextEdit?, rank: String): CompletionItem =
        simpleItem(label, kind, detail, rank).apply {
            filterText = qualifiedName
            importEdit?.let { additionalTextEdits = listOf(it) }
        }

    private fun simpleItem(label: String, kind: CompletionItemKind, detail: String, rank: String): CompletionItem =
        CompletionItem(label).apply {
            this.kind = kind
            this.detail = detail
            this.sortText = "$rank:$label:$detail"
        }

    private fun renderSignature(symbol: FunctionSymbol): String {
        val params = symbol.decl.parameters.joinToString(", ") {
            "${if (it.mutable) "var " else "val "}${it.name}: ${render(it.type)}"
        }
        val returnType = symbol.decl.returnType?.let { ": ${render(it)}" }.orEmpty()
        val suffix = when (symbol.kind) {
            FunctionKind.Plain -> ""
            else -> " (${symbol.kind})"
        }
        return "${symbol.modulePath}.${symbol.simpleName} fn ${symbol.simpleName}($params)$returnType$suffix"
    }

    private fun render(type: Ast.Type): String =
        if (type.args.isEmpty()) type.identifier else "${type.identifier}<${type.args.joinToString(", ") { render(it) }}>"

    private fun readTypeName(tokens: List<Token>, start: Int, endExclusive: Int): String? =
        tokens.getOrNull(start)?.takeIf { start < endExclusive && it.type == TokenType.IDENT }?.lexeme

    private fun receiverBeforeDot(text: String, offset: Int): String? {
        val before = text.take(offset)
        val match = Regex("""([A-Za-z_][A-Za-z0-9_]*)\.\s*$""").find(before) ?: return null
        return match.groupValues[1]
    }

    private fun tokenBefore(tokens: List<Token>, offset: Int): Token? =
        tokens.filter { it.position < offset && it.type != TokenType.EOF }.maxByOrNull { it.position }

    private fun lineStart(text: String, offset: Int): Int =
        text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }

    private fun findNext(tokens: List<Token>, start: Int, type: TokenType, stopAt: TokenType? = null): Int? {
        for (i in start until tokens.size) {
            if (stopAt != null && tokens[i].type == stopAt) return null
            if (tokens[i].type == type) return i
        }
        return null
    }

    private fun findToken(tokens: List<Token>, start: Int, type: TokenType): Token? =
        findNext(tokens, start, type)?.let { tokens[it] }

    private fun matchingBrace(tokens: List<Token>, openIndex: Int): Int? =
        matchingDelimited(tokens, openIndex, TokenType.LBRACE, TokenType.RBRACE)

    private fun matchingParen(tokens: List<Token>, openIndex: Int): Int? =
        matchingDelimited(tokens, openIndex, TokenType.LPAREN, TokenType.RPAREN)

    private fun matchingDelimited(tokens: List<Token>, openIndex: Int, open: TokenType, close: TokenType): Int? {
        var depth = 0
        for (i in openIndex until tokens.size) {
            when (tokens[i].type) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
                else -> {}
            }
        }
        return null
    }

    private fun isInsideFunction(tokens: List<Token>, offset: Int): Boolean {
        val fnIndex = tokens.indexOfLast { it.type == TokenType.FN && it.position < offset }
        if (fnIndex < 0) return false
        val bodyStart = findNext(tokens, fnIndex, TokenType.LBRACE) ?: return false
        val bodyEnd = matchingBrace(tokens, bodyStart) ?: tokens.lastIndex
        return offset in tokens[bodyStart].position..tokens[bodyEnd].endPosition
    }

    private fun isBuiltinType(name: String): Boolean = name in builtinTypes

    private sealed interface CompletionContext {
        data object ImportPath : CompletionContext
        data object TypePosition : CompletionContext
        data object AnnotationPosition : CompletionContext
        data class MemberAccess(val receiver: String) : CompletionContext
        data class DictLiteralField(val fields: List<Ast.Field>) : CompletionContext
        data class EnumShorthand(val enumSymbol: EnumSymbol) : CompletionContext
        data object ExpressionOrStatement : CompletionContext
    }

    private data class LocalScope(val symbols: Map<String, LocalSymbol>)
    private data class LocalSymbol(val name: String, val typeName: String?, val detail: String)
    private data class ImportableFunction(val symbol: FunctionSymbol, val importQualifiedName: String)
    private data class ImportableCompletion(
        val label: String,
        val kind: CompletionItemKind,
        val qualifiedName: String,
        val detail: String,
        val edit: TextEdit?,
    )
}

internal object FlangSemanticTokensProvider {
    val tokenTypes = listOf(
        "keyword", "function", "method", "variable", "parameter", "property", "type",
        "enumMember", "string", "number", "operator", "comment"
    )
    val tokenModifiers = listOf("declaration", "readonly", "static", "deprecated")

    private val keywordTypes = setOf(
        TokenType.FN, TokenType.INTERNAL, TokenType.VAL, TokenType.VAR, TokenType.MOD, TokenType.DICT,
        TokenType.ENUM, TokenType.SINGLETON, TokenType.IMPL, TokenType.WITH, TokenType.IMPORT,
        TokenType.PACKAGE, TokenType.IF, TokenType.ELSE, TokenType.RETURN, TokenType.TRUE, TokenType.FALSE
    )
    private val operatorTypes = setOf(
        TokenType.AT, TokenType.EQ, TokenType.EQEQ, TokenType.NEQ, TokenType.ANDAND, TokenType.OROR,
        TokenType.BANG, TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH, TokenType.CARET,
        TokenType.DOT, TokenType.COMMA, TokenType.SEMI, TokenType.COLON, TokenType.LT, TokenType.GT,
        TokenType.LPAREN, TokenType.RPAREN, TokenType.LBRACE, TokenType.RBRACE
    )

    fun full(view: FlangWorkspaceView, uri: String): SemanticTokens =
        tokens(view.file(uri), null)

    fun range(view: FlangWorkspaceView, uri: String, range: Range): SemanticTokens =
        tokens(view.file(uri), range)

    private fun tokens(file: CachedFile?, range: Range?): SemanticTokens {
        if (file == null) return SemanticTokens(emptyList())
        val document = SourceDocument(file.uri, file.text)
        val requestedRange = range?.let {
            SourceRange(
                document.positionToOffset(it.start.line, it.start.character),
                document.positionToOffset(it.end.line, it.end.character)
            )
        }
        val semanticTokens = mutableListOf<RawSemanticToken>()
        for (comment in scanComments(file.text)) {
            if (requestedRange == null || comment.range.overlaps(requestedRange)) {
                semanticTokens += RawSemanticToken(comment.range, typeIndex("comment"), 0)
            }
        }
        val tokens = file.tokens.filter { it.type != TokenType.EOF }
        tokens.forEachIndexed { index, token ->
            if (requestedRange != null && !token.range.overlaps(requestedRange)) return@forEachIndexed
            classify(tokens, index)?.let { (type, modifiers) ->
                semanticTokens += RawSemanticToken(token.range, typeIndex(type), modifiers)
            }
        }
        return SemanticTokens(encode(semanticTokens.sortedWith(compareBy({ it.range.start }, { it.range.end })), document))
    }

    private fun classify(tokens: List<Token>, index: Int): Pair<String, Int>? {
        val token = tokens[index]
        val prev = tokens.getOrNull(index - 1)?.type
        val next = tokens.getOrNull(index + 1)?.type
        val prev2 = tokens.getOrNull(index - 2)?.type
        return when {
            token.type in keywordTypes -> "keyword" to 0
            token.type == TokenType.STRING_LIT || token.type == TokenType.TEXT_LIT -> "string" to 0
            token.type == TokenType.NUMBER_LIT -> "number" to 0
            token.type in operatorTypes -> "operator" to 0
            token.type == TokenType.IDENT && prev == TokenType.FN -> "function" to modifier("declaration")
            token.type == TokenType.IDENT && prev == TokenType.DICT -> "type" to modifier("declaration")
            token.type == TokenType.IDENT && prev == TokenType.ENUM -> "type" to modifier("declaration")
            token.type == TokenType.IDENT && prev == TokenType.SINGLETON -> "type" to modifier("declaration")
            token.type == TokenType.IDENT && prev == TokenType.IMPL -> "type" to 0
            token.type == TokenType.IDENT && prev == TokenType.AT -> "type" to 0
            token.type == TokenType.IDENT && prev == TokenType.DOT && next == TokenType.LPAREN -> "method" to 0
            token.type == TokenType.IDENT && prev == TokenType.DOT -> "property" to 0
            token.type == TokenType.IDENT && next == TokenType.LPAREN -> "function" to 0
            token.type == TokenType.IDENT && prev == TokenType.COLON -> "type" to 0
            token.type == TokenType.IDENT && (prev2 == TokenType.VAL || prev2 == TokenType.VAR) -> "parameter" to modifier("declaration")
            token.type == TokenType.IDENT && (prev == TokenType.VAL || prev == TokenType.VAR) -> "variable" to modifier("declaration")
            token.type == TokenType.IDENT -> "variable" to 0
            else -> null
        }
    }

    private fun scanComments(text: String): List<CommentRange> {
        val comments = mutableListOf<CommentRange>()
        var offset = 0
        for (line in text.lineSequence()) {
            val commentStart = line.indexOf("//")
            if (commentStart >= 0) {
                comments += CommentRange(SourceRange(offset + commentStart, offset + line.length))
            }
            offset += line.length + 1
        }
        return comments
    }

    private fun encode(tokens: List<RawSemanticToken>, document: SourceDocument): List<Int> {
        val data = mutableListOf<Int>()
        var previousLine = 0
        var previousStart = 0
        for (token in tokens) {
            val start = document.offsetToPosition(token.range.start)
            val end = document.offsetToPosition(token.range.end)
            val length = if (start.line == end.line) end.character - start.character else 1
            val deltaLine = start.line - previousLine
            val deltaStart = if (deltaLine == 0) start.character - previousStart else start.character
            data += deltaLine
            data += deltaStart
            data += length.coerceAtLeast(1)
            data += token.type
            data += token.modifiers
            previousLine = start.line
            previousStart = start.character
        }
        return data
    }

    private fun typeIndex(type: String): Int = tokenTypes.indexOf(type).coerceAtLeast(0)

    private fun modifier(name: String): Int {
        val index = tokenModifiers.indexOf(name)
        return if (index < 0) 0 else 1 shl index
    }

    private fun SourceRange.overlaps(other: SourceRange): Boolean =
        start < other.end && other.start < end

    private data class CommentRange(val range: SourceRange)
    private data class RawSemanticToken(val range: SourceRange, val type: Int, val modifiers: Int)
}
