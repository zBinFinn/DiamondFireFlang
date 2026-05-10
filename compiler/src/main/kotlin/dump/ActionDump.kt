package com.zbinfinn.dump

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object ActionDump {
    private lateinit var dump: ActionDumpContainer;
    fun parse(file: File) {
        this.dump = ActionDumpContainer.fromJson(JsonParser().parse(file.readText(Charsets.UTF_8)).asJsonObject)
    }

    fun get(): ActionDumpContainer {
        return dump
    }
}

data class ActionDumpContainer(
    val actions: Actions,
) {
    companion object {
        fun fromJson(json: JsonObject): ActionDumpContainer {
            val actions = Actions.fromJson(json["actions"].asJsonArray)


            return ActionDumpContainer(actions)
        }
    }
}

data class Actions(
    val actions: List<Action>
) {
    companion object {
        fun fromJson(json: JsonArray): Actions {
            val actions = mutableListOf<Action>()
            for (element in json) {
                actions.add(Action.fromJson(element.asJsonObject))
            }

            return Actions(actions)
        }
    }
}

data class Action(
    val name: String,
) {
    companion object {
        fun fromJson(json: JsonObject): Action {
            val name = json["name"].asString
            return Action(name)
        }
    }
}