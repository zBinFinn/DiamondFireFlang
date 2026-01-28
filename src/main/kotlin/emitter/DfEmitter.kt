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
    }

    private fun emitInstr(instr: Ir.Instr, sb: StringBuilder) {
        when (instr) {
            is Ir.SimpleAction -> emitSimpleAction(instr, sb)
            is Ir.InlineIr -> emitInlineIr(instr, sb)
        }
    }

    private fun emitInlineIr(instr: Ir.InlineIr, sb: StringBuilder) {
        sb.append(instr.ir)
    }

    private fun emitSimpleAction(
        instr: Ir.SimpleAction,
        sb: StringBuilder
    ) {
        sb.append(instr.blockIrName) // pa
        sb.append(" ")
        sb.append("\"${instr.actionName}\"") // "SendMessage"
        sb.append(" ")
        if (instr.subAction != null) {
            sb.append("\"${instr.subAction}\"") // "SubAction"
            sb.append(" ")
        }
        if (instr.target != null) {
            sb.append("target(")
            sb.append(instr.target)
            sb.append(")")
            sb.append(" ")
        }
        if (instr.args.isNotEmpty()) {
            sb.append("args(")
            sb.append(instr.args.joinToString(", ") { emitValue(it) }) // args(s"Hello", s"Bye")
            sb.append(")")
            sb.append(" ")
        }
        if (instr.tags.isNotEmpty()) {
            sb.append("tags(")
            sb.append(instr.tags.joinToString(", ") { emitTag(it) }) // tags(26 "Hello" "World", 25 "Bye" "Hi")
            sb.append(")")
            sb.append(" ")
        }
        sb.appendLine()
    }

    private fun emitValue(value: Ir.Value): String {
        return when(value) {
            is Ir.StringValue -> """s"${escape(value.value)}""""
            is Ir.StyledText -> """t"${escape(value.value)}""""
            is Ir.NumberValue -> """n"${value.value}""""
            is Ir.Variable -> """vLI${value.name}""" // TODO: not just line variables
        }
    }

    private fun emitTag(tag: Ir.Tag): String {
        return "${tag.slot} \"${tag.name}\" \"${tag.selectedOption}\""
    }

    private fun escape(text: String): String {
        return text.replace("\"", "\\\"")
    }
}