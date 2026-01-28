package com.zbinfinn.nbt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.rmi.UnexpectedException

class TemplateNbtGenerator(
    private val source: String
) {
    fun generate(): List<JsonObject> {
        return generateCode().mapIndexed { index, compressedCode ->
            val templateJson = JsonObject()
            templateJson.addProperty("name", "Flang - $index")
            templateJson.addProperty("author", "Flang")
            templateJson.addProperty("version", 1)
            templateJson.addProperty("code", compressedCode)

            templateJson
        }
    }

    private fun generateCode(): List<String> {
        return generateRaw().map {
            String(it.toString().toByteArray().toGzip().toBase64())
        }
    }

    private fun generateRaw(): List<JsonObject> {
        val toProcess = mutableListOf<String>()
        val processed = mutableListOf<JsonObject>()
        for (line in source.lines()) {
            if (line.isBlank()) {
                continue
            }

            val line = line.trim()

            if (line.equals("end")) {
                processed.add(process(toProcess))
                toProcess.clear()
            } else {
                toProcess.add(line)
            }
        }
        return processed
    }

    private fun process(toProcess: List<String>): JsonObject {
        val json = JsonObject()
        json.add("blocks", processBlocksArray(toProcess))
        return json
    }

    private fun processBlocksArray(toProcess: List<String>): JsonArray {
        val json = JsonArray()
        for (line in toProcess) {
            json.add(processBlock(line))
        }
        return json
    }

    private fun processBlock(line: String): JsonObject {
        val json = JsonObject()
        val starter = line.substringBefore(' ')
        val line = line.substringAfter(' ')
        when (starter) {
            "pe", "pa", "sv", "cf", "so" -> {
                json.addProperty("id", "block")
                json.addProperty("block", mapIdentifierToDfIdentifier(starter))
                json.add("args", processArgsAndTags(line))
                when (starter) {
                    "cf" -> json.addProperty("data", processAction(line))
                    "pe", "pa", "sv", "so" -> json.addProperty("action", processAction(line))
                    else -> throw UnexpectedException("Unknown starter $starter")
                }
            }
        }
        return json
    }

    /**
     * This gets `"SendMessage" [...]`
     */
    private fun processAction(line: String): String {
        val builder = StringBuilder()
        for (char in line.substringAfter('"')) {
            if (char == '"') {
                break
            }
            builder.append(char)
        }
        return builder.toString()
    }

    private fun processArgsAndTags(line: String): JsonObject {
        val jsonObject = JsonObject()
        val json = JsonArray()

        // TODO:

        jsonObject.add("items", json)
        return jsonObject
    }

    private fun mapIdentifierToDfIdentifier(identifier: String): String {
        return when (identifier) {
            "pe" -> "event"
            "pa" -> "player_action"
            "cf" -> "call_func"
            "so" -> "select_obj"
            "sv" -> "set_var"
            else -> throw UnexpectedException("Identifier $identifier not yet supported.")
        }
    }
}