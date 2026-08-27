package entkt.codegen.client

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.SchemaInput
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.interfaceType
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.metadata.VIEWER_CONTEXT
import entkt.codegen.metadata.toTypeName
import entkt.codegen.query.indexHelperTree
import entkt.schema.EntSchema

private val DRIVER = ClassName("entkt.runtime.driver", "DatabaseDriver")
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
 * The intended privacy posture is visible in the wrapper and rule-context
 * types, not hidden instance state: validation contexts pair
 * `EntValidationReadClient` with `readViewerContext`, while privacy contexts
 * pair `EntPrivacyReadClient` with the caller's `viewerContext`. Every nested
 * terminal takes that context explicitly. Both stable wrappers delegate
 * `EntReadClient` to one contextless `EntReadClientImpl`, which owns the read
 * state and repositories —
 * the two semantic types cannot drift, and helpers that are genuinely
 * posture-agnostic accept the shared interface.
 *
 * Each read repo exposes the read surface only — the byId family, the
 * full `query { }` DSL (all terminals come with the query class), and
 * the generated index helpers. The write
 * surface (create / update / save / delete* / edge mutators /
 * `withTransaction` / config setters) simply does not
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
 * Construction is framework-internal: `EntClient` constructs one shared
 * implementation and one wrapper of each posture, and transaction clients do
 * the same over their transaction driver. The constructors carry
 * `@EntktInternal internal`; that is a deliberate-use gate, not a security
 * boundary — see the marker's KDoc.
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

        return kotlinFile(packageName, "EntReadClient") {
            // The impl implements the `@EntktInternal`-guarded
            // EntReadRuntime, the repos implement the guarded read
            // surfaces, and the constructors are themselves guarded; the
            // file-level OptIn consumes the requirement here without
            // propagating it to rule code.
            addAnnotation(
                annotation(ClassName("kotlin", "OptIn")) {
                    useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    addMember("%T::class", ENTKT_INTERNAL)
                },
            )

            for (input in schemas) {
                addType(buildReadRepo(input, schemaNames))
            }
            addType(buildClientInterface(sorted))
            addType(
                buildPostureWrapper(
                    name = "EntValidationReadClient",
                    kdoc = "Stable read client handed to validation rules through `ValidationRuleContext`. Rules\n" +
                        "pass `context.readViewerContext` (the framework's explicit\n" +
                        "`PrivacyBypass(\"validation read\")` context), so\n" +
                        "invariant checks observe all rows and raw terminals (`rawCount`,\n" +
                        "`rawExists`, raw aggregates) are available. Same driver instance as\n" +
                        "the operation's client (transaction-scoped reads see prior writes),\n" +
                        "same read interceptors. There is no client re-scoping surface. Helpers that require\n" +
                        "privacy-bypassing reads should accept this type; posture-agnostic\n" +
                        "helpers accept [EntReadClient].",
                ),
            )
            addType(
                buildPostureWrapper(
                    name = "EntPrivacyReadClient",
                    kdoc = "Stable read client handed to privacy rules through `PrivacyRuleContext`. Rules pass\n" +
                        "`context.viewerContext`, so materialized entities are\n" +
                        "evaluated under the caller's LOAD privacy. Raw terminals (`rawCount`,\n" +
                        "`rawExists`, raw aggregates) are explicit storage-level reads that\n" +
                        "skip LOAD privacy and entity materialization. Same\n" +
                        "driver instance as the operation's client (transaction-scoped reads\n" +
                        "see prior writes), same read interceptors. There is no client\n" +
                        "re-scoping surface. Helpers\n" +
                        "that participate in authorization decisions should accept this\n" +
                        "type; posture-agnostic helpers accept [EntReadClient].",
                ),
            )
            addType(buildClientImpl(sorted))
        }
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

        return classType("${schemaName}ReadRepo") {
            addKdoc(
                "Read-only `%L` repository handed to validators and privacy rules via\n" +
                    "`EntReadClient`. Reads behave exactly like the full repo's (same query\n" +
                    "machinery, read interceptors, LOAD-privacy delegation) under each\n" +
                    "terminal-supplied context; the write surface does not exist here, so\n" +
                    "rule writes fail to compile.",
                schemaName,
            )
            addSuperinterface(readSurfaceClass)
            primaryConstructor {
                addAnnotation(ENTKT_INTERNAL)
                addModifiers(KModifier.INTERNAL)
                parameter("driver", DRIVER)
                parameter("host", readSurfaceClass)
            }
            property("driver", DRIVER) {
                addModifiers(KModifier.PRIVATE)
                initializer("driver")
            }
            property(
                // The host client's repo, narrowed to the read surface: the
                // LOAD-privacy delegation target. The narrow type keeps the
                // no-writes guarantee structural — no member anywhere in the
                // read client's object graph is typed EntClient or
                // ${schemaName}Repo.
                "host",
                readSurfaceClass,
            ) {
                addModifiers(KModifier.PRIVATE)
                initializer("host")
            }
            property(
                // Set by EntReadClient's init block (the client and its
                // repos reference each other, mirroring the EntClient/repo
                // wiring).
                "runtime",
                ClassName(packageName, "EntReadRuntime"),
            ) {
                addModifiers(KModifier.INTERNAL, KModifier.LATEINIT)
                mutable(true)
            }
            function("hasLoadPrivacy", returnType = BOOLEAN) {
                addModifiers(KModifier.OVERRIDE)
                statement("return host.hasLoadPrivacy()")
            }
            function(
                "loadDenials",
                returnType = LIST.parameterizedBy(PRIVACY_DENIAL.copy(nullable = true)),
            ) {
                addModifiers(KModifier.OVERRIDE)
                parameter("viewerContext", VIEWER_CONTEXT)
                parameter("entities", LIST.parameterizedBy(entityClass))
                statement("return host.loadDenials(viewerContext, entities)")
            }
            function("loadDenialOrNull", returnType = PRIVACY_DENIAL.copy(nullable = true)) {
                addModifiers(KModifier.OVERRIDE)
                parameter("viewerContext", VIEWER_CONTEXT)
                parameter("entity", entityClass)
                statement("return host.loadDenialOrNull(viewerContext, entity)")
            }
            addFunction(buildQueryEntry(queryClass, clientRef = "runtime"))
            // Index-helper namespace: the same `${schemaName}Indexes`
            // stages the full repo exposes, constructed with the read
            // runtime. Emitted under the same eligibility condition.
            if (indexHelperTree(input.schema, schemaNames) != null) {
                addProperty(buildIndexesProperty(indexesClass, clientRef = "runtime"))
            }
            addFunction(buildFindById(schemaName, entityClass, idType, clientRef = "runtime"))
        }
    }

    private fun buildClientInterface(sorted: List<SchemaInput>): TypeSpec {
        return interfaceType("EntReadClient") {
            addKdoc(
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
                property(input.clientName, ClassName(packageName, "${input.name}ReadRepo")) {
                    addModifiers(KModifier.ABSTRACT)
                }
            }
        }
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
        return classType(name) {
            addKdoc(kdoc)
            primaryConstructor {
                addAnnotation(ENTKT_INTERNAL)
                addModifiers(KModifier.INTERNAL)
                parameter("delegate", implClass)
            }
            property("delegate", implClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("delegate")
            }
            addSuperinterface(ClassName(packageName, "EntReadClient"), CodeBlock.of("delegate"))
        }
    }

    private fun buildClientImpl(sorted: List<SchemaInput>): TypeSpec {
        return classType("EntReadClientImpl") {
            addKdoc(
                "Contextless shared implementation behind [EntValidationReadClient] and\n" +
                    "[EntPrivacyReadClient]. Constructed once by `EntClient`: same driver instance\n" +
                    "(a transaction-scoped client yields a transaction-scoped read\n" +
                    "client), same transaction execution authorization, same read\n" +
                    "interceptors, and same per-repo LOAD-privacy behavior. Every terminal\n" +
                    "receives its `ViewerContext` explicitly. Owns repository construction and the [EntReadRuntime]\n" +
                    "contract so the two wrappers cannot drift; no public generated\n" +
                    "signature exposes this type.",
            )
            addAnnotation(ENTKT_INTERNAL)
            addModifiers(KModifier.INTERNAL)
            addSuperinterface(ClassName(packageName, "EntReadClient"))
            addSuperinterface(ClassName(packageName, "EntReadRuntime"))

            primaryConstructor {
                parameter("driver", DRIVER)
                parameter("entityInterceptors", ENT_INTERCEPTORS_CONFIG)
                parameter("transactionExecutionGuard", TRANSACTION_EXECUTION_GUARD)
                parameter(
                    "transactionExecutionToken",
                    TRANSACTION_EXECUTION_TOKEN.copy(nullable = true),
                )
                for (input in sorted) {
                    parameter(
                        "${input.clientName}Host",
                        ClassName(packageName, "${input.name}ReadSurface"),
                    )
                }
            }

            property("transactionExecutionGuard", TRANSACTION_EXECUTION_GUARD) {
                addModifiers(KModifier.PRIVATE)
                initializer("transactionExecutionGuard")
            }
            property(
                "transactionExecutionToken",
                TRANSACTION_EXECUTION_TOKEN.copy(nullable = true),
            ) {
                addModifiers(KModifier.PRIVATE)
                initializer("transactionExecutionToken")
            }
            property(
                // Same instance as the host client's registry, same
                // `@EntktInternal` guard on the override. KotlinPoet merges
                // this same-name-initialized property into the constructor, so
                // the marker needs the `property:` use-site target — opt-in
                // markers can't annotate a parameter.
                "entityInterceptors",
                ENT_INTERCEPTORS_CONFIG,
            ) {
                addAnnotation(
                    annotation(ENTKT_INTERNAL) {
                        useSiteTarget(AnnotationSpec.UseSiteTarget.PROPERTY)
                    },
                )
                addModifiers(KModifier.OVERRIDE)
                initializer("entityInterceptors")
            }
            for (input in sorted) {
                val propName = input.clientName
                val repoClass = ClassName(packageName, "${input.name}ReadRepo")
                property(
                    // One override satisfies both supertypes: EntReadClient's
                    // `${prop}: ${Entity}ReadRepo` accessor exactly, and
                    // EntReadRuntime's `${prop}: ${Entity}ReadSurface`
                    // accessor covariantly.
                    propName,
                    repoClass,
                ) {
                    addModifiers(KModifier.OVERRIDE)
                    initializer("%T(driver, %LHost)", repoClass, propName)
                }
            }

            addInitializerBlock(
                codeBlock {
                    for (input in sorted) {
                        statement("%L.runtime = this", input.clientName)
                    }
                },
            )

            function("checkReadExecution") {
                addModifiers(KModifier.OVERRIDE)
                statement(
                    "transactionExecutionGuard.checkClientOperation(transactionExecutionToken)",
                )
            }
        }
    }
}
