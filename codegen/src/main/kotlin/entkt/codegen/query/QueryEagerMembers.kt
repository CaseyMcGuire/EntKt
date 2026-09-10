package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.UNIT
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.statement

/** Supply schema-specific types to the runtime's edge-selection implementation. */
internal fun buildEdgeLoadMethod(
    edge: ResolvedQueryEdge,
    scopeClass: ClassName,
): FunSpec {
    val targetScope = ClassName(scopeClass.packageName, "${edge.targetName}QueryScope")
    return function(
        edge.loadMethodName,
        returnType = ClassName("entkt.runtime.query", "EdgeLoad").parameterizedBy(scopeClass),
    ) {
        addAnnotation(JvmOverloads::class)
        parameter("block", LambdaTypeName.get(receiver = targetScope, returnType = UNIT)) {
            defaultValue("{}")
        }
        statement(
            "return loadEdge(%T, %T(driver, client), block)",
            edge.edgeDescriptorClass,
            targetScope,
        )
    }
}
