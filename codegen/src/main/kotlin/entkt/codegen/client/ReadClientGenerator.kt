package entkt.codegen.client

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.SchemaInput
import entkt.codegen.metadata.toTypeName
import entkt.codegen.query.indexHelperTree
import entkt.schema.EntSchema

private val DRIVER = ClassName("entkt.runtime.driver", "DatabaseDriver")
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val PRIVACY_DENIAL = ClassName("entkt.runtime.result", "PrivacyDenial")
private val LIST = ClassName("kotlin.collections", "List")
private val ENT_INTERCEPTORS_CONFIG = ClassName("entkt.runtime.query", "EntInterceptorsConfig")
private val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")
private val TRANSACTION_EXECUTION_GUARD = ClassName("entkt.runtime.result", "TransactionExecutionGuard")
private val TRANSACTION_EXECUTION_TOKEN = ClassName("entkt.runtime.result", "TransactionExecutionToken")

/**
 * Emits the read-only client surface shared validation and privacy rule
 * contexts expose to rule code: the `EntReadClient` interface, the
 * posture-specific `EntValidationReadClient` and `EntPrivacyReadClient`
 * wrappers, the shared internal `EntReadClientImpl`, and one
 * `${Entity}ReadRepo` per schema.
 *
 * The privacy posture is visible in the wrapper type, not hidden
 * instance state: validation rule contexts carry `EntValidationReadClient`
 * (fixed `PrivacyBypass("validation read")` context, so invariant
 * checks are not blocked by LOAD privacy and raw terminals work);
 * privacy rule contexts carry `EntPrivacyReadClient` (the caller's own
 * context, so materializing authorization reads see only what the
 * viewer sees; raw terminals remain explicit storage-level reads that
 * skip LOAD privacy). Both wrappers delegate `EntReadClient` to one
 * `EntReadClientImpl`, which owns the read state and repositories —
 * the two semantic types cannot drift, and helpers that are genuinely
 * posture-agnostic accept the shared interface.
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
 * `EntReadClientImpl` as their [EntReadRuntime], never an `EntClient`
 * — no rule-reachable object holds a full client, even privately. The
 * wrappers do not implement `EntReadRuntime`; that framework-internal
 * contract stays on the delegate. LOAD-privacy behavior is delegated
 * to the host client's repos (typed as the narrow read surfaces), so
 * `hasLoadPrivacy` / `loadDenials` behave identically through
 * either client.
 *
 * Construction is framework-internal: the constructors here carry
 * `@EntktInternal internal`, and the only supported minting paths are
 * the generated evaluators' `asValidationReadClientForInternalUse()` /
 * `asPrivacyReadClientForInternalUse(privacy)` adapter calls. That is
 * a deliberate-use gate, not a security boundary — see the marker's
 * KDoc.
 */
