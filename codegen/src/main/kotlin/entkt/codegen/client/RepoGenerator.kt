package entkt.codegen.client

import entkt.codegen.apiName
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.lifecycleValueSnapshot
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.anonymousType
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.getter
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.setter
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.idStrategyName
import entkt.codegen.metadata.resolvedTypeName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.toTypeName
import entkt.codegen.metadata.VIEWER_CONTEXT
import entkt.codegen.mutation.MUTATION_RESULT
import entkt.codegen.mutation.MUTATION_VALIDATION_VIOLATION
import entkt.codegen.mutation.CreateGenerator
import entkt.codegen.query.indexHelperTree
import entkt.schema.EntSchema
import entkt.schema.Field
import entkt.codegen.metadata.EdgeFk

private val DRIVER = ClassName("entkt.runtime.driver", "DatabaseDriver")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val LIST = ClassName("kotlin.collections", "List")
private val INT = Int::class.asClassName()
private val UPDATE_CONSISTENCY = ClassName("entkt.runtime.mutation", "UpdateConsistency")
private val RELATIONSHIP_LOCKING = ClassName("entkt.runtime.mutation", "RelationshipLocking")
private val ENT_CLIENT_NAME = "EntClient"
private val PRIVACY_DENIAL = ClassName("entkt.runtime.result", "PrivacyDenial")
private val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")
private val LOAD_PRIVACY_PHASE =
    ClassName("entkt.runtime.query.execution", "LoadPrivacyPhase")
private val LOAD_PRIVACY_PHASE_FACTORY =
    MemberName("entkt.runtime.query.execution", "loadPrivacyPhaseForInternalUse")
private val CREATE_MUTATION_SPEC =
    ClassName("entkt.runtime.mutation.execution", "CreateMutationSpec")
private val CREATE_OPERATION =
    ClassName("entkt.runtime.mutation.execution", "CreateOperation")
private val DELETE_MUTATION_SPEC =
    ClassName("entkt.runtime.mutation.execution", "DeleteMutationSpec")
private val DELETE_OPERATION =
    ClassName("entkt.runtime.mutation.execution", "DeleteOperation")
private val DELETE_RULE_CANDIDATE =
    ClassName("entkt.runtime.mutation.execution", "DeleteRuleCandidate")
private val MUTATION_HOOK_PHASE =
    MemberName("entkt.runtime.mutation.execution", "mutationHookPhaseForInternalUse")
private val MUTATION_PRIVACY_PHASE =
    MemberName("entkt.runtime.mutation.execution", "mutationPrivacyPhaseForInternalUse")
private val MUTATION_VALIDATION_PHASE =
    MemberName("entkt.runtime.mutation.execution", "mutationValidationPhaseForInternalUse")
private val WITH_PRIVACY_FALLBACK =
    MemberName("entkt.runtime.mutation.execution", "withPrivacyFallbackForInternalUse")
private val CREATE_MUTATION = ClassName("entkt.runtime.mutation", "CreateMutation")
private val CREATE_MUTATION_REPOSITORY =
    ClassName("entkt.runtime.mutation", "CreateMutationRepository")

/**
 * Emits a per-schema repository class. The repo is the only entry point
 * for I/O — it owns the [DatabaseDriver] and exposes `query`, `create`,
 * `update(id)`, and `byId` accessors. Its `init` block registers the
 * entity's [entkt.runtime.driver.EntitySchema] so the driver knows the table
 * layout before any other call lands, and every mutation input it hands back is
 * constructed with the same driver reference.
 *
 * The client supplies the repository's runtime context, hooks, privacy,
 * and validation configuration through the constructor. A repository is
 * therefore complete as soon as it is visible; no attach or apply phase is
 * required after construction.
 */
