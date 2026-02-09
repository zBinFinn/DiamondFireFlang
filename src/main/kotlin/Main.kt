package com.zbinfinn

import com.zbinfinn.ast.Ast
import com.zbinfinn.ast.Parser
import com.zbinfinn.compiler.FunctionResolver
import com.zbinfinn.compiler.GlobalFunctionTable
import com.zbinfinn.emitter.DfEmitter
import com.zbinfinn.ir.IrLowerer
import com.zbinfinn.nbt.TemplateNbtGenerator
import com.zbinfinn.stdlib.ImportContext
import com.zbinfinn.stdlib.StdlibAst
import com.zbinfinn.tokenizer.Tokenizer
import kotlin.io.path.Path
import kotlin.io.path.readText

fun main() {
    // TODO: I love hardcoding file paths
    val sourceFiles = listOf(
        Path("C:\\Users\\User\\Documents\\IntelliJ Projects\\DiamondFireFlang\\examples\\src\\funny.fl"),
        Path("C:\\Users\\User\\Documents\\IntelliJ Projects\\DiamondFireFlang\\examples\\src\\main.fl")
    )

    val programs = sourceFiles.map { path ->
        val code = path.readText()
        val tokens = Tokenizer(code).tokenize()
        Parser(tokens).parseProgram()
    }

    val globals = GlobalFunctionTable()
    programs.forEach { program ->
        globals.register(program)
    }
    registerAllStdlibAst(globals)

    println("registered functions: ")
    globals.allFunctions().forEach {
        println(it.qualifiedName)
    }

    val resolver = FunctionResolver(globals)

    val irPrograms = programs.map { program ->
        IrLowerer(program, globals, resolver).lowerProgram()
    }

    val emittedDf = irPrograms.joinToString(separator = "\n") { program ->
        DfEmitter().emit(program)
    };

    println("!! DF:")
    println(emittedDf)

    println("!! NBT:")
    val nbtGenerator = TemplateNbtGenerator(emittedDf)
    val generated = nbtGenerator.generate()
    generated.forEach {
        println(it)
        println("minecraft:ender_chest[minecraft:custom_data={PublicBukkitValues:{\"hypercube:codetemplatedata\":'${it}'}}]")
    }
}

fun registerAllStdlibAst(globals: GlobalFunctionTable) {
    for (std in StdlibAst.functions) {
        val modulePath = std.importPath.substringBeforeLast(".")
        val fnName = std.decl.name
        val qualifiedName = "$modulePath.$fnName"

        globals.registerFunction(
            modulePath = modulePath,
            function = std.decl,
            qualifiedName = qualifiedName
        )
    }
}