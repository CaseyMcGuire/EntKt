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
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.lifecycleValueSnapshot
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.idStrategyName
import entkt.codegen.metadata.resolvedTypeName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.toTypeName
import entkt.codegen.metadata.VIEWER_CONTEXT
import entkt.codegen.mutation.MUTATION_RESULT
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
private val PRIVACY_EVALUATION = ClassName("entkt.runtime.privacy", "PrivacyEvaluation")
private val LOAD_PRIVACY_EVALUATOR =
    ClassName("entkt.runtime.privacy", "LoadPrivacyEvaluator")
private val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")
private val CREATE_MUTATION_SPEC =
    ClassName("entkt.runtime.mutation.execution", "CreateMutationSpec")
private val CREATE_MUTATION_EXECUTOR =
    ClassName("entkt.runtime.mutation.execution", "CreateMutationExecutor")
private val CREATE_MUTATION_HOOK_STATE_CONVERTER =
    ClassName("entkt.runtime.mutation.execution", "CreateMutationHookStateConverter")
private val CREATE_OPERATION =
    ClassName("entkt.runtime.mutation.execution", "CreateOperation")
private val DELETE_MUTATION_SPEC =
    ClassName("entkt.runtime.mutation.execution", "DeleteMutationSpec")
private val DELETE_MUTATION_EXECUTOR =
    ClassName("entkt.runtime.mutation.execution", "DeleteMutationExecutor")
private val DELETE_OPERATION =
    ClassName("entkt.runtime.mutation.execution", "DeleteOperation")
private val DELETE_RULE_CANDIDATE =
    ClassName("entkt.runtime.mutation.execution", "DeleteRuleCandidate")
private val FIELD_PATCH = ClassName("entkt.runtime.mutation", "FieldPatch")
private val LOAD_PRIVACY_EVALUATOR_FACTORY =
    MemberName("entkt.runtime.privacy", "loadPrivacyEvaluatorForInternalUse")
private val MUTATION_PRIVACY_EVALUATOR_FACTORY =
    MemberName("entkt.runtime.privacy", "mutationPrivacyEvaluatorForInternalUse")
private val PRIVACY_DECISION_EVALUATOR_FACTORY =
    MemberName("entkt.runtime.privacy", "privacyDecisionEvaluatorForInternalUse")
private val MUTATION_VALIDATION_EVALUATOR_FACTORY =
    MemberName("entkt.runtime.validation", "mutationValidationEvaluatorForInternalUse")
private val PENDING_CREATE_MUTATION =
    ClassName("entkt.runtime.mutation", "PendingCreateMutation")
private val CREATE_MUTATION_REPOSITORY =
    ClassName("entkt.runtime.mutation", "CreateMutationRepository")
private val PENDING_UPDATE_MUTATION =
    ClassName("entkt.runtime.mutation", "PendingUpdateMutation")
private val UPDATE_MUTATION_REQUEST =
    ClassName("entkt.runtime.mutation", "UpdateMutationRequest")