internal class ReadClientGenerator(
    private val packageName: String,
) {

    fun generate(
        schemas: List<SchemaInput>,
        schemaNames: Map<EntSchema, String>,
    ): FileSpec {
        // Same accessor/parameter order as EntClient's repo properties and
        // readClientImpl()'s positional host arguments.
        val sorted = topologicalSort(schemas)

        val fileBuilder = FileSpec.builder(packageName, "EntReadClient")
            // The impl implements the `@EntktInternal`-guarded
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
        fileBuilder.addType(buildClientInterface(sorted))
        fileBuilder.addType(
            buildPostureWrapper(
                name = "EntValidationReadClient",
                kdoc = "Read client handed to validation rules through `ValidationRuleContext`. Reads bypass\n" +
                    "LOAD privacy (fixed `PrivacyBypass(\"validation read\")` context), so\n" +
                    "invariant checks observe all rows and raw terminals (`rawCount`,\n" +
                    "`rawExists`, raw aggregates) are available. Same driver instance as\n" +
                    "the operation's client (transaction-scoped reads see prior writes),\n" +
                    "same read interceptors. The posture is fixed for the instance's\n" +
                    "lifetime — there is no re-scoping surface. Helpers that require\n" +
                    "privacy-bypassing reads should accept this type; posture-agnostic\n" +
                    "helpers accept [EntReadClient].",
            )
        )
        fileBuilder.addType(
            buildPostureWrapper(
                name = "EntPrivacyReadClient",
                kdoc = "Read client handed to privacy rules through `PrivacyRuleContext`. Reads are\n" +
                    "viewer-scoped when they materialize rows: returned entities are\n" +
                    "evaluated under the caller's LOAD privacy. Raw terminals (`rawCount`,\n" +
                    "`rawExists`, raw aggregates) are explicit storage-level reads that\n" +
                    "skip LOAD privacy and entity materialization. Same\n" +
                    "driver instance as the operation's client (transaction-scoped reads\n" +
                    "see prior writes), same read interceptors. The posture is fixed for\n" +
                    "the instance's lifetime — there is no re-scoping surface. Helpers\n" +
                    "that participate in authorization decisions should accept this\n" +
                    "type; posture-agnostic helpers accept [EntReadClient].",
            )
        )
        fileBuilder.addType(buildClientImpl(sorted))

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
                FunSpec.builder("loadDenials")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("privacy", PRIVACY_CONTEXT)
                    .addParameter("entities", LIST.parameterizedBy(entityClass))
                    .returns(LIST.parameterizedBy(PRIVACY_DENIAL.copy(nullable = true)))
                    .addStatement("return host.loadDenials(privacy, entities)")
                    .build()
            )
            .addFunction(
                FunSpec.builder("loadDenialOrNull")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("privacy", PRIVACY_CONTEXT)
                    .addParameter("entity", entityClass)
                    .returns(PRIVACY_DENIAL.copy(nullable = true))
                    .addStatement("return host.loadDenialOrNull(privacy, entity)")
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
            .addFunction(buildFindById(schemaName, entityClass, idType, clientRef = "runtime"))
            .build()
    }

    private fun buildClientInterface(sorted: List<SchemaInput>): TypeSpec {
        val builder = TypeSpec.interfaceBuilder("EntReadClient")
            .addKdoc(
                "Shared read-only repository surface exposed in validation and privacy\n" +
                    "rule contexts. Implemented by [EntValidationReadClient]\n" +
                    "(privacy-bypassing reads, for validators) and [EntPrivacyReadClient]\n" +
                    "(viewer-scoped reads, for privacy rules). A helper accepting this\n" +
                    "interface promises to work correctly under either posture. Raw\n" +
                    "terminals are available under both postures and always skip LOAD\n" +
                    "privacy; helpers should use them only when storage-level facts are\n" +
                    "the intended authorization input. Helpers that rely on one posture\n" +
                    "should accept the matching concrete type instead. Write-side state\n" +
                    "(`transactionRequirement`, hooks, validation config) is deliberately\n" +
                    "absent from the whole surface — its absence is part of the\n" +
                    "no-writes guarantee.",
            )
        for (input in sorted) {
            val propName = input.clientName
            builder.addProperty(
                PropertySpec.builder(propName, ClassName(packageName, "${input.name}ReadRepo"))
                    .addModifiers(KModifier.ABSTRACT)
                    .build()
            )
        }
        return builder.build()
    }

    /**
     * One of the two posture wrappers. Real distinct classes, not type
     * aliases — aliases would not reject cross-posture helper calls.
     * Delegation to the shared [EntReadClientImpl] keeps repository
     * construction and `EntReadRuntime` in one place; the wrapper adds
     * nothing but the statically visible posture.
     */
    private fun buildPostureWrapper(name: String, kdoc: String): TypeSpec {
        val implClass = ClassName(packageName, "EntReadClientImpl")
        return TypeSpec.classBuilder(name)
            .addKdoc(kdoc)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addAnnotation(ENTKT_INTERNAL)
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("delegate", implClass)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("delegate", implClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("delegate")
                    .build()
            )
            .addSuperinterface(ClassName(packageName, "EntReadClient"), CodeBlock.of("delegate"))
            .build()
    }

    private fun buildClientImpl(sorted: List<SchemaInput>): TypeSpec {
        val builder = TypeSpec.classBuilder("EntReadClientImpl")
            .addKdoc(
                "Shared implementation behind [EntValidationReadClient] and\n" +
                    "[EntPrivacyReadClient]. Constructed by the `EntClient` posture\n" +
                "adapters from the operation's current client: same driver instance\n" +
                "(a transaction-scoped client yields a transaction-scoped read\n" +
                    "client), same transaction execution authorization, same read\n" +
                    "interceptors, same per-repo LOAD-privacy\n" +
                    "behavior, and the passed context fixed for this instance's lifetime\n" +
                    "— bypass-scoped for validation reads, caller-scoped for privacy\n" +
                    "rule reads. Owns repository construction and the [EntReadRuntime]\n" +
                    "contract so the two wrappers cannot drift; no public generated\n" +
                    "signature exposes this type.",
            )
            .addAnnotation(ENTKT_INTERNAL)
            .addModifiers(KModifier.INTERNAL)
            .addSuperinterface(ClassName(packageName, "EntReadClient"))
            .addSuperinterface(ClassName(packageName, "EntReadRuntime"))

        val ctor = FunSpec.constructorBuilder()
            .addParameter("driver", DRIVER)
            .addParameter("privacyContext", PRIVACY_CONTEXT)
            .addParameter("entityInterceptors", ENT_INTERCEPTORS_CONFIG)
            .addParameter("transactionExecutionGuard", TRANSACTION_EXECUTION_GUARD)
            .addParameter(
                "transactionExecutionToken",
                TRANSACTION_EXECUTION_TOKEN.copy(nullable = true),
            )
        for (input in sorted) {
            val propName = input.clientName
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
            PropertySpec.builder("transactionExecutionGuard", TRANSACTION_EXECUTION_GUARD)
                .addModifiers(KModifier.PRIVATE)
                .initializer("transactionExecutionGuard")
                .build()
        )
        builder.addProperty(
            PropertySpec.builder(
                "transactionExecutionToken",
                TRANSACTION_EXECUTION_TOKEN.copy(nullable = true),
            )
                .addModifiers(KModifier.PRIVATE)
                .initializer("transactionExecutionToken")
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
        for (input in sorted) {
            val propName = input.clientName
            val repoClass = ClassName(packageName, "${input.name}ReadRepo")
            builder.addProperty(
                // One override satisfies both supertypes: EntReadClient's
                // `${prop}: ${Entity}ReadRepo` accessor exactly, and
                // EntReadRuntime's `${prop}: ${Entity}ReadSurface`
                // accessor covariantly.
                PropertySpec.builder(propName, repoClass)
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer("%T(driver, %LHost)", repoClass, propName)
                    .build()
            )
        }

        val init = CodeBlock.builder()
        for (input in sorted) {
            val propName = input.clientName
            init.addStatement("%L.runtime = this", propName)
        }
        builder.addInitializerBlock(init.build())

        builder.addFunction(
            FunSpec.builder("currentPrivacyContext")
                .addModifiers(KModifier.OVERRIDE)
                .returns(PRIVACY_CONTEXT)
                .addStatement(
                    "transactionExecutionGuard.checkClientOperation(transactionExecutionToken)",
                )
                .addStatement("return privacyContext")
                .build()
        )

        return builder.build()
    }
}
