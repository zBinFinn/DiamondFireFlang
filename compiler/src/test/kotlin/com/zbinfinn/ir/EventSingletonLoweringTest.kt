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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventSingletonLoweringTest {

    @Test
    fun `event singleton lowers to event and internal event action`() {
        val emitted = compile(
            """
            mod main;
            import std.events.PlayerJoinEvent;
            @Event(PlayerJoinEvent)
            fn join(var event: PlayerJoinEvent) {
                event.setCancelled(true);
            }
            """.trimIndent()
        )

        assertTrue(emitted.contains("pe \"Join\""))
        assertTrue(emitted.contains("iv \"=\" args(n\"1\", n\"1\")"))
        assertTrue(emitted.contains("ga \"CancelEvent\""))
        assertTrue(emitted.contains("ga \"UncancelEvent\""))
        assertFalse(emitted.contains("args(pm\"event\")"))
    }

    @Test
    fun `setCancelled branches on runtime boolean values`() {
        val emitted = compile(
            """
            mod main;
            import std.events.PlayerJoinEvent;
            @Event(PlayerJoinEvent)
            fn join(var event: PlayerJoinEvent) {
                var cancelled = true;
                event.setCancelled(cancelled);
            }
            """.trimIndent()
        )

        assertTrue(emitted.contains("iv \"=\" args(n\"1\", vLI\"cancelled\")"))
        assertTrue(emitted.contains("ga \"CancelEvent\""))
        assertTrue(emitted.contains("else"))
        assertTrue(emitted.contains("ga \"UncancelEvent\""))
    }

    @Test
    fun `singleton parameters are omitted from runtime function calls`() {
        val emitted = compile(
            """
            mod main;
            import std.events.PlayerJoinEvent;
            @Event(PlayerJoinEvent)
            fn join(var event: PlayerJoinEvent) {
                helper(event);
                helper(PlayerJoinEvent);
            }
            fn helper(value: PlayerJoinEvent) {}
            """.trimIndent()
        )

        assertTrue(emitted.contains("fn \"main.helper\""))
        assertTrue(emitted.contains("cf \"main.helper\""))
        assertFalse(emitted.contains("fn \"main.helper\" args("))
    }

    @Test
    fun `stdlib player singleton selection member lowers with role suffix`() {
        val emitted = compile(
            """
            mod main;
            import std.events.PlayerJoinEvent;
            import std.player.Player;
            import std.player.selection.defaultPlayer;

            @Event(PlayerJoinEvent)
            fn join(var event: PlayerJoinEvent) {
                with defaultPlayer() {
                    val name = Player.getName();
                }
            }
            """.trimIndent()
        )

        assertTrue(emitted.contains("fn \"std.player.Player.getName\$onPlayerSelection\""))
        assertTrue(emitted.contains("cf \"std.player.Player.getName\$onPlayerSelection\""))
        assertTrue(emitted.contains("gvT\"Name |Selection\""))
    }

    private fun compile(source: String): String {
        val program = Parser(Tokenizer(source).tokenize()).parseProgram()
        val globals = GlobalFunctionTable()
        registerAllStdlibAst(globals)
        globals.register(program)
        val typeTable = GlobalTypeTable()
        registerAllStdlibTypes(typeTable)
        typeTable.register(program)
        val resolver = FunctionResolver(globals)
        val diags = TypeChecker(globals, resolver, typeTable).check(program)
        assertTrue(diags.isEmpty(), "Expected no type errors, got: ${diags.joinToString()}")
        return DfEmitter().emit(IrLowerer(program, globals, resolver, typeTable).lowerProgram())
    }
}
