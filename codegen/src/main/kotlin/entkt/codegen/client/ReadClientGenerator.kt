package entkt.codegen.client

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
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
 * contexts expose to rule code: the `ReadOnlyEntClient` interface, its
 * internal `ReadOnlyEntClientImpl`, and one
 * `${Entity}ReadRepo` per schema.
 *
 * Privacy and validation rules receive the same stable, contextless client.
 * Every nested terminal takes a `ViewerContext` explicitly: privacy rules
 * normally pass `context.viewerContext`, while validation rules normally pass
 * `context.readViewerContext`. The client type intentionally does not enforce
 * that convention because callers may explicitly choose another context.
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
 * `ReadOnlyEntClientImpl` as their [EntReadRuntime], never an `EntClient`
 * — no rule-reachable object holds a full client, even privately. The
 * public interface does not extend `EntReadRuntime`; that framework-internal
 * contract stays on the implementation. LOAD-privacy behavior is delegated
 * to the host client's repos (typed as the narrow read surfaces), so
 * `hasLoadPrivacy` / `loadDenials` behave identically through
 * the read-only client.
 *
 * Construction is framework-internal: each `EntClient` constructs one stable
 * implementation, and transaction clients construct one over their transaction
 * driver. The implementation constructor carries
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
        // ReadOnlyEntClientImpl's positional host arguments.
        val sorted = topologicalSort(schemas)

        return kotlinFile(packageName, "ReadOnlyEntClient") {
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
                    "`ReadOnlyEntClient`. Reads behave exactly like the full repo's (same query\n" +
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
                // Set by ReadOnlyEntClientImpl's init block (the client and its
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
        return interfaceType("ReadOnlyEntClient") {
            addKdoc(
                "Read-only repository surface exposed in validation and privacy rule\n" +
                    "contexts. Every terminal requires an explicit `ViewerContext`; the\n" +
                    "supplied context determines LOAD privacy and interceptor behavior.\n" +
                    "Raw terminals always skip LOAD privacy, regardless of context, and\n" +
                    "should be used only when storage-level facts are intended. Write-side state\n" +
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

    private fun buildClientImpl(sorted: List<SchemaInput>): TypeSpec {
        return classType("ReadOnlyEntClientImpl") {
            addKdoc(
                "Contextless implementation of [ReadOnlyEntClient]. Constructed once by\n" +
                    "`EntClient`: same driver instance\n" +
                    "(a transaction-scoped client yields a transaction-scoped read\n" +
                    "client), same transaction execution authorization, same read\n" +
                    "interceptors, and same per-repo LOAD-privacy behavior. Every terminal\n" +
                    "receives its `ViewerContext` explicitly. Owns repository construction and the [EntReadRuntime]\n" +
                    "contract; no public generated\n" +
                    "signature exposes this type.",
            )
            addAnnotation(ENTKT_INTERNAL)
            addModifiers(KModifier.INTERNAL)
            addSuperinterface(ClassName(packageName, "ReadOnlyEntClient"))
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
                    // One override satisfies both supertypes: ReadOnlyEntClient's
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
