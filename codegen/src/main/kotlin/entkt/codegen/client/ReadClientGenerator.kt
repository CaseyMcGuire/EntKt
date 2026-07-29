package entkt.codegen.client

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.SchemaInput
import entkt.codegen.metadata.toTypeName
import entkt.codegen.pluralize
import entkt.codegen.query.indexHelperTree
import entkt.schema.EntSchema

private val DRIVER = ClassName("entkt.runtime.driver", "Driver")
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val VIEWER = ClassName("entkt.runtime.privacy", "Viewer")
private val ENT_INTERCEPTORS_CONFIG = ClassName("entkt.runtime.query", "EntInterceptorsConfig")
private val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")

/**
 * Emits `EntReadClient` plus one `${Entity}ReadRepo` per schema: the
 * read-only client generated validation and privacy contexts expose to
 * rule code.
 *
 * The client's privacy posture is instance state, not part of the type
 * — exactly like `EntClient` itself. The `asReadClientForInternalUse`
 * adapter fixes whatever context its call site passes:
 * `PrivacyBypass("validation read")` from validation evaluators (so
 * invariant checks are not blocked by LOAD privacy), the caller's own
 * context from privacy evaluators (so authorization reads see only
 * what the viewer sees).
 *
 * Each read repo exposes the read surface only — the byId family, the
 * full `query { }` DSL (all terminals come with the query class), the
 * explainById* variants, and the generated index helpers. The write
 * surface (create / update / save / delete* / edge mutators /
 * `withTransaction` / re-scoping / config setters) simply does not
 * exist on these types, so rule code calling it is a compile error —
 * the read-only guarantee is structural, not conventional.
 *
 * Queries and index stages constructed through these repos receive the
 * `EntReadClient` itself as their [EntReadRuntime], never an
 * `EntClient` — no rule-reachable object holds a full client, even
 * privately. LOAD-privacy behavior is delegated to the host client's
 * repos (typed as the narrow read surfaces), so `hasLoadPrivacy` /
 * `evaluateLoadPrivacy` behave identically through either client.
 *
 * Construction is framework-internal: the constructors here carry
 * `@EntktInternal internal`, and the only supported minting path is
 * the generated evaluators' adapter calls. That is a deliberate-use
 * gate, not a security boundary — see the marker's KDoc.
 */
