package com.zbinfinn.compiler

import com.zbinfinn.ast.Parser
import com.zbinfinn.emitter.DfEmitter
import com.zbinfinn.ir.IrLowerer
import com.zbinfinn.registerAllStdlibTypes
import com.zbinfinn.tokenizer.Tokenizer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FunctionOverloadRulesTest {

    @Test
    fun `player and entity selection handlers may share a name`() {
        val program = parse(
            """
            mod main;
            @OnPlayerSelection
            fn handle() { val x = 1; }
            @OnEntitySelection
            fn handle() { val x = 2; }
            """.trimIndent()
        )

        GlobalFunctionTable().register(program)
    }

    @Test
    fun `plain function cannot share a name with selection handlers`() {
        val program = parse(
            """
            mod main;
            fn handle() {}
            @OnPlayerSelection
            fn handle() { val x = 1; }
            """.trimIndent()
        )

        assertFailsWith<IllegalStateException> {
            GlobalFunctionTable().register(program)
        }
    }

    @Test
    fun `same function kind cannot share a name`() {
        val program = parse(
            """
            mod main;
            @Event(MissingEvent)
            fn handle() {}
            fn handle() {}
            """.trimIndent()
        )

        assertFailsWith<IllegalStateException> {
            GlobalFunctionTable().register(program)
        }
    }

    @Test
    fun `singleton member functions may use function role annotations`() {
        val program = parse(
            """
            mod main;
            singleton Player {
                @OnPlayerSelection
                fn getName(): Text {
                    return gval("Name", .SELECTION);
                }
            }
            """.trimIndent()
        )

        GlobalFunctionTable().register(program)
    }

    @Test
    fun `selection overloads emit unique DiamondFire names and calls`() {
        val program = parse(
            """
            mod main;
            import std.events.PlayerJoinEvent;
            @PlayerSelector
            fn pick() { val x = 1; }
            @OnPlayerSelection
            fn handle() { val x = 2; }
            @OnEntitySelection
            fn handle() { val x = 3; }
            @Event(PlayerJoinEvent)
            fn join(var event: PlayerJoinEvent) {
                with pick() {
                    handle();
                }
            }
            """.trimIndent()
        )

        val globals = GlobalFunctionTable()
        com.zbinfinn.registerAllStdlibAst(globals)
        globals.register(program)
        val resolver = FunctionResolver(globals)
        val typeTable = GlobalTypeTable()
        registerAllStdlibTypes(typeTable)
        typeTable.register(program)

        val emitted = DfEmitter().emit(IrLowerer(program, globals, resolver, typeTable).lowerProgram())

        assertTrue(emitted.contains("fn \"main.pick\$playerSelector\""))
        assertTrue(emitted.contains("fn \"main.handle\$onPlayerSelection\""))
        assertTrue(emitted.contains("fn \"main.handle\$onEntitySelection\""))
        assertTrue(emitted.contains("cf \"main.pick\$playerSelector\""))
        assertTrue(emitted.contains("cf \"main.handle\$onPlayerSelection\""))
    }

    private fun parse(source: String) =
        Parser(Tokenizer(source).tokenize()).parseProgram()
}
