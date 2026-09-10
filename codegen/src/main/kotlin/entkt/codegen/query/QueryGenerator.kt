package entkt.codegen.query

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.UNIT
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.constructor
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
import entkt.schema.EntSchema

private val DRIVER = ClassName("entkt.runtime.driver", "DatabaseDriver")
private val ENTITY_QUERY_BUILDER = ClassName("entkt.runtime.query", "EntityQueryBuilder")
private val ENTITY_QUERY = ClassName("entkt.runtime.query", "EntityQuery")

/** Wires typed immutable queries into the shared runtime configuration and execution paths. */
internal class QueryGenerator(private val packageName: String) {
    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val resolved = resolveQuerySchema(packageName, schemaName, schema, schemaNames)
        val queryClass = resolved.queryClass
        val scopeClass = ClassName(packageName, "${schemaName}QueryScope")
        val clientClass = ClassName(packageName, "EntReadRuntime").copy(nullable = true)
        val descriptionType = ENTITY_QUERY.parameterizedBy(resolved.entityClass)
        val type = classType(queryClass) {
            addAnnotation(ClassName("entkt.schema", "EntktDsl"))
            superclass(ENTITY_QUERY_BUILDER.parameterizedBy(resolved.entityClass, queryClass))
            primaryConstructor {
                addModifiers(KModifier.INTERNAL)
                parameter("driver", DRIVER)
                parameter("client", clientClass)
                parameter("query", descriptionType)
            }
            addSuperclassConstructorParameter("driver = driver")
            addSuperclassConstructorParameter("executionHost = client")
            addSuperclassConstructorParameter("entityQuery = query")
            addFunction(constructor {
                parameter("driver", DRIVER)
                parameter("client", clientClass) { defaultValue("null") }
                callThisConstructor(
                    CodeBlock.of("driver"),
                    CodeBlock.of("client"),
                    CodeBlock.of("%T(%T)", ENTITY_QUERY, resolved.entityDescriptorClass),
                )
            })
            property("client", clientClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("client")
            }
            function("newQuery", queryClass) {
                addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
                parameter("query", descriptionType)
                statement("return %T(driver, client, query)", queryClass)
            }
            function("configure", queryClass) {
                parameter("block", LambdaTypeName.get(receiver = scopeClass, returnType = UNIT))
                statement("return configureQuery(%T(driver, client, entityQuery), block)", scopeClass)
            }
            for (edge in resolved.edges) {
                val traversal = if (edge.isManyToMany) {
                    buildM2MTraversal(edge, resolved, packageName)
                } else {
                    buildTraversal(edge, resolved, packageName)
                }
                if (traversal != null) addFunction(traversal)
            }
        }
        return kotlinFile(packageName, queryClass.simpleName) {
            addAnnotation(annotation(ClassName("kotlin", "OptIn")) {
                useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                addMember("%T::class", ClassName("entkt.query", "EntktInternal"))
            })
            addType(type)
        }
    }
}
