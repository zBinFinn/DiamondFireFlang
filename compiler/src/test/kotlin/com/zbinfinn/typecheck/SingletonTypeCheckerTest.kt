package com.zbinfinn.typecheck

import com.zbinfinn.ast.Parser
import com.zbinfinn.compiler.FunctionResolver
import com.zbinfinn.compiler.GlobalFunctionTable
import com.zbinfinn.compiler.GlobalTypeTable
import com.zbinfinn.tokenizer.Tokenizer
import kotlin.test.Test
import kotlin.test.assertTrue

class SingletonTypeCheckerTest {

    @Test
    fun `singletons can be assigned passed returned and used as receivers`() {
        val diags = check(
            """
            mod main;
            singleton Example {
                internal fn read(val this): Boolean;
            }
            fn id(value: Example): Example {
                return value;
            }
            fn f() {
                val local = Example;
                id(local);
                id(Example);
                val returned = id(local);
                returned.read();
            }
            """.trimIndent()
        )

        assertTrue(diags.isEmpty(), "Expected no type errors, got: ${diags.joinToString()}")
    }

    @Test
    fun `different singleton types are not assignable`() {
        val diags = check(
            """
            mod main;
            singleton A {}
            singleton B {}
            fn takesA(value: A) {}
            fn f() {
                takesA(B);
            }
            """.trimIndent()
        )

        assertTrue(diags.any { it.message.contains("expects main.A") })
    }

    @Test
    fun `mutable singleton members require mutable receiver`() {
        val diags = check(
            """
            mod main;
            singleton Example {
                internal fn mutate(var this);
            }
            fn f() {
                val local = Example;
                local.mutate();
            }
            """.trimIndent()
        )

        assertTrue(diags.any { it.message.contains("requires a mutable receiver") })
    }

    @Test
    fun `event parameter must match event singleton`() {
        val diags = check(
            """
            mod main;
            @PlayerEventProvider("Join")
            singleton JoinEvent {}
            singleton Other {}
            @Event(JoinEvent)
            fn join(var event: Other) {}
            """.trimIndent()
        )

        assertTrue(diags.any { it.message.contains("parameter must be JoinEvent") })
    }

    private fun check(source: String): List<Diagnostic> {
        val program = Parser(Tokenizer(source).tokenize()).parseProgram()
        val globals = GlobalFunctionTable()
        globals.register(program)
        val typeTable = GlobalTypeTable()
        typeTable.register(program)
        val resolver = FunctionResolver(globals)
        return TypeChecker(globals, resolver, typeTable).check(program)
    }
}
