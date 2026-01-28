package com.zbinfinn.ir

import com.zbinfinn.common.VariableScope

class SymbolTable {
    private val variables = mutableMapOf<String, VariableSymbol>()

    fun define(name: String, scope: VariableScope = VariableScope.LINE, mutable: Boolean) {
        if (variables.containsKey(name)) {
            error("Variable $name already defined")
        }
        variables[name] = VariableSymbol(name, scope, mutable)
    }

    fun assign(name: String) {
        val symbol = variables[name]
            ?: error("Variable $name not defined")

        if (!symbol.mutable) {
            error("Cannot reassign immutable variable $name")
        }
    }

    fun resolve(name: String): VariableSymbol {
        return variables[name]
            ?: error("Variable $name not defined")
    }
}

data class VariableSymbol(
    val name: String,
    val scope: VariableScope,
    val mutable: Boolean
)