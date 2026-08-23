package entkt.codegen.client

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.STAR
import entkt.codegen.SchemaInput

private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val PRIVACY_CONTEXT_PROVIDER =
    ClassName("entkt.runtime.privacy", "PrivacyContextProvider")
private val PRIVACY_DENIAL = ClassName("entkt.runtime.result", "PrivacyDenial")
private val LIST = ClassName("kotlin.collections", "List")
private val ENT_INTERCEPTORS_CONFIG = ClassName("entkt.runtime.query", "EntInterceptorsConfig")
private val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")
private val ENT_ENTITY = ClassName("entkt.runtime.entity", "EntEntity")
private val ENTITY_MAPPING = ClassName("entkt.runtime.entity", "EntityMapping")
private val LOAD_PRIVACY_EVALUATOR =
    ClassName("entkt.runtime.query.execution", "LoadPrivacyEvaluator")
private val LOAD_PRIVACY_EVALUATION =
    ClassName("entkt.runtime.query.execution", "LoadPrivacyEvaluation")
private val CORRELATE_LOAD_PRIVACY_EVALUATIONS = MemberName(
    "entkt.runtime.query.execution",
    "correlateLoadPrivacyEvaluationsForInternalUse",
)

/**
 * Emits the generated read-runtime contract: `EntReadRuntime` plus one
 * `${Entity}ReadSurface` interface per schema.
 *
 * `EntReadRuntime` names exactly what generated queries need from their
 * host — `currentPrivacyContext()`, the
 * `@EntktInternal` interceptor registry, and one accessor per entity
 * typed to that entity's read surface (`hasLoadPrivacy()` /
 * `loadDenials(...)`, the only repo members query terminals
 * call). Both `EntClient` and `EntReadClientImpl` (the shared delegate
 * behind the posture read clients) implement it, so generated query and
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

        val fileBuilder = FileSpec.builder(packageName, "EntReadRuntime")
            // The declarations below reference each other and are all
            // `@EntktInternal`-marked; the file-level OptIn covers the
            // marked-type usages without re-marking every member.
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%T::class", ENTKT_INTERNAL)
                    .build()
            )

        for (input in schemas) {
            fileBuilder.addType(buildReadSurface(input))
        }
        fileBuilder.addType(buildReadRuntime(sorted))

        return fileBuilder.build()
    }

    private fun buildReadSurface(input: SchemaInput): TypeSpec {
        val entityClass = ClassName(packageName, input.name)
        return TypeSpec.interfaceBuilder("${input.name}ReadSurface")
            .addAnnotation(ENTKT_INTERNAL)
            .addKdoc(
                "Narrow per-entity read surface of `%LRepo`: the only repo members\n" +
                    "generated query terminals call. `%LReadRepo` implements it by\n" +
                    "delegating to the host repo, so LOAD-privacy behavior is identical\n" +
                    "through either client. `loadDenials` returns one positionally aligned\n" +
                    "keyed denial (or null) per entity; `loadDenialOrNull` is its singleton\n" +
                    "projection. A rule-thrown exception escapes so the terminal's capture\n" +
                    "boundary stores it as an operational failure.",
                input.name, input.name,
            )
            .addFunction(
                FunSpec.builder("hasLoadPrivacy")
                    .addModifiers(KModifier.ABSTRACT)
                    .returns(Boolean::class)
                    .build()
            )
            .addFunction(
                FunSpec.builder("loadDenials")
                    .addModifiers(KModifier.ABSTRACT)
                    .addParameter("privacy", PRIVACY_CONTEXT)
                    .addParameter("entities", LIST.parameterizedBy(entityClass))
                    .returns(LIST.parameterizedBy(PRIVACY_DENIAL.copy(nullable = true)))
                    .build()
            )
            .addFunction(
                FunSpec.builder("loadDenialOrNull")
                    .addModifiers(KModifier.ABSTRACT)
                    .addParameter("privacy", PRIVACY_CONTEXT)
                    .addParameter("entity", entityClass)
                    .returns(PRIVACY_DENIAL.copy(nullable = true))
                    .build()
            )
            .build()
    }

    private fun buildReadRuntime(sorted: List<SchemaInput>): TypeSpec {
        val builder = TypeSpec.interfaceBuilder("EntReadRuntime")
            .addAnnotation(ENTKT_INTERNAL)
            .addSuperinterface(PRIVACY_CONTEXT_PROVIDER)
            .addSuperinterface(LOAD_PRIVACY_EVALUATOR)
            .addKdoc(
                "The read-runtime contract generated queries and index stages depend\n" +
                    "on, instead of the full `EntClient`. Implemented by `EntClient` and\n" +
                    "by `EntReadClientImpl` (the internal delegate behind\n" +
                    "`EntValidationReadClient` / `EntPrivacyReadClient` — the public\n" +
                    "`EntReadClient` interface deliberately does not extend this\n" +
                    "contract); a query constructed with either host behaves identically\n" +
                    "on the read path (privacy context, LOAD privacy, read\n" +
                    "interceptors).",
            )
            .addFunction(
                FunSpec.builder("currentPrivacyContext")
                    .addModifiers(KModifier.ABSTRACT)
                    .returns(PRIVACY_CONTEXT)
                    .build()
            )
            .addFunction(
                FunSpec.builder("get")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(PRIVACY_CONTEXT)
                    .addStatement("return currentPrivacyContext()")
                    .build(),
            )
            .addProperty(
                // Keeps the concrete property's `@EntktInternal` guard:
                // interface members can't be `internal`, so the opt-in
                // marker is what stops application code from reaching the
                // raw EntInterceptorsConfig (whose scope-key-keyed
                // `addEntity` permits wrong-entity registration via an
                // unchecked cast). Generated queries read it through
                // their files' `@file:OptIn`.
                PropertySpec.builder("entityInterceptors", ENT_INTERCEPTORS_CONFIG)
                    .addAnnotation(ENTKT_INTERNAL)
                    .addModifiers(KModifier.ABSTRACT)
                    .build()
            )

        builder.addFunction(buildIsLoadPrivacyConfigured(sorted))
        builder.addFunction(buildEvaluateLoadPrivacy(sorted))

        for (input in sorted) {
            val propName = input.clientName
            builder.addProperty(
                PropertySpec.builder(propName, ClassName(packageName, "${input.name}ReadSurface"))
                    .addModifiers(KModifier.ABSTRACT)
                    .build()
            )
        }

        return builder.build()
    }

    private fun buildIsLoadPrivacyConfigured(sorted: List<SchemaInput>): FunSpec {
        val body = com.squareup.kotlinpoet.CodeBlock.builder().add("return when (entity) {\n")
        for (input in sorted) {
            body.add(
                "  %T.GeneratedEntityMapping -> %L.hasLoadPrivacy()\n",
                ClassName(packageName, "${input.name}Query"),
                input.clientName,
            )
        }
        body.add(
            "  else -> error(%P)\n",
            "No LOAD-privacy evaluator is registered for entity '${'$'}{entity.entityName}'",
        )
        body.add("}\n")
        return FunSpec.builder("isConfigured")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("entity", ENTITY_MAPPING.parameterizedBy(STAR))
            .returns(Boolean::class)
            .addCode(body.build())
            .build()
    }

    private fun buildEvaluateLoadPrivacy(sorted: List<SchemaInput>): FunSpec {
        val entityType = TypeVariableName("Entity", ENT_ENTITY.parameterizedBy(STAR))
        val body = com.squareup.kotlinpoet.CodeBlock.builder().add("return when (entity) {\n")
        for (input in sorted) {
            body.add(
                "  %T.GeneratedEntityMapping -> %M(\n" +
                    "    %S,\n" +
                    "    entities,\n" +
                    "    %L.loadDenials(privacyContext, entities as %T<%T>),\n" +
                    "  )\n",
                ClassName(packageName, "${input.name}Query"),
                CORRELATE_LOAD_PRIVACY_EVALUATIONS,
                "${input.name} LOAD privacy",
                input.clientName,
                LIST,
                ClassName(packageName, input.name),
            )
        }
        body.add(
            "  else -> error(%P)\n",
            "No LOAD-privacy evaluator is registered for entity '${'$'}{entity.entityName}'",
        )
        body.add("}\n")
        return FunSpec.builder("evaluate")
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                    .addMember("%S", "UNCHECKED_CAST")
                    .build(),
            )
            .addModifiers(KModifier.OVERRIDE)
            .addTypeVariable(entityType)
            .addParameter("entity", ENTITY_MAPPING.parameterizedBy(entityType))
            .addParameter("privacyContext", PRIVACY_CONTEXT)
            .addParameter("entities", LIST.parameterizedBy(entityType))
            .returns(LIST.parameterizedBy(LOAD_PRIVACY_EVALUATION.parameterizedBy(entityType)))
            .addCode(body.build())
            .build()
    }
}
