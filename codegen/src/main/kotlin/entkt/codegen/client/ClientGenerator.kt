package entkt.codegen.client

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.SchemaInput
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.companionObject
import entkt.codegen.kotlinpoet.constructor
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.getter
import entkt.codegen.kotlinpoet.interfaceType
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.objectType
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.setter
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.metadata.ENTITY_SCHEMA

private val DRIVER = ClassName("entkt.runtime.driver", "DatabaseDriver")
private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val PRIVACY_CONTEXT_PROVIDER =
    ClassName("entkt.runtime.privacy", "PrivacyContextProvider")
private val MUTATION_RUNTIME = ClassName("entkt.runtime.mutation.execution", "MutationRuntime")
private val MUTATION_EVALUATOR = ClassName("entkt.runtime.mutation.execution", "MutationEvaluator")
private val VIEWER = ClassName("entkt.runtime.privacy", "Viewer")
private val ENTITY_POLICY = ClassName("entkt.runtime.privacy", "EntityPolicy")
private val TRANSACTION_REQUIREMENT = ClassName("entkt.runtime.mutation", "TransactionRequirement")
private val TRANSACTION_REQUIRED_EXCEPTION = ClassName("entkt.runtime.mutation", "TransactionRequiredException")
private val UPDATE_CONSISTENCY = ClassName("entkt.runtime.mutation", "UpdateConsistency")
private val RELATIONSHIP_LOCKING = ClassName("entkt.runtime.mutation", "RelationshipLocking")
private val TRANSACTION_EXECUTION_GUARD = ClassName("entkt.runtime.result", "TransactionExecutionGuard")
private val TRANSACTION_EXECUTION_TOKEN = ClassName("entkt.runtime.result", "TransactionExecutionToken")
private val TRANSACTION_EXECUTION_GUARD_FOR_INTERNAL_USE =
    MemberName("entkt.runtime.result", "transactionExecutionGuardForInternalUse")
private val BATCH_HOOK = ClassName("entkt.runtime.hook", "BatchHook")
private val HOOK = ClassName("entkt.runtime.hook", "Hook")
private val JVM_NAME = ClassName("kotlin.jvm", "JvmName")

/**
 * Emits the top-level `EntClient` that wires every per-schema repo
 * together, plus the hooks DSL classes (`EntClientConfig`,
 * `EntClientHooks`, and per-entity `{Entity}Hooks`).
 *
 * The client takes a [DatabaseDriver] and an optional configuration lambda:
 *
 * ```kotlin
 * val client = EntClient(driver) {
 *     hooks {
 *         users {
 *             beforeSave { it.updatedAt = Instant.now() }
 *         }
 *     }
 * }
 * ```
 *
 * Configuration is resolved before repositories are constructed, so each
 * repository receives its complete hooks, privacy, and validation inputs in
 * its constructor. Transactional and privacy-scoped clients reuse that same
 * resolved configuration.
 */
