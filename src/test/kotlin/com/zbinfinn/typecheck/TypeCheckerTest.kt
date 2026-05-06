package com.zbinfinn.typecheck

import com.zbinfinn.ast.Parser
import com.zbinfinn.compiler.FunctionResolver
import com.zbinfinn.compiler.GlobalFunctionTable
import com.zbinfinn.compiler.GlobalTypeTable
import com.zbinfinn.registerAllStdlibAst
import com.zbinfinn.tokenizer.Tokenizer
import kotlin.test.Test
import kotlin.test.assertTrue

class TypeCheckerTest {

    private fun runTypeCheck(vararg sources: String): List<Diagnostic> {
        val programs = sources.map { code ->
            Parser(Tokenizer(code).tokenize()).parseProgram()
        }

        val globals = GlobalFunctionTable()
        programs.forEach { globals.register(it) }
        registerAllStdlibAst(globals)

        val typeTable = GlobalTypeTable()
        programs.forEach { typeTable.register(it) }

        val resolver = FunctionResolver(globals)
        val checker = TypeChecker(
            globals = globals,
            functionResolver = resolver,
            typeTable = typeTable,
        )

        return programs.flatMap { checker.check(it) }
    }

    @Test
    fun `sendMessage accepts Number (since it accepts Any)`() {
        val diags = runTypeCheck(
            """
            mod main;
            import std.player.sendMessage;
            import std.player.selection.defaultPlayer;

            @PlayerEvent("Join")
            fn join() {
                with defaultPlayer() {
                    sendMessage(5);
                }
            }
            """.trimIndent()
        )

        assertTrue(diags.isEmpty(), "Expected no type errors, got: ${diags.joinToString()}")
    }

    @Test
    fun `Any is not assignable to String`() {
        val diags = runTypeCheck(
            """
            mod main;
            fn f(x: String) {}
            fn g(a: Any) { f(a); }
            """.trimIndent()
        )

        assertTrue(diags.any { it.message.contains("expects String") || it.message.contains("Argument") })
    }

    @Test
    fun `field access on Any is rejected`() {
        val diags = runTypeCheck(
            """
            mod main;
            dict Foo { x: Number }
            fn g(a: Any) {
                val y = a.x;
            }
            """.trimIndent()
        )

        assertTrue(diags.any { it.message.contains("type Any") && it.message.contains("access field") })
    }

    @Test
    fun `dict literal reports unknown missing and mismatch fields`() {
        val diags = runTypeCheck(
            """
            mod main;
            dict Foo { a: Number, b: String }
            fn t() {
                val x = Foo { a: "nope", c: 3 };
            }
            """.trimIndent()
        )

        assertTrue(diags.size >= 3, "Expected multiple diagnostics, got: ${diags.joinToString()}")
        assertTrue(diags.any { it.message.contains("Unknown field 'c'") })
        assertTrue(diags.any { it.message.contains("Missing required field 'b'") })
        assertTrue(diags.any { it.message.contains("Field 'a' expects") })
    }

    @Test
    fun `wrong arity is rejected`() {
        val diags = runTypeCheck(
            """
            mod main;
            fn f(x: Number, y: Number) {}
            fn g() { f(1); }
            """.trimIndent()
        )

        assertTrue(diags.any { it.message.contains("expects 2 argument") })
    }

    @Test
    fun `if condition must be Boolean`() {
        val diags = runTypeCheck(
            """
            mod main;
            fn f() { if (5) { } }
            """.trimIndent()
        )

        assertTrue(diags.any { it.message.contains("If condition must be Boolean") })
    }

    @Test
    fun `returning function can be used as expression`() {
        val diags = runTypeCheck(
            """
            mod main;
            fn get5(): Number { return 5; }
            fn f() {
                val x = get5();
            }
            """.trimIndent()
        )

        assertTrue(diags.isEmpty(), "Expected no type errors, got: ${diags.joinToString()}")
    }

    @Test
    fun `return type mismatch is rejected`() {
        val diags = runTypeCheck(
            """
            mod main;
            fn get5(): Number { return "five"; }
            """.trimIndent()
        )

        assertTrue(diags.any { it.message.contains("Cannot return String") })
    }

    @Test
    fun `returning function must return on every path`() {
        val diags = runTypeCheck(
            """
            mod main;
            fn maybe(test: Boolean): Number {
                if (test) {
                    return 1;
                }
            }
            """.trimIndent()
        )

        assertTrue(diags.any { it.message.contains("must return a value on every path") })
    }

    @Test
    fun `if else returning on both branches satisfies every path check`() {
        val diags = runTypeCheck(
            """
            mod main;
            fn pick(test: Boolean): Number {
                if (test) {
                    return 1;
                } else {
                    return 2;
                }
            }
            """.trimIndent()
        )

        assertTrue(diags.isEmpty(), "Expected no type errors, got: ${diags.joinToString()}")
    }

    @Test
    fun `void function cannot be used as expression`() {
        val diags = runTypeCheck(
            """
            mod main;
            fn sideEffect() {}
            fn f() {
                val x = sideEffect();
            }
            """.trimIndent()
        )

        assertTrue(diags.any { it.message.contains("does not return a value") })
    }
}