private val UPDATE_MUTATION_REPOSITORY =
    ClassName("entkt.runtime.mutation", "UpdateMutationRepository")

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
        val updateDraftClass = ClassName(packageName, "${schemaName}UpdateDraft")
        val updateAdapterClass = ClassName(packageName, "${schemaName}UpdateAdapter")
        val entityDescriptorClass = ClassName(packageName, "${schemaName}Descriptor")
        val queryClass = ClassName(packageName, "${schemaName}Query")
        val indexesClass = ClassName(packageName, "${schemaName}Indexes")
        val beforeSaveStateClass = ClassName(packageName, "${schemaName}BeforeSaveState")
        val beforeCreateStateClass = ClassName(packageName, "${schemaName}BeforeCreateState")
        val entityHooksType = resolvedEntityHooksType(packageName, schemaName)
        val privacyConfigType = resolvedEntityPrivacyConfigType(packageName, schemaName)
        val validationConfigType = resolvedEntityValidationConfigType(packageName, schemaName)
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
            receiver = updateDraftClass,
            returnType = UNIT,
        )
        val queryLambda = LambdaTypeName.get(
            receiver = queryClass,
            returnType = UNIT,
        )

        val typeSpec = classType(className) {
            // The repo is the entity's read surface: query terminals reach
            // `hasLoadPrivacy()` / `evaluateLoadPrivacy(...)` through the
            // EntReadRuntime contract's `${prop}: ${Entity}ReadSurface`
            // accessor, which EntClient overrides with this repo.
            addSuperinterface(ClassName(packageName, "${schemaName}ReadSurface"))
            addSuperinterface(
                CREATE_MUTATION_REPOSITORY.parameterizedBy(createDraftClass, entityClass),
            )
            addSuperinterface(
                UPDATE_MUTATION_REPOSITORY.parameterizedBy(updateDraftClass, entityClass),
            )
            primaryConstructor {
                addModifiers(KModifier.INTERNAL)
                parameter("driver", DRIVER)
                parameter("client", clientClass)
                parameter("configuredHooks", entityHooksType)
                parameter("configuredPrivacy", privacyConfigType)
                parameter("configuredValidation", validationConfigType)
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
            property("updateAdapter", updateAdapterClass) {
                addModifiers(KModifier.PRIVATE)
                initializer(
                    "%T(driver, client, configuredPrivacy, configuredValidation, configuredHooks.beforeSave, configuredHooks.beforeUpdate, configuredHooks.afterUpdate)",
                    updateAdapterClass,
                )
            }
            // Privacy config
            property("privacyConfig", privacyConfigType) {
                addModifiers(KModifier.INTERNAL)
                initializer("configuredPrivacy")
            }
            // Validation config
            property("validationConfig", validationConfigType) {
                addModifiers(KModifier.INTERNAL)
                initializer("configuredValidation")
            }
            addProperty(
                buildLoadPrivacyEvaluator(
                    schemaName = schemaName,
                    entityClass = entityClass,
                    loadItemClass = loadItemClass,
                    fields = fields,
                ),
            )
            addProperty(
                buildCreateMutationSpec(
                    entityDescriptorClass = entityDescriptorClass,
                    createDraftClass = createDraftClass,
                    candidateClass = candidateClass,
                    entityClass = entityClass,
                ),
            )
            addProperty(
                buildCreateMutationExecutor(
                    schemaName = schemaName,
                    createDraftClass = createDraftClass,
                    entityClass = entityClass,
                    candidateClass = candidateClass,
                    beforeSaveStateClass = beforeSaveStateClass,
                    beforeCreateStateClass = beforeCreateStateClass,
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
                    entityDescriptorClass = entityDescriptorClass,
                    queryClass = queryClass,
                    entityClass = entityClass,
                    candidateClass = candidateClass,
                ),
            )
            addProperty(
                buildDeleteMutationExecutor(
                    schemaName = schemaName,
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
            addType(
                buildCreateHookStateConverter(
                    schema = schema,
                    schemaName = schemaName,
                    createDraftClass = createDraftClass,
                    entityClass = entityClass,
                    beforeSaveStateClass = beforeSaveStateClass,
                    beforeCreateStateClass = beforeCreateStateClass,
                    fields = fields,
                    edgeFks = edgeFks,
                ),
            )
            val createGenerator = CreateGenerator(packageName)
            addFunction(
                createGenerator.buildRequiredInputViolationsFunction(
                    schemaName,
                    schema,
                    schemaNames,
                ),
            )
            addFunction(createGenerator.buildResolveFunction(schemaName, schema, schemaNames))
            addFunction(
                createGenerator.buildCreateFieldViolationsFunction(
                    schemaName,
                    schema,
                    schemaNames,
                ),
            )
            addFunction(buildSnapshotCreateCandidate(entityClass, candidateClass, fields))
            // Per-operation UpdateConsistency override (transaction locking). Defaults
            // to the client's `defaultUpdateConsistency` so callers
            // who don't pass `consistency =` get the configured
            // baseline (`ReadCurrent` unless the EntClientConfig
            // sets otherwise).
            function(
                "update",
                PENDING_UPDATE_MUTATION.parameterizedBy(updateDraftClass, entityClass),
            ) {
                parameter("id", idType)
                parameter("consistency", UPDATE_CONSISTENCY) {
                    defaultValue("client.defaultUpdateConsistency")
                }
                // Per-operation RelationshipLocking override.
                // Defaults to the client's `defaultRelationshipLocking`
                // (OwnerOnly unless the EntClientConfig sets otherwise).
                parameter("relationshipLocking", RELATIONSHIP_LOCKING) {
                    defaultValue("client.defaultRelationshipLocking")
                }
                parameter("block", updateLambda)
                statement("val draft = %T().apply(block)", updateDraftClass)
                statement(
                    "val request = %T(id, draft, consistency, relationshipLocking)",
                    UPDATE_MUTATION_REQUEST,
                )
                statement("return %T(request, this)", PENDING_UPDATE_MUTATION)
            }
            addFunction(buildExecuteUpdate(updateDraftClass, entityClass))
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
            addFunction(buildEvaluateLoadPrivacy(entityClass))
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

    private fun buildExecuteUpdate(
        updateDraftClass: ClassName,
        entityClass: ClassName,
    ): FunSpec = function(
        "executeUpdate",
        MUTATION_RESULT.parameterizedBy(entityClass),
    ) {
        addAnnotation(ENTKT_INTERNAL)
        addModifiers(KModifier.OVERRIDE)
        parameter("viewerContext", VIEWER_CONTEXT)
        parameter(
            "request",
            UPDATE_MUTATION_REQUEST.parameterizedBy(updateDraftClass),
        )
        parameter("applyLoadPrivacy", Boolean::class.asClassName())
        addCode(codeBlock {
            add("return updateAdapter.execute(\n")
            indent()
            add("viewerContext = viewerContext,\n")
            add("request = request,\n")
            add("applyLoadPrivacy = applyLoadPrivacy,\n")
            unindent()
            add(")\n")
        })
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

    /** Bind schema-specific LOAD rules and item snapshots to the reusable runtime evaluator. */
    private fun buildLoadPrivacyEvaluator(
        schemaName: String,
        entityClass: ClassName,
        loadItemClass: ClassName,
        fields: List<Field>,
    ): PropertySpec = property(
        "loadPrivacyEvaluator",
        LOAD_PRIVACY_EVALUATOR.parameterizedBy(entityClass),
    ) {
        addModifiers(KModifier.PRIVATE)
        initializer(codeBlock {
            add("%M(\n", LOAD_PRIVACY_EVALUATOR_FACTORY)
            indent()
            add("lifecycle = %S,\n", "$schemaName LOAD privacy")
            add("unresolvedReason = %S,\n", "no load rule allowed access")
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

    private fun buildEvaluateLoadPrivacy(
        entityClass: ClassName,
    ): FunSpec =
        function("evaluateLoadPrivacy", PRIVACY_EVALUATION.parameterizedBy(entityClass)) {
            addModifiers(KModifier.OVERRIDE)
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter("entities", LIST.parameterizedBy(entityClass))
            statement("return loadPrivacyEvaluator.evaluate(viewerContext, entities)")
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
        entityDescriptorClass: ClassName,
        createDraftClass: ClassName,
        candidateClass: ClassName,
        entityClass: ClassName,
    ): PropertySpec {
        val specType = CREATE_MUTATION_SPEC.parameterizedBy(
            createDraftClass,
            candidateClass,
            entityClass,
        )
        return property("createSpec", specType) {
            addModifiers(KModifier.PRIVATE)
            initializer(codeBlock {
                add("%T(\n", CREATE_MUTATION_SPEC)
                indent()
                add("entity = %T,\n", entityDescriptorClass)
                add("requiredInputViolations = ::requiredInputViolations,\n")
                add("resolveDraft = ::resolve,\n")
                add("fieldViolations = ::createFieldViolations,\n")
                unindent()
                add(")")
            })
        }
    }

    /** Bind this entity's CREATE privacy and validation evaluators directly to its executor. */
    private fun buildCreateMutationExecutor(
        schemaName: String,
        createDraftClass: ClassName,
        entityClass: ClassName,
        candidateClass: ClassName,
        beforeSaveStateClass: ClassName,
        beforeCreateStateClass: ClassName,
    ): PropertySpec {
        val ruleInputClass = ClassName(packageName, "${schemaName}CreateRuleInput")
        val executorType = CREATE_MUTATION_EXECUTOR.parameterizedBy(
            createDraftClass,
            candidateClass,
            entityClass,
            beforeSaveStateClass,
            beforeCreateStateClass,
        )
        return property("createExecutor", executorType) {
            addModifiers(KModifier.PRIVATE)
            initializer(codeBlock {
                add("%T(\n", CREATE_MUTATION_EXECUTOR)
                indent()
                add("driver = driver,\n")
                add("mutationRuntime = client,\n")
                add("privacyEvaluator = %M(\n", MUTATION_PRIVACY_EVALUATOR_FACTORY)
                indent()
                add("lifecycle = %S,\n", "$schemaName CREATE privacy")
                add("unresolvedReason = %S,\n", "no create rule allowed access")
                add("rules = privacyConfig.createRules,\n")
                add("ruleClientProvider = { client.readOnlyClient },\n")
                add("freshItem = { candidate -> %T(snapshotCreateCandidate(candidate)) },\n", ruleInputClass)
                unindent()
                add("),\n")
                add("validationEvaluator = %M(\n", MUTATION_VALIDATION_EVALUATOR_FACTORY)
                indent()
                add("lifecycle = %S,\n", "$schemaName CREATE validation")
                add("rules = validationConfig.createRules,\n")
                add("ruleClientProvider = { client.readOnlyClient },\n")
                add("freshItem = { candidate -> %T(snapshotCreateCandidate(candidate)) },\n", ruleInputClass)
                unindent()
                add("),\n")
                add("hookStateConverter = CreateHookStateConverter(client),\n")
                add("beforeSaveHookRunner = configuredHooks.beforeSave,\n")
                add("beforeCreateHookRunner = configuredHooks.beforeCreate,\n")
                add("afterCreateHookRunner = configuredHooks.afterCreate,\n")
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
                add("createExecutor.operationForInternalUse(\n")
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
        entityDescriptorClass: ClassName,
        queryClass: ClassName,
        entityClass: ClassName,
        candidateClass: ClassName,
    ): PropertySpec {
        val specType = DELETE_MUTATION_SPEC.parameterizedBy(
            entityClass,
            candidateClass,
        )
        return property("deleteSpec", specType) {
            addModifiers(KModifier.PRIVATE)
            initializer(codeBlock {
                add("%T(\n", DELETE_MUTATION_SPEC)
                indent()
                add("entity = %T,\n", entityDescriptorClass)
                add("idColumn = %T.SCHEMA.idColumn,\n", entityClass)
                add("newQuery = { %T(driver, client) },\n", queryClass)
                add("candidate = ::buildDeleteCandidate,\n")
                add("beforeDelete = configuredHooks.beforeDelete,\n")
                add("afterDelete = configuredHooks.afterDelete,\n")
                unindent()
                add(")")
            })
        }
    }

    /** Bind this entity's DELETE privacy and validation evaluators directly to its executor. */
    private fun buildDeleteMutationExecutor(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
        fields: List<Field>,
    ): PropertySpec {
        val deleteRuleInput = ClassName(packageName, "${schemaName}DeleteRuleInput")
        val createRuleInput = ClassName(packageName, "${schemaName}CreateRuleInput")
        val entitySnapshot = lifecycleValueSnapshot("item.entity", fields, entityClass)
        val candidateSnapshot = lifecycleValueSnapshot("item.candidate", fields, entityClass)
        val ruleCandidateType = DELETE_RULE_CANDIDATE.parameterizedBy(entityClass, candidateClass)
        val executorType = DELETE_MUTATION_EXECUTOR.parameterizedBy(entityClass, candidateClass)
        return property("deleteExecutor", executorType) {
            addModifiers(KModifier.PRIVATE)
            initializer(codeBlock {
                add("%T(\n", DELETE_MUTATION_EXECUTOR)
                indent()
                add("driver = driver,\n")
                add("mutationRuntime = client,\n")
                add("privacyEvaluator = %M(\n", MUTATION_PRIVACY_EVALUATOR_FACTORY)
                indent()
                add("lifecycle = %S,\n", "$schemaName DELETE privacy")
                add("unresolvedReason = %S,\n", "no delete rule allowed access")
                add("rules = privacyConfig.deleteRules,\n")
                add("ruleClientProvider = { client.readOnlyClient },\n")
                add(
                    "freshItem = { item: %T -> %T(%L, %L) },\n",
                    ruleCandidateType,
                    deleteRuleInput,
                    entitySnapshot,
                    candidateSnapshot,
                )
                add("fallback = if (privacyConfig.deleteDerivesFromCreate) {\n")
                indent()
                add("%M(\n", PRIVACY_DECISION_EVALUATOR_FACTORY)
                indent()
                add("lifecycle = %S,\n", "$schemaName DELETE privacy")
                add("rules = privacyConfig.createRules,\n")
                add("ruleClientProvider = { client.readOnlyClient },\n")
                add(
                    "freshItem = { item: %T -> %T(%L) },\n",
                    ruleCandidateType,
                    createRuleInput,
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
                add("validationEvaluator = %M(\n", MUTATION_VALIDATION_EVALUATOR_FACTORY)
                indent()
                add("lifecycle = %S,\n", "$schemaName DELETE validation")
                add("rules = validationConfig.deleteRules,\n")
                add("ruleClientProvider = { client.readOnlyClient },\n")
                add(
                    "freshItem = { item: %T -> %T(%L, %L) },\n",
                    ruleCandidateType,
                    deleteRuleInput,
                    entitySnapshot,
                    candidateSnapshot,
                )
                unindent()
                add("),\n")
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
                add("deleteExecutor.operationForInternalUse(\n")
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
        return function("create", PENDING_CREATE_MUTATION.parameterizedBy(createDraftClass, entityClass)) {
            if (idStrategy == "EXPLICIT") {
                parameter("id", schema.id().type.toTypeName())
            }
            parameter("block", createLambda)
            val createArgs = if (idStrategy == "EXPLICIT") "id = id" else ""
            statement("val draft = %T($createArgs).apply(block)", createDraftClass)
            statement("return %T(draft, this)", PENDING_CREATE_MUTATION)
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

    /** Generated conversion only; runtime owns hook sequencing and state folding. */
    private fun buildCreateHookStateConverter(
        schema: EntSchema,
        schemaName: String,
        createDraftClass: ClassName,
        entityClass: ClassName,
        beforeSaveStateClass: ClassName,
        beforeCreateStateClass: ClassName,
        fields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): TypeSpec {
        val mutableFields = fields.filterNot { it.immutable }
        val mutableEdgeFks = edgeFks.filterNot { it.immutable }
        val converterType = CREATE_MUTATION_HOOK_STATE_CONVERTER.parameterizedBy(
            createDraftClass,
            beforeSaveStateClass,
            beforeCreateStateClass,
        )
        return classType("CreateHookStateConverter") {
            addModifiers(KModifier.PRIVATE)
            addSuperinterface(converterType)
            primaryConstructor { parameter("client", ClassName(packageName, ENT_CLIENT_NAME)) }
            property("client", ClassName(packageName, ENT_CLIENT_NAME)) {
                addModifiers(KModifier.PRIVATE)
                initializer("client")
            }
            function("toBeforeSaveState", beforeSaveStateClass) {
                addModifiers(KModifier.OVERRIDE)
                parameter("draft", createDraftClass)
                addCode(codeBlock {
                    add("return %T(\n", beforeSaveStateClass)
                    indent()
                    mutableFields.forEach { field ->
                        add(
                            "%L = if (draft.isSet(%T.%L)) %T.Set(draft.%L) else %T.Unset,\n",
                            field.apiName,
                            entityClass,
                            field.apiName,
                            FIELD_PATCH,
                            field.apiName,
                            FIELD_PATCH,
                        )
                    }
                    mutableEdgeFks.forEach { fk ->
                        add(
                            "%L = if (draft.isSet(%T.%L)) %T.Set(draft.%L) else %T.Unset,\n",
                            fk.propertyName,
                            entityClass,
                            fk.propertyName,
                            FIELD_PATCH,
                            fk.propertyName,
                            FIELD_PATCH,
                        )
                    }
                    unindent()
                    add(")\n")
                })
            }
            function("toBeforeCreateState", beforeCreateStateClass) {
                addModifiers(KModifier.OVERRIDE)
                parameter("viewerContext", VIEWER_CONTEXT)
                parameter("draft", createDraftClass)
                parameter("beforeSaveState", beforeSaveStateClass)
                addCode(codeBlock {
                    add("return %T(\n", beforeCreateStateClass)
                    indent()
                    add("client = client.hookClientScopeForInternalUse,\n")
                    add("viewerContext = viewerContext,\n")
                    fields.forEach { field ->
                        if (field.immutable) {
                            add(
                                "%L = if (draft.isSet(%T.%L)) %T.Set(draft.%L) else %T.Unset,\n",
                                field.apiName,
                                entityClass,
                                field.apiName,
                                FIELD_PATCH,
                                field.apiName,
                                FIELD_PATCH,
                            )
                        } else {
                            add("%L = beforeSaveState.%L,\n", field.apiName, field.apiName)
                        }
                    }
                    edgeFks.forEach { fk ->
                        if (fk.immutable) {
                            add(
                                "%L = if (draft.isSet(%T.%L)) %T.Set(draft.%L) else %T.Unset,\n",
                                fk.propertyName,
                                entityClass,
                                fk.propertyName,
                                FIELD_PATCH,
                                fk.propertyName,
                                FIELD_PATCH,
                            )
                        } else {
                            add("%L = beforeSaveState.%L,\n", fk.propertyName, fk.propertyName)
                        }
                    }
                    unindent()
                    add(")\n")
                })
            }
            function("toPreparationDraft", createDraftClass) {
                addModifiers(KModifier.OVERRIDE)
                parameter("originalDraft", createDraftClass)
                parameter("state", beforeCreateStateClass)
                if (idStrategyName(schema) == "EXPLICIT") {
                    statement("val draft = %T(originalDraft.id)", createDraftClass)
                } else {
                    statement("val draft = %T()", createDraftClass)
                }
                (fields.map { it.apiName } + edgeFks.map { it.propertyName }).forEach { property ->
                    addCode(
                        "when (val entry = state.%L) {\n" +
                            "  %T.Unset -> Unit\n" +
                            "  is %T.Set -> draft.%L = entry.value\n" +
                            "}\n",
                        property,
                        FIELD_PATCH,
                        FIELD_PATCH,
                        property,
                    )
                }
                statement("return draft")
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
            statement("return createOperation.createMany(viewerContext, blocks.asList())")
        }
    }

}
