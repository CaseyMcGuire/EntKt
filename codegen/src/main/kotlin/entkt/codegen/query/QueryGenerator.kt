package entkt.codegen.query

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.getter
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
import entkt.schema.EntSchema

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val DRIVER = ClassName("entkt.runtime.driver", "DatabaseDriver")
private val ENTITY_QUERY_BUILDER = ClassName("entkt.runtime.query", "EntityQueryBuilder")

// Generated queries retain the read-runtime contract only to construct
// sibling queries for eager loads and traversals. EntityQueryBuilder owns
// terminal execution, interceptor lookup, and LOAD-privacy delegation.
private val ENT_READ_RUNTIME_NAME = "EntReadRuntime"

internal class QueryGenerator(
    private val packageName: String,
) {
    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        // Edge metadata (target names, joins, inverses, member names)
        // resolves once here; every member builder below reads it
        // instead of re-deriving its own copy from the raw schema.
        val resolved = resolveQuerySchema(packageName, schemaName, schema, schemaNames)
        val className = "${schemaName}Query"
        val queryClass = resolved.queryClass
        val entityClass = resolved.entityClass
        // The generated client property for this schema, declared on the
        // schema and emitted verbatim — never derived from schemaName.
        val traversalMethods = resolved.edges.mapNotNull { re ->
            if (re.isManyToMany) {
                buildM2MTraversal(re, resolved, packageName)
            } else {
                buildTraversal(re, resolved, packageName)
            }
        }

        // Edge loading: load{Edge}() methods and properties
        val eagerEdgeSpecs = resolved.edges
            .filter { it.join != null }
            .map { buildEagerEdgeSpec(it, resolved, packageName) }

        val clientClass = ClassName(packageName, ENT_READ_RUNTIME_NAME)

        val typeSpec = classType(className) {
            addKdoc(
                "Mutable query builder for [%T]. Configure and execute this instance from one " +
                    "thread at a time; query builders are not thread-safe. Do not mutate or " +
                    "execute the same instance concurrently. Create a separate query builder " +
                    "for each concurrent operation. A fully configured instance may be " +
                    "executed repeatedly when those executions are sequential.\n",
                entityClass,
            )
            addAnnotation(annotation(ENTKT_DSL))
            superclass(ENTITY_QUERY_BUILDER.parameterizedBy(entityClass, queryClass))
            primaryConstructor {
                parameter("driver", DRIVER)
                parameter("client", clientClass.copy(nullable = true)) {
                    defaultValue("null")
                }
            }
            addSuperclassConstructorParameter("driver = driver")
            addSuperclassConstructorParameter("executionHost = client")
            addSuperclassConstructorParameter("entityName = %S", schemaName)
            property("client", clientClass.copy(nullable = true)) {
                addModifiers(KModifier.PRIVATE)
                initializer("client")
            }
            property("self", queryClass) {
                addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
                getter {
                    statement("return this")
                }
            }
            addProperty(buildEntityQuerySourceProperty(entityClass))
            addProperties(eagerEdgeSpecs.map { it.property })
            addProperties(eagerEdgeSpecs.map { it.filterVisibleProperty })
            addTypes(buildEntityQueryMappings(resolved))
            addFunctions(eagerEdgeSpecs.map { it.loadMethod })
            addFunction(buildSetEntityQuerySource(entityClass))
            addFunction(buildCaptureEntityQuery(resolved))
            addFunctions(traversalMethods)
        }

        // Generated mappings and recursive query capture use framework-internal
        // runtime contracts; the generated file owns that opt-in.
        return kotlinFile(packageName, className) {
            addAnnotation(
                annotation(ClassName("kotlin", "OptIn")) {
                    useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    addMember("%T::class", ClassName("entkt.query", "EntktInternal"))
                },
            )
            addType(typeSpec)
        }
    }

}
