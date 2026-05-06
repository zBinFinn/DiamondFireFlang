package com.zbinfinn.compiler

import com.zbinfinn.ast.Ast
import com.zbinfinn.ast.Parser
import com.zbinfinn.emitter.DfEmitter
import com.zbinfinn.ir.IrLowerer
import com.zbinfinn.tokenizer.Tokenizer
import com.zbinfinn.typecheck.TypeChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DictionaryMemberFunctionTest {

    @Test
    fun `parser accepts dict field mutability and impl functions`() {
        val program = parse(
            """
            mod main;
            dict Foo { var x: Number, val y: String }
            impl Foo {
                fn setX(var this, val x: Number) { this.x = x; }
                fn new(): Foo { return Foo { x: 0, y: "fresh" }; }
            }
            """.trimIndent()
        )

        assertEquals(true, program.dicts.single().fields[0].mutable)
        assertEquals(false, program.dicts.single().fields[1].mutable)
        assertEquals("Foo", program.impls.single().typeName)
        assertEquals(true, program.impls.single().functions[0].parameters[0].mutable)
        assertEquals("Foo", program.impls.single().functions[0].parameters[0].type.identifier)
        assertTrue(program.impls.single().functions[1].parameters.isEmpty())
    }

    @Test
    fun `parser rejects unprefixed dict fields`() {
        assertFailsWith<IllegalStateException> {
            parse(
                """
                mod main;
                dict Foo { x: Number }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `member mutability and field mutability are type checked`() {
        val diags = typeCheck(
            """
            mod main;
            dict Foo { var x: Number, val y: String }
            impl Foo {
                fn setX(var this, val x: Number) { this.x = x; }
                fn getX(val this): Number { return this.x; }
            }
            fn ok() {
                var foo = Foo { x: 1, y: "a" };
                foo.setX(2);
                val n = foo.getX();
            }
            fn badReceiver() {
                val foo = Foo { x: 1, y: "a" };
                foo.setX(2);
            }
            fn badField() {
                var foo = Foo { x: 1, y: "a" };
                foo.y = "b";
            }
            """.trimIndent()
        )

        assertTrue(diags.any { it.message.contains("requires a mutable receiver") })
        assertTrue(diags.any { it.message.contains("Cannot assign immutable field 'y'") })
    }

    @Test
    fun `static member works through type import`() {
        val funny = """
            mod funny;
            dict FunnyNess { var level: Number, val message: String }
            impl FunnyNess {
                fn new(): FunnyNess {
                    return FunnyNess { level: 0, message: "Really Funny" };
                }
            }
        """.trimIndent()
        val main = """
            mod main;
            import funny.FunnyNess;
            fn make(): FunnyNess {
                return FunnyNess.new();
            }
        """.trimIndent()

        val (programs, globals, typeTable, resolver) = compileInputs(funny, main)
        val diags = programs.flatMap { TypeChecker(globals, resolver, typeTable).check(it) }
        assertTrue(diags.isEmpty(), "Expected no type errors, got: ${diags.joinToString()}")

        val emitted = DfEmitter().emit(IrLowerer(programs[1], globals, resolver, typeTable).lowerProgram())
        assertTrue(emitted.contains("cf \"funny.FunnyNess.new\" args(vLI\"\$temp_0\")"))
    }

    @Test
    fun `member functions emit type-qualified DiamondFire names and receiver argument`() {
        val source = """
            mod main;
            dict Foo { var x: Number, val y: String }
            impl Foo {
                fn setX(var this, val x: Number) { this.x = x; }
                fn getX(val this): Number { return this.x; }
            }
            fn use() {
                var foo = Foo { x: 1, y: "a" };
                foo.setX(5);
                val n = foo.getX();
            }
        """.trimIndent()

        val (programs, globals, typeTable, resolver) = compileInputs(source)
        val diags = TypeChecker(globals, resolver, typeTable).check(programs.single())
        assertTrue(diags.isEmpty(), "Expected no type errors, got: ${diags.joinToString()}")

        val emitted = DfEmitter().emit(IrLowerer(programs.single(), globals, resolver, typeTable).lowerProgram())

        assertTrue(emitted.contains("fn \"main.Foo.setX\" args(pm\"this\", p\"x\")"))
        assertTrue(emitted.contains("fn \"main.Foo.getX\" args(p\"this\", pm\"\$return\")"))
        assertTrue(emitted.contains("cf \"main.Foo.setX\" args(vLI\"foo\", n\"5.0\")"))
        assertTrue(emitted.contains("cf \"main.Foo.getX\" args(vLI\"foo\", vLI\"\$temp_"))
    }

    private fun typeCheck(source: String) =
        compileInputs(source).let { (programs, globals, typeTable, resolver) ->
            TypeChecker(globals, resolver, typeTable).check(programs.single())
        }

    private data class CompileResult(
        val programs: List<Ast.Program>,
        val globals: GlobalFunctionTable,
        val typeTable: GlobalTypeTable,
        val resolver: FunctionResolver,
    )

    private fun compileInputs(vararg sources: String): CompileResult {
        val programs = sources.map(::parse)
        val globals = GlobalFunctionTable()
        programs.forEach { globals.register(it) }

        val typeTable = GlobalTypeTable()
        programs.forEach { typeTable.register(it) }

        val resolver = FunctionResolver(globals)
        return CompileResult(programs, globals, typeTable, resolver)
    }

    private fun parse(source: String) =
        Parser(Tokenizer(source).tokenize()).parseProgram()
}
