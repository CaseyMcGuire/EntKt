package entkt.codegen.client

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
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.idStrategyName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.toTypeName
import entkt.codegen.mutation.MUTATION_CANCELLATION_EXCEPTION
import entkt.codegen.mutation.MUTATION_ENTITY_KEY
import entkt.codegen.mutation.MUTATION_ENT_OPERATION
import entkt.codegen.mutation.MUTATION_RESULT
import entkt.codegen.mutation.MUTATION_VALIDATION_VIOLATION
import entkt.codegen.mutation.MUTATION_WRITE_STATE
import entkt.codegen.mutation.ENT_MUTATION_EXCEPTION
import entkt.codegen.mutation.ENT_UNEXPECTED_MUTATION_EXCEPTION
import entkt.codegen.mutation.ENT_VALIDATION_EXCEPTION
import entkt.codegen.mutation.KOTLIN_EXCEPTION
import entkt.codegen.mutation.buildClassifyDriverFailureHelper
import entkt.codegen.mutation.driverCallFailureTail
import entkt.codegen.mutation.indented
import entkt.codegen.mutation.privacyDeniedFailure
import entkt.codegen.mutation.recordAndReturnFailure
import entkt.codegen.pluralize
import entkt.codegen.query.indexHelperTree
import entkt.codegen.toCamelCase
import entkt.schema.EntSchema

private val DRIVER = ClassName("entkt.runtime.driver", "Driver")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val LIST = ClassName("kotlin.collections", "List")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
private val INT = Int::class.asClassName()
private val UPDATE_CONSISTENCY = ClassName("entkt.runtime.mutation", "UpdateConsistency")
private val RELATIONSHIP_LOCKING = ClassName("entkt.runtime.mutation", "RelationshipLocking")
private val ENT_CLIENT_NAME = "EntClient"
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val PRIVACY_DENIAL = ClassName("entkt.runtime.result", "PrivacyDenial")
private val ENTITY_KEY = ClassName("entkt.runtime.result", "EntityKey")
private val PRIVACY_DECISION = ClassName("entkt.runtime.privacy", "PrivacyDecision")
private val VIEWER = ClassName("entkt.runtime.privacy", "Viewer")
private val VALIDATION_DECISION = ClassName("entkt.runtime.validation", "ValidationDecision")
private val TO_VALIDATION_VIOLATION = MemberName("entkt.runtime.result", "toValidationViolation")
private val TRANSACTION_RESULT = ClassName("entkt.runtime.result", "TransactionResult")
private val TRANSACTION_FAILURE_STATE = ClassName("entkt.runtime.result", "TransactionFailureState")
private val READ_OPERATION = ClassName("entkt.runtime.query", "ReadOperation")
private val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")

