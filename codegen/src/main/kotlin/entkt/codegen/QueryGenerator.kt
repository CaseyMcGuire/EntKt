package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import entkt.schema.Edge
import entkt.schema.EntSchema

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val EDGE_QUERY = ClassName("entkt.query", "EdgeQuery")

class QueryGenerator(
    private val packageName: String,
) {
    private val predicateClass = ClassName("entkt.query", "Predicate")
    private val orderFieldClass = ClassName("entkt.query", "OrderField")

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}Query"
        val queryClass = ClassName(packageName, className)

        val traversalMethods = schema.edges()
            .filter { it.through == null }
            .mapNotNull { edge -> buildTraversal(edge, schema, schemaNames) }

        val typeSpec = TypeSpec.classBuilder(className)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
            .addSuperinterface(EDGE_QUERY)
            .addProperty(
                PropertySpec.builder(
                    "predicates",
                    List::class.asClassName().parameterizedBy(predicateClass),
                )
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "orderFields",
                    List::class.asClassName().parameterizedBy(orderFieldClass),
                )
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("queryLimit", INT.copy(nullable = true))
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("queryOffset", INT.copy(nullable = true))
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addFunction(buildWhere(queryClass))
            .addFunction(buildOrderBy(queryClass))
            .addFunction(buildLimit(queryClass))
            .addFunction(buildOffset(queryClass))
            .addFunction(buildCombinedPredicate())
            .addFunctions(traversalMethods)
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
            .build()
    }

    private fun buildWhere(queryClass: ClassName): FunSpec {
        return FunSpec.builder("where")
            .addParameter("predicate", predicateClass)
            .returns(queryClass)
            .addStatement("this.predicates = this.predicates + predicate")
            .addStatement("return this")
            .build()
    }

    private fun buildOrderBy(queryClass: ClassName): FunSpec {
        return FunSpec.builder("orderBy")
            .addParameter("field", orderFieldClass)
            .returns(queryClass)
            .addStatement("this.orderFields = this.orderFields + field")
            .addStatement("return this")
            .build()
    }

    private fun buildLimit(queryClass: ClassName): FunSpec {
        return FunSpec.builder("limit")
            .addParameter("n", INT)
            .returns(queryClass)
            .addStatement("this.queryLimit = n")
            .addStatement("return this")
            .build()
    }

    private fun buildOffset(queryClass: ClassName): FunSpec {
        return FunSpec.builder("offset")
            .addParameter("n", INT)
            .returns(queryClass)
            .addStatement("this.queryOffset = n")
            .addStatement("return this")
            .build()
    }

    /**
     * Implements the [EdgeQuery] contract: returns the AND of every
     * accumulated predicate, or null if the query has no wheres. This
     * is what `EdgeRef.has { }` and the generated traversal methods
     * call to fold a query's filters into a single Predicate.
     */
    private fun buildCombinedPredicate(): FunSpec {
        return FunSpec.builder("combinedPredicate")
            .addModifiers(KModifier.OVERRIDE)
            .returns(predicateClass.copy(nullable = true))
            .addStatement(
                "return predicates.reduceOrNull { acc, p -> %T.And(acc, p) }",
                predicateClass,
            )
            .build()
    }

    /**
     * Generate a `queryX(): TargetQuery` method for [edge]. This is the
     * traversal entry point — given a query on the source schema, walk
     * across [edge] and return a query on the target.
     *
     * Lowering: the parent's combined predicate becomes a HasEdgeWith
     * predicate on the target query, naming the *inverse* edge (i.e.
     * the edge on the target that points back at the source). When the
     * parent has no wheres we still emit HasEdge so optional inverse
     * edges still filter out unrelated rows.
     *
     * Returns null when the inverse edge can't be resolved — codegen
     * just skips emitting a traversal method in that case.
     */
    private fun buildTraversal(
        edge: Edge,
        source: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): FunSpec? {
        val targetName = schemaNames[edge.target] ?: return null
        val inverse = findInverseEdge(edge, source) ?: return null
        val targetQueryClass = ClassName(packageName, "${targetName}Query")
        val methodName = "query${toPascalCase(edge.name)}"

        return FunSpec.builder(methodName)
            .returns(targetQueryClass)
            .addStatement("val parent = combinedPredicate()")
            .addStatement("val target = %T()", targetQueryClass)
            .beginControlFlow("if (parent != null)")
            .addStatement(
                "target.where(%T.HasEdgeWith(%S, parent))",
                predicateClass,
                inverse.name,
            )
            .nextControlFlow("else")
            .addStatement(
                "target.where(%T.HasEdge(%S))",
                predicateClass,
                inverse.name,
            )
            .endControlFlow()
            .addStatement("return target")
            .build()
    }
}

internal fun toPascalCase(snakeCase: String): String =
    toCamelCase(snakeCase).replaceFirstChar { it.uppercase() }