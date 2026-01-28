package com.zbinfinn.emitter

import com.zbinfinn.ir.Ir

class DfEmitter {
    fun emit(program: Ir.Program): String {
        val sb = StringBuilder()

        for (event in program.entryPoints) {
            emitEntryPoint(event, sb)
            sb.appendLine()
        }

        for (function in program.functions) {
            emitFunction(function, sb)
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun emitEntryPoint(entry: Ir.EntryPoint, sb: StringBuilder) {
        when (entry) {
            is Ir.PlayerEvent -> {
                sb.appendLine("""pe "${entry.eventName}"""")
                emitBody(entry.body, sb)
                sb.appendLine("end")
            }
            is Ir.EntityEvent -> {
                sb.appendLine("""ee "${entry.eventName}"""")
                emitBody(entry.body, sb)
                sb.appendLine("end")
            }
        }
    }

    private fun emitFunction(function: Ir.Function, sb: StringBuilder) {
        sb.appendLine("""fn ${function.name}""")
        emitBody(function.body, sb)
        sb.appendLine("end")
    }

    private fun emitBody(body: List<Ir.Instr>, sb: StringBuilder) {
        for (instr in body) {
            emitInstr(instr, sb)
        }
        sb.appendLine()
    }

    private fun emitInstr(instr: Ir.Instr, sb: StringBuilder) {
        when (instr) {
            is Ir.CallFunction -> {
                sb.appendLine("""cf "${instr.name}"""")
            }
            is Ir.PlayerAction -> emitPlayerAction(instr, sb)
            is Ir.SetVariableAction -> emitSetVariableAction(instr, sb)
        }
    }

    private fun emitSetVariableAction(instr: Ir.SetVariableAction, sb: StringBuilder) {
        sb.append("""sv "${instr.actionName}" """)
        sb.append("args(")
        sb.append(instr.args.joinToString(", ") { emitValue(it) })
        sb.append(") ")
    }

    private fun emitPlayerAction(instr: Ir.PlayerAction, sb: StringBuilder) {
        sb.append("""pa "${instr.actionName}" """)
        sb.append("args(")
        sb.append(instr.args.joinToString(", ") { emitValue(it) })
        sb.append(") ")

        // TODO: Bake tags into the IR
        if (instr.actionName == "SendMessage") {
            sb.append(
                """tags(26 "Alignment Mode" "Regular", 25 "Text Value Merging" "Add spaces", 24 "Inherit Styles" "True") """
            )
        }

        sb.appendLine("""target(${instr.target.name})""")
    }

    private fun emitValue(value: Ir.Value): String {
        return when(value) {
            is Ir.StringValue -> """s"${escape(value.value)}""""
            is Ir.StyledText -> """t"${escape(value.value)}""""
            is Ir.NumberValue -> """n"${value.value}""""
            is Ir.Variable -> """vLI${value.name}""" // TODO: not just line variables
        }
    }

    private fun escape(text: String): String {
        return text.replace("\"", "\\\"")
    }
}