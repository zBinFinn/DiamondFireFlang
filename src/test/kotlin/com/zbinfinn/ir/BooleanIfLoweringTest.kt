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

class BooleanIfLoweringTest {

    @Test
    fun `if with boolean vars compiles to iv gates`() {
        val main = """
            mod main;
            
            import std.player.sendMessage;
            import std.player.selection.defaultPlayer;
            
            import funny.sendFunny;
            import funny.FunnyNess;
            
            @PlayerEvent("Join")
            fn join() {
                val test = true;
                val test2 = false;
                with defaultPlayer() {
                    sendMessage("Test 1");
                }
                if (test && test2) {
                    with defaultPlayer() {
                        sendMessage("test && test2");
                    }
                }
                if (test || test2) {
                    with defaultPlayer() {
                        sendMessage("test || test2");
                    }
                }
                if (true) {
                    with defaultPlayer() {
                        sendMessage("true");
                    }
                }
            }
        """.trimIndent()

        val funny = """
            mod funny;
            
            import std.player.sendMessage;
            
            dict FunnyNess { val level: Number, val message: String }
            
            @OnPlayerSelection
            fn sendFunny(val funny: FunnyNess) {
                sendMessage(funny.level);
                sendMessage(funny.message);
            }
        """.trimIndent()

        val programs = listOf(main, funny).map { code ->
            Parser(Tokenizer(code).tokenize()).parseProgram()
        }

        val globals = GlobalFunctionTable()
        programs.forEach { globals.register(it) }
        registerAllStdlibAst(globals)

        val typeTable = GlobalTypeTable()
        programs.forEach { typeTable.register(it) }

        val resolver = FunctionResolver(globals)
        val diags = programs.flatMap {
            TypeChecker(globals, resolver, typeTable).check(it)
        }
        assertTrue(diags.isEmpty(), "Expected no type errors, got: ${diags.joinToString()}")

        val emittedDf = programs.joinToString("\n") { program ->
            val ir = IrLowerer(program, globals, resolver, typeTable).lowerProgram()
            DfEmitter().emit(ir)
        }

        assertTrue(emittedDf.contains("iv NOT \"=\" args(n\"0\", vLI\"test\", vLI\"test2\")"))
        assertTrue(emittedDf.contains("iv \"=\" args(n\"1\", vLI\"test\", vLI\"test2\")"))
        assertTrue(emittedDf.contains("iv NOT \"=\" args(n\"0\", n\"1\")"))
    }
}