internal class ClientGenerator(
    private val packageName: String,
) {

    fun generate(schemas: List<SchemaInput>): FileSpec {
        // Sort schemas so that FK dependencies are registered before
        // dependents — e.g. User before Friendship (which references User).
        val sorted = topologicalSort(schemas)

        val clientClass = ClassName(packageName, "EntClient")
        val clientScopeClass = ClassName(packageName, "EntClientScope")
        val transactionClientClass = ClassName(packageName, "EntTransactionClient")
        val hookClientScopeFacadeClass = ClassName(packageName, "_EntHookClientScope")
        val configClass = ClassName(packageName, "EntClientConfig")
        val hooksClass = ClassName(packageName, "EntClientHooks")
        val t = TypeVariableName("T")

        // Generated EntClient reads / writes the
        // `entityInterceptors` property (marked `@EntktInternal`
        // below) during init, clone, and per-terminal interceptor
        // lookup. The file-level OptIn covers all those access
        // sites; application code that wants to reach the raw
        // EntInterceptorsConfig must add its own
        // `@OptIn(EntktInternal::class)` and own the consequences
        // (untyped scope-key-keyed registration can pair a
        // QueryInterceptor<E> with the wrong scope).
        val entityHookTypes = schemas.map(::buildEntityHooksClass)
        val hooksType = buildHooksClass(hooksClass, schemas)

        // Generate EntClientPolicies
        val policiesClass = ClassName(packageName, "EntClientPolicies")
        val policiesType = buildPoliciesClass(policiesClass, schemas)

        // Generate EntClientInterceptors (per-entity + global registration DSL)
        val interceptorsClass = ClassName(packageName, "EntClientInterceptors")
        val interceptorsType = buildInterceptorsClass(interceptorsClass, schemas)

        // Generate EntClientConfig
        val configType = buildConfigClass(
            configClass,
            hooksClass,
            policiesClass,
            interceptorsClass,
            schemas,
        )

        // Generate EntClient
        val configLambda = LambdaTypeName.get(
            receiver = configClass,
            returnType = UNIT,
        )

        val typeSpec = classType("EntClient") {
            // EntClient satisfies the generated read-runtime contract, so
            // repos construct queries exactly as before the contract
            // existed — the query constructors' `EntReadRuntime?` accepts
            // the full client by upcast.
            addSuperinterface(ClassName(packageName, "EntReadRuntime"))
            addSuperinterface(
                MUTATION_RUNTIME.parameterizedBy(
                    ClassName(packageName, "EntPrivacyReadClient"),
                    ClassName(packageName, "EntValidationReadClient"),
                ),
            )
            addSuperinterface(clientScopeClass)
            primaryConstructor {
                addModifiers(KModifier.PRIVATE)
                parameter("driver", DRIVER)
                parameter("configuration", configClass)
            }
            addFunction(constructor {
                parameter("driver", DRIVER)
                parameter("config", configLambda) { defaultValue("{}") }
                callThisConstructor(
                    CodeBlock.of("driver"),
                    CodeBlock.of("%T().apply(config).snapshotForInternalUse()", configClass),
                )
            })
            // Batch-register the complete schema set while initializing
            // the driver property. Each repository registers its own schema
            // from a later property initializer, so this is the earliest
            // point at which the complete set is available.
            //
            // Drivers that materialize storage need the whole set at
            // once: foreign keys between mutually-referencing entities
            // have no valid one-schema-at-a-time creation order. The
            // per-repo `register` calls that follow hit the driver's
            // already-registered fast path.
            property("driver", DRIVER) {
                addModifiers(KModifier.PRIVATE)
                initializer("driver.also { it.registerAll(SCHEMAS) }")
            }
            property("configuration", configClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("configuration")
            }
            property(
                "mutations",
                MUTATION_EVALUATOR.parameterizedBy(
                    ClassName(packageName, "EntPrivacyReadClient"),
                    ClassName(packageName, "EntValidationReadClient"),
                ),
            ) {
                addKdoc("Mutation lifecycles shared by this client's generated repositories.")
                addAnnotation(ClassName("entkt.query", "EntktInternal"))
                addModifiers(KModifier.INTERNAL)
                initializer("%T(driver, this)", MUTATION_EVALUATOR)
            }
            property("privacyContextProvider", PRIVACY_CONTEXT_PROVIDER) {
                addModifiers(KModifier.INTERNAL)
                mutable(true)
                initializer(
                    "configuration.privacyContextProviderConfig ?: %T { %T(%T.Anonymous) }",
                    PRIVACY_CONTEXT_PROVIDER,
                    PRIVACY_CONTEXT,
                    VIEWER,
                )
            }
            property("transactionRequirement", TRANSACTION_REQUIREMENT) {
                addModifiers(KModifier.INTERNAL)
                mutable(true)
                initializer("configuration.transactionRequirement")
            }
            property("defaultUpdateConsistency", UPDATE_CONSISTENCY) {
                addModifiers(KModifier.INTERNAL)
                mutable(true)
                initializer("configuration.defaultUpdateConsistency")
            }
                // Client-wide default RelationshipLocking for symmetric
                // link-table M2M writes. The per-save
                // `relationshipLocking = ...` argument on `update(...)`
                // always overrides this default. OwnerOnly = the existing
                // always-on owner-edge serialization only.
            property("defaultRelationshipLocking", RELATIONSHIP_LOCKING) {
                addModifiers(KModifier.INTERNAL)
                mutable(true)
                initializer("configuration.defaultRelationshipLocking")
            }
                // The per-transaction coordinator, non-null only on the
                // transaction-scoped clone built by withTransaction (and
                // propagated through withPrivacyContext re-scoping inside
                // a transaction block). Mutation terminals record every
                // MutationResult.Failed here via
                // recordTransactionMutationFailure so an ignored failure
                // still marks the scope rollback-only.
            property(
                    "transactionCoordinator",
                    ClassName("entkt.runtime.result", "TransactionCoordinator").copy(nullable = true),
                ) {
                addModifiers(KModifier.INTERNAL)
                mutable(true)
                initializer("null")
            }
                // Shared by every generated client over this root
                // driver. During withTransaction, only the clone
                // carrying the matching token may start a terminal.
            property("transactionExecutionGuard", TRANSACTION_EXECUTION_GUARD) {
                addModifiers(KModifier.INTERNAL)
                mutable(true)
                initializer("%M(driver)", TRANSACTION_EXECUTION_GUARD_FOR_INTERNAL_USE)
            }
            property(
                    "transactionExecutionToken",
                    TRANSACTION_EXECUTION_TOKEN.copy(nullable = true),
                ) {
                addModifiers(KModifier.INTERNAL)
                mutable(true)
                initializer("null")
            }
                // Called by generated mutation terminals at every
                // MutationResult.Failed construction site. No-op outside
                // a transaction scope.
            function("recordTransactionMutationFailure") {
                addAnnotation(ClassName("entkt.query", "EntktInternal"))
                addModifiers(KModifier.OVERRIDE)
                parameter(
                        "exception",
                        ClassName("entkt.runtime.result", "EntMutationException"),
                    )
                statement("transactionCoordinator?.recordFailure(exception)")
            }
                // Per-entity interceptor registries, populated from
                // EntClientConfig.interceptorsConfig in the init
                // block, inherited unchanged by withTransaction /
                // withPrivacyContext / fixed clones. Read by
                // runtime query compilation at each terminal call
                // to supply the registered interceptor chain.
                //
                // Marked `@EntktInternal` so same-module application
                // code can't reach the raw EntInterceptorsConfig and
                // call `addEntity(scopeKey: String, ..., QueryInterceptor<E>)`
                // with an arbitrary E — `entityInterceptorsFor<E>(scopeKey)`
                // does an unchecked cast keyed on the scopeKey string,
                // so a wrong-entity QueryInterceptor would silently
                // bind to a different repo's scope. Generated code
                // reaches it via @file:OptIn at the top of this file.
                //
                // `override` (public getter) satisfies EntReadRuntime —
                // the marker, kept on the override, is what still gates
                // application access; the setter stays `internal`.
            property(
                    "entityInterceptors",
                    ClassName("entkt.runtime.query", "EntInterceptorsConfig"),
                ) {
                addAnnotation(ClassName("entkt.query", "EntktInternal"))
                addModifiers(KModifier.OVERRIDE)
                mutable(true)
                initializer("configuration.interceptorsConfig.config")
                setter { addModifiers(KModifier.INTERNAL) }
            }
                // Generated saves call this at save() / delete() preflight
                // (and at the multi-write equivalents once those land) so a
                // configured TransactionRequirement is enforced *before*
                // hooks, privacy, validation, driver reads, or driver writes.
                // [multiWrite] classifies the calling save as a logical
                // multi-row / multi-write shape. It is independent of how
                // many driver calls implement that shape (createMany now uses
                // one set-based insert); RequiredForMultiWrite fires for the
                // classified shape, RequiredForAllWrites for any write.
            function("checkTransactionRequirement") {
                addAnnotation(ClassName("entkt.query", "EntktInternal"))
                addModifiers(KModifier.OVERRIDE)
                parameter("operation", String::class.asClassName())
                parameter("multiWrite", BOOLEAN)
                addCode(codeBlock {
                    statement(
                                "transactionExecutionGuard.checkClientOperation(transactionExecutionToken)",
                            )
                    beginControlFlow("when (transactionRequirement)")
                    statement("%T.Optional -> Unit", TRANSACTION_REQUIREMENT)
                    beginControlFlow("%T.RequiredForMultiWrite ->", TRANSACTION_REQUIREMENT)
                    beginControlFlow("if (multiWrite && !driver.inTransaction)")
                    statement(
                                "throw %T(operation + %S)",
                                TRANSACTION_REQUIRED_EXCEPTION,
                                " requires a transaction-scoped client (TransactionRequirement.RequiredForMultiWrite)",
                            )
                    endControlFlow()
                    endControlFlow()
                    beginControlFlow("%T.RequiredForAllWrites ->", TRANSACTION_REQUIREMENT)
                    beginControlFlow("if (!driver.inTransaction)")
                    statement(
                                "throw %T(operation + %S)",
                                TRANSACTION_REQUIRED_EXCEPTION,
                                " requires a transaction-scoped client (TransactionRequirement.RequiredForAllWrites)",
                            )
                    endControlFlow()
                    endControlFlow()
                    endControlFlow()
                })
            }
                // Hook contexts expose a stable repository capability rather
                // than this full client. The private facade prevents a cast
                // from restoring withTransaction or configuration APIs.
            property("hookClientScopeForInternalUse", clientScopeClass) {
                addModifiers(KModifier.INTERNAL)
                initializer("%T(this)", hookClientScopeFacadeClass)
            }
            addProperties(sorted.map { buildRepoProperty(it) })
            function("currentPrivacyContext", PRIVACY_CONTEXT) {
                addModifiers(KModifier.OVERRIDE)
                statement("transactionExecutionGuard.checkClientOperation(transactionExecutionToken)")
                statement("return privacyContextProvider.get()")
            }
            function("get", PRIVACY_CONTEXT) {
                addModifiers(KModifier.OVERRIDE)
                statement("return currentPrivacyContext()")
            }
            addFunction(buildReadClientImplBuilder(sorted))
            addFunction(buildAsValidationReadClientForInternalUse())
            addFunction(buildAsPrivacyReadClientForInternalUse())
            addFunction(buildPrivacyRuleClient())
            addFunction(buildValidationRuleClient())
            addFunction(buildWithPrivacyContext(clientClass, t))
            addFunction(buildBypassPrivacyDangerous(clientClass, t))
            addFunction(buildWithTransaction(clientClass, transactionClientClass, t))
            addType(buildCompanionObject(sorted))
        }

        return kotlinFile(packageName, "EntClient") {
            addAnnotation(annotation(ClassName("kotlin", "OptIn")) {
                useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                addMember("%T::class", ClassName("entkt.query", "EntktInternal"))
            })
            addTypes(entityHookTypes)
            addType(hooksType)
            addType(policiesType)
            addType(interceptorsType)
            addType(configType)
            addType(buildClientScope(clientScopeClass, sorted))
            addType(
                buildHookClientScopeFacade(
                    clientClass,
                    clientScopeClass,
                    hookClientScopeFacadeClass,
                    sorted,
                ),
            )
            addType(
                buildTransactionClient(
                    clientClass,
                    clientScopeClass,
                    transactionClientClass,
                    sorted,
                ),
            )
            addType(typeSpec)
        }
    }

    private fun buildEntityHooksClass(input: SchemaInput): TypeSpec {
        val schemaName = input.name
        val className = "${schemaName}Hooks"
        val entityClass = ClassName(packageName, schemaName)
        val createHookCtxClass = ClassName(packageName, "${schemaName}CreateHookContext")
        val updateHookCtxClass = ClassName(packageName, "${schemaName}UpdateHookContext")
        val mutationClass = ClassName(packageName, "${schemaName}Mutation")

        val hookDefs = listOf(
            HookDef("beforeSave", mutationClass),
            // beforeCreate hooks see a CreateHookContext (restricted
            // `mutation` view + `client`), not the concrete Create
            // builder. Same shape as the update-side context.
            HookDef("beforeCreate", createHookCtxClass),
            HookDef("afterCreate", entityClass),
            HookDef("beforeUpdate", updateHookCtxClass),
            HookDef("afterUpdate", entityClass),
            HookDef("beforeDelete", entityClass),
            HookDef("afterDelete", entityClass),
        )

        return classType(className) {
            addAnnotation(annotation(ENTKT_DSL))
            for (definition in hookDefs) addHookRegistration(definition)
        }
    }

    /** Emit one ordered hook registry plus its scalar and batch DSL overloads. */
    private fun TypeSpec.Builder.addHookRegistration(definition: HookDef) {
        val lambdaType = LambdaTypeName.get(
            parameters = arrayOf(definition.paramType),
            returnType = UNIT,
        )
        val batchHookType = BATCH_HOOK.parameterizedBy(definition.paramType)
        property("${definition.name}Hooks", MUTABLE_LIST.parameterizedBy(batchHookType)) {
            addModifiers(KModifier.INTERNAL)
            initializer("mutableListOf()")
        }
        function(definition.name) {
            parameter("hook", lambdaType)
            statement("%LHooks.add(%T(hook))", definition.name, HOOK)
        }
        function(definition.name) {
            addAnnotation(annotation(JVM_NAME) {
                addMember("%S", "${definition.name}BatchHook")
            })
            parameter("hook", batchHookType)
            statement("%LHooks.add(hook)", definition.name)
        }
    }

    private fun buildHooksClass(
        hooksClass: ClassName,
        schemas: List<SchemaInput>,
    ): TypeSpec {
        return classType(hooksClass) {
            addAnnotation(annotation(ENTKT_DSL))
            for (input in schemas) {
                val entityHooksClass = ClassName(packageName, "${input.name}Hooks")
                val propName = input.clientName
                property(propName, entityHooksClass) {
                    addModifiers(KModifier.INTERNAL)
                    initializer("%T()", entityHooksClass)
                }
                function(propName) {
                    parameter(
                        "block",
                        LambdaTypeName.get(receiver = entityHooksClass, returnType = UNIT),
                    )
                    statement("%L.apply(block)", propName)
                }
            }
        }
    }

    private fun buildConfigClass(
        configClass: ClassName,
        hooksClass: ClassName,
        policiesClass: ClassName,
        interceptorsClass: ClassName,
        schemas: List<SchemaInput>,
    ): TypeSpec {
        val hooksBlockLambda = LambdaTypeName.get(
            receiver = hooksClass,
            returnType = UNIT,
        )
        val policiesBlockLambda = LambdaTypeName.get(
            receiver = policiesClass,
            returnType = UNIT,
        )
        val interceptorsBlockLambda = LambdaTypeName.get(
            receiver = interceptorsClass,
            returnType = UNIT,
        )
        return classType(configClass) {
            addAnnotation(annotation(ENTKT_DSL))
            property("hooksConfig", hooksClass) {
                addModifiers(KModifier.INTERNAL)
                initializer("%T()", hooksClass)
            }
            property("policiesConfig", policiesClass) {
                addModifiers(KModifier.INTERNAL)
                initializer("%T()", policiesClass)
            }
            property("interceptorsConfig", interceptorsClass) {
                addModifiers(KModifier.INTERNAL)
                initializer("%T()", interceptorsClass)
            }
            property(
                    "privacyContextProviderConfig",
                    PRIVACY_CONTEXT_PROVIDER.copy(nullable = true),
                ) {
                addModifiers(KModifier.INTERNAL)
                mutable(true)
                initializer("null")
            }
                // Configurable per-client transaction requirement. The config
                // exposes it as a public DSL property so callers write
                // `EntClient(driver) { transactionRequirement = TransactionRequirement.RequiredForAllWrites }`.
            property("transactionRequirement", TRANSACTION_REQUIREMENT) {
                mutable(true)
                initializer("%T.Optional", TRANSACTION_REQUIREMENT)
            }
                // Configurable client-wide default UpdateConsistency. The
                // per-save `consistency = ...` argument on `update(...)`
                // always overrides this default.
            property("defaultUpdateConsistency", UPDATE_CONSISTENCY) {
                mutable(true)
                initializer("%T.ReadCurrent", UPDATE_CONSISTENCY)
            }
                // Configurable client-wide default RelationshipLocking.
                // The per-save `relationshipLocking = ...` argument
                // on `update(...)` always overrides this default.
            property("defaultRelationshipLocking", RELATIONSHIP_LOCKING) {
                mutable(true)
                initializer("%T.OwnerOnly", RELATIONSHIP_LOCKING)
            }
            function("hooks") {
                parameter("block", hooksBlockLambda)
                statement("hooksConfig.apply(block)")
            }
            function("policies") {
                parameter("block", policiesBlockLambda)
                statement("policiesConfig.apply(block)")
            }
            function("interceptors") {
                parameter("block", interceptorsBlockLambda)
                statement("interceptorsConfig.apply(block)")
            }
            function("privacyContext") {
                parameter("provider", PRIVACY_CONTEXT_PROVIDER)
                statement("privacyContextProviderConfig = provider")
            }
            addFunction(buildConfigSnapshot(configClass, schemas))
        }
    }

    /** Supply the viewer-scoped read client used by generic CREATE-privacy evaluation. */
    private fun buildPrivacyRuleClient(): FunSpec = function(
        "privacyRuleClient",
        ClassName(packageName, "EntPrivacyReadClient"),
    ) {
        addModifiers(KModifier.OVERRIDE)
        parameter("privacyContext", PRIVACY_CONTEXT)
        statement("return asPrivacyReadClientForInternalUse(privacyContext)")
    }

    /** Supply the privileged read client used by generic CREATE-validation evaluation. */
    private fun buildValidationRuleClient(): FunSpec = function(
        "validationRuleClient",
        ClassName(packageName, "EntValidationReadClient"),
    ) {
        addModifiers(KModifier.OVERRIDE)
        statement("return asValidationReadClientForInternalUse()")
    }

    /** Freeze the mutable construction DSL into the configuration shared by every client scope. */
    private fun buildConfigSnapshot(
        configClass: ClassName,
        schemas: List<SchemaInput>,
    ): FunSpec = function("snapshotForInternalUse", configClass) {
        addAnnotation(ClassName("entkt.query", "EntktInternal"))
        addModifiers(KModifier.INTERNAL)
        statement("val snapshot = %T()", configClass)
        statement("snapshot.privacyContextProviderConfig = privacyContextProviderConfig")
        statement("snapshot.transactionRequirement = transactionRequirement")
        statement("snapshot.defaultUpdateConsistency = defaultUpdateConsistency")
        statement("snapshot.defaultRelationshipLocking = defaultRelationshipLocking")

        for (input in schemas) {
            val clientName = input.clientName
            for (phase in listOf(
                "beforeSave",
                "beforeCreate",
                "afterCreate",
                "beforeUpdate",
                "afterUpdate",
                "beforeDelete",
                "afterDelete",
            )) {
                statement(
                    "snapshot.hooksConfig.%L.%LHooks.addAll(hooksConfig.%L.%LHooks)",
                    clientName,
                    phase,
                    clientName,
                    phase,
                )
            }

            for (operation in listOf("load", "create", "update", "delete")) {
                statement(
                    "snapshot.policiesConfig.%LPrivacyConfig.%LRules" +
                        ".addAll(policiesConfig.%LPrivacyConfig.%LRules)",
                    clientName,
                    operation,
                    clientName,
                    operation,
                )
            }
            statement(
                "snapshot.policiesConfig.%LPrivacyConfig.updateDerivesFromCreate = " +
                    "policiesConfig.%LPrivacyConfig.updateDerivesFromCreate",
                clientName,
                clientName,
            )
            statement(
                "snapshot.policiesConfig.%LPrivacyConfig.deleteDerivesFromCreate = " +
                    "policiesConfig.%LPrivacyConfig.deleteDerivesFromCreate",
                clientName,
                clientName,
            )

            for (operation in listOf("create", "update", "delete")) {
                statement(
                    "snapshot.policiesConfig.%LValidationConfig.%LRules" +
                        ".addAll(policiesConfig.%LValidationConfig.%LRules)",
                    clientName,
                    operation,
                    clientName,
                    operation,
                )
            }
            statement(
                "snapshot.policiesConfig.%LValidationConfig.updateDerivesFromCreate = " +
                    "policiesConfig.%LValidationConfig.updateDerivesFromCreate",
                clientName,
                clientName,
            )
        }

        statement(
            "snapshot.interceptorsConfig.config = " +
                "interceptorsConfig.config.snapshotForInternalUse()",
        )
        statement("return snapshot")
    }

    private fun buildWithTransaction(
        clientClass: ClassName,
        transactionClientClass: ClassName,
        t: TypeVariableName,
    ): FunSpec {
        val transactionScope = ClassName("entkt.runtime.result", "TransactionScope")
        val transactionResult = ClassName("entkt.runtime.result", "TransactionResult")
        val runEntTransaction = MemberName("entkt.runtime.result", "runEntTransaction")
        val body = codeBlock {
        // The boundary loop, rollback-only bookkeeping, and failure
        // precedence live in the runtime's runEntTransaction — this
        // adapter only builds the internal transaction-scoped EntClient,
        // wires the coordinator into it so mutation terminals can record
        // failures, and returns the capability-narrowed public facade.
        // EntTransactionClient has no withTransaction member, so a nested
        // client transaction is unrepresentable through the supported API.
            statement("val executionToken = transactionExecutionGuard.enterTransaction()")
            beginControlFlow("return try")
            beginControlFlow("%M(driver, { txDriver, coordinator ->", runEntTransaction)
            statement("val tx = %T(txDriver, configuration)", clientClass)
            statement("tx.privacyContextProvider = this.privacyContextProvider")
            statement("tx.transactionRequirement = this.transactionRequirement")
            statement("tx.defaultUpdateConsistency = this.defaultUpdateConsistency")
            statement("tx.defaultRelationshipLocking = this.defaultRelationshipLocking")
            statement("tx.entityInterceptors = this.entityInterceptors")
            statement("tx.transactionCoordinator = coordinator")
            statement("tx.transactionExecutionGuard = this.transactionExecutionGuard")
            statement("tx.transactionExecutionToken = executionToken")
            statement("%T(tx)", transactionClientClass)
            endControlFlow()
            add(", block)\n")
            nextControlFlow("finally")
            statement("transactionExecutionGuard.exitTransaction(executionToken)")
            endControlFlow()
        }

        return function("withTransaction", transactionResult.parameterizedBy(t)) {
            addKdoc(
                    "The canonical transaction entry point. The block receives a\n" +
                    "transaction-scoped client. Use that client for every operation in\n" +
                    "the block: using this root client there throws before callbacks or\n" +
                    "database I/O, because it would otherwise use another connection.\n" +
                    "`orRollback()` on a read or mutation result extracts success or\n" +
                    "stops the block; a mutation failure produced through the\n" +
                    "transaction client marks the scope rollback-only even when its\n" +
                    "result is ignored. Returns the exhaustive [TransactionResult];\n" +
                    "project with `getOrThrow()` for throwing behavior.",
            )
            addTypeVariable(t)
            parameter(
                "block",
                LambdaTypeName.get(
                    receiver = transactionScope,
                    parameters = listOf(ParameterSpec.unnamed(transactionClientClass)),
                    returnType = t,
                ),
            )
            addCode(body)
        }
    }

    /**
     * Public transaction-scoped client capability. It delegates every
     * repository operation to the hidden transaction-bound [EntClient]
     * while deliberately omitting `withTransaction`, so nested client
     * transactions fail at compile time rather than at execution.
     * Privacy re-scoping returns another facade over the re-scoped
     * transaction client and therefore cannot restore the root surface.
     */
    private fun buildTransactionClient(
        clientClass: ClassName,
        clientScopeClass: ClassName,
        transactionClientClass: ClassName,
        schemas: List<SchemaInput>,
    ): TypeSpec {
        val t = TypeVariableName("T")
        return classType(transactionClientClass) {
            addKdoc(
                "Transaction-scoped EntKt client supplied to `withTransaction` blocks.\n" +
                    "It exposes the ordinary generated repository surface and preserves\n" +
                    "the transaction across privacy re-scoping, but deliberately has no\n" +
                    "`withTransaction` entry point: nested client transactions are not a\n" +
                    "supported operation and therefore do not compile.",
            )
            addSuperinterface(clientScopeClass)
            primaryConstructor {
                addAnnotation(ClassName("entkt.query", "EntktInternal"))
                addModifiers(KModifier.INTERNAL)
                parameter("delegate", clientClass)
            }
            property("delegate", clientClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("delegate")
            }

            for (input in schemas) {
                val propName = input.clientName
                val repoClass = ClassName(packageName, "${input.name}Repo")
                property(propName, repoClass) {
                    addModifiers(KModifier.OVERRIDE)
                    getter { statement("return delegate.%L", propName) }
                }
            }

            function("currentPrivacyContext", PRIVACY_CONTEXT) {
                addModifiers(KModifier.OVERRIDE)
                statement("return delegate.currentPrivacyContext()")
            }
            function("withPrivacyContext", t) {
                addTypeVariable(t)
                parameter("context", PRIVACY_CONTEXT)
                parameter(
                    "block",
                    LambdaTypeName.get(
                        parameters = listOf(ParameterSpec.unnamed(transactionClientClass)),
                        returnType = t,
                    ),
                )
                addCode(codeBlock {
                    add("return delegate.withPrivacyContext(context) { scoped ->\n")
                    add("  block(%T(scoped))\n", transactionClientClass)
                    add("}\n")
                })
            }
            function("bypassPrivacy_DANGEROUS", t) {
                addTypeVariable(t)
                parameter("reason", String::class.asClassName())
                parameter(
                    "block",
                    LambdaTypeName.get(
                        parameters = listOf(ParameterSpec.unnamed(transactionClientClass)),
                        returnType = t,
                    ),
                )
                statement(
                    "require(reason.isNotBlank()) { %S }",
                    "bypassPrivacy_DANGEROUS requires a non-blank reason",
                )
                statement(
                    "return withPrivacyContext(%T(%T.PrivacyBypass(reason)), block)",
                    PRIVACY_CONTEXT,
                    VIEWER,
                )
            }
        }
    }

    /**
     * Repository capability shared by root and transaction-scoped clients.
     * It deliberately omits transaction entry, privacy re-scoping, bypass,
     * and configuration APIs, so helpers can operate on either client without
     * regaining capabilities that are invalid from nested contexts.
     */
    private fun buildClientScope(
        clientScopeClass: ClassName,
        schemas: List<SchemaInput>,
    ): TypeSpec {
        return interfaceType(clientScopeClass) {
            addKdoc(
                "Common generated repository surface implemented by [EntClient] and\n" +
                    "[EntTransactionClient]. Accept this type in helpers that should work\n" +
                    "with either client. It intentionally omits transaction entry, privacy\n" +
                    "re-scoping and bypass, and client configuration APIs.\n",
            )
            for (input in schemas) {
                property(input.clientName, ClassName(packageName, "${input.name}Repo")) {
                    addModifiers(KModifier.ABSTRACT)
                }
            }
            function("currentPrivacyContext", PRIVACY_CONTEXT) {
                addModifiers(KModifier.ABSTRACT)
            }
        }
    }

    /**
     * Actual object handed to hook contexts. Although both public clients
     * implement [clientScopeClass], passing either concrete client would let
     * application code cast back to it and recover broader capabilities. This
     * private facade exposes only the common interface while delegating to the
     * repositories already bound to the correct driver and transaction.
     */
    private fun buildHookClientScopeFacade(
        clientClass: ClassName,
        clientScopeClass: ClassName,
        facadeClass: ClassName,
        schemas: List<SchemaInput>,
    ): TypeSpec {
        return classType(facadeClass) {
            addModifiers(KModifier.PRIVATE)
            addSuperinterface(clientScopeClass)
            primaryConstructor { parameter("delegate", clientClass) }
            property("delegate", clientClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("delegate")
            }
            for (input in schemas) {
                val propName = input.clientName
                property(propName, ClassName(packageName, "${input.name}Repo")) {
                    addModifiers(KModifier.OVERRIDE)
                    getter { statement("return delegate.%L", propName) }
                }
            }
            function("currentPrivacyContext", PRIVACY_CONTEXT) {
                addModifiers(KModifier.OVERRIDE)
                statement("return delegate.currentPrivacyContext()")
            }
        }
    }

    private fun buildCompanionObject(schemas: List<SchemaInput>): TypeSpec {
        val listType = ClassName("kotlin.collections", "List")
            .parameterizedBy(ENTITY_SCHEMA)
        val value = codeBlock {
            add("listOf(\n")
            for ((index, input) in schemas.withIndex()) {
                val suffix = if (index < schemas.lastIndex) "," else ""
                add("  %T.SCHEMA$suffix\n", ClassName(packageName, input.name))
            }
            add(")")
        }
        return entkt.codegen.kotlinpoet.companionObject {
            property("SCHEMAS", listType) { initializer(value) }
        }
    }

    /**
     * `EntClientInterceptors` is the receiver of the
     * `interceptors { ... }` config block. Holds an
     * [EntInterceptorsConfig] internally and exposes one DSL
     * method per entity (e.g. `posts(name = ..., interceptor)`)
     * plus `global(name, interceptor)`.
     *
     * Generated DSL surface:
     *
     *     EntClient(driver) {
     *         interceptors {
     *             posts(TenantReadInterceptor(...), name = "tenant-scope")
     *             global(EnforceMaxLimit(...), name = "global-max-limit")
     *         }
     *     }
     *
     * Name validation (mandatory, framework: prefix reserved,
     * unique within scope) lives in EntInterceptorsConfig at the
     * runtime layer; the generated DSL methods just forward.
     */
    private fun buildInterceptorsClass(
        interceptorsClass: ClassName,
        schemas: List<SchemaInput>,
    ): TypeSpec {
        val ENT_INTERCEPTORS_CONFIG = ClassName("entkt.runtime.query", "EntInterceptorsConfig")
        val QUERY_INTERCEPTOR = ClassName("entkt.runtime.query", "QueryInterceptor")
        val GLOBAL_QUERY_INTERCEPTOR = ClassName("entkt.runtime.query", "GlobalQueryInterceptor")

        return classType(interceptorsClass) {
            addAnnotation(annotation(ENTKT_DSL))
            // `config` is the raw `EntInterceptorsConfig` that holds
            // the per-entity and global interceptor lists. Marked
            // `@EntktInternal internal` so application code in the
            // `interceptors { ... }` block can't reach it as
            // `config.addEntity("posts", ..., QueryInterceptor<User> { ... })`
            // and bypass the typed DSL — `addEntity` itself is also
            // `@EntktInternal` (defense in depth). The typed helper
            // methods below (`posts(...)`, `users(...)`, `global(...)`)
            // live in the generated EntClient.kt file which carries
            // `@file:OptIn(EntktInternal::class)`, so the call sites
            // here compile cleanly.
            property("config", ENT_INTERCEPTORS_CONFIG) {
                addAnnotation(ClassName("entkt.query", "EntktInternal"))
                addModifiers(KModifier.INTERNAL)
                mutable(true)
                initializer("%T()", ENT_INTERCEPTORS_CONFIG)
            }

        // Per-entity DSL methods: `posts(interceptor, name = "...")`.
        for (input in schemas) {
            val entityClass = ClassName(packageName, input.name)
            val propName = input.clientName
            val interceptorType = QUERY_INTERCEPTOR.parameterizedBy(entityClass)
            function(propName) {
                parameter("interceptor", interceptorType)
                parameter("name", String::class.asClassName())
                statement(
                        "config.addEntity(%S, name, interceptor)",
                        propName,
                    )
            }
        }

        // Global: `global(interceptor, name = "...")`.
            function("global") {
                parameter("interceptor", GLOBAL_QUERY_INTERCEPTOR)
                parameter("name", String::class.asClassName())
                statement("config.addGlobal(name, interceptor)")
            }
        }
    }

    private fun buildPoliciesClass(
        policiesClass: ClassName,
        schemas: List<SchemaInput>,
    ): TypeSpec {
        return classType(policiesClass) {
            addAnnotation(annotation(ENTKT_DSL))
            for (input in schemas) {
            val entityClass = ClassName(packageName, input.name)
            val policyScopeClass = ClassName(packageName, "${input.name}PolicyScope")
            val privacyConfigClass = ClassName(packageName, "${input.name}PrivacyConfig")
            val validationConfigClass = ClassName(packageName, "${input.name}ValidationConfig")
            val propName = input.clientName
            val policyType = ENTITY_POLICY.parameterizedBy(entityClass, policyScopeClass)

            // Internal privacy config property
                property("${propName}PrivacyConfig", privacyConfigClass) {
                    addModifiers(KModifier.INTERNAL)
                    initializer("%T()", privacyConfigClass)
                }

            // Internal validation config property
                property("${propName}ValidationConfig", validationConfigClass) {
                    addModifiers(KModifier.INTERNAL)
                    initializer("%T()", validationConfigClass)
                }

            // DSL method: users(policy)
                function(propName) {
                    parameter("policy", policyType)
                    statement(
                        "policy.configure(%T(%LPrivacyConfig, %LValidationConfig))",
                        policyScopeClass,
                        propName,
                        propName,
                    )
                }
            }
        }
    }

    private fun buildWithPrivacyContext(
        clientClass: ClassName,
        t: TypeVariableName,
    ): FunSpec {
        val body = codeBlock {
            statement("val scoped = %T(driver, configuration)", clientClass)
            statement("scoped.privacyContextProvider = %T { context }", PRIVACY_CONTEXT_PROVIDER)
            statement("scoped.transactionRequirement = this.transactionRequirement")
            statement("scoped.defaultUpdateConsistency = this.defaultUpdateConsistency")
            statement("scoped.defaultRelationshipLocking = this.defaultRelationshipLocking")
            statement("scoped.transactionCoordinator = this.transactionCoordinator")
            statement("scoped.transactionExecutionGuard = this.transactionExecutionGuard")
            statement("scoped.transactionExecutionToken = this.transactionExecutionToken")
            statement("scoped.entityInterceptors = this.entityInterceptors")
            statement("return block(scoped)")
        }

        return function("withPrivacyContext", t) {
            addTypeVariable(t)
            parameter("context", PRIVACY_CONTEXT)
            parameter(
                "block",
                LambdaTypeName.get(
                    parameters = listOf(ParameterSpec.unnamed(clientClass)),
                    returnType = t,
                ),
            )
            addCode(body)
        }
    }

    /**
     * The intentionally-noisy privacy escape hatch. Scopes a
     * [Viewer.PrivacyBypass] over [block], preserving the current driver,
     * transaction scope, policies, hooks, and interceptors (it delegates to
     * `withPrivacyContext`). The loud name + required reason make bypass call
     * sites stand out in review and easy to grep for.
     */
    private fun buildBypassPrivacyDangerous(clientClass: ClassName, t: com.squareup.kotlinpoet.TypeVariableName): FunSpec {
        return function("bypassPrivacy_DANGEROUS", t) {
            addKdoc(
                "Run [block] with privacy checks bypassed (LOAD/CREATE/UPDATE/DELETE only —\n" +
                    "validation, hooks, interceptors, transactions, and DB constraints still apply).\n" +
                    "Prefer this over `withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass(...)))`\n" +
                    "so bypass call sites are obvious. [reason] must be non-blank.",
            )
            addTypeVariable(t)
            parameter("reason", String::class.asClassName())
            parameter(
                "block",
                LambdaTypeName.get(
                    parameters = listOf(ParameterSpec.unnamed(clientClass)),
                    returnType = t,
                ),
            )
            statement(
                "require(reason.isNotBlank()) { %S }",
                "bypassPrivacy_DANGEROUS requires a non-blank reason",
            )
            statement(
                "return withPrivacyContext(%T(%T.PrivacyBypass(reason)), block)",
                PRIVACY_CONTEXT, VIEWER,
            )
        }
    }

    private fun buildRepoProperty(input: SchemaInput): PropertySpec {
        val repoClass = ClassName(packageName, "${input.name}Repo")
        val propertyName = input.clientName
        // Covariant override of EntReadRuntime's `${prop}: ${Entity}ReadSurface`
        // accessor — the repo IS the entity's read surface, narrowed to the
        // full repo type for application callers.
        return property(propertyName, repoClass) {
            addModifiers(KModifier.OVERRIDE)
            initializer(
                "%T(\n" +
                    "  driver = driver,\n" +
                    "  client = this,\n" +
                    "  configuredHooks = configuration.hooksConfig.%L,\n" +
                    "  configuredPrivacy = configuration.policiesConfig.%LPrivacyConfig,\n" +
                    "  configuredValidation = configuration.policiesConfig.%LValidationConfig,\n" +
                    ")",
                repoClass,
                propertyName,
                propertyName,
                propertyName,
            )
        }
    }

    /**
     * Generates the private `readClientImpl(context)` builder both
     * posture adapters call: the shared read-only view of this client
     * that generated evaluators hand to rule code (wrapped in a posture
     * type). Copies only the read-relevant adapter state — the same
     * driver instance (so a transaction-scoped client yields a
     * transaction-scoped read client), the passed context fixed for the
     * reader's lifetime, the shared transaction execution authorization,
     * the interceptor registry, and the repos as per-entity read surfaces (LOAD-privacy
     * behavior identical to this client's). `transactionRequirement`,
     * hooks, and validation config are deliberately absent — they are
     * write-side state, and their absence is part of the no-writes
     * guarantee.
     *
     * One builder for both adapters keeps the two semantic wrappers
     * structurally identical; only the fixed context differs.
     */
    private fun buildReadClientImplBuilder(schemas: List<SchemaInput>): FunSpec {
        val implClass = ClassName(packageName, "EntReadClientImpl")
        val body = codeBlock {
            add("return %T(\n", implClass)
            add("  driver,\n")
            add("  context,\n")
            add("  entityInterceptors,\n")
            add("  transactionExecutionGuard,\n")
            add("  transactionExecutionToken,\n")
            for (input in schemas) add("  %L,\n", input.clientName)
            add(")\n")
        }
        return function("readClientImpl", implClass) {
            addModifiers(KModifier.PRIVATE)
            parameter("context", PRIVACY_CONTEXT)
            addCode(body)
        }
    }

    /**
     * Generates `asValidationReadClientForInternalUse()`: the adapter
     * generated validation evaluators call. The
     * `PrivacyBypass("validation read")` context is fixed here rather
     * than at call sites, so evaluators cannot construct a validation
     * reader under an arbitrary context. `@EntktInternal internal`
     * because minting fixed-context readers is framework wiring — the
     * `ForInternalUse` suffix matches the convention the old
     * fixed-context clone used.
     */
    private fun buildAsValidationReadClientForInternalUse(): FunSpec {
        return function(
            "asValidationReadClientForInternalUse",
            ClassName(packageName, "EntValidationReadClient"),
        ) {
            addKdoc(
                "Read-only view of this client for generated validation evaluators,\n" +
                    "with a `PrivacyBypass(\"validation read\")` context fixed for its\n" +
                    "lifetime — invariant checks are not blocked by LOAD privacy, and raw\n" +
                    "terminals are available. Same driver (transaction scoping\n" +
                    "preserved), same read interceptors. No write surface compiles\n" +
                    "against it.",
            )
            addAnnotation(ClassName("entkt.query", "EntktInternal"))
            addModifiers(KModifier.INTERNAL)
            statement(
                "return %T(readClientImpl(%T(%T.PrivacyBypass(%S))))",
                ClassName(packageName, "EntValidationReadClient"),
                PRIVACY_CONTEXT,
                VIEWER,
                "validation read",
            )
        }
    }

    /**
     * Generates `asPrivacyReadClientForInternalUse(privacy)`: the
     * adapter generated privacy evaluators call, freezing the caller's
     * context for the reader's lifetime so authorization reads see only
     * what the viewer sees. A separate adapter (instead of one taking an
     * arbitrary context) prevents evaluators from accidentally minting
     * the wrong semantic wrapper.
     */
    private fun buildAsPrivacyReadClientForInternalUse(): FunSpec {
        return function(
            "asPrivacyReadClientForInternalUse",
            ClassName(packageName, "EntPrivacyReadClient"),
        ) {
            addKdoc(
                "Read-only view of this client for generated privacy evaluators, with\n" +
                    "the caller's [privacy] context fixed for its lifetime — rule reads\n" +
                    "that materialize entities are viewer-scoped. Raw terminals remain\n" +
                    "explicit storage-level reads that skip LOAD privacy. Same driver\n" +
                    "(transaction scoping preserved), same read interceptors. No write\n" +
                    "surface compiles against it.",
            )
            addAnnotation(ClassName("entkt.query", "EntktInternal"))
            addModifiers(KModifier.INTERNAL)
            parameter("privacy", PRIVACY_CONTEXT)
            statement(
                "return %T(readClientImpl(privacy))",
                ClassName(packageName, "EntPrivacyReadClient"),
            )
        }
    }
}

