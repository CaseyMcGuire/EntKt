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
import entkt.codegen.metadata.ENTITY_SCHEMA
import entkt.codegen.pluralize

private val DRIVER = ClassName("entkt.runtime.driver", "Driver")
private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
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
 * The client takes a [Driver] and an optional configuration lambda:
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
 * Hooks are registered once at construction time and automatically
 * inherited by transactional clients via `copyHooksFrom`.
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
        val fileBuilder = FileSpec.builder(packageName, "EntClient")
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%T::class", ClassName("entkt.query", "EntktInternal"))
                    .build()
            )

        // Generate per-entity hooks DSL classes
        for (input in schemas) {
            fileBuilder.addType(buildEntityHooksClass(input))
        }

        // Generate EntClientHooks
        fileBuilder.addType(buildHooksClass(hooksClass, schemas))

        // Generate EntClientPolicies
        val policiesClass = ClassName(packageName, "EntClientPolicies")
        fileBuilder.addType(buildPoliciesClass(policiesClass, schemas))

        // Generate EntClientInterceptors (per-entity + global registration DSL)
        val interceptorsClass = ClassName(packageName, "EntClientInterceptors")
        fileBuilder.addType(buildInterceptorsClass(interceptorsClass, schemas))

        // Generate EntClientConfig
        fileBuilder.addType(buildConfigClass(configClass, hooksClass, policiesClass, interceptorsClass))

        // Generate EntClient
        val configLambda = LambdaTypeName.get(
            receiver = configClass,
            returnType = UNIT,
        )

        val privacyProviderType = LambdaTypeName.get(returnType = PRIVACY_CONTEXT)

        val typeSpec = TypeSpec.classBuilder("EntClient")
            // EntClient satisfies the generated read-runtime contract, so
            // repos construct queries exactly as before the contract
            // existed — the query constructors' `EntReadRuntime?` accepts
            // the full client by upcast.
            .addSuperinterface(ClassName(packageName, "EntReadRuntime"))
            .addSuperinterface(clientScopeClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("driver", DRIVER)
                    .addParameter(
                        ParameterSpec.builder("config", configLambda)
                            .defaultValue("{}")
                            .build(),
                    )
                    .build()
            )
            // Batch-register the complete schema set while initializing
            // the driver property — deliberately here rather than in the
            // init block below. Repo properties are declared before that
            // block and each repo registers its own schema from its
            // initializer, so an init-block call would arrive after the
            // one-at-a-time registrations had already happened. The
            // driver property is the first thing constructed, which makes
            // this the earliest point the whole set is available.
            //
            // Drivers that materialize storage need the whole set at
            // once: foreign keys between mutually-referencing entities
            // have no valid one-schema-at-a-time creation order. The
            // per-repo `register` calls that follow hit the driver's
            // already-registered fast path.
            .addProperty(
                PropertySpec.builder("driver", DRIVER)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("driver.also { it.registerAll(SCHEMAS) }")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("privacyContextProvider", privacyProviderType)
                    .addModifiers(KModifier.INTERNAL)
                    .mutable(true)
                    .initializer("{ %T(%T.Anonymous) }", PRIVACY_CONTEXT, VIEWER)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("transactionRequirement", TRANSACTION_REQUIREMENT)
                    .addModifiers(KModifier.INTERNAL)
                    .mutable(true)
                    .initializer("%T.Optional", TRANSACTION_REQUIREMENT)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("defaultUpdateConsistency", UPDATE_CONSISTENCY)
                    .addModifiers(KModifier.INTERNAL)
                    .mutable(true)
                    .initializer("%T.ReadCurrent", UPDATE_CONSISTENCY)
                    .build()
            )
            .addProperty(
                // Client-wide default RelationshipLocking for symmetric
                // link-table M2M writes. The per-save
                // `relationshipLocking = ...` argument on `update(...)`
                // always overrides this default. OwnerOnly = the existing
                // always-on owner-edge serialization only.
                PropertySpec.builder("defaultRelationshipLocking", RELATIONSHIP_LOCKING)
                    .addModifiers(KModifier.INTERNAL)
                    .mutable(true)
                    .initializer("%T.OwnerOnly", RELATIONSHIP_LOCKING)
                    .build()
            )
            .addProperty(
                // The per-transaction coordinator, non-null only on the
                // transaction-scoped clone built by withTransaction (and
                // propagated through withPrivacyContext re-scoping inside
                // a transaction block). Mutation terminals record every
                // MutationResult.Failed here via
                // recordTransactionMutationFailure so an ignored failure
                // still marks the scope rollback-only.
                PropertySpec.builder(
                    "transactionCoordinator",
                    ClassName("entkt.runtime.result", "TransactionCoordinator").copy(nullable = true),
                )
                    .addModifiers(KModifier.INTERNAL)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperty(
                // Shared by every generated client over this root
                // driver. During withTransaction, only the clone
                // carrying the matching token may start a terminal.
                PropertySpec.builder("transactionExecutionGuard", TRANSACTION_EXECUTION_GUARD)
                    .addModifiers(KModifier.INTERNAL)
                    .mutable(true)
                    .initializer("%M(driver)", TRANSACTION_EXECUTION_GUARD_FOR_INTERNAL_USE)
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "transactionExecutionToken",
                    TRANSACTION_EXECUTION_TOKEN.copy(nullable = true),
                )
                    .addModifiers(KModifier.INTERNAL)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addFunction(
                // Called by generated mutation terminals at every
                // MutationResult.Failed construction site. No-op outside
                // a transaction scope.
                FunSpec.builder("recordTransactionMutationFailure")
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter(
                        "exception",
                        ClassName("entkt.runtime.result", "EntMutationException"),
                    )
                    .addStatement("transactionCoordinator?.recordFailure(exception)")
                    .build()
            )
            .addFunction(
                // Called when a bulk terminal re-reports an
                // already-recorded row failure as a batch-level one:
                // the coordinator must retain the batch failure the
                // terminal actually returned, in the row failure's
                // encounter position. No-op outside a transaction scope.
                FunSpec.builder("replaceTransactionMutationFailure")
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter(
                        "original",
                        ClassName("entkt.runtime.result", "EntMutationException"),
                    )
                    .addParameter(
                        "replacement",
                        ClassName("entkt.runtime.result", "EntMutationException"),
                    )
                    .addStatement("transactionCoordinator?.replaceFailure(original, replacement)")
                    .build()
            )
            .addProperty(
                // Per-entity interceptor registries, populated from
                // EntClientConfig.interceptorsConfig in the init
                // block, inherited unchanged by withTransaction /
                // withPrivacyContext / fixed clones. Read by
                // generated wrapper code at each terminal call to
                // feed the InterceptorEngine.
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
                PropertySpec.builder(
                    "entityInterceptors",
                    ClassName("entkt.runtime.query", "EntInterceptorsConfig"),
                )
                    .addAnnotation(ClassName("entkt.query", "EntktInternal"))
                    .addModifiers(KModifier.OVERRIDE)
                    .mutable(true)
                    .initializer("%T()", ClassName("entkt.runtime.query", "EntInterceptorsConfig"))
                    .setter(
                        FunSpec.setterBuilder()
                            .addModifiers(KModifier.INTERNAL)
                            .build()
                    )
                    .build()
            )
            .addFunction(
                // Generated saves call this at save() / delete() preflight
                // (and at the multi-write equivalents once those land) so a
                // configured TransactionRequirement is enforced *before*
                // hooks, privacy, validation, driver reads, or driver writes.
                // [multiWrite] classifies the calling save as a logical
                // multi-row / multi-write shape. It is independent of how
                // many driver calls implement that shape (createMany now uses
                // one set-based insert); RequiredForMultiWrite fires for the
                // classified shape, RequiredForAllWrites for any write.
                FunSpec.builder("checkTransactionRequirement")
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("operation", String::class)
                    .addParameter(
                        ParameterSpec.builder("multiWrite", BOOLEAN)
                            .defaultValue("false")
                            .build(),
                    )
                    .addCode(
                        CodeBlock.builder()
                            .addStatement(
                                "transactionExecutionGuard.checkClientOperation(transactionExecutionToken)",
                            )
                            .beginControlFlow("when (transactionRequirement)")
                            .addStatement("%T.Optional -> Unit", TRANSACTION_REQUIREMENT)
                            .beginControlFlow("%T.RequiredForMultiWrite ->", TRANSACTION_REQUIREMENT)
                            .beginControlFlow("if (multiWrite && !driver.inTransaction)")
                            .addStatement(
                                "throw %T(operation + %S)",
                                TRANSACTION_REQUIRED_EXCEPTION,
                                " requires a transaction-scoped client (TransactionRequirement.RequiredForMultiWrite)",
                            )
                            .endControlFlow()
                            .endControlFlow()
                            .beginControlFlow("%T.RequiredForAllWrites ->", TRANSACTION_REQUIREMENT)
                            .beginControlFlow("if (!driver.inTransaction)")
                            .addStatement(
                                "throw %T(operation + %S)",
                                TRANSACTION_REQUIRED_EXCEPTION,
                                " requires a transaction-scoped client (TransactionRequirement.RequiredForAllWrites)",
                            )
                            .endControlFlow()
                            .endControlFlow()
                            .endControlFlow()
                            .build(),
                    )
                    .build()
            )
            .addProperty(
                // Hook contexts expose a stable repository capability rather
                // than this full client. The private facade prevents a cast
                // from restoring withTransaction or configuration APIs.
                PropertySpec.builder("hookClientScopeForInternalUse", clientScopeClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T(this)", hookClientScopeFacadeClass)
                    .build()
            )
            .addProperties(sorted.map { buildRepoProperty(it) })
            .addInitializerBlock(buildInitBlock(configClass, sorted))
            .addFunction(
                FunSpec.builder("currentPrivacyContext")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(PRIVACY_CONTEXT)
                    .addStatement(
                        "transactionExecutionGuard.checkClientOperation(transactionExecutionToken)",
                    )
                    .addStatement("return privacyContextProvider()")
                    .build()
            )
            .addFunction(buildReadClientImplBuilder(sorted))
            .addFunction(buildAsValidationReadClientForInternalUse())
            .addFunction(buildAsPrivacyReadClientForInternalUse())
            .addFunction(buildWithPrivacyContext(clientClass, t, sorted))
            .addFunction(buildBypassPrivacyDangerous(clientClass, t))
            .addFunction(buildWithTransaction(clientClass, transactionClientClass, t, sorted))
            .addType(buildCompanionObject(sorted))
            .build()

        fileBuilder.addType(buildClientScope(clientScopeClass, sorted))
        fileBuilder.addType(
            buildHookClientScopeFacade(
                clientClass,
                clientScopeClass,
                hookClientScopeFacadeClass,
                sorted,
            )
        )
        fileBuilder.addType(
            buildTransactionClient(
                clientClass,
                clientScopeClass,
                transactionClientClass,
                sorted,
            )
        )
        fileBuilder.addType(typeSpec)

        return fileBuilder.build()
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

        val builder = TypeSpec.classBuilder(className)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())

        for (def in hookDefs) {
            val lambdaType = LambdaTypeName.get(parameters = arrayOf(def.paramType), returnType = UNIT)
            val batchHookType = BATCH_HOOK.parameterizedBy(def.paramType)
            val listType = MUTABLE_LIST.parameterizedBy(batchHookType)

            // Internal property: the hook list
            builder.addProperty(
                PropertySpec.builder("${def.name}Hooks", listType)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("mutableListOf()")
                    .build()
            )

            // Public DSL method: beforeSave { ... }
            builder.addFunction(
                FunSpec.builder(def.name)
                    .addParameter("hook", lambdaType)
                    .addStatement("%LHooks.add(%T(hook))", def.name, HOOK)
                    .build()
            )

            // Explicitly batch-aware hooks use the same lifecycle name and
            // enter the same ordered registry as scalar hook lambdas.
            builder.addFunction(
                FunSpec.builder(def.name)
                    .addAnnotation(
                        AnnotationSpec.builder(JVM_NAME)
                            .addMember("%S", "${def.name}BatchHook")
                            .build(),
                    )
                    .addParameter("hook", batchHookType)
                    .addStatement("%LHooks.add(hook)", def.name)
                    .build()
            )
        }

        return builder.build()
    }

    private fun buildHooksClass(
        hooksClass: ClassName,
        schemas: List<SchemaInput>,
    ): TypeSpec {
        val builder = TypeSpec.classBuilder(hooksClass)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())

        for (input in schemas) {
            val entityHooksClass = ClassName(packageName, "${input.name}Hooks")
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })

            // Internal property holding the entity hooks
            builder.addProperty(
                PropertySpec.builder(propName, entityHooksClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", entityHooksClass)
                    .build()
            )

            // DSL method: users { ... }
            val blockLambda = LambdaTypeName.get(
                receiver = entityHooksClass,
                returnType = UNIT,
            )
            builder.addFunction(
                FunSpec.builder(propName)
                    .addParameter("block", blockLambda)
                    .addStatement("%L.apply(block)", propName)
                    .build()
            )
        }

        return builder.build()
    }

    private fun buildConfigClass(
        configClass: ClassName,
        hooksClass: ClassName,
        policiesClass: ClassName,
        interceptorsClass: ClassName,
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
        val privacyProviderType = LambdaTypeName.get(returnType = PRIVACY_CONTEXT)

        return TypeSpec.classBuilder(configClass)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
            .addProperty(
                PropertySpec.builder("hooksConfig", hooksClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", hooksClass)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("policiesConfig", policiesClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", policiesClass)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("interceptorsConfig", interceptorsClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", interceptorsClass)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("privacyContextProviderConfig", privacyProviderType.copy(nullable = true))
                    .addModifiers(KModifier.INTERNAL)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperty(
                // Configurable per-client transaction requirement. The config
                // exposes it as a public DSL property so callers write
                // `EntClient(driver) { transactionRequirement = TransactionRequirement.RequiredForAllWrites }`.
                PropertySpec.builder("transactionRequirement", TRANSACTION_REQUIREMENT)
                    .mutable(true)
                    .initializer("%T.Optional", TRANSACTION_REQUIREMENT)
                    .build()
            )
            .addProperty(
                // Configurable client-wide default UpdateConsistency. The
                // per-save `consistency = ...` argument on `update(...)`
                // always overrides this default.
                PropertySpec.builder("defaultUpdateConsistency", UPDATE_CONSISTENCY)
                    .mutable(true)
                    .initializer("%T.ReadCurrent", UPDATE_CONSISTENCY)
                    .build()
            )
            .addProperty(
                // Configurable client-wide default RelationshipLocking.
                // The per-save `relationshipLocking = ...` argument
                // on `update(...)` always overrides this default.
                PropertySpec.builder("defaultRelationshipLocking", RELATIONSHIP_LOCKING)
                    .mutable(true)
                    .initializer("%T.OwnerOnly", RELATIONSHIP_LOCKING)
                    .build()
            )
            .addFunction(
                FunSpec.builder("hooks")
                    .addParameter("block", hooksBlockLambda)
                    .addStatement("hooksConfig.apply(block)")
                    .build()
            )
            .addFunction(
                FunSpec.builder("policies")
                    .addParameter("block", policiesBlockLambda)
                    .addStatement("policiesConfig.apply(block)")
                    .build()
            )
            .addFunction(
                FunSpec.builder("interceptors")
                    .addParameter("block", interceptorsBlockLambda)
                    .addStatement("interceptorsConfig.apply(block)")
                    .build()
            )
            .addFunction(
                FunSpec.builder("privacyContext")
                    .addParameter("provider", privacyProviderType)
                    .addStatement("privacyContextProviderConfig = provider")
                    .build()
            )
            .build()
    }

    private fun buildInitBlock(
        configClass: ClassName,
        schemas: List<SchemaInput>,
    ): CodeBlock {
        val block = CodeBlock.builder()
        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            block.addStatement("%L.attachClientForInternalUse(this)", propName)
        }
        block.addStatement("val cfg = %T().apply(config)", configClass)
        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            block.addStatement("%L.applyHooks(cfg.hooksConfig.%L)", propName, propName)
        }
        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            block.addStatement("%L.applyPrivacy(cfg.policiesConfig.%LPrivacyConfig)", propName, propName)
            block.addStatement("%L.applyValidation(cfg.policiesConfig.%LValidationConfig)", propName, propName)
        }
        block.addStatement("cfg.privacyContextProviderConfig?.let { privacyContextProvider = it }")
        block.addStatement("transactionRequirement = cfg.transactionRequirement")
        block.addStatement("defaultUpdateConsistency = cfg.defaultUpdateConsistency")
        block.addStatement("defaultRelationshipLocking = cfg.defaultRelationshipLocking")
        block.addStatement("entityInterceptors = cfg.interceptorsConfig.config")
        return block.build()
    }

    private fun buildWithTransaction(
        clientClass: ClassName,
        transactionClientClass: ClassName,
        t: TypeVariableName,
        schemas: List<SchemaInput>,
    ): FunSpec {
        val transactionScope = ClassName("entkt.runtime.result", "TransactionScope")
        val transactionResult = ClassName("entkt.runtime.result", "TransactionResult")
        val runEntTransaction = MemberName("entkt.runtime.result", "runEntTransaction")
        val body = CodeBlock.builder()
        // The boundary loop, rollback-only bookkeeping, and failure
        // precedence live in the runtime's runEntTransaction — this
        // adapter only builds the internal transaction-scoped EntClient,
        // wires the coordinator into it so mutation terminals can record
        // failures, and returns the capability-narrowed public facade.
        // EntTransactionClient has no withTransaction member, so a nested
        // client transaction is unrepresentable through the supported API.
        body.addStatement("val executionToken = transactionExecutionGuard.enterTransaction()")
        body.beginControlFlow("return try")
        body.beginControlFlow("%M(driver, { txDriver, coordinator ->", runEntTransaction)
        body.addStatement("val tx = %T(txDriver)", clientClass)
        body.addStatement("tx.privacyContextProvider = this.privacyContextProvider")
        body.addStatement("tx.transactionRequirement = this.transactionRequirement")
        body.addStatement("tx.defaultUpdateConsistency = this.defaultUpdateConsistency")
        body.addStatement("tx.defaultRelationshipLocking = this.defaultRelationshipLocking")
        body.addStatement("tx.entityInterceptors = this.entityInterceptors")
        body.addStatement("tx.transactionCoordinator = coordinator")
        body.addStatement("tx.transactionExecutionGuard = this.transactionExecutionGuard")
        body.addStatement("tx.transactionExecutionToken = executionToken")
        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            body.addStatement("tx.%L.copyHooksFrom(this.%L)", propName, propName)
            body.addStatement("tx.%L.copyPrivacyFrom(this.%L)", propName, propName)
            body.addStatement("tx.%L.copyValidationFrom(this.%L)", propName, propName)
        }
        body.addStatement("%T(tx)", transactionClientClass)
        body.endControlFlow()
        body.add(", block)\n")
        body.nextControlFlow("finally")
        body.addStatement("transactionExecutionGuard.exitTransaction(executionToken)")
        body.endControlFlow()

        return FunSpec.builder("withTransaction")
            .addKdoc(
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
            .addTypeVariable(t)
            .addParameter(
                "block",
                LambdaTypeName.get(
                    receiver = transactionScope,
                    parameters = listOf(ParameterSpec.unnamed(transactionClientClass)),
                    returnType = t,
                ),
            )
            .returns(transactionResult.parameterizedBy(t))
            .addCode(body.build())
            .build()
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
        val builder = TypeSpec.classBuilder(transactionClientClass)
            .addKdoc(
                "Transaction-scoped EntKt client supplied to `withTransaction` blocks.\n" +
                    "It exposes the ordinary generated repository surface and preserves\n" +
                    "the transaction across privacy re-scoping, but deliberately has no\n" +
                    "`withTransaction` entry point: nested client transactions are not a\n" +
                    "supported operation and therefore do not compile.",
            )
            .addSuperinterface(clientScopeClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addAnnotation(ClassName("entkt.query", "EntktInternal"))
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("delegate", clientClass)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("delegate", clientClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("delegate")
                    .build(),
            )

        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            val repoClass = ClassName(packageName, "${input.name}Repo")
            builder.addProperty(
                PropertySpec.builder(propName, repoClass)
                    .addModifiers(KModifier.OVERRIDE)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return delegate.%L", propName)
                            .build(),
                    )
                    .build(),
            )
        }

        builder.addFunction(
            FunSpec.builder("currentPrivacyContext")
                .addModifiers(KModifier.OVERRIDE)
                .returns(PRIVACY_CONTEXT)
                .addStatement("return delegate.currentPrivacyContext()")
                .build(),
        )
        builder.addFunction(
            FunSpec.builder("withPrivacyContext")
                .addTypeVariable(t)
                .addParameter("context", PRIVACY_CONTEXT)
                .addParameter(
                    "block",
                    LambdaTypeName.get(
                        parameters = listOf(ParameterSpec.unnamed(transactionClientClass)),
                        returnType = t,
                    ),
                )
                .returns(t)
                .addCode(
                    CodeBlock.builder()
                        .add("return delegate.withPrivacyContext(context) { scoped ->\n")
                        .add("  block(%T(scoped))\n", transactionClientClass)
                        .add("}\n")
                        .build(),
                )
                .build(),
        )
        builder.addFunction(
            FunSpec.builder("bypassPrivacy_DANGEROUS")
                .addTypeVariable(t)
                .addParameter("reason", String::class)
                .addParameter(
                    "block",
                    LambdaTypeName.get(
                        parameters = listOf(ParameterSpec.unnamed(transactionClientClass)),
                        returnType = t,
                    ),
                )
                .returns(t)
                .addStatement(
                    "require(reason.isNotBlank()) { %S }",
                    "bypassPrivacy_DANGEROUS requires a non-blank reason",
                )
                .addStatement(
                    "return withPrivacyContext(%T(%T.PrivacyBypass(reason)), block)",
                    PRIVACY_CONTEXT,
                    VIEWER,
                )
                .build(),
        )

        return builder.build()
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
        val builder = TypeSpec.interfaceBuilder(clientScopeClass)
            .addKdoc(
                "Common generated repository surface implemented by [EntClient] and\n" +
                    "[EntTransactionClient]. Accept this type in helpers that should work\n" +
                    "with either client. It intentionally omits transaction entry, privacy\n" +
                    "re-scoping and bypass, and client configuration APIs.\n",
            )

        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            val repoClass = ClassName(packageName, "${input.name}Repo")
            builder.addProperty(
                PropertySpec.builder(propName, repoClass)
                    .addModifiers(KModifier.ABSTRACT)
                    .build(),
            )
        }

        builder.addFunction(
            FunSpec.builder("currentPrivacyContext")
                .addModifiers(KModifier.ABSTRACT)
                .returns(PRIVACY_CONTEXT)
                .build(),
        )
        return builder.build()
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
        val builder = TypeSpec.classBuilder(facadeClass)
            .addModifiers(KModifier.PRIVATE)
            .addSuperinterface(clientScopeClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("delegate", clientClass)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("delegate", clientClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("delegate")
                    .build(),
            )

        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            val repoClass = ClassName(packageName, "${input.name}Repo")
            builder.addProperty(
                PropertySpec.builder(propName, repoClass)
                    .addModifiers(KModifier.OVERRIDE)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return delegate.%L", propName)
                            .build(),
                    )
                    .build(),
            )
        }

        builder.addFunction(
            FunSpec.builder("currentPrivacyContext")
                .addModifiers(KModifier.OVERRIDE)
                .returns(PRIVACY_CONTEXT)
                .addStatement("return delegate.currentPrivacyContext()")
                .build(),
        )
        return builder.build()
    }

    private fun buildCompanionObject(schemas: List<SchemaInput>): TypeSpec {
        val listType = ClassName("kotlin.collections", "List")
            .parameterizedBy(ENTITY_SCHEMA)
        val code = CodeBlock.builder()
            .add("listOf(\n")
        for ((i, input) in schemas.withIndex()) {
            val entityClass = ClassName(packageName, input.name)
            val suffix = if (i < schemas.size - 1) "," else ""
            code.add("  %T.SCHEMA$suffix\n", entityClass)
        }
        code.add(")")
        return TypeSpec.companionObjectBuilder()
            .addProperty(
                PropertySpec.builder("SCHEMAS", listType)
                    .initializer(code.build())
                    .build()
            )
            .build()
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

        val builder = TypeSpec.classBuilder(interceptorsClass)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
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
            .addProperty(
                PropertySpec.builder("config", ENT_INTERCEPTORS_CONFIG)
                    .addAnnotation(ClassName("entkt.query", "EntktInternal"))
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", ENT_INTERCEPTORS_CONFIG)
                    .build()
            )

        // Per-entity DSL methods: `posts(interceptor, name = "...")`.
        for (input in schemas) {
            val entityClass = ClassName(packageName, input.name)
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            val interceptorType = QUERY_INTERCEPTOR.parameterizedBy(entityClass)
            builder.addFunction(
                FunSpec.builder(propName)
                    .addParameter("interceptor", interceptorType)
                    .addParameter("name", String::class)
                    .addStatement(
                        "config.addEntity(%S, name, interceptor)",
                        propName,
                    )
                    .build()
            )
        }

        // Global: `global(interceptor, name = "...")`.
        builder.addFunction(
            FunSpec.builder("global")
                .addParameter("interceptor", GLOBAL_QUERY_INTERCEPTOR)
                .addParameter("name", String::class)
                .addStatement("config.addGlobal(name, interceptor)")
                .build()
        )

        return builder.build()
    }

    private fun buildPoliciesClass(
        policiesClass: ClassName,
        schemas: List<SchemaInput>,
    ): TypeSpec {
        val builder = TypeSpec.classBuilder(policiesClass)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())

        for (input in schemas) {
            val entityClass = ClassName(packageName, input.name)
            val policyScopeClass = ClassName(packageName, "${input.name}PolicyScope")
            val privacyConfigClass = ClassName(packageName, "${input.name}PrivacyConfig")
            val validationConfigClass = ClassName(packageName, "${input.name}ValidationConfig")
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            val policyType = ENTITY_POLICY.parameterizedBy(entityClass, policyScopeClass)

            // Internal privacy config property
            builder.addProperty(
                PropertySpec.builder("${propName}PrivacyConfig", privacyConfigClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", privacyConfigClass)
                    .build()
            )

            // Internal validation config property
            builder.addProperty(
                PropertySpec.builder("${propName}ValidationConfig", validationConfigClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", validationConfigClass)
                    .build()
            )

            // DSL method: users(policy)
            builder.addFunction(
                FunSpec.builder(propName)
                    .addParameter("policy", policyType)
                    .addStatement("policy.configure(%T(%LPrivacyConfig, %LValidationConfig))", policyScopeClass, propName, propName)
                    .build()
            )
        }

        return builder.build()
    }

    private fun buildWithPrivacyContext(
        clientClass: ClassName,
        t: TypeVariableName,
        schemas: List<SchemaInput>,
    ): FunSpec {
        val body = CodeBlock.builder()
        body.addStatement("val scoped = %T(driver)", clientClass)
        body.addStatement("scoped.privacyContextProvider = { context }")
        body.addStatement("scoped.transactionRequirement = this.transactionRequirement")
        body.addStatement("scoped.defaultUpdateConsistency = this.defaultUpdateConsistency")
        body.addStatement("scoped.defaultRelationshipLocking = this.defaultRelationshipLocking")
        // Propagate the transaction coordinator so a privacy re-scope
        // inside a withTransaction block keeps rollback-only marking.
        body.addStatement("scoped.transactionCoordinator = this.transactionCoordinator")
        body.addStatement("scoped.transactionExecutionGuard = this.transactionExecutionGuard")
        body.addStatement("scoped.transactionExecutionToken = this.transactionExecutionToken")
        body.addStatement("scoped.entityInterceptors = this.entityInterceptors")
        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            body.addStatement("scoped.%L.copyHooksFrom(this.%L)", propName, propName)
            body.addStatement("scoped.%L.copyPrivacyFrom(this.%L)", propName, propName)
            body.addStatement("scoped.%L.copyValidationFrom(this.%L)", propName, propName)
        }
        body.addStatement("return block(scoped)")

        return FunSpec.builder("withPrivacyContext")
            .addTypeVariable(t)
            .addParameter("context", PRIVACY_CONTEXT)
            .addParameter(
                "block",
                LambdaTypeName.get(
                    parameters = listOf(ParameterSpec.unnamed(clientClass)),
                    returnType = t,
                ),
            )
            .returns(t)
            .addCode(body.build())
            .build()
    }

    /**
     * The intentionally-noisy privacy escape hatch. Scopes a
     * [Viewer.PrivacyBypass] over [block], preserving the current driver,
     * transaction scope, policies, hooks, and interceptors (it delegates to
     * `withPrivacyContext`). The loud name + required reason make bypass call
     * sites stand out in review and easy to grep for.
     */
    private fun buildBypassPrivacyDangerous(clientClass: ClassName, t: com.squareup.kotlinpoet.TypeVariableName): FunSpec {
        return FunSpec.builder("bypassPrivacy_DANGEROUS")
            .addKdoc(
                "Run [block] with privacy checks bypassed (LOAD/CREATE/UPDATE/DELETE only —\n" +
                    "validation, hooks, interceptors, transactions, and DB constraints still apply).\n" +
                    "Prefer this over `withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass(...)))`\n" +
                    "so bypass call sites are obvious. [reason] must be non-blank.",
            )
            .addTypeVariable(t)
            .addParameter("reason", String::class)
            .addParameter(
                "block",
                LambdaTypeName.get(
                    parameters = listOf(ParameterSpec.unnamed(clientClass)),
                    returnType = t,
                ),
            )
            .returns(t)
            .addStatement(
                "require(reason.isNotBlank()) { %S }",
                "bypassPrivacy_DANGEROUS requires a non-blank reason",
            )
            .addStatement(
                "return withPrivacyContext(%T(%T.PrivacyBypass(reason)), block)",
                PRIVACY_CONTEXT, VIEWER,
            )
            .build()
    }

    private fun buildRepoProperty(input: SchemaInput): PropertySpec {
        val repoClass = ClassName(packageName, "${input.name}Repo")
        val propertyName = pluralize(input.name.replaceFirstChar { it.lowercase() })
        // Covariant override of EntReadRuntime's `${prop}: ${Entity}ReadSurface`
        // accessor — the repo IS the entity's read surface, narrowed to the
        // full repo type for application callers.
        return PropertySpec.builder(propertyName, repoClass)
            .addModifiers(KModifier.OVERRIDE)
            .initializer("%T(driver)", repoClass)
            .build()
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
        val body = CodeBlock.builder()
        body.add("return %T(\n", implClass)
        body.add("  driver,\n")
        body.add("  context,\n")
        body.add("  entityInterceptors,\n")
        body.add("  transactionExecutionGuard,\n")
        body.add("  transactionExecutionToken,\n")
        for (input in schemas) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            body.add("  %L,\n", propName)
        }
        body.add(")\n")
        return FunSpec.builder("readClientImpl")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("context", PRIVACY_CONTEXT)
            .returns(implClass)
            .addCode(body.build())
            .build()
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
        return FunSpec.builder("asValidationReadClientForInternalUse")
            .addKdoc(
                "Read-only view of this client for generated validation evaluators,\n" +
                    "with a `PrivacyBypass(\"validation read\")` context fixed for its\n" +
                    "lifetime — invariant checks are not blocked by LOAD privacy, and raw\n" +
                    "terminals are available. Same driver (transaction scoping\n" +
                    "preserved), same read interceptors. No write surface compiles\n" +
                    "against it.",
            )
            .addAnnotation(ClassName("entkt.query", "EntktInternal"))
            .addModifiers(KModifier.INTERNAL)
            .returns(ClassName(packageName, "EntValidationReadClient"))
            .addStatement(
                "return %T(readClientImpl(%T(%T.PrivacyBypass(%S))))",
                ClassName(packageName, "EntValidationReadClient"),
                PRIVACY_CONTEXT,
                VIEWER,
                "validation read",
            )
            .build()
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
        return FunSpec.builder("asPrivacyReadClientForInternalUse")
            .addKdoc(
                "Read-only view of this client for generated privacy evaluators, with\n" +
                    "the caller's [privacy] context fixed for its lifetime — rule reads\n" +
                    "that materialize entities are viewer-scoped. Raw terminals remain\n" +
                    "explicit storage-level reads that skip LOAD privacy. Same driver\n" +
                    "(transaction scoping preserved), same read interceptors. No write\n" +
                    "surface compiles against it.",
            )
            .addAnnotation(ClassName("entkt.query", "EntktInternal"))
            .addModifiers(KModifier.INTERNAL)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .returns(ClassName(packageName, "EntPrivacyReadClient"))
            .addStatement(
                "return %T(readClientImpl(privacy))",
                ClassName(packageName, "EntPrivacyReadClient"),
            )
            .build()
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
