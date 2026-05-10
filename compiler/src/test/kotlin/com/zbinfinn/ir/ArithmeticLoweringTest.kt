package com.zbinfinn.ir

import com.zbinfinn.ast.Parser
import com.zbinfinn.compiler.FunctionResolver
import com.zbinfinn.compiler.GlobalFunctionTable
import com.zbinfinn.compiler.GlobalTypeTable
import com.zbinfinn.emitter.DfEmitter
import com.zbinfinn.tokenizer.Tokenizer
import com.zbinfinn.typecheck.TypeChecker
import kotlin.test.Test
import kotlin.test.assertTrue

class ArithmeticLoweringTest {

    @Test
    fun `arithmetic expressions lower to set variable actions`() {
        val code = """
            mod main;
            fn f() {
                val result = 1 + 2 * 3 / 4 - 5 ^ 2;
                val negative = -result;
            }
        """.trimIndent()

        val program = Parser(Tokenizer(code).tokenize()).parseProgram()
        val globals = GlobalFunctionTable()
        globals.register(program)
        val typeTable = GlobalTypeTable()
        typeTable.register(program)
        val resolver = FunctionResolver(globals)
        val diags = TypeChecker(globals, resolver, typeTable).check(program)
        assertTrue(diags.isEmpty(), "Expected no type errors, got: ${diags.joinToString()}")

        val emitted = DfEmitter().emit(IrLowerer(program, globals, resolver, typeTable).lowerProgram())

        assertTrue(emitted.contains("""sv "*" args(vLI"${'$'}temp_0", n"2.0", n"3.0")"""))
        assertTrue(emitted.contains("""sv "/" args(vLI"${'$'}temp_1", vLI"${'$'}temp_0", n"4.0")"""))
        assertTrue(emitted.contains("""sv "+" args(vLI"${'$'}temp_2", n"1.0", vLI"${'$'}temp_1")"""))
        assertTrue(emitted.contains("""sv "Exponent" args(vLI"${'$'}temp_3", n"5.0", n"2.0")"""))
        assertTrue(emitted.contains("""sv "-" args(vLI"${'$'}temp_4", vLI"${'$'}temp_2", vLI"${'$'}temp_3")"""))
        assertTrue(emitted.contains("""sv "=" args(vLI"result", vLI"${'$'}temp_4")"""))
        assertTrue(emitted.contains("""sv "-" args(vLI"${'$'}temp_5", n"0", vLI"result")"""))
        assertTrue(emitted.contains("""sv "=" args(vLI"negative", vLI"${'$'}temp_5")"""))
    }
}
