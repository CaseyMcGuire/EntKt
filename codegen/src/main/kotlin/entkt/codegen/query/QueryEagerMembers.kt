package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.UNIT

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

    val property = PropertySpec.builder(
        eagerPropName,
        targetQueryClass.copy(nullable = true),
    )
        .addModifiers(KModifier.PRIVATE)
        .mutable(true)
        .initializer("null")
        .build()

    val filterVisibleProperty = PropertySpec.builder(
        "${eagerPropName}FilterVisible",
        Boolean::class,
    )
        .addModifiers(KModifier.PRIVATE)
        .mutable(true)
        .initializer("false")
        .build()

    val blockLambda = LambdaTypeName.get(
        receiver = targetQueryClass,
        returnType = UNIT,
    )
    val loadMethod = FunSpec.builder(loadMethodName)
        .addAnnotation(JvmOverloads::class)
        .addParameter(
            ParameterSpec.builder("block", blockLambda)
                .defaultValue("{}")
                .build()
        )
        .returns(EDGE_LOAD_HANDLE.parameterizedBy(queryClass))
        .beginControlFlow("if (%L != null)", eagerPropName)
        .addStatement(
            "throw %T(\n%S,\n%S,\n)",
            ENT_QUERY_CONFIGURATION_EXCEPTION,
            resolved.schemaName,
            "${resolved.schemaName}.${re.publicName} is already selected on this " +
                "${resolved.schemaName}Query: $loadMethodName() may be called at most once " +
                "per query; compose all configuration for the edge in a single " +
                "$loadMethodName block",
        )
        .endControlFlow()
        .addStatement("val configured = %T(driver, client)", targetQueryClass)
        .addStatement("%L = configured", eagerPropName)
        .beginControlFlow("try")
        .addStatement("configured.apply(block)")
        .nextControlFlow("catch (e: %T)", Throwable::class)
        .addStatement("%L = null", eagerPropName)
        .addStatement("throw e")
        .endControlFlow()
        .addCode(
            CodeBlock.builder()
                .add("return object : %T<%T> {\n", EDGE_LOAD_HANDLE, queryClass)
                .add("  override fun filterVisible(): %T {\n", queryClass)
                .add("    %LFilterVisible = true\n", eagerPropName)
                .add("    return this@%L\n", queryClass.simpleName)
                .add("  }\n")
                .add("}\n")
                .build(),
        )
        .build()

    return EagerEdgeSpec(property, filterVisibleProperty, loadMethod)
}
