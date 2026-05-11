package com.zbinfinn.ir

import com.zbinfinn.ast.Parser
import com.zbinfinn.common.VariableScope
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

class NestedSelectionLoweringTest {
    @Test
    fun `nested with snapshots uuids and restores parent loop uuid`() {
        val emitted = compile(
            """
            mod main;
            import std.events.PlayerJoinEvent;

            @PlayerSelector
            fn outer() { val x = 1; }

            @PlayerSelector
            fn inner() { val x = 2; }

            @OnPlayerSelection
            fn handle() { val x = 3; }

            @Event(PlayerJoinEvent)
            fn join(var event: PlayerJoinEvent) {
                with outer() {
                    handle();
                    with inner() {
                        handle();
                    }
                    handle();
                }
            }
            """.trimIndent()
        )

        assertTrue(emitted.contains($$"""sv "=" args(vL"$selection_0", gv"Selection Target UUIDs")"""), emitted)
        assertTrue(emitted.contains($$"""sv "=" args(vL"$selection_1", gv"Selection Target UUIDs")"""), emitted)
        assertTrue(emitted.contains($$"""rp "ForEach" args(vL"$selection_uuid_0", vL"$selection_0") tags(26 "Allow List Changes" "False (copy list)")"""), emitted)
        assertTrue(emitted.contains($$"""rp "ForEach" args(vL"$selection_uuid_1", vL"$selection_1") tags(26 "Allow List Changes" "False (copy list)")"""), emitted)
        assertTrue(emitted.contains($$"""so "PlayerName" args(vL"$selection_uuid_0")"""), emitted)
        assertTrue(emitted.contains($$"""so "PlayerName" args(vL"$selection_uuid_1")"""), emitted)
        assertTrue(emitted.indexOf($$"""so "PlayerName" args(vL"$selection_uuid_0")""") <
            emitted.lastIndexOf($$"""so "PlayerName" args(vL"$selection_uuid_0")"""), emitted)
    }

    @Test
    fun `selection handler restores entry selection after its own nested with`() {
        val emitted = compile(
            """
            mod main;
            import std.events.PlayerJoinEvent;

            @PlayerSelector
            fn outer() { val x = 1; }

            @PlayerSelector
            fn inner() { val x = 2; }

            @OnPlayerSelection
            fn handle() { val x = 3; }

            @OnPlayerSelection
            fn nestedCall() {
                with inner() {
                    handle();
                }
                handle();
            }

            @Event(PlayerJoinEvent)
            fn join(var event: PlayerJoinEvent) {
                with outer() {
                    nestedCall();
                    handle();
                }
            }
            """.trimIndent()
        )

        val nestedCallStart = emitted.indexOf($$"""fn "main.nestedCall$onPlayerSelection"""")
        val entrySnapshot = emitted.indexOf($$"""sv "=" args(vL"$selection_entry", gv"Selection Target UUIDs")""", nestedCallStart)
        val entryRestore = emitted.indexOf($$"""so "PlayerName" args(vL"$selection_entry")""", entrySnapshot)
        val followingCall = emitted.indexOf($$"""cf "main.handle$onPlayerSelection"""", entryRestore)

        assertTrue(nestedCallStart >= 0, emitted)
        assertTrue(entrySnapshot > nestedCallStart, emitted)
        assertTrue(entryRestore > entrySnapshot, emitted)
        assertTrue(followingCall > entryRestore, emitted)
    }

    @Test
    fun `entity with restores using entity uuid selection action`() {
        val emitted = compile(
            """
            mod main;
            import std.events.PlayerJoinEvent;

            @EntitySelector
            fn entities() { val x = 1; }

            @OnEntitySelection
            fn handleEntity() { val x = 2; }

            @Event(PlayerJoinEvent)
            fn join(var event: PlayerJoinEvent) {
                with entities() {
                    handleEntity();
                }
            }
            """.trimIndent()
        )

        assertTrue(emitted.contains($$"""so "EntityUUID" args(vL"$selection_uuid_0")"""), emitted)
    }

    @Test
    fun `emitter supports local variables and repeat foreach`() {
        val emitted = DfEmitter().emit(
            Ir.Program(
                entryPoints = emptyList(),
                functions = listOf(
                    Ir.Function(
                        name = "main.test",
                        parameters = emptyList(),
                        body = listOf(
                            Ir.RepeatAction(
                                actionName = "ForEach",
                                args = listOf(
                                    Ir.Variable($$"$item", VariableScope.LOCAL),
                                    Ir.Variable($$"$items", VariableScope.LOCAL),
                                ),
                                tags = listOf(Ir.Tag(26, "Allow List Changes", "False (copy list)"))
                            )
                        )
                    )
                )
            )
        )

        assertTrue(emitted.contains($$"""rp "ForEach" args(vL"$item", vL"$items") tags(26 "Allow List Changes" "False (copy list)")"""), emitted)
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
