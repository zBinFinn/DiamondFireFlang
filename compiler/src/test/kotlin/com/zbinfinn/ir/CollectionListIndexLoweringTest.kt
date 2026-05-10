package com.zbinfinn.ir

import com.zbinfinn.ast.Ast
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

class CollectionListIndexLoweringTest {
    @Test
    fun `list get set and remove translate zero based indices to DiamondFire one based indices`() {
        val emitted = emit(
            """
            mod main;

            fn use(var xs: List<Number>) {
                val first = xs.get(0);
                xs.set(0, 7);
                xs.remove(0);
            }
            """.trimIndent()
        )

        val getIndex = emitted.indexOf("""sv "GetListValue" args(vLI"${'$'}temp_0", vLI"xs", vLI"${'$'}translatedIndex")""")
        val setIndex = emitted.indexOf("""sv "SetListValue" args(vLI"xs", vLI"${'$'}translatedIndex", n"7.0")""")
        val removeIndex = emitted.indexOf("""sv "RemoveListIndex" args(vLI"xs", vLI"${'$'}translatedIndex")""")

        assertTrue(getIndex > 0, emitted)
        assertTrue(setIndex > 0, emitted)
        assertTrue(removeIndex > 0, emitted)
        assertTrue(hasTranslationBefore(emitted, getIndex), emitted)
        assertTrue(hasTranslationBefore(emitted, setIndex), emitted)
        assertTrue(hasTranslationBefore(emitted, removeIndex), emitted)
    }

    private fun hasTranslationBefore(emitted: String, actionIndex: Int): Boolean {
        val translation = """sv "+" args(vLI"${'$'}translatedIndex", n"0.0", n"1")"""
        val translationIndex = emitted.lastIndexOf(translation, actionIndex)
        return translationIndex >= 0
    }

    private fun emit(source: String): String {
        val program = Parser(Tokenizer(source).tokenize()).parseProgram()
        val globals = GlobalFunctionTable()
        globals.register(program)
        registerAllStdlibAst(globals)

        val typeTable = GlobalTypeTable()
        registerAllStdlibTypes(typeTable)
        typeTable.register(program)

        val resolver = FunctionResolver(globals)
        val diags = TypeChecker(globals, resolver, typeTable).check(program)
        assertTrue(diags.isEmpty(), "Expected no type errors, got: ${diags.joinToString()}")

        return DfEmitter().emit(IrLowerer(program, globals, resolver, typeTable).lowerProgram())
    }
}
