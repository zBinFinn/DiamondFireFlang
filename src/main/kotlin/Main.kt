package com.zbinfinn

import com.zbinfinn.ast.Ast
import com.zbinfinn.ast.Parser
import com.zbinfinn.compiler.FunctionResolver
import com.zbinfinn.compiler.GlobalFunctionTable
import com.zbinfinn.compiler.GlobalTypeTable
import com.zbinfinn.dump.ActionDump
import com.zbinfinn.emitter.DfEmitter
import com.zbinfinn.ir.IrLowerer
import com.zbinfinn.nbt.TemplateNbtGenerator
import com.zbinfinn.stdlib.ImportContext
import com.zbinfinn.stdlib.StdlibAst
import com.zbinfinn.tokenizer.Tokenizer
import com.zbinfinn.typecheck.TypeChecker
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.readText

fun main() {
    ActionDump.parse(File("C:\\Users\\User\\Documents\\IntelliJ Projects\\DiamondFireFlang\\src\\main\\resources\\action_dump.json"))
    println(ActionDump.get().actions)

    // TODO: I love hardcoding file paths
    val sourceFiles = listOf(
//        Path("C:\\Users\\User\\Documents\\IntelliJ Projects\\DiamondFireFlang\\examples\\src\\funny.fl"),
//        Path("C:\\Users\\User\\Documents\\IntelliJ Projects\\DiamondFireFlang\\examples\\src\\main.fl")
        Path("C:\\Users\\User\\Documents\\IntelliJ Projects\\DiamondFireFlang\\examples\\src\\numerics.fl")
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

    val typeTable = GlobalTypeTable()
    registerAllStdlibTypes(typeTable)
    programs.forEach { program ->
        typeTable.register(program)
    }

    println("registered functions: ")
    globals.allFunctions().forEach {
        println(it.qualifiedName)
    }

    val resolver = FunctionResolver(globals)

    val typeChecker = TypeChecker(
        globals = globals,
        functionResolver = resolver,
        typeTable = typeTable,
    )

    val typeErrors = programs.flatMap { program ->
        typeChecker.check(program)
    }

    if (typeErrors.isNotEmpty()) {
        typeErrors.forEach { println(it) }
        return
    }

    val irPrograms = programs.map { program ->
        IrLowerer(program, globals, resolver, typeTable).lowerProgram()
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
    for (program in StdlibAst.programs) {
        globals.register(program)
    }
}

fun registerAllStdlibTypes(typeTable: GlobalTypeTable) {
    for (program in StdlibAst.programs) {
        typeTable.register(program)
    }
}
