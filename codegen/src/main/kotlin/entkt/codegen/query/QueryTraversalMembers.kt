package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.UNIT

private val QUERY_SOURCE = ClassName("entkt.runtime.query", "QuerySource")
private val REQUIRE_NO_SELECTED_EDGES =
    MemberName("entkt.runtime.query", "requireNoSelectedEdges")

private const val TRAVERSAL_EDGE_REASON =
    "traversal changes the result root and cannot carry the source query's selected " +
        "graph; traverse first and select edge loads on the target query, or " +
        "materialize the source graph with an entity terminal"

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
        receiver = edge.targetQueryClass,
        returnType = UNIT,
    )
    return FunSpec.builder(edge.queryMethodName)
        .addParameter(
            ParameterSpec.builder("block", block)
                .defaultValue("{}")
                .build(),
        )
        .returns(edge.targetQueryClass)
        .addStatement("val source = captureEntityQuery()")
        .addStatement(
            "source.%M(%S, %S)",
            REQUIRE_NO_SELECTED_EDGES,
            "${edge.queryMethodName}()",
            TRAVERSAL_EDGE_REASON,
        )
        .addStatement("val target = %T(driver, client)", edge.targetQueryClass)
        .addStatement(
            "target.setEntityQuerySource(%T.Traversal(source, %L))",
            QUERY_SOURCE,
            edge.mappingName,
        )
        .addStatement("return target.apply(block)")
        .build()
}
