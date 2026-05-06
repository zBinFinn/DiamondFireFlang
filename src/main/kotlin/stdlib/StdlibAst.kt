package com.zbinfinn.stdlib

import com.zbinfinn.ast.Ast
import com.zbinfinn.ast.Parser
import com.zbinfinn.tokenizer.Tokenizer
import java.io.File
import java.net.JarURLConnection

object StdlibAst {
    val programs: List<Ast.Program> by lazy {
        loadResourcePrograms()
    }

    private fun loadResourcePrograms(): List<Ast.Program> {
        val resources = mutableListOf<Pair<String, String>>()
        val classLoader = Thread.currentThread().contextClassLoader ?: StdlibAst::class.java.classLoader
        val root = classLoader.getResource("stdlib")

        if (root != null) {
            when (root.protocol) {
                "file" -> {
                    File(root.toURI()).walkTopDown()
                        .filter { it.isFile && it.extension == "fl" }
                        .forEach { resources += it.path to it.readText() }
                }

                "jar" -> {
                    val connection = root.openConnection() as JarURLConnection
                    val jar = connection.jarFile
                    jar.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("stdlib/") && it.name.endsWith(".fl") }
                        .forEach { entry ->
                            resources += entry.name to jar.getInputStream(entry).bufferedReader().use { it.readText() }
                        }
                }
            }
        }

        val devRoot = File("src/main/resources/stdlib")
        if (resources.isEmpty() && devRoot.exists()) {
            devRoot.walkTopDown()
                .filter { it.isFile && it.extension == "fl" }
                .forEach { resources += it.path to it.readText() }
        }

        return resources
            .sortedBy { it.first }
            .map { (_, code) -> Parser(Tokenizer(code).tokenize()).parseProgram() }
    }
}
