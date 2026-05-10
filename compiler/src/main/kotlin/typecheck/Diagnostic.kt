package com.zbinfinn.typecheck

import com.zbinfinn.source.SourceRange

enum class DiagnosticSeverity {
    Error,
    Warning,
}

data class Diagnostic(
    val message: String,
    val module: String,
    val function: String? = null,
    val range: SourceRange? = null,
    val severity: DiagnosticSeverity = DiagnosticSeverity.Error,
) {
    override fun toString(): String {
        val where = if (function != null) "$module::$function" else module
        return "TypeError[$where]: $message"
    }
}
