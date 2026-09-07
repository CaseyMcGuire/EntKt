package entkt.codegen.client

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.metadata.idStrategyName
import entkt.codegen.metadata.toTypeName
import entkt.codegen.metadata.VIEWER_CONTEXT
import entkt.codegen.mutation.MUTATION_RESULT
import entkt.codegen.query.indexHelperTree
import entkt.schema.EntSchema

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
private val CREATE_MANY_MUTATION_OPERATION =
    ClassName("entkt.runtime.mutation.execution", "CreateManyMutationOperation")
private val CREATE_MANY_MUTATION_INPUT =
    ClassName("entkt.runtime.mutation.execution", "CreateManyMutationInput")
private val CREATE_MUTATION_OPERATION =
    ClassName("entkt.runtime.mutation.execution", "CreateMutationOperation")
private val CREATE_MUTATION_INPUT =
    ClassName("entkt.runtime.mutation.execution", "CreateMutationInput")
private val DELETE_MUTATION_INPUT =
    ClassName("entkt.runtime.mutation.execution", "DeleteMutationInput")
private val MUTATION_EXECUTOR =
    ClassName("entkt.runtime.mutation.execution", "MutationExecutor")
private val MUTATION_OPERATION =
    ClassName("entkt.runtime.mutation.execution", "MutationOperation")
private val MUTATION_PRIVACY_EVALUATOR =
    ClassName("entkt.runtime.privacy", "MutationPrivacyEvaluator")
private val MUTATION_VALIDATION_EVALUATOR =
    ClassName("entkt.runtime.validation", "MutationValidationEvaluator")
private val DELETE_MANY_MUTATION_OPERATION =
    ClassName("entkt.runtime.mutation.execution", "DeleteManyMutationOperation")
private val DELETE_MANY_MUTATION_INPUT =
    ClassName("entkt.runtime.mutation.execution", "DeleteManyMutationInput")
private val DELETE_MUTATION_SPEC =
    ClassName("entkt.runtime.mutation.execution", "DeleteMutationSpec")
private val DELETE_MUTATION_OPERATION =
    ClassName("entkt.runtime.mutation.execution", "DeleteMutationOperation")
private val DELETE_RULE_CANDIDATE =
    ClassName("entkt.runtime.mutation.execution", "DeleteRuleCandidate")
private val PRIVACY_DECISION_EVALUATOR =
    ClassName("entkt.runtime.privacy", "PrivacyDecisionEvaluator")
