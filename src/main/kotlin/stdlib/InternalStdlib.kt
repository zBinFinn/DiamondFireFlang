package com.zbinfinn.stdlib

import com.zbinfinn.common.FunctionKind
import com.zbinfinn.ir.Ir
import com.zbinfinn.stdlib.impl.collections.CollectionDictionary
import com.zbinfinn.stdlib.impl.collections.CollectionList
import com.zbinfinn.stdlib.impl.events.PlayerJoinEvent
import com.zbinfinn.stdlib.impl.player.SendMessage
import com.zbinfinn.stdlib.impl.player.ShowActionBarText
import com.zbinfinn.stdlib.impl.selection.SelectDefaultPlayer

interface InternalStdlibProvider {
    fun register(builder: InternalStdlibRegistry.Builder)
}

object InternalStdlib {
    private val registry = InternalStdlibRegistry.build(
        listOf(
            SendMessage,
            ShowActionBarText,
            SelectDefaultPlayer,
            PlayerJoinEvent,
            CollectionDictionary,
            CollectionList,
        )
    )

    fun functionBody(qualifiedName: String, args: List<Ir.Value>): List<Ir.Instr>? =
        registry.functionBody(qualifiedName, args)

    fun memberBody(qualifiedName: String, args: List<Ir.Value>): List<Ir.Instr>? =
        registry.memberBody(qualifiedName, args)

    fun hasFunction(qualifiedName: String): Boolean =
        registry.hasFunction(qualifiedName)

    fun hasMember(qualifiedName: String): Boolean =
        registry.hasMember(qualifiedName)
}

class InternalStdlibRegistry private constructor(
    private val functionBodies: Map<String, (List<Ir.Value>) -> List<Ir.Instr>>,
    private val memberBodies: Map<String, (List<Ir.Value>) -> List<Ir.Instr>>,
) {
    fun functionBody(qualifiedName: String, args: List<Ir.Value>): List<Ir.Instr>? =
        functionBodies[qualifiedName]?.invoke(args)

    fun memberBody(qualifiedName: String, args: List<Ir.Value>): List<Ir.Instr>? =
        memberBodies[qualifiedName]?.invoke(args)

    fun hasFunction(qualifiedName: String): Boolean =
        qualifiedName in functionBodies

    fun hasMember(qualifiedName: String): Boolean =
        qualifiedName in memberBodies

    class Builder {
        private val functionBodies = mutableMapOf<String, (List<Ir.Value>) -> List<Ir.Instr>>()
        private val memberBodies = mutableMapOf<String, (List<Ir.Value>) -> List<Ir.Instr>>()

        fun function(
            modulePath: String,
            name: String,
            kind: FunctionKind = FunctionKind.Plain,
            body: (List<Ir.Value>) -> List<Ir.Instr>,
        ) {
            val qualifiedName = qualifiedFunctionName(modulePath, name, kind)
            register(functionBodies, qualifiedName, body)
        }

        fun onPlayerSelection(
            modulePath: String,
            name: String,
            body: (List<Ir.Value>) -> List<Ir.Instr>,
        ) {
            function(modulePath, name, FunctionKind.OnPlayerSelection, body)
        }

        fun playerSelector(
            modulePath: String,
            name: String,
            body: (List<Ir.Value>) -> List<Ir.Instr>,
        ) {
            function(modulePath, name, FunctionKind.PlayerSelector, body)
        }

        fun member(
            ownerType: String,
            name: String,
            body: (List<Ir.Value>) -> List<Ir.Instr>,
        ) {
            register(memberBodies, "$ownerType.$name", body)
        }

        fun build(): InternalStdlibRegistry =
            InternalStdlibRegistry(functionBodies.toMap(), memberBodies.toMap())

        private fun <T> register(map: MutableMap<String, T>, qualifiedName: String, value: T) {
            if (map.put(qualifiedName, value) != null) {
                error("Duplicate internal stdlib provider '$qualifiedName'")
            }
        }

        private fun qualifiedFunctionName(modulePath: String, name: String, kind: FunctionKind): String {
            val base = "$modulePath.$name"
            return kind.suffix?.let { "$base\$$it" } ?: base
        }
    }

    companion object {
        fun build(providers: List<InternalStdlibProvider>): InternalStdlibRegistry {
            val builder = Builder()
            providers.forEach { it.register(builder) }
            return builder.build()
        }
    }
}
