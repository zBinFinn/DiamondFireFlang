package com.zbinfinn.stdlib.dsl

import com.zbinfinn.ast.Ast
import com.zbinfinn.common.VariableScope
import com.zbinfinn.ir.Ir
import com.zbinfinn.stdlib.StdlibAst

@DslMarker
annotation class StdlibDsl

fun function(
    mod: String,
    name: String,
    block: FunctionBuilder.() -> Unit
): StdlibAst.StdFunction {
    val builder = FunctionBuilder(mod, name)
    block.invoke(builder)
    return builder.build()
}

@StdlibDsl
class FunctionBuilder(
    private val module: String,
    private val name: String
) {
    private val annotations = mutableListOf<Ast.Annotation>()
    private val params = mutableListOf<Ast.Parameter>()
    private val bodyStatements = mutableListOf<Ast.Statement>()

    fun annotations(block: AnnotationBuilder.() -> Unit) {
        val builder = AnnotationBuilder()
        block.invoke(builder)
        annotations += builder.build()
    }

    fun params(block: ParamBuilder.() -> Unit) {
        val builder = ParamBuilder()
        block.invoke(builder)
        params += builder.build()
    }

    fun body(block: BodyBuilder.() -> Unit) {
        val builder = BodyBuilder()
        block.invoke(builder)
        bodyStatements += builder.build()
    }

    fun build(): StdlibAst.StdFunction {
        return StdlibAst.StdFunction(
            importPath = "$module.$name",
            decl = Ast.FunctionDecl(
                name = name,
                annotations = annotations,
                parameters = params,
                body = Ast.Block(statements = bodyStatements),
            )
        )
    }
}

@StdlibDsl
class AnnotationBuilder {
    private val annotations = mutableListOf<Ast.Annotation>()

    fun playerSelector() {
        annotations += Ast.Annotation("PlayerSelector", emptyList())
    }

    fun entitySelector() {
        annotations += Ast.Annotation("EntitySelector", emptyList())
    }

    fun onPlayerSelection() {
        annotations += Ast.Annotation("OnPlayerSelection", emptyList())
    }

    fun onEntitySelection() {
        annotations += Ast.Annotation("OnEntitySelection", emptyList())
    }

    fun build(): List<Ast.Annotation> = annotations
}

@StdlibDsl
class ParamBuilder {
    private val params = mutableListOf<Ast.Parameter>()

    fun string(name: String, mutable: Boolean = false) {
        params += Ast.Parameter(
            name = name,
            type = Ast.Type("String"),
            mutable = mutable
        )
    }

    fun number(name: String, mutable: Boolean = false) {
        params += Ast.Parameter(
            name = name,
            type = Ast.Type("Number"),
            mutable = mutable
        )
    }

    fun dict(type: String, name: String, mutable: Boolean = false) {
        params += Ast.Parameter(
            name = name,
            type = Ast.Type(type),
            mutable = mutable
        )
    }

    fun build(): List<Ast.Parameter> = params
}

@StdlibDsl
class BodyBuilder {

    private val statements = mutableListOf<Ast.Statement>()

    fun playerAction(
        name: String,
        block: ActionBuilder.() -> Unit
    ) {
        val builder = ActionBuilder()
        block.invoke(builder)

        val ir = Ir.PlayerAction(
            actionName = name,
            args = builder.buildArgs(),
            tags = builder.buildTags(),
            target = null
        )

        statements += Ast.InlineIr(ir)
    }

    fun selectObject(
        name: String,
        sub: String? = null,
        block: ActionBuilder.() -> Unit
    ) {
        val builder = ActionBuilder()
        block.invoke(builder)

        val ir = Ir.SelectObject(
            actionName = name,
            subAction = sub,
            args = builder.buildArgs(),
            tags = builder.buildTags()
        )

        statements += Ast.InlineIr(ir)
    }

    internal fun build(): List<Ast.Statement> = statements
}

@StdlibDsl
class ActionBuilder {

    private val args = mutableListOf<Ir.Value>()
    private val tags = mutableListOf<Ir.Tag>()

    fun variable(
        name: String,
        scope: VariableScope = VariableScope.LINE
    ) {
        args += Ir.Variable(name, scope)
    }

    fun string(value: String) {
        args += Ir.StringValue(value)
    }

    fun styled(value: String) {
        args += Ir.StyledText(value)
    }

    fun number(value: Number) {
        args += Ir.NumberValue(value)
    }

    fun tag(slot: Int, name: String, option: String) {
        tags += Ir.Tag(slot, name, option)
    }

    internal fun buildArgs(): List<Ir.Value> = args
    internal fun buildTags(): List<Ir.Tag> = tags
}