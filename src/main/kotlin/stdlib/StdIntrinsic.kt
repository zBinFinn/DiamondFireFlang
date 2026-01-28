package com.zbinfinn.stdlib

import com.zbinfinn.ast.Ast
import com.zbinfinn.ir.Ir
import com.zbinfinn.ir.SymbolTable

interface StdIntrinsic {
    val name: String
    val importPath: String

    fun lower(
        call: Ast.FunctionCall,
        symbols: SymbolTable,
        out: MutableList<Ir.Instr>
    )
}