package entkt.codegen.client

import entkt.codegen.apiName
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
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
import entkt.codegen.lifecyclePatchSnapshot
import entkt.codegen.lifecycleValueSnapshot
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.HelperEligibleM2M
import entkt.codegen.metadata.helperEligibleM2MEdges
import entkt.codegen.metadata.idStrategyName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.toTypeName
import entkt.codegen.mutation.MUTATION_CANCELLATION_EXCEPTION
import entkt.codegen.mutation.MUTATION_ENTITY_KEY
import entkt.codegen.mutation.MUTATION_ENT_OPERATION
import entkt.codegen.mutation.MUTATION_RESULT
import entkt.codegen.mutation.MUTATION_VALIDATION_VIOLATION
import entkt.codegen.mutation.MUTATION_WRITE_STATE
import entkt.codegen.mutation.RUN_BATCH_HOOKS_FOR_INTERNAL_USE
import entkt.codegen.mutation.ENT_MUTATION_EXCEPTION
import entkt.codegen.mutation.ENT_MUTATION_PRIVACY_DENIED_EXCEPTION
import entkt.codegen.mutation.ENT_UNEXPECTED_MUTATION_EXCEPTION
import entkt.codegen.mutation.ENT_VALIDATION_EXCEPTION
import entkt.codegen.mutation.KOTLIN_EXCEPTION
import entkt.codegen.mutation.buildClassifyDriverFailureHelper
import entkt.codegen.mutation.driverCallFailureTail
import entkt.codegen.mutation.indented
import entkt.codegen.mutation.privacyDeniedFailure
import entkt.codegen.mutation.recordAndReturnFailure
import entkt.codegen.query.indexHelperTree
import entkt.schema.EntSchema
import entkt.schema.Field

private val DRIVER = ClassName("entkt.runtime.driver", "DatabaseDriver")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val LIST = ClassName("kotlin.collections", "List")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
private val INT = Int::class.asClassName()
private val UPDATE_CONSISTENCY = ClassName("entkt.runtime.mutation", "UpdateConsistency")
private val RELATIONSHIP_LOCKING = ClassName("entkt.runtime.mutation", "RelationshipLocking")
private val ENT_CLIENT_NAME = "EntClient"
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val PRIVACY_RULE_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyRuleContext")
private val VALIDATION_RULE_CONTEXT = ClassName("entkt.runtime.validation", "ValidationRuleContext")
private val PRIVACY_DENIAL = ClassName("entkt.runtime.result", "PrivacyDenial")
private val ENTITY_KEY = ClassName("entkt.runtime.result", "EntityKey")
private val PRIVACY_DECISION = ClassName("entkt.runtime.privacy", "PrivacyDecision")
private val VIEWER = ClassName("entkt.runtime.privacy", "Viewer")
private val EVALUATE_BATCH_PRIVACY_RULES =
    MemberName("entkt.runtime.privacy", "evaluateBatchPrivacyRulesForInternalUse")
private val EVALUATE_BATCH_VALIDATION_RULES =
    MemberName("entkt.runtime.validation", "evaluateBatchValidationRulesForInternalUse")
private val TO_VALIDATION_VIOLATION = MemberName("entkt.runtime.result", "toValidationViolation")
private val TRANSACTION_RESULT = ClassName("entkt.runtime.result", "TransactionResult")
private val TRANSACTION_FAILURE_STATE = ClassName("entkt.runtime.result", "TransactionFailureState")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")
private val PREPARED_CREATE = ClassName("entkt.runtime.mutation", "PreparedCreate")
private val SNAPSHOT_EDGE_CHANGES =
    MemberName("entkt.runtime.mutation", "snapshotEdgeChangesForInternalUse")

