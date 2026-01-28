package com.zbinfinn

import com.zbinfinn.ast.Parser
import com.zbinfinn.emitter.DfEmitter
import com.zbinfinn.ir.Ir
import com.zbinfinn.ir.IrLowerer
import com.zbinfinn.tokenizer.Tokenizer

fun main() {
    val testCode = """
        import hello.world;
        
        @PlayerEvent("Join")
        fn join() {
            val x = 5;
        }
    """.trimIndent()

    val tokenizer = Tokenizer(testCode)
    val tokens = tokenizer.tokenize()
    println("!! Tokens:")
    tokens.forEach { token ->
        println(token.toString())
    }

    println()

    println("!! AST:")
    val parser = Parser(tokens)
    val program = parser.parseProgram()
    println("Imports: ")
    program.imports.forEach { println(it) }
    println()
    println("Function Decls:")
    program.functions.forEach { function ->
        println("Function (${function.name}):")
        println("Annotations:")
        function.annotations.forEach { println(it) }
        println("Statements:")
        function.body.statements.forEach { println(it) }
        println("")
    }

    println()

    val lowerer = IrLowerer(program)
    val irProgram = lowerer.lowerProgram()
    println("!! IR Program:")
    println("Events:")
    irProgram.entryPoints.forEach { entry ->
        when (entry) {
            is Ir.PlayerEvent -> {
                println("Player Event: ${entry.eventName}")
                entry.body.forEach { println(it) }
            }
            is Ir.EntityEvent -> {
                println("Entity Event: ${entry.eventName}")
                entry.body.forEach { println(it) }
            }
        }
        println()
    }
    println("Functions:")
    irProgram.functions.forEach { function ->
        println("Function (${function.name}):")
        function.body.forEach { println(it) }
    }

    println()

    val emitter = DfEmitter()
    val df = emitter.emit(irProgram)

    println("!! Emitted:")
    println(df)
}