private val PRIVACY_OPERATION = ClassName("entkt.runtime.privacy", "PrivacyOperation")
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
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)
        val idType = schema.id().type.toTypeName()

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
            property("mutationExecutor", MUTATION_EXECUTOR) {
                addModifiers(KModifier.PRIVATE)
                initializer("%T(driver, client)", MUTATION_EXECUTOR)
            }
            property("updateAdapter", updateAdapterClass) {
                addModifiers(KModifier.PRIVATE)
                initializer(
                    "%T(driver, client, configuredPrivacy, configuredValidation, configuredHooks.beforeSave, configuredHooks.beforeUpdate, configuredHooks.afterUpdate)",
                    updateAdapterClass,
                )
            }
            addProperty(
                buildLoadPrivacyEvaluator(
                    entityDescriptorClass = entityDescriptorClass,
                    entityClass = entityClass,
                ),
            )
            val createConverterClass = ClassName(packageName, "${schemaName}CreateConverter")
            property("createConverter", createConverterClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("%T(driver, client.hookClientScopeForInternalUse)", createConverterClass)
            }
            addProperty(
                buildCreateManyMutationOperation(
                    schemaName = schemaName,
                    entityDescriptorClass = entityDescriptorClass,
                    createDraftClass = createDraftClass,
                    entityClass = entityClass,
                    candidateClass = candidateClass,
                    beforeSaveStateClass = beforeSaveStateClass,
                    beforeCreateStateClass = beforeCreateStateClass,
                ),
            )
            property(
                "createMutationOperation",
                MUTATION_OPERATION.parameterizedBy(
                    ClassName(packageName, "ReadOnlyEntClient"),
                    CREATE_MUTATION_INPUT.parameterizedBy(createDraftClass),
                    entityClass,
                ),
            ) {
                addModifiers(KModifier.PRIVATE)
                initializer("%T(createManyMutationOperation)", CREATE_MUTATION_OPERATION)
            }
            addProperty(
                buildDeleteMutationSpec(
                    entityDescriptorClass = entityDescriptorClass,
                    queryClass = queryClass,
                    entityClass = entityClass,
                ),
            )
            addProperties(
                buildDeleteEvaluators(
                    schemaName = schemaName,
                    entityDescriptorClass = entityDescriptorClass,
                    entityClass = entityClass,
                    candidateClass = candidateClass,
                ),
            )
            property(
                "deleteMutationOperation",
                MUTATION_OPERATION.parameterizedBy(
                    ClassName(packageName, "ReadOnlyEntClient"),
                    DELETE_MUTATION_INPUT,
                    Boolean::class.asClassName(),
                ),
            ) {
                addModifiers(KModifier.PRIVATE)
                initializer(
                    "%T(spec = deleteSpec, converter = %T, privacyEvaluator = deletePrivacyEvaluator, validationEvaluator = deleteValidationEvaluator)",
                    DELETE_MUTATION_OPERATION,
                    ClassName(packageName, "${schemaName}DeleteConverter"),
                )
            }
            property(
                "deleteManyMutationOperation",
                MUTATION_OPERATION.parameterizedBy(
                    ClassName(packageName, "ReadOnlyEntClient"),
                    DELETE_MANY_MUTATION_INPUT.parameterizedBy(entityClass),
                    INT,
                ),
            ) {
                addModifiers(KModifier.PRIVATE)
                initializer(
                    "%T(spec = deleteSpec, converter = %T, privacyEvaluator = deletePrivacyEvaluator, validationEvaluator = deleteValidationEvaluator)",
                    DELETE_MANY_MUTATION_OPERATION,
                    ClassName(packageName, "${schemaName}DeleteConverter"),
                )
            }
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
            addFunction(buildDeleteById(idType))
            if (idStrategyName(schema) != "EXPLICIT") {
                addFunction(buildCreateMany(entityClass, createDraftClass, createLambda, schema.clientName))
            }
            addFunction(buildDeleteMany(entityClass, schema.clientName))
            addFunction(buildHasPrivacy("hasLoadPrivacy", readSurfaceOverride = true))
            addFunction(buildHasPrivacy("hasCreatePrivacy"))
            addFunction(buildHasPrivacy("hasUpdatePrivacy"))
            addFunction(buildHasPrivacy("hasDeletePrivacy"))
            addFunction(buildEvaluateLoadPrivacy(entityClass))
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
            add("return mutationExecutor.execute(\n")
            indent()
            add("operation = updateAdapter.updateOperation,\n")
            add("ruleClient = client.readOnlyClient,\n")
            add("input = %T(\n", ClassName("entkt.runtime.mutation.execution", "UpdateMutationInput"))
            indent()
            add("viewerContext = viewerContext,\n")
            add("request = request,\n")
            add("applyLoadPrivacy = applyLoadPrivacy,\n")
            unindent()
            add("),\n")
            unindent()
            add(")\n")
        })
    }

    /**
     * `delete(entity): MutationResult<Unit>` — idempotent entity-handle
     * delete. `Success(Unit)` means the row is absent afterward,
     * whether this call deleted it or it was already absent.
     */
    private fun buildDelete(entityClass: ClassName): FunSpec =
        function("delete", MUTATION_RESULT.parameterizedBy(UNIT)) {
            parameter("viewerContext", VIEWER_CONTEXT)
            parameter("entity", entityClass)
            addCode(codeBlock {
                add("return mutationExecutor.execute(\n")
                indent()
                add("operation = deleteMutationOperation.mapResult { Unit },\n")
                add("ruleClient = client.readOnlyClient,\n")
                add("input = %T(viewerContext, entity.id),\n", DELETE_MUTATION_INPUT)
                unindent()
                add(")\n")
            })
        }

    /**
     * `deleteById(id): MutationResult<Boolean>` — idempotent
     * delete-by-id preserving the affected-row acknowledgement.
     */
    private fun buildDeleteById(
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec = function("deleteById", MUTATION_RESULT.parameterizedBy(Boolean::class.asClassName())) {
        parameter("viewerContext", VIEWER_CONTEXT)
        parameter("id", idType)
        addCode(codeBlock {
            add("return mutationExecutor.execute(\n")
            indent()
            add("operation = deleteMutationOperation,\n")
            add("ruleClient = client.readOnlyClient,\n")
            add("input = %T(viewerContext, id),\n", DELETE_MUTATION_INPUT)
            unindent()
            add(")\n")
        })
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
        clientName: String,
    ): FunSpec = function("deleteMany", MUTATION_RESULT.parameterizedBy(INT)) {
        parameter("viewerContext", VIEWER_CONTEXT)
        parameter("predicates", PREDICATE.parameterizedBy(entityClass)) { addModifiers(KModifier.VARARG) }
        addCode(codeBlock {
            add("return mutationExecutor.execute(\n")
            indent()
            add("operation = deleteManyMutationOperation,\n")
            add("ruleClient = client.readOnlyClient,\n")
            add("input = %T(viewerContext, predicates.asList()),\n", DELETE_MANY_MUTATION_INPUT)
            addOwnedTransactionWiring(
                clientName,
                CodeBlock.of("tx.%L.deleteManyMutationOperation", clientName),
            )
            unindent()
            add(")\n")
        })
    }

    /** Select transaction-bound dependencies; runtime owns transaction policy and completion handling. */
    private fun CodeBlock.Builder.addOwnedTransactionWiring(
        clientName: String,
        operation: CodeBlock,
    ) {
        add("ownedTransaction = { input, completionCapture ->\n")
        indent()
        add("client.withTransaction { tx ->\n")
        indent()
        add("tx.%L.mutationExecutor.executeInOwnedTransactionForInternalUse(\n", clientName)
        indent()
        add("operation = %L,\n", operation)
        add("ruleClient = tx.%L.client.readOnlyClient,\n", clientName)
        add("input = input,\n")
        add("completionCapture = completionCapture,\n")
        unindent()
        add(").orRollback()\n")
        unindent()
        add("}\n")
        unindent()
        add("},\n")
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

    /** Bind LOAD rules over the original entities to the runtime evaluator. */
    private fun buildLoadPrivacyEvaluator(
        entityDescriptorClass: ClassName,
        entityClass: ClassName,
    ): PropertySpec = property(
        "loadPrivacyEvaluator",
        LOAD_PRIVACY_EVALUATOR.parameterizedBy(
            ClassName(packageName, "ReadOnlyEntClient"),
            entityClass,
        ),
    ) {
        addModifiers(KModifier.PRIVATE)
        initializer(codeBlock {
            add("%T(\n", LOAD_PRIVACY_EVALUATOR)
            indent()
            add("entity = %T,\n", entityDescriptorClass)
            add("rules = configuredPrivacy.loadRules,\n")
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
            addCode(codeBlock {
                add("return loadPrivacyEvaluator.evaluate(\n")
                indent()
                add(
                    "context = %T(viewerContext, client.readOnlyClient),\n",
                    ClassName("entkt.runtime.privacy", "PrivacyRuleContext"),
                )
                add("entities = entities,\n")
                unindent()
                add(")\n")
            })
        }

    /** Bind this entity's CREATE dependencies once for its scalar and bulk runtime operations. */
    private fun buildCreateManyMutationOperation(
        schemaName: String,
        entityDescriptorClass: ClassName,
        createDraftClass: ClassName,
        entityClass: ClassName,
        candidateClass: ClassName,
        beforeSaveStateClass: ClassName,
        beforeCreateStateClass: ClassName,
    ): PropertySpec {
        val operationType = CREATE_MANY_MUTATION_OPERATION.parameterizedBy(
            ClassName(packageName, "ReadOnlyEntClient"),
            createDraftClass,
            candidateClass,
            entityClass,
            beforeSaveStateClass,
            beforeCreateStateClass,
        )
        return property("createManyMutationOperation", operationType) {
            addModifiers(KModifier.PRIVATE)
            initializer(codeBlock {
                add("%T(\n", CREATE_MANY_MUTATION_OPERATION)
                indent()
                add("mutationRuntime = client,\n")
                add("entity = %T,\n", entityDescriptorClass)
                add("converter = createConverter,\n")
                add("privacyEvaluator = %T(\n", MUTATION_PRIVACY_EVALUATOR)
                indent()
                add("entity = %T,\n", entityDescriptorClass)
                add("operation = %T.CREATE,\n", PRIVACY_OPERATION)
                add("rules = configuredPrivacy.createRules,\n")
                add("freshItem = { candidate -> candidate },\n")
                unindent()
                add("),\n")
                add("validationEvaluator = %M(\n", MUTATION_VALIDATION_EVALUATOR_FACTORY)
                indent()
                add("lifecycle = %S,\n", "$schemaName CREATE validation")
                add("rules = configuredValidation.createRules,\n")
                add("freshItem = { candidate -> candidate },\n")
                unindent()
                add("),\n")
                add("hookStateConverter = createConverter,\n")
                add("beforeSaveHookRunner = configuredHooks.beforeSave,\n")
                add("beforeCreateHookRunner = configuredHooks.beforeCreate,\n")
                add("afterCreateHookRunner = configuredHooks.afterCreate,\n")
                unindent()
                add(")")
            })
        }
    }

    /** Capture schema-specific DELETE adapters while runtime owns lifecycle ordering. */
    private fun buildDeleteMutationSpec(
        entityDescriptorClass: ClassName,
        queryClass: ClassName,
        entityClass: ClassName,
    ): PropertySpec {
        val specType = DELETE_MUTATION_SPEC.parameterizedBy(entityClass)
        return property("deleteSpec", specType) {
            addModifiers(KModifier.PRIVATE)
            initializer(codeBlock {
                add("%T(\n", DELETE_MUTATION_SPEC)
                indent()
                add("entity = %T,\n", entityDescriptorClass)
                add("idColumn = %T.SCHEMA.idColumn,\n", entityClass)
                add("newQuery = { %T(driver, client) },\n", queryClass)
                add("beforeDelete = configuredHooks.beforeDelete,\n")
                add("afterDelete = configuredHooks.afterDelete,\n")
                unindent()
                add(")")
            })
        }
    }

    /** Share the already-bound evaluators without introducing a lifecycle object. */
    private fun buildDeleteEvaluators(
        schemaName: String,
        entityDescriptorClass: ClassName,
        entityClass: ClassName,
        candidateClass: ClassName,
    ): List<PropertySpec> {
        val deleteRuleInput = ClassName(packageName, "${schemaName}DeleteRuleInput")
        val ruleCandidateType = DELETE_RULE_CANDIDATE.parameterizedBy(entityClass, candidateClass)
        return listOf(
            property(
                "deletePrivacyEvaluator",
                MUTATION_PRIVACY_EVALUATOR.parameterizedBy(
                    ClassName(packageName, "ReadOnlyEntClient"),
                    ruleCandidateType,
                    deleteRuleInput,
                ),
            ) {
                addModifiers(KModifier.PRIVATE)
                initializer(codeBlock {
                    add("%T(\n", MUTATION_PRIVACY_EVALUATOR)
                    indent()
                    add("entity = %T,\n", entityDescriptorClass)
                    add("operation = %T.DELETE,\n", PRIVACY_OPERATION)
                    add("rules = configuredPrivacy.deleteRules,\n")
                    add(
                        "freshItem = { item: %T -> %T(item.entity, item.candidate) },\n",
                        ruleCandidateType,
                        deleteRuleInput,
                    )
                    add("fallback = if (configuredPrivacy.deleteDerivesFromCreate) {\n")
                    indent()
                    add("%T(\n", PRIVACY_DECISION_EVALUATOR)
                    indent()
                    add("rules = configuredPrivacy.createRules,\n")
                    add(
                        "freshItem = { item: %T -> item.candidate },\n",
                        ruleCandidateType,
                    )
                    unindent()
                    add(")\n")
                    unindent()
                    add("} else {\n")
                    add("  null\n")
                    add("},\n")
                    unindent()
                    add(")")
                })
            },
            property(
                "deleteValidationEvaluator",
                MUTATION_VALIDATION_EVALUATOR.parameterizedBy(
                    ClassName(packageName, "ReadOnlyEntClient"),
                    ruleCandidateType,
                ),
            ) {
                addModifiers(KModifier.PRIVATE)
                initializer(codeBlock {
                    add("%M(\n", MUTATION_VALIDATION_EVALUATOR_FACTORY)
                    indent()
                    add("lifecycle = %S,\n", "$schemaName DELETE validation")
                    add("rules = configuredValidation.deleteRules,\n")
                    add(
                        "freshItem = { item: %T -> %T(item.entity, item.candidate) },\n",
                        ruleCandidateType,
                        deleteRuleInput,
                    )
                    unindent()
                    add(")")
                })
            },
        )
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
                add("return mutationExecutor.execute(\n")
                indent()
                add("operation = createMutationOperation.mapResult { Unit },\n")
                add("ruleClient = client.readOnlyClient,\n")
                add("input = %T(viewerContext, draft, checkReturnedEntityPrivacy = false),\n", CREATE_MUTATION_INPUT)
                unindent()
                add(")\n")
            })
        }

    private fun buildSaveAndLoadCreation(
        createDraftClass: ClassName,
        entityClass: ClassName,
    ): FunSpec = function("saveAndLoadCreation", MUTATION_RESULT.parameterizedBy(entityClass)) {
        addAnnotation(ENTKT_INTERNAL)
        addModifiers(KModifier.OVERRIDE)
        parameter("viewerContext", VIEWER_CONTEXT)
        parameter("draft", createDraftClass)
        addCode(codeBlock {
            add("return mutationExecutor.execute(\n")
            indent()
            add("operation = createMutationOperation,\n")
            add("ruleClient = client.readOnlyClient,\n")
            add("input = %T(viewerContext, draft, checkReturnedEntityPrivacy = true),\n", CREATE_MUTATION_INPUT)
            unindent()
            add(")\n")
        })
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
        createDraftClass: ClassName,
        createLambda: LambdaTypeName,
        clientName: String,
    ): FunSpec = function("createMany", MUTATION_RESULT.parameterizedBy(LIST.parameterizedBy(entityClass))) {
        parameter("viewerContext", VIEWER_CONTEXT)
        parameter("blocks", createLambda) { addModifiers(KModifier.VARARG) }
        addCode(codeBlock {
            add("return mutationExecutor.execute(\n")
            indent()
            add("operation = createManyMutationOperation,\n")
            add("ruleClient = client.readOnlyClient,\n")
            add("input = %T(viewerContext, blocks.asList(), newDraft = { %T() }),\n", CREATE_MANY_MUTATION_INPUT, createDraftClass)
            addOwnedTransactionWiring(clientName, CodeBlock.of("tx.%L.createManyMutationOperation", clientName))
            unindent()
            add(")\n")
        })
    }

}