internal class RepoGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}Repo"
        val entityClass = ClassName(packageName, schemaName)
        val createDraftClass = ClassName(packageName, "${schemaName}CreateDraft")
        val updateClass = ClassName(packageName, "${schemaName}Update")
        val queryClass = ClassName(packageName, "${schemaName}Query")
        val indexesClass = ClassName(packageName, "${schemaName}Indexes")
        val mutationClass = ClassName(packageName, "${schemaName}Mutation")
        val createHookCtxClass = ClassName(packageName, "${schemaName}CreateHookContext")
        val entityHooksClass = ClassName(packageName, "${schemaName}Hooks")
        val privacyConfigClass = ClassName(packageName, "${schemaName}PrivacyConfig")
        val validationConfigClass = ClassName(packageName, "${schemaName}ValidationConfig")
        val loadItemClass = ClassName(packageName, "${schemaName}LoadPrivacyItem")
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)
        val idType = schema.id().type.toTypeName()
        val fields = scalarFields(schema)
        val edgeFks = computeEdgeFks(schema, schemaNames)

        val createLambda = LambdaTypeName.get(
            receiver = createDraftClass,
            returnType = UNIT,
        )
        val updateLambda = LambdaTypeName.get(
            receiver = updateClass,
            returnType = UNIT,
        )
        val queryLambda = LambdaTypeName.get(
            receiver = queryClass,
            returnType = UNIT,
        )

        // Hook list types
        val updateHookCtxClass = ClassName(packageName, "${schemaName}UpdateHookContext")
        val batchHookClass = ClassName("entkt.runtime.hook", "BatchHook")
        // beforeCreate hooks receive the restricted CreateHookContext
        // (view + client), not the concrete create draft.
        val beforeCreateHookType = batchHookClass.parameterizedBy(createHookCtxClass)
        val afterCreateHookType = batchHookClass.parameterizedBy(entityClass)
        val beforeUpdateHookType = batchHookClass.parameterizedBy(updateHookCtxClass)
        val afterUpdateHookType = batchHookClass.parameterizedBy(entityClass)
        val beforeDeleteHookType = batchHookClass.parameterizedBy(entityClass)
        val afterDeleteHookType = batchHookClass.parameterizedBy(entityClass)

        fun hookList(hookType: com.squareup.kotlinpoet.TypeName) = LIST.parameterizedBy(hookType)

        val typeSpec = classType(className) {
            // The repo is the entity's read surface: query terminals reach
            // `hasLoadPrivacy()` / `loadDenials(...)` through the
            // EntReadRuntime contract's `${prop}: ${Entity}ReadSurface`
            // accessor, which EntClient overrides with this repo.
            addSuperinterface(ClassName(packageName, "${schemaName}ReadSurface"))
            addSuperinterface(
                CREATE_MUTATION_REPOSITORY.parameterizedBy(createDraftClass, entityClass),
            )
            primaryConstructor {
                addModifiers(KModifier.INTERNAL)
                parameter("driver", DRIVER)
                parameter("client", clientClass)
                parameter("configuredHooks", entityHooksClass)
                parameter("configuredPrivacy", privacyConfigClass)
                parameter("configuredValidation", validationConfigClass)
            }
            property("driver", DRIVER) {
                addModifiers(KModifier.PRIVATE)
                initializer("driver")
            }
            // Private so a repository exposed through EntTransactionClient
            // cannot leak its hidden full EntClient and restore the nested
            // transaction entry point.
            property("client", clientClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("client")
            }
            // Hook list properties
            addProperty(hookListProperty("beforeSaveHooks", hookList(batchHookClass.parameterizedBy(mutationClass))))
            addProperty(hookListProperty("beforeCreateHooks", hookList(beforeCreateHookType)))
            addProperty(hookListProperty("afterCreateHooks", hookList(afterCreateHookType)))
            addProperty(hookListProperty("beforeUpdateHooks", hookList(beforeUpdateHookType)))
            addProperty(hookListProperty("afterUpdateHooks", hookList(afterUpdateHookType)))
            addProperty(hookListProperty("beforeDeleteHooks", hookList(beforeDeleteHookType)))
            addProperty(hookListProperty("afterDeleteHooks", hookList(afterDeleteHookType)))
            // Privacy config
            property("privacyConfig", privacyConfigClass) {
                addModifiers(KModifier.INTERNAL)
                initializer("configuredPrivacy")
            }
            // Validation config
            property("validationConfig", validationConfigClass) {
                addModifiers(KModifier.INTERNAL)
                initializer("configuredValidation")
            }
            addProperty(
                buildLoadPrivacyPhase(
                    queryClass = queryClass,
                    entityClass = entityClass,
                    loadItemClass = loadItemClass,
                    fields = fields,
                ),
            )
            addProperty(
                buildCreateMutationSpec(
                    queryClass = queryClass,
                    createDraftClass = createDraftClass,
                    candidateClass = candidateClass,
                    entityClass = entityClass,
                    privacyItemClass = ClassName(packageName, "${schemaName}CreatePrivacyItem"),
                    validationItemClass = ClassName(packageName, "${schemaName}CreateValidationItem"),
                ),
            )
            addProperty(
                buildCreateOperation(
                    schema = schema,
                    clientName = schema.clientName,
                    createDraftClass = createDraftClass,
                    entityClass = entityClass,
                ),
            )
            addProperty(
                buildDeleteMutationSpec(
                    schemaName = schemaName,
                    queryClass = queryClass,
                    entityClass = entityClass,
                    candidateClass = candidateClass,
                    fields = fields,
                ),
            )
            addProperty(
                buildDeleteOperation(
                    clientName = schema.clientName,
                    entityClass = entityClass,
                ),
            )
            addInitializerBlock(
                CodeBlock.of("driver.register(%T.SCHEMA)\n", entityClass),
            )
            addFunction(buildQueryEntry(queryClass, clientRef = "client"))
            // Index-helper namespace. Emitted only when the schema has at
            // least one eligible index (matching the conditional
            // `${schemaName}Indexes` file).
            if (indexHelperTree(schema, schemaNames) != null) {
                addProperty(buildIndexesProperty(indexesClass, clientRef = "client"))
            }
            addFunction(buildRepoCreate(schema, entityClass, createDraftClass, createLambda))
            addFunction(buildSaveCreation(createDraftClass))
            addFunction(buildSaveAndLoadCreation(createDraftClass, entityClass))
            addFunction(
                buildCreateBeforeSaveView(
                    createDraftClass,
                    mutationClass,
                    fields.filter { !it.immutable },
                    edgeFks.filter { !it.immutable },
                ),
            )
            addFunction(
                buildBeforeCreateContext(
                    createDraftClass,
                    createHookCtxClass,
                    ClassName(packageName, "${schemaName}CreateMutationView"),
                    fields,
                    edgeFks,
                ),
            )
            addFunction(CreateGenerator(packageName).buildResolveFunction(schemaName, schema, schemaNames))
            addFunction(buildSnapshotCreateCandidate(entityClass, candidateClass, fields))
                // Per-save UpdateConsistency override (transaction locking). Defaults
                // to the client's `defaultUpdateConsistency` so callers
                // who don't pass `consistency =` get the configured
                // baseline (`ReadCurrent` unless the EntClientConfig
                // sets otherwise).
            function("update", updateClass) {
                parameter("id", idType)
                parameter("consistency", UPDATE_CONSISTENCY) {
                    defaultValue("client.defaultUpdateConsistency")
                }
                    // Per-save RelationshipLocking override.
                    // Defaults to the client's `defaultRelationshipLocking`
                    // (OwnerOnly unless the EntClientConfig sets otherwise).
                parameter("relationshipLocking", RELATIONSHIP_LOCKING) {
                    defaultValue("client.defaultRelationshipLocking")
                }
                parameter("block", updateLambda)
                statement(
                        "return %T(driver, client, id, consistency, relationshipLocking, beforeSaveHooks, beforeUpdateHooks, afterUpdateHooks).apply(block)",
                        updateClass,
                    )
            }
            addFunction(buildFindById(schemaName, entityClass, idType, clientRef = "client"))
            addFunction(buildDelete(entityClass))
            addFunction(buildDeleteById(entityClass, idType))
            if (idStrategyName(schema) != "EXPLICIT") {
                    addFunction(buildCreateMany(entityClass, createLambda))
            }
            addFunction(buildDeleteMany(entityClass))
            addFunction(buildHasPrivacy("hasLoadPrivacy", readSurfaceOverride = true))
            addFunction(buildHasPrivacy("hasCreatePrivacy"))
            addFunction(buildHasPrivacy("hasUpdatePrivacy"))
            addFunction(buildHasPrivacy("hasDeletePrivacy"))
            addFunction(buildLoadDenials(entityClass))
            addFunction(buildLoadDenialOrNull(entityClass))
            addFunction(buildBuildDeleteCandidate(schemaName, schema, entityClass, candidateClass, schemaNames))
        }

        // The repo class implements the `@EntktInternal`-guarded
        // `${schemaName}ReadSurface`; the file-level OptIn consumes the
        // requirement at the declaration site without propagating it to
        // application code using the repo.
        return kotlinFile(packageName, className) {
            addAnnotation(annotation(ClassName("kotlin", "OptIn")) {
                useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                addMember("%T::class", ClassName("entkt.query", "EntktInternal"))
            })
            addType(typeSpec)
        }
    }

    /**
     * `delete(entity): MutationResult<Unit>` — idempotent entity-handle
     * delete. `Success(Unit)` means the row is absent afterward,
     * whether this call deleted it or it was already absent.
     */
    private fun buildDelete(entityClass: ClassName): FunSpec {
        val resultType = MUTATION_RESULT.parameterizedBy(UNIT)
        return function("delete", resultType) {
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter("entity", entityClass)
            addKdoc(
                "Delete [entity]'s row. The passed entity is an **id handle\n" +
                    "only**: the current row is reloaded (bypassing LOAD privacy — the\n" +
                    "delete-side privacy rule is the authoritative check) and DELETE\n" +
                    "privacy / validation / lifecycle hooks all run against that fresh\n" +
                    "state, never the caller's copy. `Success(Unit)` means the row is\n" +
                    "absent afterward — whether this call deleted it or it was already\n" +
                    "absent (an absent row runs no callbacks; the Boolean affected-row\n" +
                    "signal lives on [deleteById]). Absence is never\n" +
                    "`EntTargetAbsentException`. `Failed` carries a typed\n" +
                    "[entkt.runtime.result.EntMutationException] whose `writeState`\n" +
                    "records the database effect; `Failed` does NOT imply the delete\n" +
                    "rolled back. There is no `orNull()` projection — project with\n" +
                    "`getOrThrow()` or match on the result.",
            )
            addCode(codeBlock {
                add("return deleteOperation.delete(viewerContext, entity)\n")
            })
        }
    }

    /**
     * `deleteById(id): MutationResult<Boolean>` — idempotent
     * delete-by-id preserving the affected-row acknowledgement.
     */
    private fun buildDeleteById(
        entityClass: ClassName,
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec {
        val resultType = MUTATION_RESULT.parameterizedBy(Boolean::class.asClassName())
        return function("deleteById", resultType) {
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter("id", idType)
            addKdoc(
                "Delete the row with [id] through the same reload-then-delete\n" +
                    "pipeline as [delete], preserving the affected-row signal:\n" +
                    "`Success(true)` only when this call deleted the row,\n" +
                    "`Success(false)` when the row was absent at reload time or\n" +
                    "disappeared before the final delete — never\n" +
                    "`EntTargetAbsentException`. An absent row runs no DELETE privacy,\n" +
                    "validation, or lifecycle callbacks. The reload bypasses LOAD\n" +
                    "privacy — the delete-side privacy rule is the authoritative check.\n" +
                    "`Failed` carries a typed\n" +
                    "[entkt.runtime.result.EntMutationException] whose `writeState`\n" +
                    "records the database effect; `Failed` does NOT imply the delete\n" +
                    "rolled back. There is no `orNull()` projection — project with\n" +
                    "`getOrThrow()` or match on the result.",
            )
            addCode(codeBlock {
                add("return deleteOperation.deleteById(viewerContext, id)\n")
            })
        }
    }

    /**
     * `deleteMany(vararg predicates): MutationResult<Int>` — strict,
     * atomic bulk delete. Candidate selection flows through the
     * DELETE_CANDIDATES interceptor chain once. DELETE privacy, validation,
     * and hooks then run phase-major over the complete ordered candidate
     * list before one logical ID-returning driver delete. The write reasserts
     * both the approved IDs and the frozen effective predicates; afterDelete
     * receives only rows the driver confirms it removed.
     */
    private fun buildDeleteMany(
        entityClass: ClassName,
    ): FunSpec {
        val resultType = MUTATION_RESULT.parameterizedBy(INT)
        return function("deleteMany", resultType) {
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter(
                // vararg predicates: Predicate<EntityClass> — typed in
                // the entity scope so callers can only pass predicates
                // for this repo's entity, matching the rest of the
                // typed query DSL surface.
                "predicates",
                PREDICATE.parameterizedBy(entityClass),
            ) { addModifiers(KModifier.VARARG) }
            addKdoc(
                "Atomically delete every row matching [predicates].\n" +
                    "`Success(n)` is the number of rows removed; the whole operation\n" +
                    "shares one transaction (the caller's, or an EntKt-owned one), so\n" +
                    "DELETE privacy, validation, and lifecycle hooks run phase-major\n" +
                    "over all selected candidates. The final write reasserts the\n" +
                    "approved IDs and effective predicates, and afterDelete runs only\n" +
                    "for rows actually removed. A failure leaves no committed subset\n" +
                    "after a confirmed rollback; no denied or invalid candidate is\n" +
                    "silently skipped. `Failed` carries\n" +
                    "a typed [entkt.runtime.result.EntMutationException] whose\n" +
                    "`writeState` records the database effect; inside a caller-owned\n" +
                    "transaction `Failed` marks the scope rollback-only rather than\n" +
                    "implying an immediate rollback. There is no `orNull()`\n" +
                    "projection — project with `getOrThrow()` or match on the result.",
            )
            statement("return deleteOperation.deleteMany(viewerContext, predicates.asList())")
        }
    }

    // Privacy is fail-closed: every operation requires an explicit Allow, so
    // every entity is privacy-enforced regardless of which rules are declared.
    // These flags therefore always report true (the call sites that gate on
    // them always take the enforce path).
    //
    // hasLoadPrivacy is the read surface's flag and overrides
    // `${Entity}ReadSurface` (public — interface members can't be
    // internal); the write-side flags stay internal.
    private fun buildHasPrivacy(name: String, readSurfaceOverride: Boolean = false): FunSpec =
        function(name, Boolean::class.asClassName()) {
            addModifiers(if (readSurfaceOverride) KModifier.OVERRIDE else KModifier.INTERNAL)
            statement("return true")
        }

    /** Bind schema-specific LOAD rules and item snapshots to the reusable runtime phase. */
    private fun buildLoadPrivacyPhase(
        queryClass: ClassName,
        entityClass: ClassName,
        loadItemClass: ClassName,
        fields: List<Field>,
    ): PropertySpec = property("loadPrivacyPhase", LOAD_PRIVACY_PHASE.parameterizedBy(entityClass)) {
        addModifiers(KModifier.PRIVATE)
        initializer(codeBlock {
            add("%M(\n", LOAD_PRIVACY_PHASE_FACTORY)
            indent()
            add("entity = %T.GeneratedEntityMapping,\n", queryClass)
            add("rules = privacyConfig.loadRules,\n")
            add("ruleClientProvider = { client.readOnlyClient },\n")
            add(
                "freshItem = { item -> %T(%L) },\n",
                loadItemClass,
                lifecycleValueSnapshot("item", fields, entityClass),
            )
            unindent()
            add(")")
        })
    }

    private fun buildLoadDenials(
        entityClass: ClassName,
    ): FunSpec =
        function("loadDenials", LIST.parameterizedBy(PRIVACY_DENIAL.copy(nullable = true))) {
            addModifiers(KModifier.OVERRIDE)
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter("entities", LIST.parameterizedBy(entityClass))
            statement("return loadPrivacyPhase.denials(viewerContext, entities)")
        }

    private fun buildLoadDenialOrNull(entityClass: ClassName): FunSpec =
        function("loadDenialOrNull", PRIVACY_DENIAL.copy(nullable = true)) {
            addModifiers(KModifier.OVERRIDE)
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter("entity", entityClass)
            statement("return loadDenials(viewerContext, listOf(entity)).single()")
        }

    private fun buildBuildDeleteCandidate(
        schemaName: String,
        schema: EntSchema,
        entityClass: ClassName,
        candidateClass: ClassName,
        schemaNames: Map<EntSchema, String>,
    ): FunSpec {
        val fields = scalarFields(schema)
        val edgeFks = computeEdgeFks(schema, schemaNames)
        val body = codeBlock {
            add("return %T(\n", candidateClass)
            for (field in fields) {
                add("  %L = entity.%L,\n", field.apiName, field.apiName)
            }
            for (fk in edgeFks) {
                add("  %L = entity.%L,\n", fk.propertyName, fk.propertyName)
            }
            add(")\n")
        }

        return function("buildDeleteCandidate", candidateClass) {
            addModifiers(KModifier.PRIVATE)
            parameter("entity", entityClass)
            addCode(body)
        }
    }

    /** Detach mutable candidate fields before exposing them to one CREATE rule. */
    private fun buildSnapshotCreateCandidate(
        entityClass: ClassName,
        candidateClass: ClassName,
        fields: List<Field>,
    ): FunSpec = function("snapshotCreateCandidate", candidateClass) {
        addModifiers(KModifier.PRIVATE)
        parameter("candidate", candidateClass)
        statement("return %L", lifecycleValueSnapshot("candidate", fields, entityClass))
    }

    /** Capture the immutable inputs consumed by the shared runtime create lifecycle. */
    private fun buildCreateMutationSpec(
        queryClass: ClassName,
        createDraftClass: ClassName,
        candidateClass: ClassName,
        entityClass: ClassName,
        privacyItemClass: ClassName,
        validationItemClass: ClassName,
    ): PropertySpec {
        val specType = CREATE_MUTATION_SPEC.parameterizedBy(
            createDraftClass,
            candidateClass,
            entityClass,
            ClassName(packageName, "ReadOnlyEntClient"),
        )
        return property("createSpec", specType) {
            addModifiers(KModifier.PRIVATE)
            initializer(codeBlock {
                add("%T(\n", CREATE_MUTATION_SPEC)
                indent()
                add("entity = %T.GeneratedEntityMapping,\n", queryClass)
                add("resolveDraft = ::resolve,\n")
                add("beforeSave = %M(beforeSaveHooks) { _, draft -> createBeforeSaveView(draft) },\n", MUTATION_HOOK_PHASE)
                add("beforeCreate = %M(beforeCreateHooks, ::createBeforeCreateContext),\n", MUTATION_HOOK_PHASE)
                add("afterCreate = afterCreateHooks,\n")
                add(
                    "privacy = %M(%S, privacyConfig.createRules) { candidate -> %T(snapshotCreateCandidate(candidate)) },\n",
                    MUTATION_PRIVACY_PHASE,
                    "${entityClass.simpleName} CREATE privacy",
                    privacyItemClass,
                )
                add(
                    "validation = %M(%S, validationConfig.createRules) { candidate -> %T(snapshotCreateCandidate(candidate)) },\n",
                    MUTATION_VALIDATION_PHASE,
                    "${entityClass.simpleName} CREATE validation",
                    validationItemClass,
                )
                unindent()
                add(")")
            })
        }
    }

    /** Bind the generated create adapters once to the runtime create operation. */
    private fun buildCreateOperation(
        schema: EntSchema,
        clientName: String,
        createDraftClass: ClassName,
        entityClass: ClassName,
    ): PropertySpec {
        val operationType = CREATE_OPERATION.parameterizedBy(createDraftClass, entityClass)
        return property("createOperation", operationType) {
            addModifiers(KModifier.PRIVATE)
            delegate(codeBlock {
                add("lazy({\n")
                indent()
                add("client.createMutations.operationForInternalUse(\n")
                indent()
                add("spec = createSpec,\n")
                if (idStrategyName(schema) != "EXPLICIT") {
                    add("newDraft = { %T() },\n", createDraftClass)
                    add("ownedTransaction = { vc, blocks, disclosureCapture ->\n")
                    indent()
                    add("client.withTransaction { tx ->\n")
                    indent()
                    add("tx.%L.createOperation.createManyInOwnedTransactionForInternalUse(\n", clientName)
                    indent()
                    add("vc = vc,\n")
                    add("blocks = blocks,\n")
                    add("disclosureCapture = disclosureCapture,\n")
                    unindent()
                    add(").orRollback()\n")
                    unindent()
                    add("}\n")
                    unindent()
                    add("},\n")
                }
                unindent()
                add(")\n")
                unindent()
                add("})")
            })
        }
    }

    /** Capture schema-specific DELETE adapters while runtime owns lifecycle ordering. */
    private fun buildDeleteMutationSpec(
        schemaName: String,
        queryClass: ClassName,
        entityClass: ClassName,
        candidateClass: ClassName,
        fields: List<Field>,
    ): PropertySpec {
        val deletePrivacyItem = ClassName(packageName, "${schemaName}DeletePrivacyItem")
        val createPrivacyItem = ClassName(packageName, "${schemaName}CreatePrivacyItem")
        val deleteValidationItem = ClassName(packageName, "${schemaName}DeleteValidationItem")
        val entitySnapshot = lifecycleValueSnapshot("item.entity", fields, entityClass)
        val candidateSnapshot = lifecycleValueSnapshot("item.candidate", fields, entityClass)
        val specType = DELETE_MUTATION_SPEC.parameterizedBy(
            entityClass,
            candidateClass,
            ClassName(packageName, "ReadOnlyEntClient"),
        )
        val ruleCandidateType = DELETE_RULE_CANDIDATE.parameterizedBy(entityClass, candidateClass)
        return property("deleteSpec", specType) {
            addModifiers(KModifier.PRIVATE)
            initializer(codeBlock {
                add("%T(\n", DELETE_MUTATION_SPEC)
                indent()
                add("entity = %T.GeneratedEntityMapping,\n", queryClass)
                add("idColumn = %T.SCHEMA.idColumn,\n", entityClass)
                add("newQuery = { %T(driver, client) },\n", queryClass)
                add("candidate = ::buildDeleteCandidate,\n")
                add("privacy = %M(\n", MUTATION_PRIVACY_PHASE)
                indent()
                add("lifecycle = %S,\n", "$schemaName DELETE privacy")
                add("rules = privacyConfig.deleteRules,\n")
                add(
                    "freshItem = { item: %T -> %T(%L, %L) },\n",
                    ruleCandidateType,
                    deletePrivacyItem,
                    entitySnapshot,
                    candidateSnapshot,
                )
                unindent()
                add(").%M(\n", WITH_PRIVACY_FALLBACK)
                indent()
                add("fallback = if (privacyConfig.deleteDerivesFromCreate) {\n")
                indent()
                add("%M(\n", MUTATION_PRIVACY_PHASE)
                indent()
                add("lifecycle = %S,\n", "$schemaName DELETE privacy")
                add("rules = privacyConfig.createRules,\n")
                add(
                    "freshItem = { item: %T -> %T(%L) },\n",
                    ruleCandidateType,
                    createPrivacyItem,
                    candidateSnapshot,
                )
                unindent()
                add(")\n")
                unindent()
                add("} else {\n")
                add("  null\n")
                add("},\n")
                unindent()
                add("),\n")
                add("validation = %M(\n", MUTATION_VALIDATION_PHASE)
                indent()
                add("lifecycle = %S,\n", "$schemaName DELETE validation")
                add("rules = validationConfig.deleteRules,\n")
                add(
                    "freshItem = { item: %T -> %T(%L, %L) },\n",
                    ruleCandidateType,
                    deleteValidationItem,
                    entitySnapshot,
                    candidateSnapshot,
                )
                unindent()
                add("),\n")
                add("beforeDelete = beforeDeleteHooks,\n")
                add("afterDelete = afterDeleteHooks,\n")
                unindent()
                add(")")
            })
        }
    }

    /** Bind the generated delete adapters once to the runtime delete operation. */
    private fun buildDeleteOperation(
        clientName: String,
        entityClass: ClassName,
    ): PropertySpec {
        val operationType = DELETE_OPERATION.parameterizedBy(entityClass)
        return property("deleteOperation", operationType) {
            addModifiers(KModifier.PRIVATE)
            delegate(codeBlock {
                add("lazy({\n")
                indent()
                add("client.deleteMutations.operationForInternalUse(\n")
                indent()
                add("spec = deleteSpec,\n")
                add("ownedTransaction = { vc, predicates ->\n")
                indent()
                add("client.withTransaction { tx ->\n")
                indent()
                add("tx.%L.deleteOperation.deleteManyInOwnedTransactionForInternalUse(\n", clientName)
                indent()
                add("vc = vc,\n")
                add("predicates = predicates,\n")
                unindent()
                add(").orRollback()\n")
                unindent()
                add("}\n")
                unindent()
                add("},\n")
                unindent()
                add(")\n")
                unindent()
                add("})")
            })
        }
    }

    private fun buildRepoCreate(
        schema: EntSchema,
        entityClass: ClassName,
        createDraftClass: ClassName,
        createLambda: LambdaTypeName,
    ): FunSpec {
        val idStrategy = idStrategyName(schema)
        return function("create", CREATE_MUTATION.parameterizedBy(createDraftClass, entityClass)) {
            if (idStrategy == "EXPLICIT") {
                parameter("id", schema.id().type.toTypeName())
            }
            parameter("block", createLambda)
            val createArgs = if (idStrategy == "EXPLICIT") "id = id" else ""
            statement("val draft = %T($createArgs).apply(block)", createDraftClass)
            statement("return %T(draft, this)", CREATE_MUTATION)
        }
    }

    private fun buildSaveCreation(createDraftClass: ClassName): FunSpec =
        function("saveCreation", MUTATION_RESULT.parameterizedBy(UNIT)) {
            addAnnotation(ENTKT_INTERNAL)
            addModifiers(KModifier.OVERRIDE)
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter("draft", createDraftClass)
            addCode(codeBlock {
                    add("return when (val result = createOperation.create(\n")
                    .indent()
                    .add("vc = viewerContext,\n")
                    .add("draft = draft,\n")
                    .add("checkReturnedEntityPrivacy = false,\n")
                    .unindent()
                    .add(")) {\n")
                    .add("  is %T.Success -> %T.Success(Unit)\n", MUTATION_RESULT, MUTATION_RESULT)
                    .add("  is %T.Failed -> result\n", MUTATION_RESULT)
                    .add("}\n")
            })
        }

    private fun buildSaveAndLoadCreation(
        createDraftClass: ClassName,
        entityClass: ClassName,
    ): FunSpec =
        function("saveAndLoadCreation", MUTATION_RESULT.parameterizedBy(entityClass)) {
            addAnnotation(ENTKT_INTERNAL)
            addModifiers(KModifier.OVERRIDE)
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter("draft", createDraftClass)
            statement(
                "return createOperation.create(viewerContext, draft, checkReturnedEntityPrivacy = true)",
            )
        }

    private fun buildCreateBeforeSaveView(
        createDraftClass: ClassName,
        mutationClass: ClassName,
        fields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): FunSpec {
        val adapter = anonymousType {
            addSuperinterface(mutationClass)
            fields.forEach { field ->
                addProperty(createDraftForwarder(
                    field.apiName,
                    field.resolvedTypeName().copy(nullable = true),
                ))
            }
            edgeFks.forEach { fk ->
                addProperty(createDraftForwarder(
                    fk.propertyName,
                    fk.idType.toTypeName().copy(nullable = !fk.required),
                    required = fk.required,
                ))
            }
        }
        return function("createBeforeSaveView", mutationClass) {
            addModifiers(KModifier.PRIVATE)
            parameter("draft", createDraftClass)
            statement("return %L", adapter)
        }
    }

    private fun buildBeforeCreateContext(
        createDraftClass: ClassName,
        createHookContextClass: ClassName,
        createMutationViewClass: ClassName,
        fields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): FunSpec {
        val adapter = anonymousType {
            addSuperinterface(createMutationViewClass)
            fields.forEach { field ->
                addProperty(createDraftForwarder(
                    field.apiName,
                    field.resolvedTypeName().copy(nullable = true),
                ))
            }
            edgeFks.forEach { fk ->
                addProperty(createDraftForwarder(
                    fk.propertyName,
                    fk.idType.toTypeName().copy(nullable = !fk.required),
                    required = fk.required,
                ))
            }
        }
        return function("createBeforeCreateContext", createHookContextClass) {
            addModifiers(KModifier.PRIVATE)
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter("draft", createDraftClass)
            statement(
                "return %T(client.hookClientScopeForInternalUse, viewerContext, %L)",
                createHookContextClass,
                adapter,
            )
        }
    }

    private fun createDraftForwarder(
        propertyName: String,
        type: com.squareup.kotlinpoet.TypeName,
        required: Boolean = false,
    ): PropertySpec {
        return property(propertyName, type) {
            addModifiers(KModifier.OVERRIDE)
            mutable(true)
            getter {
                if (required) {
                    statement(
                        "return draft.%L ?: throw IllegalStateException(%S)",
                        propertyName,
                        "$propertyName is required",
                    )
                } else {
                    statement("return draft.%L", propertyName)
                }
            }
            setter {
                parameter("value", type)
                statement("draft.%L = value", propertyName)
            }
        }
    }

    /**
     * `createMany(*blocks): MutationResult<List<T>>` — strict, atomic,
     * phase-major bulk create. Every draft and before-hook phase completes,
     * then CREATE privacy and validation evaluate the complete candidate list,
     * before one correlated `DatabaseDriver.insertMany` persists the batch. Every row
     * is hydrated before the single afterCreate phase begins.
     *
     * Returned LOAD disclosure uses the same supplied `ViewerContext` instance as
     * CREATE privacy. A caller-owned transaction maps disclosure failure to
     * `TransactionPending` and rollback-only. An EntKt-owned transaction
     * captures it as a neutral value, attempts commit, and reports `Committed`
     * only after commit is confirmed.
     */
    private fun buildCreateMany(
        entityClass: ClassName,
        createLambda: LambdaTypeName,
    ): FunSpec {
        val resultType = MUTATION_RESULT.parameterizedBy(LIST.parameterizedBy(entityClass))
        return function("createMany", resultType) {
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter("blocks", createLambda) { addModifiers(KModifier.VARARG) }
            addKdoc(
                "Atomically create one row per block. Lifecycle work is phase-major:\n" +
                    "all before-hooks, CREATE privacy, and validation finish before one\n" +
                    "set-based insert. `Success` carries the complete hydrated list in\n" +
                    "input order; a partial list is never returned. Returned LOAD\n" +
                    "privacy is batch-evaluated after every row is hydrated and after\n" +
                    "the full afterCreate phase. In a caller-owned transaction a\n" +
                    "disclosure failure is `TransactionPending` and marks rollback-only;\n" +
                    "in an EntKt-owned transaction a confirmed commit reports it as\n" +
                    "`Committed`, a confirmed rollback as `NotPersisted`, and an uncertain\n" +
                    "boundary as `PersistenceUnknown`. `Failed` therefore\n" +
                    "does not by itself imply rollback; inspect `exception.writeState`.\n" +
                    "There is no `orNull()` projection — use `getOrThrow()` or match on\n" +
                    "the result.",
            )
            statement("return createOperation.createMany(viewerContext, blocks.asList())")
        }
    }

    private fun hookListProperty(
        name: String,
        type: com.squareup.kotlinpoet.TypeName,
    ): PropertySpec = property(name, type) {
        addModifiers(KModifier.PRIVATE)
        initializer("configuredHooks.%LHooks.toList()", name.removeSuffix("Hooks"))
    }

}
