package com.zbinfinn.ir

import com.zbinfinn.ast.Parser
import com.zbinfinn.compiler.FunctionResolver
import com.zbinfinn.compiler.GlobalFunctionTable
import com.zbinfinn.compiler.GlobalTypeTable
import com.zbinfinn.emitter.DfEmitter
import com.zbinfinn.registerAllStdlibAst
import com.zbinfinn.registerAllStdlibTypes
import com.zbinfinn.tokenizer.Tokenizer
import com.zbinfinn.typecheck.TypeChecker
import kotlin.test.Test
import kotlin.test.assertTrue

class CollectionDictionaryLoweringTest {
    @Test
    fun `dictionary methods type check and emit DiamondFire dictionary actions`() {
        val emitted = emit(
            """
            mod main;

            fn use() {
                var scores = Dictionary<Number>.new();
                scores.set("kills", 5);
                val kills = scores.get("kills");
                val hasKills = scores.has("kills");
                val count = scores.size();
                scores.remove("kills");
            }
            """.trimIndent()
        )

        assertTrue(emitted.contains("""sv "CreateDict" args(vLI"${'$'}temp_0")"""), emitted)
        assertTrue(emitted.contains("""sv "SetDictValue" args(vLI"scores", s"kills", n"5.0")"""), emitted)
        assertTrue(emitted.contains("""sv "GetDictValue" args(vLI"${'$'}temp_1", vLI"scores", s"kills")"""), emitted)
        assertTrue(emitted.contains("""iv "DictHasKey" args(vLI"scores", s"kills")"""), emitted)
        assertTrue(emitted.contains("""sv "GetDictSize" args(vLI"${'$'}temp_3", vLI"scores")"""), emitted)
        assertTrue(emitted.contains("""sv "RemoveDictEntry" args(vLI"scores", s"kills")"""), emitted)
    }

    @Test
    fun `dictionary keys must be strings and values use generic type`() {
        val diags = check(
            """
            mod main;

            fn use() {
                var scores = Dictionary<Number>.new();
                scores.set(1, 5);
                scores.set("kills", "five");
            }
            """.trimIndent()
        )

        assertTrue(diags.any { it.message.contains("expects String") }, diags.joinToString())
        assertTrue(diags.any { it.message.contains("expects Number") }, diags.joinToString())
    }

    private fun emit(source: String): String {
        val (program, globals, typeTable, resolver) = compile(source)
        val diags = TypeChecker(globals, resolver, typeTable).check(program)
        assertTrue(diags.isEmpty(), "Expected no type errors, got: ${diags.joinToString()}")
        return DfEmitter().emit(IrLowerer(program, globals, resolver, typeTable).lowerProgram())
    }

    private fun check(source: String) =
        compile(source).let { (program, globals, typeTable, resolver) ->
            TypeChecker(globals, resolver, typeTable).check(program)
        }

    private data class CompileResult(
        val program: com.zbinfinn.ast.Ast.Program,
        val globals: GlobalFunctionTable,
        val typeTable: GlobalTypeTable,
        val resolver: FunctionResolver,
    )

    private fun compile(source: String): CompileResult {
        val program = Parser(Tokenizer(source).tokenize()).parseProgram()
        val globals = GlobalFunctionTable()
        globals.register(program)
        registerAllStdlibAst(globals)

        val typeTable = GlobalTypeTable()
        registerAllStdlibTypes(typeTable)
        typeTable.register(program)

        val resolver = FunctionResolver(globals)
        return CompileResult(program, globals, typeTable, resolver)
    }
}
