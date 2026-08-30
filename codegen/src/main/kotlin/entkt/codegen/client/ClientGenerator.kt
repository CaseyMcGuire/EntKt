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
private val MUTATION_RUNTIME = ClassName("entkt.runtime.mutation.execution", "MutationRuntime")
private val CREATE_MUTATION_EXECUTOR =
    ClassName("entkt.runtime.mutation.execution", "CreateMutationExecutor")
private val DELETE_MUTATION_EXECUTOR =
    ClassName("entkt.runtime.mutation.execution", "DeleteMutationExecutor")
private val UPDATE_MUTATION_EXECUTOR =
    ClassName("entkt.runtime.mutation.execution", "UpdateMutationExecutor")
private val ENTITY_POLICY = ClassName("entkt.runtime.privacy", "EntityPolicy")
private val TRANSACTION_REQUIREMENT = ClassName("entkt.runtime.mutation", "TransactionRequirement")
private val TRANSACTION_REQUIRED_EXCEPTION = ClassName("entkt.runtime.mutation", "TransactionRequiredException")
private val UPDATE_CONSISTENCY = ClassName("entkt.runtime.mutation", "UpdateConsistency")
private val RELATIONSHIP_LOCKING = ClassName("entkt.runtime.mutation", "RelationshipLocking")
private val TRANSACTION_EXECUTION_GUARD = ClassName("entkt.runtime.result", "TransactionExecutionGuard")
private val TRANSACTION_EXECUTION_TOKEN = ClassName("entkt.runtime.result", "TransactionExecutionToken")
private val TRANSACTION_EXECUTION_GUARD_FOR_INTERNAL_USE =
    MemberName("entkt.runtime.result", "transactionExecutionGuardForInternalUse")
private val HOOK_REGISTRY = ClassName("entkt.runtime.hook", "HookRegistry")

/**
 * Emits the top-level `EntClient` that wires every per-schema repo together,
 * plus one same-named file for each generated client-support type
 * (`EntClientConfig`, `EntClientHooks`, and per-entity `{Entity}Hooks`).
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
 * its constructor. Transactional clients reuse that same resolved
 * configuration; viewer identity remains operation-scoped.
 */
