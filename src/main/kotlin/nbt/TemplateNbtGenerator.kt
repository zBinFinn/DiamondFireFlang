package com.zbinfinn.nbt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.rmi.UnexpectedException
import java.util.regex.Pattern

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

    private val lineRegex = Pattern.compile(
        """^(?<type>\w+)\s+"(?<action>[^"]+)"(?:\s+args\((?<args>[^)]*)\))?\s*(?:tags\((?<tags>.*)\))?$"""
    )

    private fun processBlock(line: String): JsonObject {
        val json = JsonObject()
        val matcher = lineRegex.matcher(line)
        if (!matcher.find()) {
            error("Regex did not work on: $line")
        }

        val type = matcher.group("type")
        val action = matcher.group("action")
        val args: String? = matcher.group("args")
        val tags: String? = matcher.group("tags")
        println("Currently Processing: $type $action: $args | $tags")
        when (type) {
            "pe", "pa", "sv", "cf", "so", "sp", "fn", "pr" -> {
                json.addProperty("id", "block")
                json.addProperty("block", mapIdentifierToDfIdentifier(type))
                json.add("args", processArgsAndTags(args, tags, mapIdentifierToDfIdentifier(type), if (setOf("fn", "pr", "sp", "cf").contains(type)) { "dynamic" } else { action } ))
                when (type) {
                    "cf", "sp", "fn", "pr" -> json.addProperty("data", action)
                    "pe", "pa", "sv", "so" -> json.addProperty("action", action)
                    else -> throw UnexpectedException("Unknown starter $type")
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

    /*
    vLI"Hello", s"Hello World"
    or
    26 "Name" "SelectedOption", [...]
     */
    private val argsRegex = Pattern.compile("""(?<type>\w+)"(?<data>[^"]+)"\s*(?:,|$)""")
    private val tagsRegex = Pattern.compile("""(?<slot>\d+)\s"(?<name>[^"]+)"\s"(?<option>[^"]+)"(?:,\s*|$)""")
    private fun processArgsAndTags(args: String?, tags: String?, actionType: String, actionName: String): JsonObject {
        val jsonObject = JsonObject()
        val json = JsonArray()

        if (args != null) {
            val matcher = argsRegex.matcher(args)
            var slot = 0
            while (matcher.find()) {
                val type = matcher.group("type")
                val data = matcher.group("data")
                json.add(processArg(slot, type, data))
                slot++
            }
        }

        if (tags != null) {
            val matcher = tagsRegex.matcher(tags)
            while (matcher.find()) {
                val name = matcher.group("name")
                val option = matcher.group("option")
                val slot = matcher.group("slot").toInt()
                println("Looking at tag: $name $option $slot")
                json.add(processTag(slot, name, option, actionType, actionName))
            }
        }

        // TODO: Tags

        jsonObject.add("items", json)
        return jsonObject
    }

    private fun processArg(slot: Int, type: String, content: String): JsonObject {
        val fullJson = JsonObject()
        val json = JsonObject()
        json.addProperty("id", when(type) {
            "vLI" -> "var"
            "s" -> "txt"
            "t" -> "comp"
            "n" -> "num"
            "p", "pm" -> "pn_el"
            else -> error("Unknown type $type")
        })

        val data = JsonObject()
        when(type) {
            "vLI" -> data.addProperty("scope", "line")
            "p" -> {
                data.addProperty("type", "any")
                data.addProperty("plural", false)
                data.addProperty("optional", false)
            }
            "pm" -> {
                data.addProperty("type", "var")
                data.addProperty("plural", false)
                data.addProperty("optional", false)
            }
        }
        data.addProperty("name", content)

        json.add("data", data)
        fullJson.add("item", json)
        fullJson.addProperty("slot", slot)
        return fullJson
    }

    private fun processTag(slot: Int, tagName: String, option: String, actionType: String, actionName: String): JsonObject {
        val fullJson = JsonObject()
        val json = JsonObject()
        json.addProperty("id", "bl_tag")

        val data = JsonObject()
        data.addProperty("tag", tagName)
        data.addProperty("option", option)
        data.addProperty("block", actionType)
        data.addProperty("action", actionName)

        json.add("data", data)
        fullJson.add("item", json)
        fullJson.addProperty("slot", slot)
        return fullJson
    }

    private fun mapIdentifierToDfIdentifier(identifier: String): String {
        return when (identifier) {
            "pe" -> "event"
            "pa" -> "player_action"
            "cf" -> "call_func"
            "so" -> "select_obj"
            "sv" -> "set_var"
            "fn" -> "func"
            else -> throw UnexpectedException("Identifier $identifier not yet supported.")
        }
    }
}