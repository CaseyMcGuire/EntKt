package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.UNIT
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.statement

/**
 * Generate a many-to-many traversal by capturing the immutable source query and
 * assigning it as the target query's relational origin.
 */
internal fun buildM2MTraversal(
    edge: ResolvedQueryEdge,
    resolved: ResolvedQuerySchema,
    packageName: String,
): FunSpec? {
    if (resolved.sourceName == null) return null
    return buildTraversalMethod(edge, resolved)
}

/**
 * Generate a direct traversal by capturing the immutable source query and
 * assigning it as the target query's relational origin.
 */
internal fun buildTraversal(
    edge: ResolvedQueryEdge,
    resolved: ResolvedQuerySchema,
    packageName: String,
): FunSpec? {
    if (resolved.sourceName == null || edge.inverse == null || edge.join == null) return null
    return buildTraversalMethod(edge, resolved)
}

private fun buildTraversalMethod(
    edge: ResolvedQueryEdge,
    resolved: ResolvedQuerySchema,
): FunSpec {
    val block = LambdaTypeName.get(
        receiver = ClassName(edge.targetClass.packageName, "${edge.targetName}QueryScope"),
        returnType = UNIT,
    )
    return function(edge.queryMethodName, returnType = edge.targetQueryClass) {
        parameter("block", block) {
            defaultValue("{}")
        }
        statement(
            "return %T(driver, client, traversalQuery(%T, %S)).configure(block)",
            edge.targetQueryClass,
            edge.edgeDescriptorClass,
            "${edge.queryMethodName}()",
        )
    }
}