internal class ReadClientGenerator(
    private val packageName: String,
) {

    fun generate(
        schemas: List<SchemaInput>,
        schemaNames: Map<EntSchema, String>,
    ): FileSpec {
        // Same accessor/parameter order as EntClient's repo properties and
        // asReadClientForInternalUse()'s positional host arguments.
        val sorted = topologicalSort(schemas)

        val fileBuilder = FileSpec.builder(packageName, "EntReadClient")
            // The client implements the `@EntktInternal`-guarded
            // EntReadRuntime, the repos implement the guarded read
            // surfaces, and the constructors are themselves guarded; the
            // file-level OptIn consumes the requirement here without
            // propagating it to rule code.
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%T::class", ENTKT_INTERNAL)
                    .build()
            )

        for (input in schemas) {
            fileBuilder.addType(buildReadRepo(input, schemaNames))
        }
        fileBuilder.addType(buildClient(sorted))

        return fileBuilder.build()
    }

    private fun buildReadRepo(
        input: SchemaInput,
        schemaNames: Map<EntSchema, String>,
    ): TypeSpec {
        val schemaName = input.name
        val entityClass = ClassName(packageName, schemaName)
        val queryClass = ClassName(packageName, "${schemaName}Query")
        val indexesClass = ClassName(packageName, "${schemaName}Indexes")
        val readSurfaceClass = ClassName(packageName, "${schemaName}ReadSurface")
        val idType = input.schema.id().type.toTypeName()

        return TypeSpec.classBuilder("${schemaName}ReadRepo")
            .addKdoc(
                "Read-only `%L` repository handed to validators and privacy rules via\n" +
                    "`EntReadClient`. Reads behave exactly like the full repo's (same query\n" +
                    "machinery, read interceptors, LOAD-privacy delegation) under the\n" +
                    "client's fixed context; the write surface does not exist here, so\n" +
                    "rule writes fail to compile.",
                schemaName,
            )
            .addSuperinterface(readSurfaceClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addAnnotation(ENTKT_INTERNAL)
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("driver", DRIVER)
                    .addParameter("host", readSurfaceClass)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("driver", DRIVER)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("driver")
                    .build()
            )
            .addProperty(
                // The host client's repo, narrowed to the read surface: the
                // LOAD-privacy delegation target. The narrow type keeps the
                // no-writes guarantee structural — no member anywhere in the
                // read client's object graph is typed EntClient or
                // ${schemaName}Repo.
                PropertySpec.builder("host", readSurfaceClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("host")
                    .build()
            )
            .addProperty(
                // Set by EntReadClient's init block (the client and its
                // repos reference each other, mirroring the EntClient/repo
                // wiring).
                PropertySpec.builder("runtime", ClassName(packageName, "EntReadRuntime"))
                    .addModifiers(KModifier.INTERNAL, KModifier.LATEINIT)
                    .mutable(true)
                    .build()
            )
            .addFunction(
                FunSpec.builder("hasLoadPrivacy")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(Boolean::class)
                    .addStatement("return host.hasLoadPrivacy()")
                    .build()
            )
            .addFunction(
                FunSpec.builder("evaluateLoadPrivacy")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("privacy", PRIVACY_CONTEXT)
                    .addParameter("entity", entityClass)
                    .addStatement("host.evaluateLoadPrivacy(privacy, entity)")
                    .build()
            )
            .addFunction(buildQueryEntry(queryClass, clientRef = "runtime"))
            // Index-helper namespace: the same `${schemaName}Indexes`
            // stages the full repo exposes, constructed with the read
            // runtime. Emitted under the same eligibility condition.
            .also { builder ->
                if (indexHelperTree(input.schema, schemaNames) != null) {
                    builder.addProperty(buildIndexesProperty(indexesClass, clientRef = "runtime"))
                }
            }
            .addFunction(buildByIdOrNull(schemaName, entityClass, idType, clientRef = "runtime"))
            .addFunction(buildByIdOrThrow(entityClass, idType))
            .addFunction(buildVisibleByIdOrNull(entityClass, idType))
            .addFunction(buildByIdOrError(schemaName, entityClass, idType))
            .addFunctions(buildByIdExplainMethods(schemaName, entityClass, idType, clientRef = "runtime"))
            .build()
    }

    private fun buildClient(sorted: List<SchemaInput>): TypeSpec {
        val builder = TypeSpec.classBuilder("EntReadClient")
            .addKdoc(
                "Read-only client exposed in generated validation and privacy contexts.\n" +
                    "Constructed by `EntClient.asReadClientForInternalUse(context)` from the\n" +
                    "operation's current client: same driver instance (a\n" +
                    "transaction-scoped client yields a transaction-scoped read client),\n" +
                    "same read interceptors, same per-repo LOAD-privacy behavior, and the\n" +
                    "passed context fixed for this instance's lifetime — bypass-scoped for\n" +
                    "validation reads, caller-scoped for privacy rule reads. Write-side\n" +
                    "state (`transactionRequirement`, hooks, validation config) is\n" +
                    "deliberately absent — its absence is part of the no-writes guarantee.",
            )
            .addSuperinterface(ClassName(packageName, "EntReadRuntime"))

        val ctor = FunSpec.constructorBuilder()
            .addAnnotation(ENTKT_INTERNAL)
            .addModifiers(KModifier.INTERNAL)
            .addParameter("driver", DRIVER)
            .addParameter("privacyContext", PRIVACY_CONTEXT)
            .addParameter("entityInterceptors", ENT_INTERCEPTORS_CONFIG)
            .addParameter("visibleOverfetchLimit", INT)
        for (input in sorted) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            ctor.addParameter("${propName}Host", ClassName(packageName, "${input.name}ReadSurface"))
        }
        builder.primaryConstructor(ctor.build())

        builder.addProperty(
            PropertySpec.builder("privacyContext", PRIVACY_CONTEXT)
                .addModifiers(KModifier.PRIVATE)
                .initializer("privacyContext")
                .build()
        )
        builder.addProperty(
            // Same instance as the host client's registry, same
            // `@EntktInternal` guard on the override. KotlinPoet merges
            // this same-name-initialized property into the constructor, so
            // the marker needs the `property:` use-site target — opt-in
            // markers can't annotate a parameter.
            PropertySpec.builder("entityInterceptors", ENT_INTERCEPTORS_CONFIG)
                .addAnnotation(
                    AnnotationSpec.builder(ENTKT_INTERNAL)
                        .useSiteTarget(AnnotationSpec.UseSiteTarget.PROPERTY)
                        .build()
                )
                .addModifiers(KModifier.OVERRIDE)
                .initializer("entityInterceptors")
                .build()
        )
        builder.addProperty(
            PropertySpec.builder("visibleOverfetchLimit", INT)
                .addModifiers(KModifier.OVERRIDE)
                .initializer("visibleOverfetchLimit")
                .build()
        )

        for (input in sorted) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            val repoClass = ClassName(packageName, "${input.name}ReadRepo")
            builder.addProperty(
                // Covariant override of EntReadRuntime's read-surface
                // accessor, narrowed to the rule-facing repo.
                PropertySpec.builder(propName, repoClass)
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer("%T(driver, %LHost)", repoClass, propName)
                    .build()
            )
        }

        val init = CodeBlock.builder()
        for (input in sorted) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            init.addStatement("%L.runtime = this", propName)
        }
        builder.addInitializerBlock(init.build())

        builder.addFunction(
            FunSpec.builder("currentPrivacyContext")
                .addModifiers(KModifier.OVERRIDE)
                .returns(PRIVACY_CONTEXT)
                .addStatement("return privacyContext")
                .build()
        )

        builder.addFunction(
            // Under bypass (validation reads) raw and visible coincide, so
            // raw terminals stay available; under a real viewer (privacy
            // rule reads) a privacy-bypassing read could leak invisible
            // rows into an authorization decision — fail loudly instead.
            FunSpec.builder("checkPrivacyBypassingRead")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("terminal", String::class)
                .addCode(
                    CodeBlock.builder()
                        .beginControlFlow("check(privacyContext.viewer is %T.PrivacyBypass)", VIEWER)
                        .addStatement(
                            "terminal + %S",
                            " bypasses LOAD privacy and is unavailable on viewer-scoped privacy-rule " +
                                "readers; use a LOAD-checked terminal (firstOrNull / allOrThrow / the " +
                                "visible* family) instead",
                        )
                        .endControlFlow()
                        .build()
                )
                .build()
        )

        return builder.build()
    }
}
