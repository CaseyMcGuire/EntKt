package entkt.codegen.client

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.STAR
import entkt.codegen.SchemaInput
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.interfaceType
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.metadata.VIEWER_CONTEXT

private val PRIVACY_EVALUATION = ClassName("entkt.runtime.privacy", "PrivacyEvaluation")
private val LIST = ClassName("kotlin.collections", "List")
private val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")
private val ENT_ENTITY = ClassName("entkt.runtime.entity", "EntEntity")
private val ENTITY_MAPPING = ClassName("entkt.runtime.entity", "EntityMapping")
private val READ_QUERY_EXECUTION_HOST =
    ClassName("entkt.runtime.query.execution", "ReadQueryExecutionHost")

/**
 * Emits the generated read-runtime contract: `EntReadRuntime` plus one
 * `${Entity}ReadSurface` interface per schema.
 *
 * `EntReadRuntime` names exactly what generated queries need from their
 * host — the read-execution guard, the `@EntktInternal` interceptor
 * registry, and one accessor per entity
 * typed to that entity's read surface (`hasLoadPrivacy()` /
 * `evaluateLoadPrivacy(...)`, the only repo members query terminals
 * call). Both `EntClient` and `ReadOnlyEntClientImpl` implement it, so generated query and
 * index-stage constructors can accept the contract instead of the full
 * client.
 *
 * Everything here is `public` + `@EntktInternal`, not Kotlin-`internal`:
 * the public query constructors cannot expose an internal parameter
 * type, the public `EntClient` cannot
 * expose an internal supertype, and `internal` would be no guard anyway
 * — generated code compiles into the consuming application's module,
 * where `internal` stays visible to exactly the application code the
 * contract is meant to keep out. The opt-in marker is the gate;
 * generated files consume it with `@file:OptIn`, which does not
 * propagate to application call sites.
 */
internal class ReadRuntimeGenerator(
    private val packageName: String,
) {

    fun generate(schemas: List<SchemaInput>): FileSpec {
        // Same accessor order as EntClient's repo properties.
        val sorted = topologicalSort(schemas)

        return kotlinFile(packageName, "EntReadRuntime") {
            // The declarations below reference each other and are all
            // `@EntktInternal`-marked; the file-level OptIn covers the
            // marked-type usages without re-marking every member.
            addAnnotation(
                annotation(ClassName("kotlin", "OptIn")) {
                    useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    addMember("%T::class", ENTKT_INTERNAL)
                },
            )
            for (input in schemas) {
                addType(buildReadSurface(input))
            }
            addType(buildReadRuntime(sorted))
        }
    }

    private fun buildReadSurface(input: SchemaInput): TypeSpec {
        val entityClass = ClassName(packageName, input.name)
        return interfaceType("${input.name}ReadSurface") {
            addAnnotation(ENTKT_INTERNAL)
            function("hasLoadPrivacy", returnType = BOOLEAN) {
                addModifiers(KModifier.ABSTRACT)
            }
            function(
                "evaluateLoadPrivacy",
                returnType = PRIVACY_EVALUATION.parameterizedBy(entityClass),
            ) {
                addModifiers(KModifier.ABSTRACT)
                parameter("viewerContext", VIEWER_CONTEXT)
                parameter("entities", LIST.parameterizedBy(entityClass))
            }
        }
    }

    private fun buildReadRuntime(sorted: List<SchemaInput>): TypeSpec {
        return interfaceType("EntReadRuntime") {
            addAnnotation(ENTKT_INTERNAL)
            addSuperinterface(READ_QUERY_EXECUTION_HOST)
            addFunction(buildIsLoadPrivacyConfigured(sorted))
            addFunction(buildEvaluateLoadPrivacy(sorted))

            for (input in sorted) {
                property(input.clientName, ClassName(packageName, "${input.name}ReadSurface")) {
                    addModifiers(KModifier.ABSTRACT)
                }
            }
        }
    }

    private fun buildIsLoadPrivacyConfigured(sorted: List<SchemaInput>): FunSpec {
        val body = codeBlock {
            add("return when (entity) {\n")
            for (input in sorted) {
                add(
                    "  %T -> %L.hasLoadPrivacy()\n",
                    ClassName(packageName, "${input.name}Descriptor"),
                    input.clientName,
                )
            }
            add(
                "  else -> error(%P)\n",
                "No LOAD-privacy evaluator is registered for entity '${'$'}{entity.entityName}'",
            )
            add("}\n")
        }
        return function("isConfigured", returnType = BOOLEAN) {
            addModifiers(KModifier.OVERRIDE)
            parameter("entity", ENTITY_MAPPING.parameterizedBy(STAR))
            addCode(body)
        }
    }

    private fun buildEvaluateLoadPrivacy(sorted: List<SchemaInput>): FunSpec {
        val entityType = TypeVariableName("Entity", ENT_ENTITY.parameterizedBy(STAR))
        val body = codeBlock {
            add("return when (entity) {\n")
            for (input in sorted) {
                add(
                    "  %T -> %L.evaluateLoadPrivacy(\n" +
                        "    viewerContext,\n" +
                        "    entities as %T<%T>,\n" +
                        "  ) as %T<%L>\n",
                    ClassName(packageName, "${input.name}Descriptor"),
                    input.clientName,
                    LIST,
                    ClassName(packageName, input.name),
                    PRIVACY_EVALUATION,
                    entityType,
                )
            }
            add(
                "  else -> error(%P)\n",
                "No LOAD-privacy evaluator is registered for entity '${'$'}{entity.entityName}'",
            )
            add("}\n")
        }
        return function(
            "evaluate",
            returnType = PRIVACY_EVALUATION.parameterizedBy(entityType),
        ) {
            addAnnotation(
                annotation(ClassName("kotlin", "Suppress")) {
                    addMember("%S", "UNCHECKED_CAST")
                },
            )
            addModifiers(KModifier.OVERRIDE)
            addTypeVariable(entityType)
            parameter("entity", ENTITY_MAPPING.parameterizedBy(entityType))
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter("entities", LIST.parameterizedBy(entityType))
            addCode(body)
        }
    }
}
