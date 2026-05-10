package com.zbinfinn.ir

import com.zbinfinn.ast.Parser
import com.zbinfinn.compiler.FunctionResolver
import com.zbinfinn.compiler.GlobalFunctionTable
import com.zbinfinn.compiler.GlobalTypeTable
import com.zbinfinn.emitter.DfEmitter
import com.zbinfinn.registerAllStdlibAst
import com.zbinfinn.tokenizer.Tokenizer
import com.zbinfinn.typecheck.TypeChecker
import kotlin.test.Test
import kotlin.test.assertTrue

class ReturnLoweringTest {

    @Test
    fun `returning function lowers through mutable parameter and return control block`() {
        val source = """
            mod main;
            fn get5(): Number {
                return 5;
            }
            fn use() {
                val x = get5();
            }
        """.trimIndent()

        val program = Parser(Tokenizer(source).tokenize()).parseProgram()
        val globals = GlobalFunctionTable()
        globals.register(program)
        registerAllStdlibAst(globals)

        val typeTable = GlobalTypeTable()
        typeTable.register(program)

        val resolver = FunctionResolver(globals)
        val diags = TypeChecker(globals, resolver, typeTable).check(program)
        assertTrue(diags.isEmpty(), "Expected no type errors, got: ${diags.joinToString()}")

        val emitted = DfEmitter().emit(IrLowerer(program, globals, resolver).lowerProgram())

        assertTrue(emitted.contains("fn \"main.get5\" args(pm\"\$return\")"))
        assertTrue(emitted.contains("sv \"=\" args(vLI\"\$return\", n\"5.0\")"))
        assertTrue(emitted.contains("ctrl \"Return\""))
        assertTrue(emitted.contains("cf \"main.get5\" args(vLI\"\$temp_0\")"))
        assertTrue(emitted.contains("sv \"=\" args(vLI\"x\", vLI\"\$temp_0\")"))
    }

    @Test
    fun `explicit parameter mutability controls emitted parameter kind and local reassignment`() {
        val source = """
            mod main;
            fn mutate(var mutable: Number, val immutable: Number) {
                mutable = immutable;
            }
        """.trimIndent()

        val program = Parser(Tokenizer(source).tokenize()).parseProgram()
        val globals = GlobalFunctionTable()
        globals.register(program)
        registerAllStdlibAst(globals)

        val typeTable = GlobalTypeTable()
        typeTable.register(program)

        val resolver = FunctionResolver(globals)
        val diags = TypeChecker(globals, resolver, typeTable).check(program)
        assertTrue(diags.isEmpty(), "Expected no type errors, got: ${diags.joinToString()}")

        val emitted = DfEmitter().emit(IrLowerer(program, globals, resolver).lowerProgram())

        assertTrue(emitted.contains("fn \"main.mutate\" args(pm\"mutable\", p\"immutable\")"))
        assertTrue(emitted.contains("sv \"=\" args(vLI\"mutable\", vLI\"immutable\")"))
    }
}