/**
 * Emits a per-schema repository class. The repo is the only entry point
 * for I/O — it owns the [Driver] and exposes `query`, `create`,
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
        val loadCtxClass = ClassName(packageName, "${schemaName}LoadPrivacyContext")
        val deleteCtxClass = ClassName(packageName, "${schemaName}DeletePrivacyContext")
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)
        val idType = schema.id().type.toTypeName()

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
        val beforeSaveHookLambda = LambdaTypeName.get(parameters = arrayOf(mutationClass), returnType = UNIT)
        // beforeCreate hooks receive the restricted CreateHookContext
        // (view + client), not the concrete Create builder.
        val beforeCreateHookLambda = LambdaTypeName.get(parameters = arrayOf(createHookCtxClass), returnType = UNIT)
        val afterCreateHookLambda = LambdaTypeName.get(parameters = arrayOf(entityClass), returnType = UNIT)
        val beforeUpdateHookLambda = LambdaTypeName.get(parameters = arrayOf(updateHookCtxClass), returnType = UNIT)
        val afterUpdateHookLambda = LambdaTypeName.get(parameters = arrayOf(entityClass), returnType = UNIT)
        val beforeDeleteHookLambda = LambdaTypeName.get(parameters = arrayOf(entityClass), returnType = UNIT)
        val afterDeleteHookLambda = LambdaTypeName.get(parameters = arrayOf(entityClass), returnType = UNIT)

        fun mutableHookList(lambdaType: LambdaTypeName) =
            MUTABLE_LIST.parameterizedBy(lambdaType)

        val typeSpec = TypeSpec.classBuilder(className)
            // The repo is the entity's read surface: query terminals reach
            // `hasLoadPrivacy()` / `evaluateLoadPrivacy(...)` through the
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
            .addProperty(hookListProperty("beforeSaveHooks", mutableHookList(beforeSaveHookLambda)))
            .addProperty(hookListProperty("beforeCreateHooks", mutableHookList(beforeCreateHookLambda)))
            .addProperty(hookListProperty("afterCreateHooks", mutableHookList(afterCreateHookLambda)))
            .addProperty(hookListProperty("beforeUpdateHooks", mutableHookList(beforeUpdateHookLambda)))
            .addProperty(hookListProperty("afterUpdateHooks", mutableHookList(afterUpdateHookLambda)))
            .addProperty(hookListProperty("beforeDeleteHooks", mutableHookList(beforeDeleteHookLambda)))
            .addProperty(hookListProperty("afterDeleteHooks", mutableHookList(afterDeleteHookLambda)))
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
            .addFunction(buildFindByIdExplainMethod(schemaName, entityClass, idType, clientRef = "client"))
            .addFunction(buildDelete(schemaName, entityClass))
            .addFunction(buildDeleteLoaded(schemaName, entityClass))
            .addFunction(buildDeleteById(schemaName, entityClass, idType))
            .also { builder ->
                if (idStrategyName(schema) != "EXPLICIT") {
                    builder.addFunction(buildCreateMany(schemaName, entityClass, createLambda, idType))
                }
            }
            .addFunction(buildDeleteMany(schemaName, entityClass))
            .addFunction(buildClassifyDriverFailureHelper(schemaName, "DELETE"))
            .addFunction(buildApplyHooks(entityHooksClass))
            .addFunction(buildCopyHooksFrom(repoClass))
            .addFunction(buildApplyPrivacy(privacyConfigClass))
            .addFunction(buildCopyPrivacyFrom(repoClass))
            .addFunction(buildHasPrivacy("hasLoadPrivacy", readSurfaceOverride = true))
            .addFunction(buildHasPrivacy("hasCreatePrivacy"))
            .addFunction(buildHasPrivacy("hasUpdatePrivacy"))
            .addFunction(buildHasPrivacy("hasDeletePrivacy"))
            .addFunction(buildLoadDenialOrNull(schemaName, entityClass, loadCtxClass))
            .addFunction(buildCreateDenialReasonOrNull(schemaName, candidateClass))
            .addFunction(buildUpdateDenialReasonOrNull(schemaName, entityClass, candidateClass))
            .addFunction(buildDeleteDenialReasonOrNull(schemaName, entityClass, candidateClass))
            .addFunction(buildBuildDeleteCandidate(schemaName, schema, entityClass, candidateClass, schemaNames))
            .addFunction(buildApplyValidation(validationConfigClass))
            .addFunction(buildCopyValidationFrom(repoClass))
            .addFunction(buildEvaluateCreateValidation(schemaName, candidateClass))
            .addFunction(buildEvaluateUpdateValidation(schemaName, entityClass, candidateClass))
            .addFunction(buildEvaluateDeleteValidation(schemaName, entityClass, candidateClass))
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
                    .add(driverCallFailureTail("NotPersisted").indented())
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
     * entity freshly loaded from the database — every caller reloads
     * ([buildDelete] and [buildDeleteById] via `byId`, deleteMany
     * via its candidate query), so privacy, validation, and hooks
     * always see current row state rather than a caller-controlled
     * copy. Extracting this avoids a double preflight and keeps ONE
     * classified delete pipeline for all three terminals.
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
                    .add("  for (hook in beforeDeleteHooks) hook(entity)\n")
                    .add("  val deleted = try {\n")
                    .add("    driver.delete(%T.TABLE, entity.id)\n", entityClass)
                    .add(driverCallFailureTail("PersistenceUnknown").indented())
                    .add("  if (deleted) {\n")
                    .add(
                        "    writeState = postWriteState\n",
                    )
                    .add("    for (hook in afterDeleteHooks) hook(entity)\n")
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
                    .add(driverCallFailureTail("NotPersisted").indented())
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

    /**
     * `deleteMany(vararg predicates): MutationResult<Int>` — strict,
     * ATOMIC bulk delete. Candidate selection flows through the
     * read-interceptor chain (operation = DELETE_CANDIDATES) so
     * predicate-shaping interceptors (tenant scoping, framework
     * soft-delete, etc.) apply uniformly to bulk deletes — a bulk
     * delete can NOT escape read-side scoping that would have hidden
     * the same rows on the read path. Per-entity DELETE privacy /
     * validation / hooks run inside `deleteLoaded` for each candidate,
     * classified positionally like the single-row pipeline; a denied
     * or invalid candidate is never silently skipped.
     *
     * Atomicity: inside a caller-owned transaction the selection and
     * per-row deletes run in place — the first per-row `Failed` is
     * recorded on the coordinator (rollback-only) and returned, so the
     * rows already staged cannot commit. Outside a transaction the
     * terminal self-delegates through the canonical
     * `client.withTransaction` boundary so atomicity and rollback are
     * owned by the tested runtime path, then converts the
     * `TransactionResult` (a confirmed rollback with a recorded
     * mutation exception is returned as-is; anything else wraps into
     * `EntUnexpectedMutationException` with NotPersisted after a
     * confirmed rollback or PersistenceUnknown for an unknown
     * outcome). Interceptor rejection of the candidate query is
     * `Failed(EntUnexpectedMutationException(NotPersisted,
     * EntQueryRejectedException))`.
     */
    private fun buildDeleteMany(
        schemaName: String,
        entityClass: ClassName,
    ): FunSpec {
        val queryClass = ClassName(entityClass.packageName, "${schemaName}Query")
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
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
                    "a privacy, validation, hook, or driver failure on any candidate\n" +
                    "leaves no committed subset after a confirmed rollback — no\n" +
                    "denied or invalid candidate is silently skipped. `Failed` carries\n" +
                    "a typed [entkt.runtime.result.EntMutationException] whose\n" +
                    "`writeState` records the database effect; inside a caller-owned\n" +
                    "transaction `Failed` marks the scope rollback-only rather than\n" +
                    "implying an immediate rollback. There is no `orNull()`\n" +
                    "projection — project with `getOrThrow()` or match on the result.",
            )
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    // Transaction-requirement preflight — classifies by
                    // operation shape (multiWrite) against the CALLER's
                    // transaction posture before any selection work; its
                    // throw becomes Failed(EntUnexpectedMutationException(
                    // NotPersisted, TransactionRequiredException)).
                    .add(
                        "  client.checkTransactionRequirement(%S, multiWrite = true)\n",
                        "$schemaName deleteMany",
                    )
                    .add("  if (driver.inTransaction) {\n")
                    // Caller-owned transaction: run selection + per-row
                    // deletes in place. Seed predicates through the public
                    // `where()` DSL rather than writing the backing list
                    // directly — `predicates` on the generated query class
                    // is `private`, and the public DSL is functionally
                    // equivalent. The chain's rejection throws
                    // EntQueryRejectedException, captured by the terminal
                    // boundary below.
                    .add(
                        "    val q = %T(driver, client).apply { for (p in predicates) where(p) }\n",
                        queryClass,
                    )
                    .add(
                        "    val spec = q.runReadInterceptors(%T.DELETE_CANDIDATES)\n",
                        READ_OPERATION,
                    )
                    .add(
                        "    val rows = driver.query(%T.TABLE, spec.predicates, emptyList(), null, null)\n",
                        entityClass,
                    )
                    .add("    val entities = rows.map { %T.fromRow(it) }\n", entityClass)
                    .add("    var count = 0\n")
                    .add("    for (entity in entities) {\n")
                    .add("      when (val result = deleteLoaded(entity)) {\n")
                    .add("        is %T.Success -> if (result.value) count++\n", MUTATION_RESULT)
                    // First per-row failure: already recorded on the
                    // coordinator inside deleteLoaded (rollback-only), so
                    // the rows staged so far roll back at the boundary.
                    // When earlier rows already deleted, a NotPersisted
                    // row failure would misdescribe the BATCH — staged
                    // effects exist in the open transaction — so the bulk
                    // result reports TransactionPending with the row
                    // failure as its cause.
                    .add("        is %T.Failed -> {\n", MUTATION_RESULT)
                    .add("          val rowException = result.exception\n")
                    .add(
                        "          return if (count > 0 && rowException.writeState == %T.NotPersisted) {\n",
                        MUTATION_WRITE_STATE,
                    )
                    .add(
                        "            val staged = %T(%T.TransactionPending, rowException)\n",
                        ENT_UNEXPECTED_MUTATION_EXCEPTION, MUTATION_WRITE_STATE,
                    )
                    .add("            client.replaceTransactionMutationFailure(rowException, staged)\n")
                    .add("            %T.failedForInternalUse(staged)\n", MUTATION_RESULT)
                    .add("          } else result\n")
                    .add("        }\n")
                    .add("      }\n")
                    .add("    }\n")
                    .add("    %T.Success(count)\n", MUTATION_RESULT)
                    .add("  } else {\n")
                    // EntKt-owned: self-delegate through the canonical
                    // transaction boundary so atomicity + rollback are
                    // owned by the tested runtime path (the nested call
                    // takes the caller-owned branch above).
                    .add("    val txResult = client.withTransaction { tx ->\n")
                    .add("      tx.%L.deleteMany(*predicates).orRollback()\n", repoPropName)
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

    private fun buildLoadDenialOrNull(
        schemaName: String,
        entityClass: ClassName,
        loadCtxClass: ClassName,
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
        return FunSpec.builder("loadDenialOrNull")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("entity", entityClass)
            .returns(PRIVACY_DENIAL.copy(nullable = true))
            .addCode(CodeBlock.builder()
                .addStatement("if (privacy.viewer is %T.PrivacyBypass) return null", VIEWER)
                .addStatement("val rules = privacyConfig.loadRules")
                .addStatement("val privacyClient = client.asPrivacyReadClientForInternalUse(privacy)")
                .addStatement("val ctx = %T(privacy, privacyClient, entity)", loadCtxClass)
                .beginControlFlow("for (rule in rules)")
                .beginControlFlow("when (val decision = rule.run(ctx))")
                .addStatement("is %T.Allow -> return null", PRIVACY_DECISION)
                .addStatement(
                    "is %T.Deny -> return %T(%S, %T(%S, entity.id), decision.reason)",
                    PRIVACY_DECISION, PRIVACY_DENIAL, schemaName, ENTITY_KEY, "id",
                )
                .addStatement("is %T.Continue -> { }", PRIVACY_DECISION)
                .endControlFlow()
                .endControlFlow()
                // Fail-closed: no rule allowed → denied.
                .addStatement(
                    "return %T(%S, %T(%S, entity.id), %S)",
                    PRIVACY_DENIAL, schemaName, ENTITY_KEY, "id", "no load rule allowed access",
                )
                .build()
            )
            .build()
    }

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

    private fun buildCreateDenialReasonOrNull(
        schemaName: String,
        candidateClass: ClassName,
    ): FunSpec {
        val createCtxClass = ClassName(packageName, "${schemaName}CreatePrivacyContext")
        return FunSpec.builder("createDenialReasonOrNull")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("candidate", candidateClass)
            .returns(String::class.asClassName().copy(nullable = true))
            .addCode(CodeBlock.builder()
                .addStatement("if (privacy.viewer is %T.PrivacyBypass) return null", VIEWER)
                .addStatement("val rules = privacyConfig.createRules")
                .addStatement("val privacyClient = client.asPrivacyReadClientForInternalUse(privacy)")
                .addStatement("val ctx = %T(privacy, privacyClient, candidate)", createCtxClass)
                .beginControlFlow("for (rule in rules)")
                .beginControlFlow("when (val decision = rule.run(ctx))")
                .addStatement("is %T.Allow -> return null", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> return decision.reason", PRIVACY_DECISION)
                .addStatement("is %T.Continue -> { }", PRIVACY_DECISION)
                .endControlFlow()
                .endControlFlow()
                // Fail-closed: no rule allowed → denied.
                .addStatement("return %S", "no create rule allowed access")
                .build()
            )
            .build()
    }

    private fun buildUpdateDenialReasonOrNull(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
    ): FunSpec {
        val updateCtxClass = ClassName(packageName, "${schemaName}UpdatePrivacyContext")
        val createCtxClass = ClassName(packageName, "${schemaName}CreatePrivacyContext")
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
                .addStatement(
                    "val ctx = %T(privacy, privacyClient, before, requestedPatch, effectivePatch, candidate, edgeChanges)",
                    updateCtxClass,
                )
                .beginControlFlow("for (rule in rules)")
                .beginControlFlow("when (val decision = rule.run(ctx))")
                .addStatement("is %T.Allow -> return null", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> return decision.reason", PRIVACY_DECISION)
                .addStatement("is %T.Continue -> { }", PRIVACY_DECISION)
                .endControlFlow()
                .endControlFlow()
                .beginControlFlow("if (privacyConfig.updateDerivesFromCreate)")
                .addStatement("val createCtx = %T(privacy, privacyClient, candidate)", createCtxClass)
                .beginControlFlow("for (rule in privacyConfig.createRules)")
                .beginControlFlow("when (val decision = rule.run(createCtx))")
                .addStatement("is %T.Allow -> return null", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> return decision.reason", PRIVACY_DECISION)
                .addStatement("is %T.Continue -> { }", PRIVACY_DECISION)
                .endControlFlow()
                .endControlFlow()
                .endControlFlow()
                // Fail-closed: no rule (incl. derived create rules) allowed → denied.
                .addStatement("return %S", "no update rule allowed access")
                .build()
            )
            .build()
    }

    private fun buildDeleteDenialReasonOrNull(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
    ): FunSpec {
        val deleteCtxClass = ClassName(packageName, "${schemaName}DeletePrivacyContext")
        val createCtxClass = ClassName(packageName, "${schemaName}CreatePrivacyContext")
        return FunSpec.builder("deleteDenialReasonOrNull")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("entity", entityClass)
            .addParameter("candidate", candidateClass)
            .returns(String::class.asClassName().copy(nullable = true))
            .addCode(CodeBlock.builder()
                .addStatement("if (privacy.viewer is %T.PrivacyBypass) return null", VIEWER)
                .addStatement("val rules = privacyConfig.deleteRules")
                .addStatement("val privacyClient = client.asPrivacyReadClientForInternalUse(privacy)")
                .addStatement("val ctx = %T(privacy, privacyClient, entity, candidate)", deleteCtxClass)
                .beginControlFlow("for (rule in rules)")
                .beginControlFlow("when (val decision = rule.run(ctx))")
                .addStatement("is %T.Allow -> return null", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> return decision.reason", PRIVACY_DECISION)
                .addStatement("is %T.Continue -> { }", PRIVACY_DECISION)
                .endControlFlow()
                .endControlFlow()
                .beginControlFlow("if (privacyConfig.deleteDerivesFromCreate)")
                .addStatement("val createCtx = %T(privacy, privacyClient, candidate)", createCtxClass)
                .beginControlFlow("for (rule in privacyConfig.createRules)")
                .beginControlFlow("when (val decision = rule.run(createCtx))")
                .addStatement("is %T.Allow -> return null", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> return decision.reason", PRIVACY_DECISION)
                .addStatement("is %T.Continue -> { }", PRIVACY_DECISION)
                .endControlFlow()
                .endControlFlow()
                .endControlFlow()
                // Fail-closed: no rule (incl. derived create rules) allowed → denied.
                .addStatement("return %S", "no delete rule allowed access")
                .build()
            )
            .build()
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
        val body = CodeBlock.builder()
        body.add("return %T(\n", candidateClass)
        for (field in fields) {
            val propName = toCamelCase(field.name)
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
     * `createMany(*blocks): MutationResult<List<T>>` — strict, ATOMIC
     * bulk create sharing the per-row create pipeline
     * (`${Schema}Create.executeSaveForInternalUse` WITHOUT the
     * disclosure step) and running return processing — LOAD disclosure
     * per created entity, input order, fail-fast — only after every
     * row's writes and write-side lifecycle callbacks succeeded:
     *
     *  - Caller-owned transaction (`driver.inTransaction`): rows run
     *    in place; the first write-phase `Failed` is recorded
     *    (rollback-only) and returned, so staged rows cannot commit.
     *    A post-write disclosure failure reports `TransactionPending`
     *    and marks the scope rollback-only.
     *
     *  - EntKt-owned (no surrounding transaction): the whole batch
     *    runs inside one `client.withTransaction`. A pre-completion
     *    failure rolls the whole batch back (no committed subset); a
     *    disclosure failure AFTER all writes succeeded deliberately
     *    bypasses the coordinator, COMMITS the complete batch, and
     *    reports the failure with `Committed` — successful
     *    persistence is not undone merely because the requested
     *    return value cannot be disclosed.
     *
     *  - Zero blocks → `Success(emptyList())` without transaction
     *    work (after the transaction-requirement preflight).
     *
     * The old hard `driver.inTransaction` throw is gone — atomicity is
     * owned here. `checkTransactionRequirement(multiWrite = blocks.size > 1)`
     * still classifies against the caller's transaction posture first.
     */
    private fun buildCreateMany(
        schemaName: String,
        entityClass: ClassName,
        createLambda: LambdaTypeName,
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val resultType = MUTATION_RESULT.parameterizedBy(LIST.parameterizedBy(entityClass))
        return FunSpec.builder("createMany")
            .addParameter(
                ParameterSpec.builder("blocks", createLambda)
                    .addModifiers(KModifier.VARARG)
                    .build()
            )
            .returns(resultType)
            .addKdoc(
                "Atomically create one row per block. `Success` carries every\n" +
                    "created entity in input order; the whole batch shares one\n" +
                    "transaction (the caller's, or an EntKt-owned one), so any failure\n" +
                    "before the batch's writes and write-side callbacks complete\n" +
                    "leaves no committed subset after a confirmed rollback — a partial\n" +
                    "list is never returned. Hook-required hydration is write-side\n" +
                    "work and happens per row; return processing (LOAD disclosure\n" +
                    "plus any additional return materialization, input order,\n" +
                    "fail-fast) runs after all writes: a\n" +
                    "denial there is `Failed(EntMutationPrivacyDeniedException)` with\n" +
                    "`operation = LOAD` identifying the one denied entity, and — in an\n" +
                    "EntKt-owned transaction — the batch still COMMITS, reported via\n" +
                    "`writeState = Committed`. `Failed` therefore does NOT imply\n" +
                    "rollback; inspect `exception.writeState`. There is no `orNull()`\n" +
                    "projection — project with `getOrThrow()` or match on the result.",
            )
            .addCode(
                CodeBlock.builder()
                    .add("var writeState = %T.NotPersisted\n", MUTATION_WRITE_STATE)
                    .add("try {\n")
                    // multiWrite reflects the documented "issues more
                    // than one driver write" contract: a zero- or
                    // one-row batch is not a multi-write, so
                    // RequiredForMultiWrite must not reject it.
                    .add(
                        "  client.checkTransactionRequirement(%S, multiWrite = blocks.size > 1)\n",
                        "$schemaName createMany",
                    )
                    .add("  if (blocks.isEmpty()) return %T.Success(emptyList())\n", MUTATION_RESULT)
                    .add("  if (driver.inTransaction) {\n")
                    // ---- Caller-owned transaction: per-row pipeline in
                    // place (write-side only; no disclosure), then batch
                    // return processing. Each row's Failed was already
                    // recorded on the coordinator inside the create
                    // pipeline. ----
                    .add("    val out = %T<%T>(blocks.size)\n", ArrayList::class.asClassName(), entityClass)
                    .add("    for (block in blocks) {\n")
                    .add("      when (val result = create(block).executeSaveForInternalUse(applyLoadPrivacy = false)) {\n")
                    .add("        is %T.Success -> out.add(result.value)\n", MUTATION_RESULT)
                    // Same staged-batch honesty rule as deleteMany: after
                    // any prior row wrote, a NotPersisted row failure is
                    // re-reported as TransactionPending for the batch.
                    .add("        is %T.Failed -> {\n", MUTATION_RESULT)
                    .add("          val rowException = result.exception\n")
                    .add(
                        "          return if (out.isNotEmpty() && rowException.writeState == %T.NotPersisted) {\n",
                        MUTATION_WRITE_STATE,
                    )
                    .add(
                        "            val staged = %T(%T.TransactionPending, rowException)\n",
                        ENT_UNEXPECTED_MUTATION_EXCEPTION, MUTATION_WRITE_STATE,
                    )
                    .add("            client.replaceTransactionMutationFailure(rowException, staged)\n")
                    .add("            %T.failedForInternalUse(staged)\n", MUTATION_RESULT)
                    .add("          } else result\n")
                    .add("        }\n")
                    .add("      }\n")
                    .add("    }\n")
                    // All writes + write-side callbacks succeeded; the
                    // batch is staged in the caller's open transaction.
                    // Return-processing failures from here (a thrown
                    // privacy rule reaches the terminal boundary; a
                    // returned denial is typed below) report
                    // TransactionPending and mark rollback-only.
                    .add("    writeState = %T.TransactionPending\n", MUTATION_WRITE_STATE)
                    .add("    val privacy = client.currentPrivacyContext()\n")
                    .add("    for (entity in out) {\n")
                    .add("      val denial = loadDenialOrNull(privacy, entity)\n")
                    .add("      if (denial != null) {\n")
                    .add(
                        privacyDeniedFailure(
                            writeStateExpr = CodeBlock.of("%T.TransactionPending", MUTATION_WRITE_STATE),
                            schemaName = schemaName,
                            operationName = "LOAD",
                            entityKeyExpr = CodeBlock.of("%T(%S, entity.id)", MUTATION_ENTITY_KEY, "id"),
                            reasonExpr = "denial.reason",
                        ).indented().indented().indented(),
                    )
                    .add("      }\n")
                    .add("    }\n")
                    .add("    return %T.Success(out.toList())\n", MUTATION_RESULT)
                    .add("  }\n")
                    // ---- EntKt-owned: one transaction for the whole
                    // batch. Write failures abort via orRollback (the
                    // boundary rolls back — no committed subset); return-
                    // processing failures are CAPTURED into locals instead
                    // of thrown so the block returns normally and the
                    // batch COMMITS. ----
                    .add("  var disclosureDeniedId: %T = null\n", idType.copy(nullable = true))
                    .add("  var disclosureDenialReason: String? = null\n")
                    .add("  var disclosureFailure: %T? = null\n", KOTLIN_EXCEPTION)
                    .add("  val txResult = client.withTransaction { tx ->\n")
                    .add("    val batch = %T<%T>(blocks.size)\n", ArrayList::class.asClassName(), entityClass)
                    .add("    for (block in blocks) {\n")
                    .add(
                        "      batch.add(tx.%L.create(block).executeSaveForInternalUse(applyLoadPrivacy = false).orRollback())\n",
                        repoPropName,
                    )
                    .add("    }\n")
                    // Disclosure runs through the tx-scoped repo so
                    // privacy-rule reads see the staged batch. Obtaining
                    // the privacy context is already return processing:
                    // a provider failure is captured so the completed
                    // batch still COMMITS and reports the disclosure
                    // failure with `Committed`.
                    .add("    val privacy = try {\n")
                    .add("      tx.currentPrivacyContext()\n")
                    .add("    } catch (e: %T) {\n", MUTATION_CANCELLATION_EXCEPTION)
                    .add("      throw e\n")
                    .add("    } catch (e: %T) {\n", KOTLIN_EXCEPTION)
                    .add("      disclosureFailure = e\n")
                    .add("      return@withTransaction null\n")
                    .add("    }\n")
                    .add("    for (entity in batch) {\n")
                    .add("      val denial = try {\n")
                    .add("        tx.%L.loadDenialOrNull(privacy, entity)\n", repoPropName)
                    .add("      } catch (e: %T) {\n", MUTATION_CANCELLATION_EXCEPTION)
                    .add("        throw e\n")
                    .add("      } catch (e: %T) {\n", KOTLIN_EXCEPTION)
                    .add("        disclosureFailure = e\n")
                    .add("        return@withTransaction null\n")
                    .add("      }\n")
                    .add("      if (denial != null) {\n")
                    .add("        disclosureDeniedId = entity.id\n")
                    .add("        disclosureDenialReason = denial.reason\n")
                    .add("        return@withTransaction null\n")
                    .add("      }\n")
                    .add("    }\n")
                    .add("    batch.toList()\n")
                    .add("  }\n")
                    .add("  return when (txResult) {\n")
                    .add("    is %T.Success -> {\n", TRANSACTION_RESULT)
                    .add("      val entities = txResult.value\n")
                    .add("      if (entities != null) {\n")
                    .add("        %T.Success(entities)\n", MUTATION_RESULT)
                    .add("      } else {\n")
                    // Commit succeeded but the return value cannot be
                    // disclosed / materialized: report Committed. Not
                    // routed through the coordinator by construction
                    // (there is no caller-owned scope on this path).
                    .add("        val reason = disclosureDenialReason\n")
                    .add("        val exception = if (reason != null) {\n")
                    .add(
                        "          %T(%T.Committed, %S, %T.LOAD, %T(%S, disclosureDeniedId!!), reason)\n",
                        ClassName("entkt.runtime.result", "EntMutationPrivacyDeniedException"),
                        MUTATION_WRITE_STATE, schemaName, MUTATION_ENT_OPERATION, MUTATION_ENTITY_KEY, "id",
                    )
                    .add("        } else {\n")
                    .add(
                        "          %T(%T.Committed, disclosureFailure!!)\n",
                        ENT_UNEXPECTED_MUTATION_EXCEPTION, MUTATION_WRITE_STATE,
                    )
                    .add("        }\n")
                    .add("        client.recordTransactionMutationFailure(exception)\n")
                    .add("        %T.failedForInternalUse(exception)\n", MUTATION_RESULT)
                    .add("      }\n")
                    .add("    }\n")
                    .add("    is %T.Failed -> {\n", TRANSACTION_RESULT)
                    .add(txFailureConversion().indented().indented().indented())
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

    private fun buildEvaluateCreateValidation(
        schemaName: String,
        candidateClass: ClassName,
    ): FunSpec {
        val createCtxClass = ClassName(packageName, "${schemaName}CreateValidationContext")
        return FunSpec.builder("evaluateCreateValidation")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("candidate", candidateClass)
            .returns(LIST.parameterizedBy(MUTATION_VALIDATION_VIOLATION))
            .addCode(CodeBlock.builder()
                .addStatement("val rules = validationConfig.createRules")
                .addStatement("if (rules.isEmpty()) return emptyList()")
                .addStatement("val validationClient = client.asValidationReadClientForInternalUse()")
                .addStatement("val ctx = %T(validationClient, candidate)", createCtxClass)
                .addStatement("return rules.mapNotNull { rule ->")
                .addStatement("  when (val decision = rule.validate(ctx)) {")
                .addStatement("    is %T.Valid -> null", VALIDATION_DECISION)
                .addStatement("    is %T.Invalid -> decision.%M()", VALIDATION_DECISION, TO_VALIDATION_VIOLATION)
                .addStatement("  }")
                .addStatement("}")
                .build()
            )
            .build()
    }

    private fun buildEvaluateUpdateValidation(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
    ): FunSpec {
        val updateCtxClass = ClassName(packageName, "${schemaName}UpdateValidationContext")
        val createCtxClass = ClassName(packageName, "${schemaName}CreateValidationContext")
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
                .addStatement(
                    "val updateCtx = %T(validationClient, before, requestedPatch, effectivePatch, candidate, edgeChanges)",
                    updateCtxClass,
                )
                .addStatement("val violations = mutableListOf<%T>()", MUTATION_VALIDATION_VIOLATION)
                .beginControlFlow("for (rule in rules)")
                .beginControlFlow("when (val decision = rule.validate(updateCtx))")
                .addStatement("is %T.Valid -> { }", VALIDATION_DECISION)
                .addStatement("is %T.Invalid -> violations.add(decision.%M())", VALIDATION_DECISION, TO_VALIDATION_VIOLATION)
                .endControlFlow()
                .endControlFlow()
                .beginControlFlow("if (validationConfig.updateDerivesFromCreate)")
                .addStatement("val createCtx = %T(validationClient, candidate)", createCtxClass)
                .beginControlFlow("for (rule in validationConfig.createRules)")
                .beginControlFlow("when (val decision = rule.validate(createCtx))")
                .addStatement("is %T.Valid -> { }", VALIDATION_DECISION)
                .addStatement("is %T.Invalid -> violations.add(decision.%M())", VALIDATION_DECISION, TO_VALIDATION_VIOLATION)
                .endControlFlow()
                .endControlFlow()
                .endControlFlow()
                .addStatement("return violations")
                .build()
            )
            .build()
    }

    private fun buildEvaluateDeleteValidation(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
    ): FunSpec {
        val deleteCtxClass = ClassName(packageName, "${schemaName}DeleteValidationContext")
        return FunSpec.builder("evaluateDeleteValidation")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("entity", entityClass)
            .addParameter("candidate", candidateClass)
            .returns(LIST.parameterizedBy(MUTATION_VALIDATION_VIOLATION))
            .addCode(CodeBlock.builder()
                .addStatement("val rules = validationConfig.deleteRules")
                .addStatement("if (rules.isEmpty()) return emptyList()")
                .addStatement("val validationClient = client.asValidationReadClientForInternalUse()")
                .addStatement("val ctx = %T(validationClient, entity, candidate)", deleteCtxClass)
                .addStatement("return rules.mapNotNull { rule ->")
                .addStatement("  when (val decision = rule.validate(ctx)) {")
                .addStatement("    is %T.Valid -> null", VALIDATION_DECISION)
                .addStatement("    is %T.Invalid -> decision.%M()", VALIDATION_DECISION, TO_VALIDATION_VIOLATION)
                .addStatement("  }")
                .addStatement("}")
                .build()
            )
            .build()
    }
}
