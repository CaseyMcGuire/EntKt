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

/** Generates only the schema-specific configuration surface, never query execution. */
internal class QueryScopeGenerator(private val packageName: String) {
    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val resolved = resolveQuerySchema(packageName, schemaName, schema, schemaNames)
        val scopeClass = ClassName(packageName, "${schemaName}QueryScope")
        val driverClass = ClassName("entkt.runtime.driver", "DatabaseDriver")
        val clientClass = ClassName(packageName, "EntReadRuntime").copy(nullable = true)
        val descriptionClass = ClassName("entkt.runtime.query", "EntityQuery")
        val type = classType(scopeClass) {
            addAnnotation(ClassName("entkt.schema", "EntktDsl"))
            superclass(
                ClassName("entkt.runtime.query", "EntityQueryScope")
                    .parameterizedBy(resolved.entityClass, scopeClass),
            )
            primaryConstructor {
                addModifiers(KModifier.INTERNAL)
                parameter("driver", driverClass)
                parameter("client", clientClass) { defaultValue("null") }
                parameter("query", descriptionClass.parameterizedBy(resolved.entityClass)) {
                    defaultValue("%T(%T)", descriptionClass, resolved.entityDescriptorClass)
                }
            }
            addSuperclassConstructorParameter("driver = driver")
            addSuperclassConstructorParameter("initialQuery = query")
            addSuperclassConstructorParameter("edgeOrder = %T.edgesByStorageName.values", resolved.entityDescriptorClass)
            property("client", clientClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("client")
            }
            property("self", scopeClass) {
                addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
                getter { statement("return this") }
            }
            for (edge in resolved.edges) {
                if (edge.join != null) addFunction(buildEdgeLoadMethod(edge, scopeClass))
            }
        }
        return kotlinFile(packageName, scopeClass.simpleName) {
            addAnnotation(annotation(ClassName("kotlin", "OptIn")) {
                useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                addMember("%T::class", ClassName("entkt.query", "EntktInternal"))
            })
            addType(type)
        }
    }
}
