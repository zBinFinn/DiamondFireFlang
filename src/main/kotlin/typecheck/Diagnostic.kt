package com.zbinfinn.typecheck

data class Diagnostic(
    val message: String,
    val module: String,
    val function: String? = null,
) {
    override fun toString(): String {
        val where = if (function != null) "$module::$function" else module
        return "TypeError[$where]: $message"
    }
}