/**
 * Topologically sort schemas so that FK dependencies come before the
 * schemas that reference them. Falls back to the original order for
 * schemas with no dependency relationship (stable sort).
 *
 * Shared by [ClientGenerator], [ReadRuntimeGenerator], and
 * [ReadClientGenerator] so the per-entity accessor order is identical
 * across `EntClient`, `EntReadRuntime`, and `EntReadClient` — and so
 * `readClientImpl()`'s positional host arguments line up with
 * `EntReadClientImpl`'s constructor parameters.
 */
internal fun topologicalSort(schemas: List<SchemaInput>): List<SchemaInput> {
    val bySchema = schemas.associateBy { it.schema }
    // Build adjacency: schema → set of schemas it depends on (FK targets)
    val deps = schemas.associate { input ->
        input to input.schema.edges()
            .filter { edge -> edge.kind is entkt.schema.EdgeKind.BelongsTo }
            .mapNotNull { edge -> bySchema[edge.target] }
            .toSet()
    }

    val result = mutableListOf<SchemaInput>()
    val visited = mutableSetOf<SchemaInput>()
    val visiting = mutableSetOf<SchemaInput>() // cycle guard

    fun visit(input: SchemaInput) {
        if (input in visited) return
        if (input in visiting) return // cycle — break it
        visiting.add(input)
        for (dep in deps[input].orEmpty()) {
            visit(dep)
        }
        visiting.remove(input)
        visited.add(input)
        result.add(input)
    }

    for (input in schemas) visit(input)
    return result
}

private data class HookDef(val name: String, val paramType: ClassName)
