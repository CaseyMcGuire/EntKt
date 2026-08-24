package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.UNIT
import entkt.codegen.kotlinpoet.body
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement

private val EDGE_LOAD_HANDLE = ClassName("entkt.runtime.query", "EdgeLoad")
private val ENT_QUERY_CONFIGURATION_EXCEPTION =
    ClassName("entkt.runtime.result", "EntQueryConfigurationException")

/** Generated state and DSL method for one selected edge. */
internal data class EagerEdgeSpec(
    val property: PropertySpec,
    val filterVisibleProperty: PropertySpec,
    val loadMethod: FunSpec,
)

/** Build the captured query state and `load{Edge}()` method for one loadable edge. */
internal fun buildEagerEdgeSpec(
    re: ResolvedQueryEdge,
    resolved: ResolvedQuerySchema,
    packageName: String,
): EagerEdgeSpec {
    val targetQueryClass = re.targetQueryClass
    val queryClass = ClassName(packageName, "${resolved.sourceName}Query")
    val eagerPropName = re.eagerPropName
    val loadMethodName = re.loadMethodName

    val property = property(
        eagerPropName,
        targetQueryClass.copy(nullable = true),
    ) {
        addModifiers(KModifier.PRIVATE)
        mutable(true)
        initializer("null")
    }

    val filterVisibleProperty = property(
        "${eagerPropName}FilterVisible",
        BOOLEAN,
    ) {
        addModifiers(KModifier.PRIVATE)
        mutable(true)
        initializer("false")
    }

    val blockLambda = LambdaTypeName.get(
        receiver = targetQueryClass,
        returnType = UNIT,
    )
    val loadMethod = function(
        loadMethodName,
        returnType = EDGE_LOAD_HANDLE.parameterizedBy(queryClass),
    ) {
        addAnnotation(JvmOverloads::class)
        parameter("block", blockLambda) {
            defaultValue("{}")
        }
        beginControlFlow("if (%L != null)", eagerPropName)
        statement(
            "throw %T(\n%S,\n%S,\n)",
            ENT_QUERY_CONFIGURATION_EXCEPTION,
            resolved.schemaName,
            "${resolved.schemaName}.${re.publicName} is already selected on this " +
                "${resolved.schemaName}Query: $loadMethodName() may be called at most once " +
                "per query; compose all configuration for the edge in a single " +
                "$loadMethodName block",
        )
        endControlFlow()
        statement("val configured = %T(driver, client)", targetQueryClass)
        statement("%L = configured", eagerPropName)
        beginControlFlow("try")
        statement("configured.apply(block)")
        nextControlFlow("catch (e: %T)", Throwable::class)
        statement("%L = null", eagerPropName)
        statement("throw e")
        endControlFlow()
        body {
            add("return object : %T<%T> {\n", EDGE_LOAD_HANDLE, queryClass)
            add("  override fun filterVisible(): %T {\n", queryClass)
            add("    %LFilterVisible = true\n", eagerPropName)
            add("    return this@%L\n", queryClass.simpleName)
            add("  }\n")
            add("}\n")
        }
    }

    return EagerEdgeSpec(property, filterVisibleProperty, loadMethod)
}
