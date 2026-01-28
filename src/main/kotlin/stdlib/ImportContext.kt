package com.zbinfinn.stdlib

class ImportContext(imports: List<String>) {
    private val imported = imports.toSet()

    fun isImported(path: String): Boolean = path in imported
}