/**
 * Emits a per-schema repository class. The repo is the only entry point
 * for I/O — it owns the [DatabaseDriver] and exposes `query`, `create`,
 * `update(id)`, and `byId` accessors. Its `init` block registers the
 * entity's [entkt.runtime.driver.EntitySchema] so the driver knows the table
 * layout before any other call lands, and every builder it hands back is
 * constructed with the same driver reference.
 *
 * Hooks are applied from the client's hooks DSL via [applyHooks] at
 * construction time, and inherited by transactional repos via
 * [copyHooksFrom].
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
        val repoClass = ClassName(packageName, className)
        val entityClass = ClassName(packageName, schemaName)
        val createClass = ClassName(packageName, "${schemaName}Create")
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
        val helperEligibleEdges = helperEligibleM2MEdges(schema, schemaNames)

        val createLambda = LambdaTypeName.get(
            receiver = createClass,
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
        // (view + client), not the concrete Create builder.
        val beforeCreateHookType = batchHookClass.parameterizedBy(createHookCtxClass)
        val afterCreateHookType = batchHookClass.parameterizedBy(entityClass)
        val beforeUpdateHookType = batchHookClass.parameterizedBy(updateHookCtxClass)
        val afterUpdateHookType = batchHookClass.parameterizedBy(entityClass)
        val beforeDeleteHookType = batchHookClass.parameterizedBy(entityClass)
        val afterDeleteHookType = batchHookClass.parameterizedBy(entityClass)

        fun mutableHookList(hookType: com.squareup.kotlinpoet.TypeName) =
            MUTABLE_LIST.parameterizedBy(hookType)

        val typeSpec = TypeSpec.classBuilder(className)
            // The repo is the entity's read surface: query terminals reach
            // `hasLoadPrivacy()` / `loadDenials(...)` through the
            // EntReadRuntime contract's `${prop}: ${Entity}ReadSurface`
            // accessor, which EntClient overrides with this repo.
            .addSuperinterface(ClassName(packageName, "${schemaName}ReadSurface"))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("driver", DRIVER)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("driver", DRIVER)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("driver")
                    .build()
            )
            // Client reference — attached by EntClient after construction.
            // Private so a repository exposed through EntTransactionClient
            // cannot leak its hidden full EntClient and restore the nested
            // transaction entry point.
            .addProperty(
                PropertySpec.builder("client", clientClass)
                    .addModifiers(KModifier.PRIVATE, KModifier.LATEINIT)
                    .mutable(true)
                    .build()
            )
            .addFunction(
                FunSpec.builder("attachClientForInternalUse")
                    .addAnnotation(ENTKT_INTERNAL)
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("client", clientClass)
                    .addStatement("this.client = client")
                    .build()
            )
            // Hook list properties
            .addProperty(hookListProperty("beforeSaveHooks", mutableHookList(batchHookClass.parameterizedBy(mutationClass))))
            .addProperty(hookListProperty("beforeCreateHooks", mutableHookList(beforeCreateHookType)))
            .addProperty(hookListProperty("afterCreateHooks", mutableHookList(afterCreateHookType)))
            .addProperty(hookListProperty("beforeUpdateHooks", mutableHookList(beforeUpdateHookType)))
            .addProperty(hookListProperty("afterUpdateHooks", mutableHookList(afterUpdateHookType)))
            .addProperty(hookListProperty("beforeDeleteHooks", mutableHookList(beforeDeleteHookType)))
            .addProperty(hookListProperty("afterDeleteHooks", mutableHookList(afterDeleteHookType)))
            // Privacy config
            .addProperty(
                PropertySpec.builder("privacyConfig", privacyConfigClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", privacyConfigClass)
                    .build()
            )
            // Validation config
            .addProperty(
                PropertySpec.builder("validationConfig", validationConfigClass)
                    .addModifiers(KModifier.INTERNAL)
                    .initializer("%T()", validationConfigClass)
                    .build()
            )
            .addInitializerBlock(
                CodeBlock.of("driver.register(%T.SCHEMA)\n", entityClass),
            )
            .addFunction(buildQueryEntry(queryClass, clientRef = "client"))
            // Index-helper namespace. Emitted only when the schema has at
            // least one eligible index (matching the conditional
            // `${schemaName}Indexes` file).
            .also { builder ->
                if (indexHelperTree(schema, schemaNames) != null) {
                    builder.addProperty(buildIndexesProperty(indexesClass, clientRef = "client"))
                }
            }
            .addFunction(buildRepoCreate(schema, entityClass, createClass, createLambda))
            .addFunction(
                // Per-save UpdateConsistency override (transaction locking). Defaults
                // to the client's `defaultUpdateConsistency` so callers
                // who don't pass `consistency =` get the configured
                // baseline (`ReadCurrent` unless the EntClientConfig
                // sets otherwise).
                FunSpec.builder("update")
                    .addParameter("id", idType)
                    .addParameter(
                        ParameterSpec.builder("consistency", UPDATE_CONSISTENCY)
                            .defaultValue("client.defaultUpdateConsistency")
                            .build(),
                    )
                    // Per-save RelationshipLocking override.
                    // Defaults to the client's `defaultRelationshipLocking`
                    // (OwnerOnly unless the EntClientConfig sets otherwise).
                    .addParameter(
                        ParameterSpec.builder("relationshipLocking", RELATIONSHIP_LOCKING)
                            .defaultValue("client.defaultRelationshipLocking")
                            .build(),
                    )
                    .addParameter("block", updateLambda)
                    .returns(updateClass)
                    .addStatement(
                        "return %T(driver, client, id, consistency, relationshipLocking, beforeSaveHooks, beforeUpdateHooks, afterUpdateHooks).apply(block)",
                        updateClass,
                    )
                    .build()
            )
            .addFunction(buildFindById(schemaName, entityClass, idType, clientRef = "client"))
            .addFunction(buildDelete(schemaName, entityClass))
            .addFunction(buildDeleteLoaded(schemaName, entityClass))
            .addFunction(buildDeleteById(schemaName, entityClass, idType))
            .also { builder ->
                if (idStrategyName(schema) != "EXPLICIT") {
                    builder.addType(buildCreateManyWriteCompletionType(entityClass))
                    builder.addType(buildCreateManyDisclosureType(entityClass))
                    builder.addFunction(
                        buildExecuteCreateManyWritePhases(
                            schemaName = schemaName,
                            entityClass = entityClass,
                            createLambda = createLambda,
                            candidateClass = candidateClass,
                        ),
                    )
                    builder.addFunction(buildCreateMany(schemaName, schema.clientName, entityClass, createLambda))
                    builder.addFunction(
                        buildClassifyDriverFailureHelper(
                            schemaName,
                            "CREATE",
                            helperName = "_classifyCreateDriverFailure",
                        ),
                    )
                }
            }
            .addFunction(buildExecuteDeleteManyPhases(schemaName, entityClass))
            .addFunction(buildDeleteMany(schemaName, schema.clientName, entityClass))
            .addFunction(
                buildClassifyDriverFailureHelper(
                    schemaName,
                    "DELETE",
                    helperName = "_classifyDeleteDriverFailure",
                ),
            )
            .addFunction(buildApplyHooks(entityHooksClass))
            .addFunction(buildCopyHooksFrom(repoClass))
            .addFunction(buildApplyPrivacy(privacyConfigClass))
            .addFunction(buildCopyPrivacyFrom(repoClass))
            .addFunction(buildHasPrivacy("hasLoadPrivacy", readSurfaceOverride = true))
            .addFunction(buildHasPrivacy("hasCreatePrivacy"))
            .addFunction(buildHasPrivacy("hasUpdatePrivacy"))
            .addFunction(buildHasPrivacy("hasDeletePrivacy"))
            .addFunction(buildLoadDenials(schemaName, entityClass, loadItemClass, fields))
            .addFunction(buildLoadDenialOrNull(entityClass))
            .addFunction(buildCreateDenialReasons(schemaName, candidateClass, fields))
            .addFunction(buildCreateDenialReasonOrNull(candidateClass))
            .addFunction(
                buildUpdateDenialReasonOrNull(
                    schemaName,
                    entityClass,
                    candidateClass,
                    fields,
                    helperEligibleEdges,
                ),
            )
            .addFunction(buildDeleteDenialReasons(schemaName, entityClass, candidateClass, fields))
            .addFunction(buildDeleteDenialReasonOrNull(entityClass, candidateClass))
            .addFunction(buildBuildDeleteCandidate(schemaName, schema, entityClass, candidateClass, schemaNames))
            .addFunction(buildApplyValidation(validationConfigClass))
            .addFunction(buildCopyValidationFrom(repoClass))
            .addFunction(buildEvaluateCreateValidations(schemaName, candidateClass, fields))
            .addFunction(buildEvaluateCreateValidation(candidateClass))
            .addFunction(
                buildEvaluateUpdateValidation(
                    schemaName,
                    entityClass,
                    candidateClass,
                    fields,
                    helperEligibleEdges,
                ),
            )
            .addFunction(buildEvaluateDeleteValidations(schemaName, entityClass, candidateClass, fields))
            .addFunction(buildEvaluateDeleteValidation(entityClass, candidateClass))
            .build()

        // The repo class implements the `@EntktInternal`-guarded
        // `${schemaName}ReadSurface`; the file-level OptIn consumes the
        // requirement at the declaration site without propagating it to
        // application code using the repo.
        return FileSpec.builder(packageName, className)
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%T::class", ClassName("entkt.query", "EntktInternal"))
                    .build()
            )
            .addType(typeSpec)
            .build()
    }

    /**
     * `delete(entity): MutationResult<Unit>` — idempotent entity-handle
     * delete. `Success(Unit)` means the row is absent afterward,
     * whether this call deleted it or it was already absent.
     */
    private fun buildDelete(schemaName: String, entityClass: ClassName): FunSpec {
        val resultType = MUTATION_RESULT.parameterizedBy(UNIT)
        return FunSpec.builder("delete")
            .addParameter("entity", entityClass)
            .returns(resultType)
            .addKdoc(
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
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add("  client.checkTransactionRequirement(%S)\n", "$schemaName delete")
                    .add("  val row = try {\n")
                    .add("    driver.byId(%T.TABLE, entity.id)\n", entityClass)
                    .add(
                        driverCallFailureTail(
                            "NotPersisted",
                            classifierName = "_classifyDeleteDriverFailure",
                        ).indented(),
                    )
                    .add("  if (row == null) {\n")
                    .add("    %T.Success(Unit)\n", MUTATION_RESULT)
                    .add("  } else {\n")
                    .add("    when (val result = deleteLoaded(%T.fromRow(row))) {\n", entityClass)
                    .add("      is %T.Success -> %T.Success(Unit)\n", MUTATION_RESULT, MUTATION_RESULT)
                    .add("      is %T.Failed -> result\n", MUTATION_RESULT)
                    .add("    }\n")
                    .add("  }\n")
                    .add(terminalBoundaryTailExpression())
                    .build(),
            )
            .build()
    }

    /**
     * Private internal-only delete pipeline that assumes the caller
     * already ran the transaction-requirement preflight AND passes an
     * entity freshly loaded from the database. [buildDelete] and
     * [buildDeleteById] both reload via `byId`, so privacy, validation,
     * and hooks see current row state rather than a caller-controlled copy.
     * Bulk deletion has a separate phase-major pipeline in
     * [buildExecuteDeleteManyPhases].
     *
     * Positional classification: a rule-RETURNED Deny / Invalid from
     * the decision-returning evaluators becomes the typed
     * privacy/validation exception (hardcoded NotPersisted); the
     * `driver.delete` statement routes exceptions through
     * `_classifyDriverFailure` with the PersistenceUnknown write
     * fallback; every other exception (hooks, rule bodies) is foreign
     * and reaches the terminal boundary as
     * `EntUnexpectedMutationException` with the current write state —
     * NotPersisted before the delete, TransactionPending / Committed
     * for afterDelete hooks once the row was removed.
     *
     * `Success(true)` = this call removed the row; `Success(false)` =
     * the row vanished between reload and delete (before-delete hooks
     * may have run; after-delete hooks did not).
     */
    private fun buildDeleteLoaded(
        schemaName: String,
        entityClass: ClassName,
    ): FunSpec {
        return FunSpec.builder("deleteLoaded")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("entity", entityClass)
            .returns(MUTATION_RESULT.parameterizedBy(Boolean::class.asClassName()))
            .addCode(
                CodeBlock.builder()
                    .add("var writeState = %T.NotPersisted\n", MUTATION_WRITE_STATE)
                    .add("try {\n")
                    // Posture snapshot inside the terminal boundary —
                    // see CreateGenerator.
                    .add(
                        "  val postWriteState = if (driver.inTransaction) %T.TransactionPending else %T.Committed\n",
                        MUTATION_WRITE_STATE, MUTATION_WRITE_STATE,
                    )
                    .add("  val privacy = client.currentPrivacyContext()\n")
                    .add("  val candidate = buildDeleteCandidate(entity)\n")
                    .add("  val denialReason = deleteDenialReasonOrNull(privacy, entity, candidate)\n")
                    .add("  if (denialReason != null) {\n")
                    .add(
                        privacyDeniedFailure(
                            writeStateExpr = CodeBlock.of("%T.NotPersisted", MUTATION_WRITE_STATE),
                            schemaName = schemaName,
                            operationName = "DELETE",
                            entityKeyExpr = CodeBlock.of("%T(%S, entity.id)", MUTATION_ENTITY_KEY, "id"),
                            reasonExpr = "denialReason",
                        ).indented(),
                    )
                    .add("  }\n")
                    .add("  val violations = evaluateDeleteValidation(entity, candidate)\n")
                    .add("  if (violations.isNotEmpty()) {\n")
                    .add(
                        recordAndReturnFailure(
                            CodeBlock.of(
                                "%T(%S, %T.DELETE, violations)",
                                ENT_VALIDATION_EXCEPTION, schemaName, MUTATION_ENT_OPERATION,
                            ),
                        ).indented(),
                    )
                    .add("  }\n")
                    .add("  %M(listOf(entity), beforeDeleteHooks)\n", RUN_BATCH_HOOKS_FOR_INTERNAL_USE)
                    .add("  val deleted = try {\n")
                    .add("    driver.delete(%T.TABLE, entity.id)\n", entityClass)
                    .add(
                        driverCallFailureTail(
                            "PersistenceUnknown",
                            classifierName = "_classifyDeleteDriverFailure",
                        ).indented(),
                    )
                    .add("  if (deleted) {\n")
                    .add(
                        "    writeState = postWriteState\n",
                    )
                    .add("    %M(listOf(entity), afterDeleteHooks)\n", RUN_BATCH_HOOKS_FOR_INTERNAL_USE)
                    .add("  }\n")
                    .add("  return %T.Success(deleted)\n", MUTATION_RESULT)
                    .add("} catch (e: %T) {\n", MUTATION_CANCELLATION_EXCEPTION)
                    .add("  throw e\n")
                    .add("} catch (e: %T) {\n", KOTLIN_EXCEPTION)
                    .add(
                        recordAndReturnFailure(
                            CodeBlock.of("%T(writeState, e)", ENT_UNEXPECTED_MUTATION_EXCEPTION),
                        ).indented(),
                    )
                    .add("}\n")
                    .build(),
            )
            .build()
    }

    /**
     * `deleteById(id): MutationResult<Boolean>` — idempotent
     * delete-by-id preserving the affected-row acknowledgement.
     */
    private fun buildDeleteById(
        schemaName: String,
        entityClass: ClassName,
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec {
        val resultType = MUTATION_RESULT.parameterizedBy(Boolean::class.asClassName())
        return FunSpec.builder("deleteById")
            .addParameter("id", idType)
            .returns(resultType)
            .addKdoc(
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
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add("  client.checkTransactionRequirement(%S)\n", "$schemaName delete")
                    .add("  val row = try {\n")
                    .add("    driver.byId(%T.TABLE, id)\n", entityClass)
                    .add(
                        driverCallFailureTail(
                            "NotPersisted",
                            classifierName = "_classifyDeleteDriverFailure",
                        ).indented(),
                    )
                    .add("  if (row == null) {\n")
                    .add("    %T.Success(false)\n", MUTATION_RESULT)
                    .add("  } else {\n")
                    .add("    deleteLoaded(%T.fromRow(row))\n", entityClass)
                    .add("  }\n")
                    .add(terminalBoundaryTailExpression())
                    .build(),
            )
            .build()
    }

    /** Execute the phase-major delete-many pipeline on a transaction-scoped repo. */
    private fun buildExecuteDeleteManyPhases(
        schemaName: String,
        entityClass: ClassName,
    ): FunSpec {
        val queryClass = ClassName(entityClass.packageName, "${schemaName}Query")
        val predicateType = PREDICATE.parameterizedBy(entityClass)
        return FunSpec.builder("_executeDeleteManyPhases")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("predicates", LIST.parameterizedBy(predicateType))
            .addParameter("promoteDriverNotPersisted", BOOLEAN)
            .returns(MUTATION_RESULT.parameterizedBy(INT))
            .addCode(
                CodeBlock.builder()
                    .add("var writeState = %T.NotPersisted\n", MUTATION_WRITE_STATE)
                    .add("try {\n")
                    .add("  check(driver.inTransaction) { %S }\n", "deleteMany phases require a transaction-scoped driver")
                    .add(
                        "  val q = %T(driver, client).apply { for (predicate in predicates) where(predicate) }\n",
                        queryClass,
                    )
                    .add("  val privacy = client.currentPrivacyContext()\n")
                    .add(
                        "  val spec = q.runReadInterceptors(%T.DELETE_CANDIDATES, privacy)\n",
                        READ_OPERATION,
                    )
                    .add("  val effectivePredicates = spec.predicates.toList()\n")
                    .add(
                        "  val rows = driver.query(%T.TABLE, effectivePredicates, emptyList(), null, null)\n",
                        entityClass,
                    )
                    .add("  val entities = rows.map { %T.fromRow(it) }\n", entityClass)
                    .add("  if (entities.isEmpty()) return %T.Success(0)\n", MUTATION_RESULT)
                    .add("  val candidates = entities.map { buildDeleteCandidate(it) }\n")
                    .add("  val denialReasons = deleteDenialReasons(privacy, entities, candidates)\n")
                    .add("  val deniedIndex = denialReasons.indexOfFirst { it != null }\n")
                    .add("  if (deniedIndex >= 0) {\n")
                    .add(
                        privacyDeniedFailure(
                            writeStateExpr = CodeBlock.of("%T.NotPersisted", MUTATION_WRITE_STATE),
                            schemaName = schemaName,
                            operationName = "DELETE",
                            entityKeyExpr = CodeBlock.of("%T(%S, entities[deniedIndex].id)", MUTATION_ENTITY_KEY, "id"),
                            reasonExpr = "denialReasons[deniedIndex]!!",
                        ).indented(),
                    )
                    .add("  }\n")
                    .add("  val violationsByCandidate = evaluateDeleteValidations(entities, candidates)\n")
                    .add("  val invalidIndex = violationsByCandidate.indexOfFirst { it.isNotEmpty() }\n")
                    .add("  if (invalidIndex >= 0) {\n")
                    .add(
                        recordAndReturnFailure(
                            CodeBlock.of(
                                "%T(%S, %T.DELETE, violationsByCandidate[invalidIndex])",
                                ENT_VALIDATION_EXCEPTION,
                                schemaName,
                                MUTATION_ENT_OPERATION,
                            ),
                        ).indented(),
                    )
                    .add("  }\n")
                    .add("  %M(entities, beforeDeleteHooks)\n", RUN_BATCH_HOOKS_FOR_INTERNAL_USE)
                    .add("  val approvedIds = entities.map { it.id }\n")
                    // A default/optimized driver may execute several physical
                    // statements. Once the logical call begins, earlier IDs
                    // may already be staged in this open transaction.
                    .add("  writeState = %T.TransactionPending\n", MUTATION_WRITE_STATE)
                    .add("  val deletedIds = try {\n")
                    .add(
                        "    driver.deleteManyByIds(%T.TABLE, %T.SCHEMA.idColumn, approvedIds, effectivePredicates)\n",
                        entityClass,
                        entityClass,
                    )
                    .add("  } catch (e: %T) {\n", MUTATION_CANCELLATION_EXCEPTION)
                    .add("    throw e\n")
                    .add("  } catch (e: %T) {\n", KOTLIN_EXCEPTION)
                    .add(
                        "    val classified = _classifyDeleteDriverFailure(e, %T.TransactionPending)\n",
                        MUTATION_WRITE_STATE,
                    )
                    .add(
                        "    val reported = if (promoteDriverNotPersisted && approvedIds.size > 1 && " +
                            "classified.writeState == %T.NotPersisted) {\n",
                        MUTATION_WRITE_STATE,
                    )
                    .add(
                        "      %T(%T.TransactionPending, classified)\n",
                        ENT_UNEXPECTED_MUTATION_EXCEPTION,
                        MUTATION_WRITE_STATE,
                    )
                    .add("    } else classified\n")
                    .add("    client.recordTransactionMutationFailure(reported)\n")
                    .add("    return %T.failedForInternalUse(reported)\n", MUTATION_RESULT)
                    .add("  }\n")
                    .add("  val deletedIdSnapshot = deletedIds.toList()\n")
                    .add("  val approvedIdSet = approvedIds.toSet()\n")
                    .add("  val deletedIdSet = deletedIdSnapshot.toSet()\n")
                    .add(
                        "  check(deletedIdSnapshot.size == deletedIdSet.size && deletedIdSet.all { it in approvedIdSet }) { %S }\n",
                        "DatabaseDriver.deleteManyByIds returned duplicate or unapproved IDs",
                    )
                    .add("  val deletedEntities = entities.filter { it.id in deletedIdSet }\n")
                    .add(
                        "  check(deletedEntities.size == deletedIdSnapshot.size) { %S }\n",
                        "DatabaseDriver.deleteManyByIds acknowledgement could not be correlated to candidates",
                    )
                    .add("  %M(deletedEntities, afterDeleteHooks)\n", RUN_BATCH_HOOKS_FOR_INTERNAL_USE)
                    .add("  return %T.Success(deletedIdSnapshot.size)\n", MUTATION_RESULT)
                    .add("} catch (e: %T) {\n", MUTATION_CANCELLATION_EXCEPTION)
                    .add("  throw e\n")
                    .add("} catch (e: %T) {\n", KOTLIN_EXCEPTION)
                    .add(
                        recordAndReturnFailure(
                            CodeBlock.of("%T(writeState, e)", ENT_UNEXPECTED_MUTATION_EXCEPTION),
                        ).indented(),
                    )
                    .add("}\n")
                    .build(),
            )
            .build()
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
        schemaName: String,
        clientName: String,
        entityClass: ClassName,
    ): FunSpec {
        val repoPropName = clientName
        val resultType = MUTATION_RESULT.parameterizedBy(INT)
        return FunSpec.builder("deleteMany")
            .addParameter(
                // vararg predicates: Predicate<EntityClass> — typed in
                // the entity scope so callers can only pass predicates
                // for this repo's entity, matching the rest of the
                // typed query DSL surface.
                ParameterSpec.builder("predicates", PREDICATE.parameterizedBy(entityClass))
                    .addModifiers(KModifier.VARARG)
                    .build(),
            )
            .returns(resultType)
            .addKdoc(
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
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add(
                        "  client.checkTransactionRequirement(%S, multiWrite = true)\n",
                        "$schemaName deleteMany",
                    )
                    .add("  val predicateSnapshot = predicates.toList()\n")
                    .add("  if (driver.inTransaction) {\n")
                    .add("    _executeDeleteManyPhases(predicateSnapshot, promoteDriverNotPersisted = true)\n")
                    .add("  } else {\n")
                    .add("    val txResult = client.withTransaction { tx ->\n")
                    .add(
                        "      tx.%L._executeDeleteManyPhases(" +
                            "predicateSnapshot, promoteDriverNotPersisted = false).orRollback()\n",
                        repoPropName,
                    )
                    .add("    }\n")
                    .add("    when (txResult) {\n")
                    .add("      is %T.Success -> %T.Success(txResult.value)\n", TRANSACTION_RESULT, MUTATION_RESULT)
                    .add("      is %T.Failed -> {\n", TRANSACTION_RESULT)
                    .add(txFailureConversion().indented().indented().indented())
                    .add("      }\n")
                    .add("    }\n")
                    .add("  }\n")
                    .add(terminalBoundaryTailExpression())
                    .build(),
            )
            .build()
    }

    /**
     * Shared conversion of a `TransactionResult.Failed` from an
     * EntKt-owned self-delegated bulk transaction into a
     * `MutationResult` failure. After a confirmed rollback
     * (`NotCommitted`), the RFC requires the bulk result to carry
     * `NotPersisted`: a stored `EntMutationException` that already
     * hardcodes `NotPersisted` (validation, privacy, target-absence,
     * recognized constraints/conflicts — the common cases) passes
     * through unchanged so typed catches keep working, while a stored
     * exception carrying a mid-transaction state
     * (`TransactionPending`/`PersistenceUnknown`) is re-reported as
     * `EntUnexpectedMutationException(NotPersisted, stored)` — the
     * rollback made `NotPersisted` the accurate batch state, and the
     * original typed failure stays reachable as the cause. Any other
     * confirmed-rollback exception wraps with NotPersisted, and an
     * unknown outcome wraps with PersistenceUnknown. The record call
     * is a no-op here (this path only runs outside a caller-owned
     * transaction) but keeps every Failed construction site uniform.
     */
    private fun txFailureConversion(): CodeBlock =
        CodeBlock.builder()
            .add("val stored = txResult.exception\n")
            .add(
                "val exception = if (txResult.transactionState == %T.OutcomeUnknown) {\n",
                TRANSACTION_FAILURE_STATE,
            )
            .add("  %T(%T.PersistenceUnknown, stored)\n", ENT_UNEXPECTED_MUTATION_EXCEPTION, MUTATION_WRITE_STATE)
            .add(
                "} else if (stored is %T && stored.writeState == %T.NotPersisted) {\n",
                ENT_MUTATION_EXCEPTION, MUTATION_WRITE_STATE,
            )
            .add("  stored\n")
            .add("} else {\n")
            // Re-report as NotPersisted (rollback confirmed). When the
            // stored exception is itself an unexpected-failure wrapper,
            // wrap its CAUSE instead so the typed row failure stays one
            // level away rather than two.
            .add(
                "  %T(%T.NotPersisted, ((stored as? %T)?.cause as? %T) ?: stored)\n",
                ENT_UNEXPECTED_MUTATION_EXCEPTION, MUTATION_WRITE_STATE,
                ENT_UNEXPECTED_MUTATION_EXCEPTION, KOTLIN_EXCEPTION,
            )
            .add("}\n")
            .add("client.recordTransactionMutationFailure(exception)\n")
            .add("%T.failedForInternalUse(exception)\n", MUTATION_RESULT)
            .build()

    /**
     * Create-many returned LOAD failures normally become `Committed` after a
     * successful owned transaction. A LOAD rule may instead execute SQL that
     * aborts PostgreSQL; the transaction driver then rolls back and reports its
     * generic aborted-state marker. Preserve the actual disclosure exception
     * as the confirmed-rollback cause unless the transaction coordinator had
     * already recorded an earlier mutation failure; encounter-order precedence
     * keeps that failure primary and attaches disclosure diagnostically. An
     * unknown transaction outcome always stays primary.
     */
    private fun createManyTxFailureConversion(schemaName: String): CodeBlock =
        CodeBlock.builder()
            .add("val stored = txResult.exception\n")
            .add("val disclosure = disclosureFailure\n")
            .add("val denial = disclosureDenial\n")
            .add(
                "val exception = if (txResult.transactionState == %T.OutcomeUnknown) {\n",
                TRANSACTION_FAILURE_STATE,
            )
            .add(
                "  val unknown = %T(%T.PersistenceUnknown, stored)\n",
                ENT_UNEXPECTED_MUTATION_EXCEPTION,
                MUTATION_WRITE_STATE,
            )
            .add("  if (denial != null) {\n")
            .add(
                "    unknown.addSuppressed(%T(%T.PersistenceUnknown, %S, %T.LOAD, " +
                    "denial.entityKey, denial.reason))\n",
                ENT_MUTATION_PRIVACY_DENIED_EXCEPTION,
                MUTATION_WRITE_STATE,
                schemaName,
                MUTATION_ENT_OPERATION,
            )
            .add("  }\n")
            .add("  if (disclosure != null && disclosure !== stored) unknown.addSuppressed(disclosure)\n")
            .add("  unknown\n")
            .add("} else if (stored is %T) {\n", ENT_MUTATION_EXCEPTION)
            .add("  val primary = if (stored.writeState == %T.NotPersisted) {\n", MUTATION_WRITE_STATE)
            .add("    stored\n")
            .add("  } else {\n")
            .add(
                "    %T(%T.NotPersisted, ((stored as? %T)?.cause as? %T) ?: stored)\n",
                ENT_UNEXPECTED_MUTATION_EXCEPTION,
                MUTATION_WRITE_STATE,
                ENT_UNEXPECTED_MUTATION_EXCEPTION,
                KOTLIN_EXCEPTION,
            )
            .add("  }\n")
            .add("  if (primary !== stored) {\n")
            .add("    stored.suppressed.forEach { suppressed ->\n")
            .add("      if (suppressed !== primary && primary.suppressed.none { it === suppressed }) {\n")
            .add("        primary.addSuppressed(suppressed)\n")
            .add("      }\n")
            .add("    }\n")
            .add("  }\n")
            .add("  if (denial != null) {\n")
            .add(
                "    primary.addSuppressed(%T(%T.NotPersisted, %S, %T.LOAD, " +
                    "denial.entityKey, denial.reason))\n",
                ENT_MUTATION_PRIVACY_DENIED_EXCEPTION,
                MUTATION_WRITE_STATE,
                schemaName,
                MUTATION_ENT_OPERATION,
            )
            .add("  }\n")
            .add(
                "  if (disclosure != null && disclosure !== primary && " +
                    "primary.suppressed.none { it === disclosure }) primary.addSuppressed(disclosure)\n",
            )
            .add("  primary\n")
            .add("} else if (denial != null) {\n")
            .add(
                "  val rolledBack = %T(%T.NotPersisted, %S, %T.LOAD, " +
                    "denial.entityKey, denial.reason)\n",
                ENT_MUTATION_PRIVACY_DENIED_EXCEPTION,
                MUTATION_WRITE_STATE,
                schemaName,
                MUTATION_ENT_OPERATION,
            )
            .add("  if (stored !== rolledBack) rolledBack.addSuppressed(stored)\n")
            .add("  rolledBack\n")
            .add("} else if (disclosure != null) {\n")
            .add(
                "  val rolledBack = %T(%T.NotPersisted, disclosure)\n",
                ENT_UNEXPECTED_MUTATION_EXCEPTION,
                MUTATION_WRITE_STATE,
            )
            .add("  if (stored !== disclosure) rolledBack.addSuppressed(stored)\n")
            .add("  rolledBack\n")
            .add("} else {\n")
            .add(
                "  %T(%T.NotPersisted, ((stored as? %T)?.cause as? %T) ?: stored)\n",
                ENT_UNEXPECTED_MUTATION_EXCEPTION,
                MUTATION_WRITE_STATE,
                ENT_UNEXPECTED_MUTATION_EXCEPTION,
                KOTLIN_EXCEPTION,
            )
            .add("}\n")
            .add("client.recordTransactionMutationFailure(exception)\n")
            .add("%T.failedForInternalUse(exception)\n", MUTATION_RESULT)
            .build()

    /**
     * The expression-style terminal capture boundary shared by the
     * repo mutation terminals: rethrow cancellation, then wrap any
     * other ordinary exception as foreign with the pre-write
     * NotPersisted state (each repo terminal's own write phase is
     * classified inside `deleteLoaded` / the per-row create pipeline,
     * so only pre-write work can throw here). Closes the `return try {`
     * opened by the caller.
     */
    private fun terminalBoundaryTailExpression(): CodeBlock =
        CodeBlock.builder()
            .add("} catch (e: %T) {\n", MUTATION_CANCELLATION_EXCEPTION)
            .add("  throw e\n")
            .add("} catch (e: %T) {\n", KOTLIN_EXCEPTION)
            .add(
                "  val exception = %T(%T.NotPersisted, e)\n",
                ENT_UNEXPECTED_MUTATION_EXCEPTION, MUTATION_WRITE_STATE,
            )
            .add("  client.recordTransactionMutationFailure(exception)\n")
            .add("  %T.failedForInternalUse(exception)\n", MUTATION_RESULT)
            .add("}\n")
            .build()

    private fun buildApplyPrivacy(privacyConfigClass: ClassName): FunSpec =
        FunSpec.builder("applyPrivacy")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("config", privacyConfigClass)
            .addStatement("privacyConfig.loadRules.addAll(config.loadRules)")
            .addStatement("privacyConfig.createRules.addAll(config.createRules)")
            .addStatement("privacyConfig.updateRules.addAll(config.updateRules)")
            .addStatement("privacyConfig.deleteRules.addAll(config.deleteRules)")
            .addStatement("if (config.updateDerivesFromCreate) privacyConfig.updateDerivesFromCreate = true")
            .addStatement("if (config.deleteDerivesFromCreate) privacyConfig.deleteDerivesFromCreate = true")
            .build()

    private fun buildCopyPrivacyFrom(repoClass: ClassName): FunSpec =
        FunSpec.builder("copyPrivacyFrom")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("other", repoClass)
            .addStatement("privacyConfig.loadRules.addAll(other.privacyConfig.loadRules)")
            .addStatement("privacyConfig.createRules.addAll(other.privacyConfig.createRules)")
            .addStatement("privacyConfig.updateRules.addAll(other.privacyConfig.updateRules)")
            .addStatement("privacyConfig.deleteRules.addAll(other.privacyConfig.deleteRules)")
            .addStatement("privacyConfig.updateDerivesFromCreate = other.privacyConfig.updateDerivesFromCreate")
            .addStatement("privacyConfig.deleteDerivesFromCreate = other.privacyConfig.deleteDerivesFromCreate")
            .build()

    // Privacy is fail-closed: every operation requires an explicit Allow, so
    // every entity is privacy-enforced regardless of which rules are declared.
    // These flags therefore always report true (the call sites that gate on
    // them always take the enforce path).
    //
    // hasLoadPrivacy is the read surface's flag and overrides
    // `${Entity}ReadSurface` (public — interface members can't be
    // internal); the write-side flags stay internal.
    private fun buildHasPrivacy(name: String, readSurfaceOverride: Boolean = false): FunSpec =
        FunSpec.builder(name)
            .addModifiers(if (readSurfaceOverride) KModifier.OVERRIDE else KModifier.INTERNAL)
            .returns(Boolean::class)
            .addStatement("return true")
            .build()

    private fun buildLoadDenials(
        schemaName: String,
        entityClass: ClassName,
        loadItemClass: ClassName,
        fields: List<Field>,
    ): FunSpec {
        // Overrides `${Entity}ReadSurface` (public, was internal): the
        // LOAD evaluation is what validation read repos delegate to, so
        // privacy behavior through either client is this exact body.
        //
        // Decision-returning rather than throwing so read terminals can
        // aggregate keyed denials (strict all() reports every denied
        // root row) and so a rule-RETURNED Deny is positionally
        // distinguishable from a rule-THROWN exception: the former
        // becomes a typed PrivacyDenial here, the latter escapes to the
        // terminal's capture boundary as an operational failure.
        return FunSpec.builder("loadDenials")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("entities", LIST.parameterizedBy(entityClass))
            .returns(LIST.parameterizedBy(PRIVACY_DENIAL.copy(nullable = true)))
            .addCode(CodeBlock.builder()
                .addStatement("if (entities.isEmpty()) return emptyList()")
                .addStatement("val entitySnapshot = entities.toList()")
                .addStatement(
                    "if (privacy.viewer is %T.PrivacyBypass) return %T(entitySnapshot.size) { null }",
                    VIEWER,
                    LIST,
                )
                .addStatement("val rules = privacyConfig.loadRules")
                .addStatement("val privacyClient = client.asPrivacyReadClientForInternalUse(privacy)")
                .addStatement("val ruleContext = %T(privacy, privacyClient)", PRIVACY_RULE_CONTEXT)
                .addStatement(
                    "val decisions = %M(%S, entitySnapshot, rules, ruleContext) { item ->\n" +
                        "  %T(%L)\n" +
                        "}",
                    EVALUATE_BATCH_PRIVACY_RULES,
                    "$schemaName LOAD privacy",
                    loadItemClass,
                    lifecycleValueSnapshot("item", fields, entityClass),
                )
                .beginControlFlow("return entitySnapshot.mapIndexed { index, entity ->")
                .beginControlFlow("when (val decision = decisions[index])")
                .addStatement("is %T.Allow -> null", PRIVACY_DECISION)
                .addStatement(
                    "is %T.Deny -> %T(%S, %T(%S, entity.id), decision.reason)",
                    PRIVACY_DECISION, PRIVACY_DENIAL, schemaName, ENTITY_KEY, "id",
                )
                .addStatement(
                    "is %T.Continue -> %T(%S, %T(%S, entity.id), %S)",
                    PRIVACY_DECISION,
                    PRIVACY_DENIAL, schemaName, ENTITY_KEY, "id", "no load rule allowed access",
                )
                .endControlFlow()
                .endControlFlow()
                .build()
            )
            .build()
    }

    private fun buildLoadDenialOrNull(entityClass: ClassName): FunSpec =
        FunSpec.builder("loadDenialOrNull")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("entity", entityClass)
            .returns(PRIVACY_DENIAL.copy(nullable = true))
            .addStatement("return loadDenials(privacy, listOf(entity)).single()")
            .build()

    // ── Write-side privacy evaluators ─────────────────────────────
    // DECISION-RETURNING (String? denial reason; null = allowed), so
    // the mutation terminals can distinguish a rule-RETURNED Deny /
    // fail-closed end-of-list (→ typed EntMutationPrivacyDeniedException
    // constructed at the call site) from a rule-THROWN exception —
    // there is deliberately no catch inside these helpers: a thrown
    // exception escapes to the terminal's capture boundary as a
    // foreign failure. Privacy stays fail-closed: with no explicit
    // Allow, the helper returns the "no <op> rule allowed access"
    // reason.

    private fun buildCreateDenialReasons(
        schemaName: String,
        candidateClass: ClassName,
        fields: List<Field>,
    ): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        val createItemClass = ClassName(packageName, "${schemaName}CreatePrivacyItem")
        return FunSpec.builder("createDenialReasons")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("candidates", LIST.parameterizedBy(candidateClass))
            .returns(LIST.parameterizedBy(String::class.asClassName().copy(nullable = true)))
            .addCode(CodeBlock.builder()
                .addStatement("if (candidates.isEmpty()) return emptyList()")
                .addStatement("val candidateSnapshot = candidates.toList()")
                .addStatement(
                    "if (privacy.viewer is %T.PrivacyBypass) return %T(candidateSnapshot.size) { null }",
                    VIEWER,
                    LIST,
                )
                .addStatement("val rules = privacyConfig.createRules")
                .addStatement("val privacyClient = client.asPrivacyReadClientForInternalUse(privacy)")
                .addStatement("val ruleContext = %T(privacy, privacyClient)", PRIVACY_RULE_CONTEXT)
                .addStatement(
                    "val decisions = %M(%S, candidateSnapshot, rules, ruleContext) { item ->\n" +
                        "  %T(%L)\n" +
                        "}",
                    EVALUATE_BATCH_PRIVACY_RULES,
                    "$schemaName CREATE privacy",
                    createItemClass,
                    lifecycleValueSnapshot("item", fields, entityClass),
                )
                .beginControlFlow("return decisions.map { decision ->")
                .beginControlFlow("when (decision)")
                .addStatement("is %T.Allow -> null", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> decision.reason", PRIVACY_DECISION)
                .addStatement("is %T.Continue -> %S", PRIVACY_DECISION, "no create rule allowed access")
                .endControlFlow()
                .endControlFlow()
                .build()
            )
            .build()
    }

    private fun buildCreateDenialReasonOrNull(candidateClass: ClassName): FunSpec =
        FunSpec.builder("createDenialReasonOrNull")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("candidate", candidateClass)
            .returns(String::class.asClassName().copy(nullable = true))
            .addStatement("return createDenialReasons(privacy, listOf(candidate)).single()")
            .build()

    /**
     * Build a fresh per-rule edge-change sidecar. A Kotlin `Set` is only
     * read-only at compile time, so each runtime EdgeChanges value also wraps
     * detached sets whose mutators fail on the JVM.
     */
    private fun edgeChangesSnapshot(
        source: String,
        helperEligibleEdges: List<HelperEligibleM2M>,
    ): CodeBlock {
        if (helperEligibleEdges.isEmpty()) return CodeBlock.of("%L", source)

        return CodeBlock.builder()
            .add("%L.copy(\n", source)
            .apply {
                for (edge in helperEligibleEdges) {
                    add(
                        "  %L = %M(%L.%L),\n",
                        edge.mutatorPropertyName,
                        SNAPSHOT_EDGE_CHANGES,
                        source,
                        edge.mutatorPropertyName,
                    )
                }
            }
            .add(")")
            .build()
    }

    private fun buildUpdateDenialReasonOrNull(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
        fields: List<Field>,
        helperEligibleEdges: List<HelperEligibleM2M>,
    ): FunSpec {
        val updateItemClass = ClassName(packageName, "${schemaName}UpdatePrivacyItem")
        val createItemClass = ClassName(packageName, "${schemaName}CreatePrivacyItem")
        val patchClass = ClassName(packageName, "${schemaName}UpdatePatch")
        val edgeChangesViewClass = ClassName(packageName, "${schemaName}EdgeChangesView")
        return FunSpec.builder("updateDenialReasonOrNull")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("before", entityClass)
            .addParameter("requestedPatch", patchClass)
            .addParameter("effectivePatch", patchClass)
            .addParameter("candidate", candidateClass)
            .addParameter("edgeChanges", edgeChangesViewClass)
            .returns(String::class.asClassName().copy(nullable = true))
            .addCode(CodeBlock.builder()
                .addStatement("if (privacy.viewer is %T.PrivacyBypass) return null", VIEWER)
                .addStatement("val rules = privacyConfig.updateRules")
                .addStatement("val privacyClient = client.asPrivacyReadClientForInternalUse(privacy)")
                .addStatement("val ruleContext = %T(privacy, privacyClient)", PRIVACY_RULE_CONTEXT)
                .addStatement(
                    "var decision = %M(%S, listOf(candidate), rules, ruleContext) { item ->\n" +
                    "  %T(%L, %L, %L, %L, %L)\n" +
                        "}.single()",
                    EVALUATE_BATCH_PRIVACY_RULES,
                    "$schemaName UPDATE privacy",
                    updateItemClass,
                    lifecycleValueSnapshot("before", fields, entityClass),
                    lifecyclePatchSnapshot("requestedPatch", fields, entityClass),
                    lifecyclePatchSnapshot("effectivePatch", fields, entityClass),
                    lifecycleValueSnapshot("item", fields, entityClass),
                    edgeChangesSnapshot("edgeChanges", helperEligibleEdges),
                )
                .beginControlFlow(
                    "if (decision is %T.Continue && privacyConfig.updateDerivesFromCreate)",
                    PRIVACY_DECISION,
                )
                .addStatement(
                    "decision = %M(%S, listOf(candidate), privacyConfig.createRules, ruleContext) { item ->\n" +
                        "  %T(%L)\n" +
                        "}.single()",
                    EVALUATE_BATCH_PRIVACY_RULES,
                    "$schemaName UPDATE privacy",
                    createItemClass,
                    lifecycleValueSnapshot("item", fields, entityClass),
                )
                .endControlFlow()
                .beginControlFlow("return when (decision)")
                .addStatement("is %T.Allow -> null", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> decision.reason", PRIVACY_DECISION)
                .addStatement("is %T.Continue -> %S", PRIVACY_DECISION, "no update rule allowed access")
                .endControlFlow()
                .build()
            )
            .build()
    }

    private fun buildDeleteDenialReasons(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
        fields: List<Field>,
    ): FunSpec {
        val deleteItemClass = ClassName(packageName, "${schemaName}DeletePrivacyItem")
        val createItemClass = ClassName(packageName, "${schemaName}CreatePrivacyItem")
        return FunSpec.builder("deleteDenialReasons")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("entities", LIST.parameterizedBy(entityClass))
            .addParameter("candidates", LIST.parameterizedBy(candidateClass))
            .returns(LIST.parameterizedBy(String::class.asClassName().copy(nullable = true)))
            .addCode(CodeBlock.builder()
                .addStatement("require(entities.size == candidates.size) { %S }", "DELETE entity/candidate count mismatch")
                .addStatement("if (entities.isEmpty()) return emptyList()")
                .addStatement("val entitySnapshot = entities.toList()")
                .addStatement("val candidateSnapshot = candidates.toList()")
                .addStatement(
                    "if (privacy.viewer is %T.PrivacyBypass) return %T(entitySnapshot.size) { null }",
                    VIEWER,
                    LIST,
                )
                .addStatement("val indexes = entitySnapshot.indices.toList()")
                .addStatement("val rules = privacyConfig.deleteRules")
                .addStatement("val privacyClient = client.asPrivacyReadClientForInternalUse(privacy)")
                .addStatement("val ruleContext = %T(privacy, privacyClient)", PRIVACY_RULE_CONTEXT)
                .addStatement(
                    "val decisions = %M(%S, indexes, rules, ruleContext) { index ->\n" +
                        "  %T(%L, %L)\n" +
                        "}.toMutableList()",
                    EVALUATE_BATCH_PRIVACY_RULES,
                    "$schemaName DELETE privacy",
                    deleteItemClass,
                    lifecycleValueSnapshot("entitySnapshot[index]", fields, entityClass),
                    lifecycleValueSnapshot("candidateSnapshot[index]", fields, entityClass),
                )
                .addStatement(
                    "val unresolvedIndexes = decisions.indices.filter { decisions[it] is %T.Continue }",
                    PRIVACY_DECISION,
                )
                .beginControlFlow("if (unresolvedIndexes.isNotEmpty() && privacyConfig.deleteDerivesFromCreate)")
                .addStatement(
                    "val derived = %M(%S, unresolvedIndexes, privacyConfig.createRules, ruleContext) { index ->\n" +
                        "  %T(%L)\n" +
                        "}",
                    EVALUATE_BATCH_PRIVACY_RULES,
                    "$schemaName DELETE privacy",
                    createItemClass,
                    lifecycleValueSnapshot("candidateSnapshot[index]", fields, entityClass),
                )
                .addStatement(
                    "unresolvedIndexes.forEachIndexed { resultIndex, originalIndex -> " +
                        "decisions[originalIndex] = derived[resultIndex] }",
                )
                .endControlFlow()
                .beginControlFlow("return decisions.map { decision ->")
                .beginControlFlow("when (decision)")
                .addStatement("is %T.Allow -> null", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> decision.reason", PRIVACY_DECISION)
                .addStatement("is %T.Continue -> %S", PRIVACY_DECISION, "no delete rule allowed access")
                .endControlFlow()
                .endControlFlow()
                .build()
            )
            .build()
    }

    private fun buildDeleteDenialReasonOrNull(
        entityClass: ClassName,
        candidateClass: ClassName,
    ): FunSpec = FunSpec.builder("deleteDenialReasonOrNull")
        .addModifiers(KModifier.INTERNAL)
        .addParameter("privacy", PRIVACY_CONTEXT)
        .addParameter("entity", entityClass)
        .addParameter("candidate", candidateClass)
        .returns(String::class.asClassName().copy(nullable = true))
        .addStatement(
            "return deleteDenialReasons(privacy, listOf(entity), listOf(candidate)).single()",
        )
        .build()

    private fun buildBuildDeleteCandidate(
        schemaName: String,
        schema: EntSchema,
        entityClass: ClassName,
        candidateClass: ClassName,
        schemaNames: Map<EntSchema, String>,
    ): FunSpec {
        val fields = scalarFields(schema)
        val edgeFks = computeEdgeFks(schema, schemaNames)
        val body = CodeBlock.builder()
        body.add("return %T(\n", candidateClass)
        for (field in fields) {
            val propName = field.apiName
            body.add("  %L = entity.%L,\n", propName, propName)
        }
        for (fk in edgeFks) {
            body.add("  %L = entity.%L,\n", fk.propertyName, fk.propertyName)
        }
        body.add(")\n")

        return FunSpec.builder("buildDeleteCandidate")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("entity", entityClass)
            .returns(candidateClass)
            .addCode(body.build())
            .build()
    }

    private fun buildRepoCreate(
        schema: EntSchema,
        entityClass: ClassName,
        createClass: ClassName,
        createLambda: LambdaTypeName,
    ): FunSpec {
        val idStrategy = idStrategyName(schema)
        val builder = FunSpec.builder("create")
        if (idStrategy == "EXPLICIT") {
            builder.addParameter("id", schema.id().type.toTypeName())
        }
        builder.addParameter("block", createLambda)
            .returns(createClass)
        val createArgs = if (idStrategy == "EXPLICIT") {
            "driver, client, beforeSaveHooks, beforeCreateHooks, afterCreateHooks, id = id"
        } else {
            "driver, client, beforeSaveHooks, beforeCreateHooks, afterCreateHooks"
        }
        builder.addStatement("return %T($createArgs).apply(block)", createClass)
        return builder.build()
    }

    /**
     * Private generated carrier for the completed write-side portion of
     * `createMany`. The exact privacy context captured for CREATE privacy
     * travels with the hydrated entities so returned LOAD disclosure cannot
     * accidentally authorize under a second viewer.
     */
    private fun buildCreateManyWriteCompletionType(entityClass: ClassName): TypeSpec =
        TypeSpec.classBuilder("CreateManyWriteCompletion")
            .addModifiers(KModifier.PRIVATE, KModifier.DATA)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("entities", LIST.parameterizedBy(entityClass))
                    .addParameter("privacy", PRIVACY_CONTEXT)
                    .addParameter(
                        "managedSaveFailures",
                        LIST.parameterizedBy(ENT_MUTATION_EXCEPTION),
                    )
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("entities", LIST.parameterizedBy(entityClass))
                    .initializer("entities")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("privacy", PRIVACY_CONTEXT)
                    .initializer("privacy")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "managedSaveFailures",
                    LIST.parameterizedBy(ENT_MUTATION_EXCEPTION),
                )
                    .initializer("managedSaveFailures")
                    .build(),
            )
            .build()

    /**
     * Observe an attempted scalar save on any builder owned by the active
     * createMany call. The builder records its framework-created failure as
     * soon as save is attempted; this projection makes the enclosing bulk
     * terminal return that failure too. If the batch write has begun, promote
     * it to the enclosing operation's current write state and replace the
     * coordinator entry in place.
     */
    private fun createManyManagedSaveFailureCheck(caughtException: String? = null): CodeBlock =
        CodeBlock.builder()
            .add("managedSaveFailures.firstOrNull()?.let { managedFailure ->\n")
            .apply {
                if (caughtException != null) {
                    add(
                        "  if (%L !== managedFailure && managedFailure.suppressed.none { it === %L }) {\n",
                        caughtException,
                        caughtException,
                    )
                    add("    managedFailure.addSuppressed(%L)\n", caughtException)
                    add("  }\n")
                }
            }
            .add(
                "  val reported = if (writeState == %T.NotPersisted) managedFailure " +
                    "else %T(writeState, managedFailure)\n",
                MUTATION_WRITE_STATE,
                ENT_UNEXPECTED_MUTATION_EXCEPTION,
            )
            .add("  client.replaceTransactionMutationFailure(managedFailure, reported)\n")
            .add("  return %T.failedForInternalUse(reported)\n", MUTATION_RESULT)
            .add("}\n")
            .build()

    /**
     * Neutral returned-disclosure outcome. In an EntKt-owned transaction a
     * denial or ordinary LOAD failure must leave the block normally so commit
     * can be attempted; only a confirmed successful commit may later classify
     * it as `Committed`.
     */
    private fun buildCreateManyDisclosureType(entityClass: ClassName): TypeSpec {
        val repoClass = ClassName(packageName, "${entityClass.simpleName}Repo")
        val disclosureClass = repoClass.nestedClass("CreateManyDisclosure")
        return TypeSpec.interfaceBuilder("CreateManyDisclosure")
            .addModifiers(KModifier.PRIVATE, KModifier.SEALED)
            .addType(
                TypeSpec.classBuilder("Allowed")
                    .addModifiers(KModifier.DATA)
                    .primaryConstructor(
                        FunSpec.constructorBuilder()
                            .addParameter("entities", LIST.parameterizedBy(entityClass))
                            .build(),
                    )
                    .addProperty(
                        PropertySpec.builder("entities", LIST.parameterizedBy(entityClass))
                            .initializer("entities")
                            .build(),
                    )
                    .addSuperinterface(disclosureClass)
                    .build(),
            )
            .addType(
                TypeSpec.classBuilder("Denied")
                    .addModifiers(KModifier.DATA)
                    .primaryConstructor(
                        FunSpec.constructorBuilder()
                            .addParameter("denial", PRIVACY_DENIAL)
                            .build(),
                    )
                    .addProperty(
                        PropertySpec.builder("denial", PRIVACY_DENIAL)
                            .initializer("denial")
                            .build(),
                    )
                    .addSuperinterface(disclosureClass)
                    .build(),
            )
            .addType(
                TypeSpec.classBuilder("Failed")
                    .addModifiers(KModifier.DATA)
                    .primaryConstructor(
                        FunSpec.constructorBuilder()
                            .addParameter("exception", KOTLIN_EXCEPTION)
                            .build(),
                    )
                    .addProperty(
                        PropertySpec.builder("exception", KOTLIN_EXCEPTION)
                            .initializer("exception")
                            .build(),
                    )
                    .addSuperinterface(disclosureClass)
                    .build(),
            )
            .build()
    }

    /**
     * Execute every pre-completion create-many phase on an already
     * transaction-scoped repo. All hooks and rules are phase-major; the sole
     * persistence call is `insertMany`.
     */
    private fun buildExecuteCreateManyWritePhases(
        schemaName: String,
        entityClass: ClassName,
        createLambda: LambdaTypeName,
        candidateClass: ClassName,
    ): FunSpec {
        val repoClass = ClassName(packageName, "${schemaName}Repo")
        val completionClass = repoClass.nestedClass("CreateManyWriteCompletion")
        val preparedType = PREPARED_CREATE.parameterizedBy(candidateClass)
        val createClass = ClassName(packageName, "${schemaName}Create")
        return FunSpec.builder("_executeCreateManyWritePhases")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("blocks", LIST.parameterizedBy(createLambda))
            .addParameter("promoteDriverNotPersisted", BOOLEAN)
            .returns(MUTATION_RESULT.parameterizedBy(completionClass))
            .addCode(
                CodeBlock.builder()
                    .add("var writeState = %T.NotPersisted\n", MUTATION_WRITE_STATE)
                    .add("val managedSaveFailures = %T<%T>()\n", ArrayList::class, ENT_MUTATION_EXCEPTION)
                    .add("try {\n")
                    .add("  check(driver.inTransaction) { %S }\n", "createMany write phases require a transaction-scoped driver")
                    .add("  val builders = %T<%T>(blocks.size)\n", ArrayList::class, createClass)
                    .add("  for (block in blocks) {\n")
                    .add("    val builder = create { }\n")
                    .add("    val configured = try {\n")
                    .add(
                        "      builder.configureForCreateManyForInternalUse(block, managedSaveFailures)\n",
                    )
                    .add("    } catch (e: %T) {\n", MUTATION_CANCELLATION_EXCEPTION)
                    .add("      throw e\n")
                    .add("    } catch (e: %T) {\n", KOTLIN_EXCEPTION)
                    .add("      val managedFailure = managedSaveFailures.firstOrNull() ?: throw e\n")
                    .add("      if (e !== managedFailure && managedFailure.suppressed.none { it === e }) {\n")
                    .add("        managedFailure.addSuppressed(e)\n")
                    .add("      }\n")
                    .add("      client.recordTransactionMutationFailure(managedFailure)\n")
                    .add("      return %T.failedForInternalUse(managedFailure)\n", MUTATION_RESULT)
                    .add("    }\n")
                    .add("    val managedFailure = managedSaveFailures.firstOrNull()\n")
                    .add("    if (managedFailure != null) {\n")
                    .add("      client.recordTransactionMutationFailure(managedFailure)\n")
                    .add("      return %T.failedForInternalUse(managedFailure)\n", MUTATION_RESULT)
                    .add("    }\n")
                    .add("    when (configured) {\n")
                    .add("      is %T.Success -> builders.add(configured.value)\n", MUTATION_RESULT)
                    .add("      is %T.Failed -> return configured\n", MUTATION_RESULT)
                    .add("    }\n")
                    .add("  }\n")
                    .add(
                        "  %M(builders.map { it.beforeSaveHookValueForInternalUse() }, beforeSaveHooks)\n",
                        RUN_BATCH_HOOKS_FOR_INTERNAL_USE,
                    )
                    .add(createManyManagedSaveFailureCheck().indented())
                    .add(
                        "  %M(builders.map { it.beforeCreateHookValueForInternalUse() }, beforeCreateHooks)\n",
                        RUN_BATCH_HOOKS_FOR_INTERNAL_USE,
                    )
                    .add(createManyManagedSaveFailureCheck().indented())
                    .add("  val prepared = %T<%T>(builders.size)\n", ArrayList::class.asClassName(), preparedType)
                    .add("  for (builder in builders) {\n")
                    .add("    val result = builder.prepareForInternalUse()\n")
                    .add(createManyManagedSaveFailureCheck().indented().indented())
                    .add("    when (result) {\n")
                    .add("      is %T.Success -> prepared.add(result.value)\n", MUTATION_RESULT)
                    .add("      is %T.Failed -> return result\n", MUTATION_RESULT)
                    .add("    }\n")
                    .add("  }\n")
                    .add("  val privacy = client.currentPrivacyContext()\n")
                    .add(createManyManagedSaveFailureCheck().indented())
                    .add("  val candidates = prepared.map { it.candidate }\n")
                    .add("  val denialReasons = createDenialReasons(privacy, candidates)\n")
                    .add(createManyManagedSaveFailureCheck().indented())
                    .add("  val deniedIndex = denialReasons.indexOfFirst { it != null }\n")
                    .add("  if (deniedIndex >= 0) {\n")
                    .add(
                        privacyDeniedFailure(
                            writeStateExpr = CodeBlock.of("%T.NotPersisted", MUTATION_WRITE_STATE),
                            schemaName = schemaName,
                            operationName = "CREATE",
                            entityKeyExpr = CodeBlock.of("null"),
                            reasonExpr = "denialReasons[deniedIndex]!!",
                        ).indented(),
                    )
                    .add("  }\n")
                    .add("  val violationsByCandidate = evaluateCreateValidations(candidates)\n")
                    .add(createManyManagedSaveFailureCheck().indented())
                    .add("  val invalidIndex = violationsByCandidate.indexOfFirst { it.isNotEmpty() }\n")
                    .add("  if (invalidIndex >= 0) {\n")
                    .add(
                        recordAndReturnFailure(
                            CodeBlock.of(
                                "%T(%S, %T.CREATE, violationsByCandidate[invalidIndex])",
                                ENT_VALIDATION_EXCEPTION,
                                schemaName,
                                MUTATION_ENT_OPERATION,
                            ),
                        ).indented(),
                    )
                    .add("  }\n")
                    // A transaction-scoped DatabaseDriver.insertMany may use several
                    // physical statements. Once the call begins, an exception
                    // can follow an earlier staged chunk, so the batch is
                    // conservatively pending until its transaction resolves.
                    .add("  writeState = %T.TransactionPending\n", MUTATION_WRITE_STATE)
                    .add("  val rows = try {\n")
                    .add("    driver.insertMany(%T.TABLE, prepared.map { it.values })\n", entityClass)
                    .add("  } catch (e: %T) {\n", MUTATION_CANCELLATION_EXCEPTION)
                    .add("    throw e\n")
                    .add("  } catch (e: %T) {\n", KOTLIN_EXCEPTION)
                    .add(
                        "    val classified = _classifyCreateDriverFailure(e, %T.TransactionPending)\n",
                        MUTATION_WRITE_STATE,
                    )
                    .add("    val reported = if (promoteDriverNotPersisted && prepared.size > 1 && ")
                    .add("classified.writeState == %T.NotPersisted) {\n", MUTATION_WRITE_STATE)
                    .add(
                        "      %T(%T.TransactionPending, classified)\n",
                        ENT_UNEXPECTED_MUTATION_EXCEPTION,
                        MUTATION_WRITE_STATE,
                    )
                    .add("    } else classified\n")
                    .add("    client.recordTransactionMutationFailure(reported)\n")
                    .add("    return %T.failedForInternalUse(reported)\n", MUTATION_RESULT)
                    .add("  }\n")
                    .add("  check(rows.size == prepared.size) {\n")
                    .add(
                        "    %S + prepared.size + %S + rows.size",
                        "DatabaseDriver.insertMany contract violation for $schemaName: expected ",
                        " persisted rows but received ",
                    )
                    .add("\n  }\n")
                    .add("  val entities = rows.map { row -> %T.fromRow(row) }\n", entityClass)
                    .add("  %M(entities, afterCreateHooks)\n", RUN_BATCH_HOOKS_FOR_INTERNAL_USE)
                    .add(createManyManagedSaveFailureCheck().indented())
                    .add(
                        "  return %T.Success(%T(entities, privacy, managedSaveFailures))\n",
                        MUTATION_RESULT,
                        completionClass,
                    )
                    .add("} catch (e: %T) {\n", MUTATION_CANCELLATION_EXCEPTION)
                    .add("  throw e\n")
                    .add("} catch (e: %T) {\n", KOTLIN_EXCEPTION)
                    .add(createManyManagedSaveFailureCheck("e").indented())
                    .add(
                        recordAndReturnFailure(
                            CodeBlock.of("%T(writeState, e)", ENT_UNEXPECTED_MUTATION_EXCEPTION),
                        ).indented(),
                    )
                    .add("}\n")
                    .build(),
            )
            .build()
    }

    /**
     * `createMany(*blocks): MutationResult<List<T>>` — strict, atomic,
     * phase-major bulk create. Every builder and before-hook phase completes,
     * then CREATE privacy and validation evaluate the complete candidate list,
     * before one correlated `DatabaseDriver.insertMany` persists the batch. Every row
     * is hydrated before the single afterCreate phase begins.
     *
     * Returned LOAD disclosure uses the same privacy-context snapshot as
     * CREATE privacy. A caller-owned transaction maps disclosure failure to
     * `TransactionPending` and rollback-only. An EntKt-owned transaction
     * captures it as a neutral value, attempts commit, and reports `Committed`
     * only after commit is confirmed.
     */
    private fun buildCreateMany(
        schemaName: String,
        clientName: String,
        entityClass: ClassName,
        createLambda: LambdaTypeName,
    ): FunSpec {
        val repoPropName = clientName
        val repoClass = ClassName(packageName, "${schemaName}Repo")
        val disclosureClass = repoClass.nestedClass("CreateManyDisclosure")
        val resultType = MUTATION_RESULT.parameterizedBy(LIST.parameterizedBy(entityClass))
        return FunSpec.builder("createMany")
            .addParameter(
                ParameterSpec.builder("blocks", createLambda)
                    .addModifiers(KModifier.VARARG)
                    .build(),
            )
            .returns(resultType)
            .addKdoc(
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
            .addCode(
                CodeBlock.builder()
                    .add("var writeState = %T.NotPersisted\n", MUTATION_WRITE_STATE)
                    .add("try {\n")
                    .add(
                        "  client.checkTransactionRequirement(%S, multiWrite = blocks.size > 1)\n",
                        "$schemaName createMany",
                    )
                    .add("  if (blocks.isEmpty()) return %T.Success(emptyList())\n", MUTATION_RESULT)
                    .add("  val blockSnapshot = blocks.toList()\n")
                    .add("  if (driver.inTransaction) {\n")
                    .add(
                        "    val completion = when (val result = " +
                            "_executeCreateManyWritePhases(blockSnapshot, promoteDriverNotPersisted = true)) {\n",
                    )
                    .add("      is %T.Success -> result.value\n", MUTATION_RESULT)
                    .add("      is %T.Failed -> return result\n", MUTATION_RESULT)
                    .add("    }\n")
                    .add("    writeState = %T.TransactionPending\n", MUTATION_WRITE_STATE)
                    .add("    val denial = try {\n")
                    .add(
                        "      loadDenials(completion.privacy, completion.entities)" +
                            ".filterNotNull().firstOrNull()\n",
                    )
                    .add("    } catch (e: %T) {\n", MUTATION_CANCELLATION_EXCEPTION)
                    .add("      throw e\n")
                    .add("    } catch (e: %T) {\n", KOTLIN_EXCEPTION)
                    .add("      val managedFailure = completion.managedSaveFailures.firstOrNull() ?: throw e\n")
                    .add("      if (e !== managedFailure && managedFailure.suppressed.none { it === e }) {\n")
                    .add("        managedFailure.addSuppressed(e)\n")
                    .add("      }\n")
                    .add(
                        "      val reported = %T(%T.TransactionPending, managedFailure)\n",
                        ENT_UNEXPECTED_MUTATION_EXCEPTION,
                        MUTATION_WRITE_STATE,
                    )
                    .add("      client.replaceTransactionMutationFailure(managedFailure, reported)\n")
                    .add("      return %T.failedForInternalUse(reported)\n", MUTATION_RESULT)
                    .add("    }\n")
                    .add("    completion.managedSaveFailures.firstOrNull()?.let { managedFailure ->\n")
                    .add(
                        "      val reported = %T(%T.TransactionPending, managedFailure)\n",
                        ENT_UNEXPECTED_MUTATION_EXCEPTION,
                        MUTATION_WRITE_STATE,
                    )
                    .add("      client.replaceTransactionMutationFailure(managedFailure, reported)\n")
                    .add("      return %T.failedForInternalUse(reported)\n", MUTATION_RESULT)
                    .add("    }\n")
                    .add("    if (denial != null) {\n")
                    .add(
                        privacyDeniedFailure(
                            writeStateExpr = CodeBlock.of("%T.TransactionPending", MUTATION_WRITE_STATE),
                            schemaName = schemaName,
                            operationName = "LOAD",
                            entityKeyExpr = CodeBlock.of("denial.entityKey"),
                            reasonExpr = "denial.reason",
                        ).indented(),
                    )
                    .add("    }\n")
                    .add("    return %T.Success(completion.entities)\n", MUTATION_RESULT)
                    .add("  }\n")
                    .add("  var disclosureFailure: %T? = null\n", KOTLIN_EXCEPTION)
                    .add("  var disclosureDenial: %T? = null\n", PRIVACY_DENIAL)
                    .add("  val txResult = client.withTransaction { tx ->\n")
                    .add(
                        "    val completion = tx.%L._executeCreateManyWritePhases(" +
                            "blockSnapshot, promoteDriverNotPersisted = false).orRollback()\n",
                        repoPropName,
                    )
                    .add("    try {\n")
                    .add(
                        "      val denial = tx.%L.loadDenials(completion.privacy, completion.entities)" +
                            ".filterNotNull().firstOrNull()\n",
                        repoPropName,
                    )
                    .add("      completion.managedSaveFailures.firstOrNull()?.let { throw it }\n")
                    .add("      if (denial == null) {\n")
                    .add("        %T.Allowed(completion.entities)\n", disclosureClass)
                    .add("      } else {\n")
                    .add("        disclosureDenial = denial\n")
                    .add("        %T.Denied(denial)\n", disclosureClass)
                    .add("      }\n")
                    .add("    } catch (e: %T) {\n", MUTATION_CANCELLATION_EXCEPTION)
                    .add("      throw e\n")
                    .add("    } catch (e: %T) {\n", KOTLIN_EXCEPTION)
                    .add("      val managedFailure = completion.managedSaveFailures.firstOrNull()\n")
                    .add("      if (managedFailure != null) {\n")
                    .add("        if (e !== managedFailure && managedFailure.suppressed.none { it === e }) {\n")
                    .add("          managedFailure.addSuppressed(e)\n")
                    .add("        }\n")
                    .add("        throw managedFailure\n")
                    .add("      }\n")
                    .add("      disclosureFailure = e\n")
                    .add("      %T.Failed(e)\n", disclosureClass)
                    .add("    }\n")
                    .add("  }\n")
                    .add("  return when (txResult) {\n")
                    .add("    is %T.Success -> when (val disclosure = txResult.value) {\n", TRANSACTION_RESULT)
                    .add("      is %T.Allowed -> %T.Success(disclosure.entities)\n", disclosureClass, MUTATION_RESULT)
                    .add("      is %T.Denied -> {\n", disclosureClass)
                    .add(
                        "        val exception = %T(%T.Committed, %S, %T.LOAD, " +
                            "disclosure.denial.entityKey, disclosure.denial.reason)\n",
                        ENT_MUTATION_PRIVACY_DENIED_EXCEPTION,
                        MUTATION_WRITE_STATE,
                        schemaName,
                        MUTATION_ENT_OPERATION,
                    )
                    .add("        client.recordTransactionMutationFailure(exception)\n")
                    .add("        %T.failedForInternalUse(exception)\n", MUTATION_RESULT)
                    .add("      }\n")
                    .add("      is %T.Failed -> {\n", disclosureClass)
                    .add(
                        "        val exception = %T(%T.Committed, disclosure.exception)\n",
                        ENT_UNEXPECTED_MUTATION_EXCEPTION,
                        MUTATION_WRITE_STATE,
                    )
                    .add("        client.recordTransactionMutationFailure(exception)\n")
                    .add("        %T.failedForInternalUse(exception)\n", MUTATION_RESULT)
                    .add("      }\n")
                    .add("    }\n")
                    .add("    is %T.Failed -> {\n", TRANSACTION_RESULT)
                    .add(createManyTxFailureConversion(schemaName).indented().indented().indented())
                    .add("    }\n")
                    .add("  }\n")
                    .add("} catch (e: %T) {\n", MUTATION_CANCELLATION_EXCEPTION)
                    .add("  throw e\n")
                    .add("} catch (e: %T) {\n", KOTLIN_EXCEPTION)
                    .add(
                        recordAndReturnFailure(
                            CodeBlock.of("%T(writeState, e)", ENT_UNEXPECTED_MUTATION_EXCEPTION),
                        ).indented(),
                    )
                    .add("}\n")
                    .build(),
            )
            .build()
    }



    private fun buildApplyHooks(entityHooksClass: ClassName): FunSpec =
        FunSpec.builder("applyHooks")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("hooks", entityHooksClass)
            .addStatement("beforeSaveHooks.addAll(hooks.beforeSaveHooks)")
            .addStatement("beforeCreateHooks.addAll(hooks.beforeCreateHooks)")
            .addStatement("afterCreateHooks.addAll(hooks.afterCreateHooks)")
            .addStatement("beforeUpdateHooks.addAll(hooks.beforeUpdateHooks)")
            .addStatement("afterUpdateHooks.addAll(hooks.afterUpdateHooks)")
            .addStatement("beforeDeleteHooks.addAll(hooks.beforeDeleteHooks)")
            .addStatement("afterDeleteHooks.addAll(hooks.afterDeleteHooks)")
            .build()

    private fun buildCopyHooksFrom(repoClass: ClassName): FunSpec =
        FunSpec.builder("copyHooksFrom")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("other", repoClass)
            .addStatement("beforeSaveHooks.addAll(other.beforeSaveHooks)")
            .addStatement("beforeCreateHooks.addAll(other.beforeCreateHooks)")
            .addStatement("afterCreateHooks.addAll(other.afterCreateHooks)")
            .addStatement("beforeUpdateHooks.addAll(other.beforeUpdateHooks)")
            .addStatement("afterUpdateHooks.addAll(other.afterUpdateHooks)")
            .addStatement("beforeDeleteHooks.addAll(other.beforeDeleteHooks)")
            .addStatement("afterDeleteHooks.addAll(other.afterDeleteHooks)")
            .build()

    private fun hookListProperty(name: String, type: com.squareup.kotlinpoet.TypeName): PropertySpec =
        PropertySpec.builder(name, type)
            .addModifiers(KModifier.PRIVATE)
            .initializer("mutableListOf()")
            .build()

    private fun buildApplyValidation(validationConfigClass: ClassName): FunSpec =
        FunSpec.builder("applyValidation")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("config", validationConfigClass)
            .addStatement("validationConfig.createRules.addAll(config.createRules)")
            .addStatement("validationConfig.updateRules.addAll(config.updateRules)")
            .addStatement("validationConfig.deleteRules.addAll(config.deleteRules)")
            .addStatement("if (config.updateDerivesFromCreate) validationConfig.updateDerivesFromCreate = true")
            .build()

    private fun buildCopyValidationFrom(repoClass: ClassName): FunSpec =
        FunSpec.builder("copyValidationFrom")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("other", repoClass)
            .addStatement("validationConfig.createRules.addAll(other.validationConfig.createRules)")
            .addStatement("validationConfig.updateRules.addAll(other.validationConfig.updateRules)")
            .addStatement("validationConfig.deleteRules.addAll(other.validationConfig.deleteRules)")
            .addStatement("validationConfig.updateDerivesFromCreate = other.validationConfig.updateDerivesFromCreate")
            .build()

    // ── Write-side validation evaluators ──────────────────────────
    // DECISION-RETURNING (List<ValidationViolation>; empty = valid),
    // so the mutation terminals construct the typed
    // EntValidationException at their own classification point. A
    // rule-RETURNED Invalid is mapped through toValidationViolation();
    // a rule-THROWN exception escapes (no catch here) to the
    // terminal's capture boundary as a foreign failure — even when the
    // rule threw an EntValidationException itself.

    private fun buildEvaluateCreateValidations(
        schemaName: String,
        candidateClass: ClassName,
        fields: List<Field>,
    ): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        val createItemClass = ClassName(packageName, "${schemaName}CreateValidationItem")
        val violationList = LIST.parameterizedBy(MUTATION_VALIDATION_VIOLATION)
        return FunSpec.builder("evaluateCreateValidations")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("candidates", LIST.parameterizedBy(candidateClass))
            .returns(LIST.parameterizedBy(violationList))
            .addCode(CodeBlock.builder()
                .addStatement("if (candidates.isEmpty()) return emptyList()")
                .addStatement("val candidateSnapshot = candidates.toList()")
                .addStatement("val rules = validationConfig.createRules")
                .addStatement("if (rules.isEmpty()) return %T(candidateSnapshot.size) { emptyList() }", LIST)
                .addStatement("val validationClient = client.asValidationReadClientForInternalUse()")
                .addStatement("val ruleContext = %T(validationClient)", VALIDATION_RULE_CONTEXT)
                .addStatement(
                    "val invalidsByCandidate = %M(%S, candidateSnapshot, rules, ruleContext) { item ->\n" +
                        "  %T(%L)\n" +
                        "}",
                    EVALUATE_BATCH_VALIDATION_RULES,
                    "$schemaName CREATE validation",
                    createItemClass,
                    lifecycleValueSnapshot("item", fields, entityClass),
                )
                .addStatement(
                    "return invalidsByCandidate.map { invalids -> invalids.map { it.%M() } }",
                    TO_VALIDATION_VIOLATION,
                )
                .build()
            )
            .build()
    }

    private fun buildEvaluateCreateValidation(candidateClass: ClassName): FunSpec =
        FunSpec.builder("evaluateCreateValidation")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("candidate", candidateClass)
            .returns(LIST.parameterizedBy(MUTATION_VALIDATION_VIOLATION))
            .addStatement("return evaluateCreateValidations(listOf(candidate)).single()")
            .build()

    private fun buildEvaluateUpdateValidation(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
        fields: List<Field>,
        helperEligibleEdges: List<HelperEligibleM2M>,
    ): FunSpec {
        val updateItemClass = ClassName(packageName, "${schemaName}UpdateValidationItem")
        val createItemClass = ClassName(packageName, "${schemaName}CreateValidationItem")
        val patchClass = ClassName(packageName, "${schemaName}UpdatePatch")
        val edgeChangesViewClass = ClassName(packageName, "${schemaName}EdgeChangesView")
        return FunSpec.builder("evaluateUpdateValidation")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("before", entityClass)
            .addParameter("requestedPatch", patchClass)
            .addParameter("effectivePatch", patchClass)
            .addParameter("candidate", candidateClass)
            .addParameter("edgeChanges", edgeChangesViewClass)
            .returns(LIST.parameterizedBy(MUTATION_VALIDATION_VIOLATION))
            .addCode(CodeBlock.builder()
                .addStatement("val rules = validationConfig.updateRules")
                .addStatement("if (rules.isEmpty() && !validationConfig.updateDerivesFromCreate) return emptyList()")
                .addStatement("val validationClient = client.asValidationReadClientForInternalUse()")
                .addStatement("val ruleContext = %T(validationClient)", VALIDATION_RULE_CONTEXT)
                .addStatement(
                    "val invalids = %M(%S, listOf(candidate), rules, ruleContext) { item ->\n" +
                    "  %T(%L, %L, %L, %L, %L)\n" +
                        "}.single().toMutableList()",
                    EVALUATE_BATCH_VALIDATION_RULES,
                    "$schemaName UPDATE validation",
                    updateItemClass,
                    lifecycleValueSnapshot("before", fields, entityClass),
                    lifecyclePatchSnapshot("requestedPatch", fields, entityClass),
                    lifecyclePatchSnapshot("effectivePatch", fields, entityClass),
                    lifecycleValueSnapshot("item", fields, entityClass),
                    edgeChangesSnapshot("edgeChanges", helperEligibleEdges),
                )
                .beginControlFlow("if (validationConfig.updateDerivesFromCreate)")
                .addStatement(
                    "invalids += %M(%S, listOf(candidate), validationConfig.createRules, ruleContext) { item ->\n" +
                        "  %T(%L)\n" +
                        "}.single()",
                    EVALUATE_BATCH_VALIDATION_RULES,
                    "$schemaName UPDATE validation",
                    createItemClass,
                    lifecycleValueSnapshot("item", fields, entityClass),
                )
                .endControlFlow()
                .addStatement("return invalids.map { it.%M() }", TO_VALIDATION_VIOLATION)
                .build()
            )
            .build()
    }

    private fun buildEvaluateDeleteValidations(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
        fields: List<Field>,
    ): FunSpec {
        val deleteItemClass = ClassName(packageName, "${schemaName}DeleteValidationItem")
        val violationList = LIST.parameterizedBy(MUTATION_VALIDATION_VIOLATION)
        return FunSpec.builder("evaluateDeleteValidations")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("entities", LIST.parameterizedBy(entityClass))
            .addParameter("candidates", LIST.parameterizedBy(candidateClass))
            .returns(LIST.parameterizedBy(violationList))
            .addCode(CodeBlock.builder()
                .addStatement("require(entities.size == candidates.size) { %S }", "DELETE entity/candidate count mismatch")
                .addStatement("if (entities.isEmpty()) return emptyList()")
                .addStatement("val entitySnapshot = entities.toList()")
                .addStatement("val candidateSnapshot = candidates.toList()")
                .addStatement("val rules = validationConfig.deleteRules")
                .addStatement("if (rules.isEmpty()) return %T(entitySnapshot.size) { emptyList() }", LIST)
                .addStatement("val validationClient = client.asValidationReadClientForInternalUse()")
                .addStatement("val ruleContext = %T(validationClient)", VALIDATION_RULE_CONTEXT)
                .addStatement(
                    "val invalidsByCandidate = %M(%S, entitySnapshot.indices.toList(), rules, ruleContext) { index ->\n" +
                        "  %T(%L, %L)\n" +
                        "}",
                    EVALUATE_BATCH_VALIDATION_RULES,
                    "$schemaName DELETE validation",
                    deleteItemClass,
                    lifecycleValueSnapshot("entitySnapshot[index]", fields, entityClass),
                    lifecycleValueSnapshot("candidateSnapshot[index]", fields, entityClass),
                )
                .addStatement(
                    "return invalidsByCandidate.map { invalids -> invalids.map { it.%M() } }",
                    TO_VALIDATION_VIOLATION,
                )
                .build()
            )
            .build()
    }

    private fun buildEvaluateDeleteValidation(
        entityClass: ClassName,
        candidateClass: ClassName,
    ): FunSpec = FunSpec.builder("evaluateDeleteValidation")
        .addModifiers(KModifier.INTERNAL)
        .addParameter("entity", entityClass)
        .addParameter("candidate", candidateClass)
        .returns(LIST.parameterizedBy(MUTATION_VALIDATION_VIOLATION))
        .addStatement("return evaluateDeleteValidations(listOf(entity), listOf(candidate)).single()")
        .build()
}