internal class ClientGenerator(
    private val packageName: String,
) {

    fun generate(schemas: List<SchemaInput>): List<FileSpec> {
        // Sort schemas so that FK dependencies are registered before
        // dependents — e.g. User before Friendship (which references User).
        val sorted = topologicalSort(schemas)

        val clientClass = ClassName(packageName, "EntClient")
        val clientScopeClass = ClassName(packageName, "EntClientScope")
        val transactionClientClass = ClassName(packageName, "EntTransactionClient")
        val hookClientScopeFacadeClass = ClassName(packageName, "_EntHookClientScope")
        val newHookClientScope = MemberName(packageName, "newEntHookClientScopeForInternalUse")
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
            addSuperinterface(MUTATION_RUNTIME)
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
                // transaction-scoped clone built by withTransaction.
                // Mutation terminals record every
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
                // block, inherited unchanged by withTransaction. Read by
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
                initializer("%M(this)", newHookClientScope)
            }
            addProperties(sorted.map { buildRepoProperty(it) })
            addProperty(buildReadOnlyClientProperty(sorted))
            property(
                "createMutations",
                CREATE_MUTATION_EXECUTOR.parameterizedBy(
                    ClassName(packageName, "ReadOnlyEntClient"),
                ),
            ) {
                addAnnotation(ClassName("entkt.query", "EntktInternal"))
                addModifiers(KModifier.INTERNAL)
                delegate(
                    "lazy { %T(\n" +
                        "  driver = driver,\n" +
                        "  mutationRuntime = this,\n" +
                        "  ruleClient = readOnlyClient,\n" +
                        ") }",
                    CREATE_MUTATION_EXECUTOR,
                )
            }
            property(
                "deleteMutations",
                DELETE_MUTATION_EXECUTOR.parameterizedBy(
                    ClassName(packageName, "ReadOnlyEntClient"),
                ),
            ) {
                addAnnotation(ClassName("entkt.query", "EntktInternal"))
                addModifiers(KModifier.INTERNAL)
                delegate(
                    "lazy { %T(\n" +
                        "  driver = driver,\n" +
                        "  mutationRuntime = this,\n" +
                        "  ruleClient = readOnlyClient,\n" +
                        ") }",
                    DELETE_MUTATION_EXECUTOR,
                )
            }
            property(
                "updateMutations",
                UPDATE_MUTATION_EXECUTOR.parameterizedBy(
                    ClassName(packageName, "ReadOnlyEntClient"),
                ),
            ) {
                addAnnotation(ClassName("entkt.query", "EntktInternal"))
                addModifiers(KModifier.INTERNAL)
                delegate(
                    "lazy { %T(\n" +
                        "  driver = driver,\n" +
                        "  mutationRuntime = this,\n" +
                        "  ruleClient = readOnlyClient,\n" +
                        ") }",
                    UPDATE_MUTATION_EXECUTOR,
                )
            }
            function("checkReadExecution") {
                addModifiers(KModifier.OVERRIDE)
                statement("transactionExecutionGuard.checkClientOperation(transactionExecutionToken)")
            }
            addFunction(buildWithTransaction(clientClass, transactionClientClass, t))
            addType(buildCompanionObject(sorted))
        }

        val clientScopeType = buildClientScope(clientScopeClass, sorted)
        val hookClientScopeType = buildHookClientScopeFacade(
            clientClass,
            clientScopeClass,
            hookClientScopeFacadeClass,
            sorted,
        )
        val transactionClientType = buildTransactionClient(
            clientClass,
            clientScopeClass,
            transactionClientClass,
            sorted,
        )

        return buildList {
            entityHookTypes.forEach { add(buildClientFile(it)) }
            add(buildClientFile(hooksType))
            add(buildClientFile(policiesType))
            add(buildClientFile(interceptorsType))
            add(buildClientFile(configType))
            add(buildClientFile(clientScopeType))
            add(
                buildClientFile(requireNotNull(hookClientScopeType.name)) {
                    addFunction(
                        buildHookClientScopeFactory(
                            clientClass,
                            clientScopeClass,
                            hookClientScopeFacadeClass,
                        ),
                    )
                    addType(hookClientScopeType)
                },
            )
            add(buildClientFile(transactionClientType))
            add(buildClientFile(typeSpec))
        }
    }

    /** Emit one generated top-level client type in its own same-named file. */
    private fun buildClientFile(type: TypeSpec): FileSpec =
        buildClientFile(requireNotNull(type.name)) { addType(type) }

    /** Emit a generated client-support file with the internal API opt-in. */
    private fun buildClientFile(
        fileName: String,
        content: FileSpec.Builder.() -> Unit,
    ): FileSpec = kotlinFile(packageName, fileName) {
        addAnnotation(annotation(ClassName("kotlin", "OptIn")) {
            useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
            addMember("%T::class", ClassName("entkt.query", "EntktInternal"))
        })
        content(this)
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

    /** Emit one typed registration property backed by the reusable runtime hook registry. */
    private fun TypeSpec.Builder.addHookRegistration(definition: HookDef) {
        property(definition.name, HOOK_REGISTRY.parameterizedBy(definition.paramType)) {
            initializer("%T()", HOOK_REGISTRY)
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
            addFunction(buildConfigSnapshot(configClass, schemas))
        }
    }

    /** Freeze the mutable construction DSL into the configuration shared by every client scope. */
    private fun buildConfigSnapshot(
        configClass: ClassName,
        schemas: List<SchemaInput>,
    ): FunSpec = function("snapshotForInternalUse", configClass) {
        addAnnotation(ClassName("entkt.query", "EntktInternal"))
        addModifiers(KModifier.INTERNAL)
        statement("val snapshot = %T()", configClass)
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
                    "snapshot.hooksConfig.%L.%L.copyFromForInternalUse(hooksConfig.%L.%L)",
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
     */
    private fun buildTransactionClient(
        clientClass: ClassName,
        clientScopeClass: ClassName,
        transactionClientClass: ClassName,
        schemas: List<SchemaInput>,
    ): TypeSpec {
        return classType(transactionClientClass) {
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
        }
    }

    /**
     * Repository capability shared by root and transaction-scoped clients.
     * It deliberately omits transaction entry and configuration APIs, so
     * helpers can operate on either client without
     * regaining capabilities that are invalid from nested contexts.
     */
    private fun buildClientScope(
        clientScopeClass: ClassName,
        schemas: List<SchemaInput>,
    ): TypeSpec {
        return interfaceType(clientScopeClass) {
            for (input in schemas) {
                property(input.clientName, ClassName(packageName, "${input.name}Repo")) {
                    addModifiers(KModifier.ABSTRACT)
                }
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
        }
    }

    /** Keep the concrete hook facade file-private while allowing EntClient to construct it. */
    private fun buildHookClientScopeFactory(
        clientClass: ClassName,
        clientScopeClass: ClassName,
        facadeClass: ClassName,
    ): FunSpec = function("newEntHookClientScopeForInternalUse", clientScopeClass) {
        addAnnotation(ClassName("entkt.query", "EntktInternal"))
        addModifiers(KModifier.INTERNAL)
        parameter("client", clientClass)
        statement("return %T(client)", facadeClass)
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
            // live in a generated client-support file which carries
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

    /** One stable, contextless read-only client shared by every rule context. */
    private fun buildReadOnlyClientProperty(schemas: List<SchemaInput>): PropertySpec {
        val clientClass = ClassName(packageName, "ReadOnlyEntClient")
        val implClass = ClassName(packageName, "ReadOnlyEntClientImpl")
        val body = codeBlock {
            add("lazy { %T(\n", implClass)
            add("  driver,\n")
            add("  entityInterceptors,\n")
            add("  transactionExecutionGuard,\n")
            add("  transactionExecutionToken,\n")
            for (input in schemas) add("  %L,\n", input.clientName)
            add(") }\n")
        }
        return property("readOnlyClient", clientClass) {
            addModifiers(KModifier.INTERNAL)
            delegate(body)
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
 * across `EntClient`, `EntReadRuntime`, and `ReadOnlyEntClient` — and so
 * `buildReadOnlyClientProperty()`'s positional host arguments line up with
 * `ReadOnlyEntClientImpl`'s constructor parameters.
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
