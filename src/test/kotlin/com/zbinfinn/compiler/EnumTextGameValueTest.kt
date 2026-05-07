package com.zbinfinn.compiler

import com.zbinfinn.ast.Ast
import com.zbinfinn.ast.Parser
import com.zbinfinn.emitter.DfEmitter
import com.zbinfinn.ir.IrLowerer
import com.zbinfinn.nbt.TemplateNbtGenerator
import com.zbinfinn.registerAllStdlibAst
import com.zbinfinn.registerAllStdlibTypes
import com.zbinfinn.tokenizer.Tokenizer
import com.zbinfinn.typecheck.TypeChecker
import kotlin.test.Test
import kotlin.test.assertTrue

class EnumTextGameValueTest {
    @Test
    fun `enum cases store strings and expose name and ordinal helpers`() {
        val emitted = emit(
            """
            mod main;
            enum Test { AAA = "Hello World", BBB = "Bello World", CCC, }

            fn use(val t: Test) {
                val aaa = Test.AAA;
                val ccc = Test.CCC;
                val staticName = Test.BBB.name();
                val staticOrdinal = Test.BBB.ordinal();
                val name = t.name();
                val ordinal = t.ordinal();
            }
            """.trimIndent()
        )

        assertTrue(emitted.contains("""sv "=" args(vLI"aaa", s"Hello World")"""), emitted)
        assertTrue(emitted.contains("""sv "=" args(vLI"ccc", s"CCC")"""), emitted)
        assertTrue(emitted.contains("""sv "=" args(vLI"staticName", s"BBB")"""), emitted)
        assertTrue(emitted.contains("""sv "=" args(vLI"staticOrdinal", n"1")"""), emitted)
        assertTrue(emitted.contains("""sv "=" args(vLI"${'$'}temp_0", s"<invalid>")"""), emitted)
        assertTrue(emitted.contains("""iv "=" args(vLI"t", s"Hello World")"""), emitted)
        assertTrue(emitted.contains("""sv "=" args(vLI"${'$'}temp_0", s"AAA")"""), emitted)
        assertTrue(emitted.contains("""sv "=" args(vLI"${'$'}temp_0", s"BBB")"""), emitted)
        assertTrue(emitted.contains("""sv "=" args(vLI"${'$'}temp_0", s"CCC")"""), emitted)
        assertTrue(emitted.contains("""sv "=" args(vLI"${'$'}temp_1", n"-1")"""), emitted)
        assertTrue(emitted.contains("""sv "=" args(vLI"${'$'}temp_1", n"0")"""), emitted)
        assertTrue(emitted.contains("""sv "=" args(vLI"${'$'}temp_1", n"1")"""), emitted)
        assertTrue(emitted.contains("""sv "=" args(vLI"${'$'}temp_1", n"2")"""), emitted)
    }

    @Test
    fun `enum shorthand uses argument context and fails without context`() {
        val ok = check(
            """
            mod main;
            enum Direction { NORTH, EAST }
            fn turn(val dir: Direction) {}
            fn use() { turn(.NORTH); }
            """.trimIndent()
        )
        assertTrue(ok.isEmpty(), ok.joinToString())

        val bad = check(
            """
            mod main;
            enum Direction { NORTH, EAST }
            fn use() { val dir = .NORTH; }
            """.trimIndent()
        )
        assertTrue(bad.any { it.message.contains("requires an enum context") }, bad.joinToString())
    }

    @Test
    fun `text literals are distinct from strings`() {
        val ok = check(
            """
            mod main;
            fn textOnly(val text: Text) {}
            fn use() { textOnly(s"<green>Hello"); }
            """.trimIndent()
        )
        assertTrue(ok.isEmpty(), ok.joinToString())

        val bad = check(
            """
            mod main;
            fn textOnly(val text: Text) {}
            fn use() { textOnly("Hello"); }
            """.trimIndent()
        )
        assertTrue(bad.any { it.message.contains("expects Text") }, bad.joinToString())
    }

    @Test
    fun `game values infer return types and emit game value values`() {
        val typeErrors = check(
            """
            mod main;
            fn takesText(val value: Text) {}
            fn takesString(val value: String) {}
            fn use() {
                takesText(gval("Name ", .DEFAULT));
                takesString(gval("UUID", .DEFAULT));
                takesString(gval("Name ", .DEFAULT));
            }
            """.trimIndent()
        )
        assertTrue(typeErrors.any { it.message.contains("expects String") && it.message.contains("Text") }, typeErrors.joinToString())

        val emitted = emit(
            """
            mod main;
            fn use() {
                val loc = gval("Location", .DEFAULT);
                val time = gval("Timestamp");
                val styled = s"<green>Hello";
            }
            """.trimIndent()
        )
        assertTrue(emitted.contains("""gvT"Location|Default""""))
        assertTrue(emitted.contains("""gv"Timestamp""""))
        assertTrue(emitted.contains("""t"<green>Hello""""))

        val raw = TemplateNbtGenerator(emitted).generateRaw().first().toString()
        assertTrue(raw.contains("g_val"), raw)
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
        val program: Ast.Program,
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
