package entkt.codegen

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
import entkt.schema.EntSchema

private val DRIVER = ClassName("entkt.runtime", "Driver")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val LIST = ClassName("kotlin.collections", "List")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
private val INT = Int::class.asClassName()
private val UPDATE_CONSISTENCY = ClassName("entkt.runtime", "UpdateConsistency")
private val RELATIONSHIP_LOCKING = ClassName("entkt.runtime", "RelationshipLocking")
private val ENT_CLIENT_NAME = "EntClient"
private val PRIVACY_CONTEXT = ClassName("entkt.runtime", "PrivacyContext")
private val PRIVACY_OPERATION = ClassName("entkt.runtime", "PrivacyOperation")
private val PRIVACY_DENIED = ClassName("entkt.runtime", "PrivacyDeniedException")
private val PRIVACY_DECISION = ClassName("entkt.runtime", "PrivacyDecision")
private val VIEWER = ClassName("entkt.runtime", "Viewer")
private val VALIDATION_DECISION = ClassName("entkt.runtime", "ValidationDecision")
private val VALIDATION_EXCEPTION = ClassName("entkt.runtime", "ValidationException")
private val ENT_ERROR = ClassName("entkt.runtime", "EntError")
private val ENT_OPERATION = ClassName("entkt.runtime", "EntOperation")
private val ENT_RESULT = ClassName("entkt.runtime", "EntResult")
private val ENT_QUERY_REJECTED_EXCEPTION = ClassName("entkt.runtime", "EntQueryRejectedException")
private val ABORT_QUERY_REJECTED = ClassName("entkt.runtime", "AbortQueryRejected")
private val QUERY_SPEC_BUILDER = ClassName("entkt.runtime", "QuerySpecBuilder")
private val QUERY_CONTEXT = ClassName("entkt.runtime", "QueryContext")
private val READ_OPERATION = ClassName("entkt.runtime", "ReadOperation")
private val INTERCEPTOR_ENGINE = ClassName("entkt.runtime", "InterceptorEngine")
private val OP = ClassName("entkt.query", "Op")

/**
 * Emits a per-schema repository class. The repo is the only entry point
 * for I/O — it owns the [Driver] and exposes `query`, `create`,
 * `update(id)`, and `byId` accessors. Its `init` block registers the
 * entity's [entkt.runtime.EntitySchema] so the driver knows the table
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
            // Client reference — set by EntClient after construction.
            .addProperty(
                PropertySpec.builder("client", clientClass)
                    .addModifiers(KModifier.INTERNAL, KModifier.LATEINIT)
                    .mutable(true)
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
            .addFunction(
                FunSpec.builder("query")
                    .addParameter(
                        ParameterSpec.builder("block", queryLambda)
                            .defaultValue("{}")
                            .build()
                    )
                    .returns(queryClass)
                    .addStatement("return %T(driver, client).apply(block)", queryClass)
                    .build()
            )
            // Index-helper namespace. Emitted only when the schema has at
            // least one eligible index (matching the conditional
            // `${schemaName}Indexes` file). A computed getter so the
            // lateinit `client` is read at access time, not construction;
            // the namespace itself is stateless (driver + client only).
            .also { builder ->
                if (indexHelperTree(schema, schemaNames) != null) {
                    builder.addProperty(
                        PropertySpec.builder("indexes", indexesClass)
                            .getter(
                                FunSpec.getterBuilder()
                                    .addStatement("return %T(driver, client)", indexesClass)
                                    .build(),
                            )
                            .build(),
                    )
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
            .addFunction(buildByIdOrNull(schemaName, entityClass, idType))
            .addFunction(buildByIdOrThrow(schemaName, entityClass, idType))
            .addFunction(buildVisibleByIdOrNull(entityClass, idType))
            .addFunction(buildByIdOrError(schemaName, entityClass, idType))
            .addFunction(buildExplainByIdOrThrow(schemaName, entityClass, idType))
            .addFunction(buildExplainByIdOrNull(schemaName, entityClass, idType))
            .addFunction(buildExplainVisibleByIdOrNull(schemaName, entityClass, idType))
            .addFunction(buildExplainByIdOrError(schemaName, entityClass, idType))
            .addFunction(buildDeleteOrThrow(schemaName, entityClass))
            .addFunction(buildDeleteOrError(schemaName, entityClass))
            .addFunction(buildDeleteLoaded(entityClass))
            .addFunction(buildDeleteByIdOrError(schemaName, entityClass, idType))
            .also { builder ->
                if (idStrategyName(schema) != "EXPLICIT") {
                    builder.addFunction(buildCreateManyOrError(schemaName, entityClass, createLambda))
                }
            }
            .addFunction(buildDeleteMany(schemaName, entityClass, candidateClass))
            .addFunction(buildApplyHooks(entityHooksClass))
            .addFunction(buildCopyHooksFrom(repoClass))
            .addFunction(buildApplyPrivacy(privacyConfigClass))
            .addFunction(buildCopyPrivacyFrom(repoClass))
            .addFunction(buildHasPrivacy("hasLoadPrivacy"))
            .addFunction(buildHasPrivacy("hasCreatePrivacy"))
            .addFunction(buildHasPrivacy("hasUpdatePrivacy"))
            .addFunction(buildHasPrivacy("hasDeletePrivacy"))
            .addFunction(buildEvaluateLoadPrivacy(schemaName, entityClass, loadCtxClass))
            .addFunction(buildEvaluateCreatePrivacy(schemaName, candidateClass))
            .addFunction(buildEvaluateUpdatePrivacy(schemaName, entityClass, candidateClass))
            .addFunction(buildEvaluateDeletePrivacy(schemaName, entityClass, candidateClass))
            .addFunction(buildBuildDeleteCandidate(schemaName, schema, entityClass, candidateClass, schemaNames))
            .addFunction(buildApplyValidation(validationConfigClass))
            .addFunction(buildCopyValidationFrom(repoClass))
            .addFunction(buildEvaluateCreateValidation(schemaName, candidateClass))
            .addFunction(buildEvaluateUpdateValidation(schemaName, entityClass, candidateClass))
            .addFunction(buildEvaluateDeleteValidation(schemaName, entityClass, candidateClass))
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
            .build()
    }

    /**
     * `byIdOrNull(id): T?` — the absence-collapses-to-null read API.
     *
     * - Row missing → `null`
     * - Row exists but LOAD privacy denies → throws `PrivacyDeniedException`
     * - Row exists and visible → returns the hydrated entity
     *
     * The privacy-denial throw is the raw [PrivacyDeniedException];
     * structured [EntException] wrapping is provided by [byIdOrError]
     * and [byIdOrThrow] (which both wrap this method). The visible-or-
     * null collapse is provided by [visibleByIdOrNull].
     */
    private fun buildByIdOrNull(
        schemaName: String,
        entityClass: ClassName,
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec {
        val queryClass = ClassName(entityClass.packageName, "${schemaName}Query")
        val body = CodeBlock.builder()
        body.addStatement("val privacy = client.currentPrivacyContext()")
        // Route the by-id read through the generated *Query's
        // runReadInterceptors so we get the same predicate-walk
        // post-processing the query terminals get. In particular,
        // if a by-id interceptor adds `Edge.has { ... }`, the
        // walker fires the target entity's EDGE_PREDICATE
        // interceptors on the inner — without this route the
        // target soft-delete / tenant interceptors would be silently
        // bypassed on by-id reads. extraStructural carries the
        // `id = X` predicate; the walker skips structural
        // predicates so the id leaf is never re-walked.
        body.add(
            "val q = %T(driver, client)\n",
            queryClass,
        )
        body.add(
            "val spec = q.runReadInterceptors(\n",
        )
        body.add("  operation = %T.BY_ID,\n", READ_OPERATION)
        body.add("  entOperation = %T.LOAD,\n", ENT_OPERATION)
        body.add(
            "  extraStructural = listOf(%T.Leaf<%T>(%S, %T.EQ, id)),\n",
            PREDICATE, entityClass, "id", OP,
        )
        body.add(")\n")
        // Use driver.query (not driver.byId) because interceptors
        // may have added predicates (e.g. tenant_id = X) that
        // byId's PK lookup wouldn't honor.
        body.addStatement(
            "val row = driver.query(%T.TABLE, spec.predicates, emptyList(), 1, null).firstOrNull()",
            entityClass,
        )
        body.addStatement("if (row == null) return null")
        body.addStatement("val entity = %T.fromRow(row)", entityClass)
        body.addStatement("evaluateLoadPrivacy(privacy, entity)")
        body.addStatement("return entity")
        return FunSpec.builder("byIdOrNull")
            .addParameter("id", idType)
            .returns(entityClass.copy(nullable = true))
            .addCode(body.build())
            .build()
    }

    /**
     * `byIdOrThrow(id): T` — the strict read API. Throws structured
     * EntException subclasses for every failure surface; on the happy
     * path returns the hydrated entity.
     *
     * Delegates to [byIdOrError] + `getOrThrow()` so the mapping table
     * (NotFound / PrivacyDenied / DriverFailure) lives in one place.
     */
    private fun buildByIdOrThrow(
        schemaName: String,
        entityClass: ClassName,
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec {
        return FunSpec.builder("byIdOrThrow")
            .addParameter("id", idType)
            .returns(entityClass)
            .addStatement(
                "return byIdOrError(id).%M()",
                MemberName("entkt.runtime", "getOrThrow"),
            )
            .build()
    }

    /**
     * `visibleByIdOrNull(id): T?` — the absence-OR-invisibility
     * collapse. Treats both "row missing" and "row exists but LOAD
     * privacy denies" as `null`. Driver failures still propagate.
     *
     * Implemented as `try { byIdOrNull(id) } catch
     * (PrivacyDeniedException) { null }`. The driver-failure surface
     * remains as exceptions on this path — `byIdOrError` is the
     * structured alternative.
     */
    private fun buildVisibleByIdOrNull(
        entityClass: ClassName,
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec {
        return FunSpec.builder("visibleByIdOrNull")
            .addParameter("id", idType)
            .returns(entityClass.copy(nullable = true))
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add("  byIdOrNull(id)\n")
                    .add("} catch (_: %T) {\n", PRIVACY_DENIED)
                    .add("  null\n")
                    .add("}\n")
                    .build(),
            )
            .build()
    }

    /**
     * `byIdOrError(id): EntResult<T>` — the structured-result read API.
     * Maps each failure mode to its [EntError] variant:
     *
     * - row missing → `Err(NotFound)`
     * - privacy denied → `Err(PrivacyDenied)`
     * - any other uncaught Exception → routed through
     *   [classifyDriverError] → `Err(ConstraintViolation)` for
     *   recognized constraints or `Err(DriverFailure)` for the
     *   fallback.
     */
    private fun buildByIdOrError(
        schemaName: String,
        entityClass: ClassName,
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec {
        val resultType = ENT_RESULT.parameterizedBy(entityClass)
        return FunSpec.builder("byIdOrError")
            .addParameter("id", idType)
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add(
                        "  byIdOrNull(id)?.let { %T.Ok(it) } ?: %T.Err(%T.NotFound(%S, %T.LOAD, id))\n",
                        ENT_RESULT, ENT_RESULT, ENT_ERROR, schemaName, ENT_OPERATION,
                    )
                    .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                    .add("  %T.Err(e.queryRejected)\n", ENT_RESULT)
                    .add("} catch (e: %T) {\n", PRIVACY_DENIED)
                    .add(
                        "  %T.Err(%T.PrivacyDenied(e.entity, %T.valueOf(e.operation.name), e.reason))\n",
                        ENT_RESULT, ENT_ERROR, ENT_OPERATION,
                    )
                    .add("} catch (e: %T) {\n", Exception::class.asClassName())
                    .add(
                        "  %T.Err(%M(driver, e, %S, %T.LOAD))\n",
                        ENT_RESULT,
                        MemberName("entkt.runtime", "classifyDriverError"),
                        schemaName,
                        ENT_OPERATION,
                    )
                    .add("}\n")
                    .build(),
            )
            .build()
    }

    /**
     * Shared by-id explain wrapper. Builds a *Query instance, runs
     * its interceptor chain with operation = BY_ID, and either
     * delegates to the query's [buildQueryPlan] for a happy-path
     * plan or returns a rejected [QueryPlan] via
     * `QueryPlan.rejected(...)`. All four `explainById*` variants
     * produce the same plan — the result-shape suffix in the name
     * is for call-site discovery, not plan content (by contract).
     */
    private fun buildByIdExplainMethod(
        name: String,
        terminalName: String,
        schemaName: String,
        entityClass: ClassName,
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec {
        val queryClass = ClassName(entityClass.packageName, "${schemaName}Query")
        val queryPlan = ClassName("entkt.runtime", "QueryPlan")
        return FunSpec.builder(name)
            .addParameter("id", idType)
            .returns(queryPlan)
            .addKdoc(
                "Return a [QueryPlan] describing the query [$terminalName] would\n" +
                "execute. Interceptors run with operation = BY_ID; limit operations\n" +
                "are silent no-ops by contract. On interceptor rejection, returns\n" +
                "a plan with `rejected = true` carrying the rejection metadata;\n" +
                "explain does NOT throw."
            )
            .addCode(
                CodeBlock.builder()
                    .add("val q = %T(driver, client)\n", queryClass)
                    .add("return try {\n")
                    .add("  val spec = q.runReadInterceptors(\n")
                    .add("    operation = %T.BY_ID,\n", READ_OPERATION)
                    .add("    entOperation = %T.LOAD,\n", ENT_OPERATION)
                    .add(
                        "    extraStructural = listOf(%T.Leaf<%T>(%S, %T.EQ, id)),\n",
                        PREDICATE, entityClass, "id", OP,
                    )
                    .add("  )\n")
                    // By-id is a single-row PK lookup; hardwire
                    // limit = 1 / offset = null in the plan so the
                    // explain output matches the runtime call.
                    .add("  q.buildQueryPlan(spec.copy(limit = 1, offset = null), includeEager = false)\n")
                    .add("} catch (e: %T) {\n", ENT_QUERY_REJECTED_EXCEPTION)
                    .add("  %T.rejected(e.queryRejected)\n", queryPlan)
                    .add("}\n")
                    .build(),
            )
            .build()
    }

    private fun buildExplainByIdOrThrow(
        schemaName: String,
        entityClass: ClassName,
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec = buildByIdExplainMethod("explainByIdOrThrow", "byIdOrThrow", schemaName, entityClass, idType)

    private fun buildExplainByIdOrNull(
        schemaName: String,
        entityClass: ClassName,
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec = buildByIdExplainMethod("explainByIdOrNull", "byIdOrNull", schemaName, entityClass, idType)

    private fun buildExplainVisibleByIdOrNull(
        schemaName: String,
        entityClass: ClassName,
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec = buildByIdExplainMethod("explainVisibleByIdOrNull", "visibleByIdOrNull", schemaName, entityClass, idType)

    private fun buildExplainByIdOrError(
        schemaName: String,
        entityClass: ClassName,
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec = buildByIdExplainMethod("explainByIdOrError", "byIdOrError", schemaName, entityClass, idType)

    /**
     * `deleteOrThrow(entity): Unit` — strict delete. Throws structured
     * EntException subclasses for every failure surface; on the happy
     * path the row is gone (or was already gone concurrently — see
     * the note in [buildDeleteOrError] for why "already gone" is
     * silent here).
     */
    private fun buildDeleteOrThrow(schemaName: String, entityClass: ClassName): FunSpec {
        return FunSpec.builder("deleteOrThrow")
            .addParameter("entity", entityClass)
            .returns(UNIT)
            .addStatement(
                "deleteOrError(entity).%M()",
                MemberName("entkt.runtime", "getOrThrow"),
            )
            .build()
    }

    /**
     * `deleteOrError(entity): EntResult<Unit>` — structured-result
     * delete. Returns `Ok(Unit)` on a successful delete OR a
     * silent no-op (the entity already vanished concurrently); the
     * Boolean "was-deleted?" signal is intentionally dropped here
     * because the caller passed an entity it had in hand, so this is
     * not exceptional.
     *
     * Failure mapping mirrors saveOrError: PrivacyDenied →
     * Err(PrivacyDenied); delete-side ValidationException →
     * Err(ValidationFailed); EntException carried through; any other
     * Exception routed through [classifyDriverError] so SQLSTATE
     * 23xxx (e.g. an FK-from-other-rows constraint blocking the
     * delete) surfaces as Err(ConstraintViolation).
     */
    private fun buildDeleteOrError(schemaName: String, entityClass: ClassName): FunSpec {
        val resultType = ENT_RESULT.parameterizedBy(UNIT)
        return FunSpec.builder("deleteOrError")
            .addParameter("entity", entityClass)
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add("  client.checkTransactionRequirement(%S)\n", "$schemaName delete")
                    .add("  deleteLoaded(entity)\n")
                    .add("  %T.Ok(Unit)\n", ENT_RESULT)
                    .add("} catch (e: %T) {\n", PRIVACY_DENIED)
                    .add(
                        "  %T.Err(%T.PrivacyDenied(e.entity, %T.valueOf(e.operation.name), e.reason))\n",
                        ENT_RESULT, ENT_ERROR, ENT_OPERATION,
                    )
                    .add("} catch (e: %T) {\n", VALIDATION_EXCEPTION)
                    .add(
                        "  %T.Err(%T.ValidationFailed(e.entity, %T.DELETE, e.violations.map { it.%M() }))\n",
                        ENT_RESULT, ENT_ERROR, ENT_OPERATION,
                        MemberName("entkt.runtime", "toValidationViolation"),
                    )
                    .add("} catch (e: %T) {\n", ClassName("entkt.runtime", "EntException"))
                    .add("  %T.Err(e.error)\n", ENT_RESULT)
                    .add("} catch (e: %T) {\n", Exception::class.asClassName())
                    .add(
                        "  %T.Err(%M(driver, e, %S, %T.DELETE))\n",
                        ENT_RESULT,
                        MemberName("entkt.runtime", "classifyDriverError"),
                        schemaName, ENT_OPERATION,
                    )
                    .add("}\n")
                    .build(),
            )
            .build()
    }

    /**
     * Private internal-only delete pipeline that assumes the caller
     * already ran the transaction-requirement preflight. Used by
     * `deleteOrError` (public; `deleteOrThrow` delegates to it, runs
     * preflight first), `deleteByIdOrError` (runs preflight + byId,
     * then this), and `deleteMany` (runs multi-write preflight +
     * query, then this in a loop). Extracting this avoids the double
     * preflight that `deleteByIdOrError -> deleteOrError` and
     * `deleteMany -> deleteOrError` would otherwise do.
     */
    private fun buildDeleteLoaded(
        entityClass: ClassName,
    ): FunSpec {
        return FunSpec.builder("deleteLoaded")
            .addModifiers(KModifier.PRIVATE)
            .addParameter("entity", entityClass)
            .returns(Boolean::class)
            .addStatement("val privacy = client.currentPrivacyContext()")
            .addStatement("val candidate = buildDeleteCandidate(entity)")
            .addStatement("evaluateDeletePrivacy(privacy, entity, candidate)")
            .addStatement("evaluateDeleteValidation(entity, candidate)")
            .addStatement("for (hook in beforeDeleteHooks) hook(entity)")
            .addStatement("val deleted = driver.delete(%T.TABLE, entity.id)", entityClass)
            .addStatement("if (deleted) for (hook in afterDeleteHooks) hook(entity)")
            .addStatement("return deleted")
            .build()
    }

    /**
     * `deleteByIdOrError(id): EntResult<Boolean>` — structured-result
     * idempotent delete-by-id. Returns `Ok(true)` when a row was
     * actually deleted, `Ok(false)` when no row existed. Missing rows
     * are idempotent no-ops here; failure variants are the same set
     * saveOrError /
     * deleteOrError produce.
     *
     * Reads via the bare driver, bypassing repo-level LOAD privacy —
     * the delete-side privacy rule runs inside `deleteLoaded` and is
     * the authoritative check.
     */
    private fun buildDeleteByIdOrError(
        schemaName: String,
        entityClass: ClassName,
        idType: com.squareup.kotlinpoet.TypeName,
    ): FunSpec {
        val resultType = ENT_RESULT.parameterizedBy(Boolean::class.asClassName())
        return FunSpec.builder("deleteByIdOrError")
            .addParameter("id", idType)
            .returns(resultType)
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    .add("  client.checkTransactionRequirement(%S)\n", "$schemaName delete")
                    .add(
                        "  val row = driver.byId(%T.TABLE, id)\n",
                        entityClass,
                    )
                    .add("  if (row == null) {\n")
                    .add("    %T.Ok(false)\n", ENT_RESULT)
                    .add("  } else {\n")
                    .add(
                        "    %T.Ok(deleteLoaded(%T.fromRow(row)))\n",
                        ENT_RESULT, entityClass,
                    )
                    .add("  }\n")
                    .add("} catch (e: %T) {\n", PRIVACY_DENIED)
                    .add(
                        "  %T.Err(%T.PrivacyDenied(e.entity, %T.valueOf(e.operation.name), e.reason))\n",
                        ENT_RESULT, ENT_ERROR, ENT_OPERATION,
                    )
                    .add("} catch (e: %T) {\n", VALIDATION_EXCEPTION)
                    .add(
                        "  %T.Err(%T.ValidationFailed(e.entity, %T.DELETE, e.violations.map { it.%M() }))\n",
                        ENT_RESULT, ENT_ERROR, ENT_OPERATION,
                        MemberName("entkt.runtime", "toValidationViolation"),
                    )
                    .add("} catch (e: %T) {\n", ClassName("entkt.runtime", "EntException"))
                    .add("  %T.Err(e.error)\n", ENT_RESULT)
                    .add("} catch (e: %T) {\n", Exception::class.asClassName())
                    .add(
                        "  %T.Err(%M(driver, e, %S, %T.DELETE))\n",
                        ENT_RESULT,
                        MemberName("entkt.runtime", "classifyDriverError"),
                        schemaName, ENT_OPERATION,
                    )
                    .add("}\n")
                    .build(),
            )
            .build()
    }

    private fun buildDeleteMany(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
    ): FunSpec {
        val queryClass = ClassName(entityClass.packageName, "${schemaName}Query")
        // deleteMany candidates flow through the read-interceptor
        // chain (operation = DELETE_CANDIDATES, entOperation = DELETE)
        // so predicate-shaping interceptors (tenant scoping, framework
        // soft-delete, etc.) apply uniformly to bulk deletes — a
        // bulk delete can NOT escape read-side scoping that would
        // have hidden the same rows on the read path. Per-entity
        // DELETE privacy / validation / hooks still run inside
        // deleteLoaded for each surviving candidate.
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
            .returns(INT)
            // Transaction-requirement preflight (transaction locking) — fires before
            // the candidate query so:
            //  - an empty-result call under RequiredForAllWrites still
            //    throws (instead of silently returning 0 because
            //    nothing matched), and a non-empty result doesn't get
            //    partway through the candidate query before the
            //    per-entity delete throws;
            //  - RequiredForMultiWrite fires too — deleteMany is a
            //    multi-write API regardless of how many rows match,
            //    so we classify by operation shape before the query
            //    rather than waiting for per-entity preflights to
            //    each report single-write. (The per-entity delete
            //    preflight then runs single-write inside the
            //    transaction.) This mirrors empty-patch classification:
            //    classify request shape before normalizing it.
            .addStatement(
                "client.checkTransactionRequirement(%S, multiWrite = true)",
                "$schemaName deleteMany",
            )
            // Route candidate selection through runReadInterceptors
            // on a transient *Query instance seeded with the caller's
            // predicates. Caller predicates land tagged CALLER; any
            // interceptor predicates land tagged INTERCEPTOR. The
            // chain's rejection throws EntQueryRejectedException
            // (since deleteMany returns Int, not EntResult).
            // Seed predicates through the public `where()` DSL rather
            // than writing the backing list directly — `predicates` on
            // the generated query class is `private`, and the public
            // DSL is functionally equivalent (both accumulate onto the
            // same list) and keeps generated code on the documented
            // public surface.
            .addStatement(
                "val q = %T(driver, client).apply { for (p in predicates) where(p) }",
                queryClass,
            )
            .addStatement(
                "val spec = q.runReadInterceptors(%T.DELETE_CANDIDATES, %T.DELETE)",
                READ_OPERATION, ENT_OPERATION,
            )
            .addStatement(
                "val rows = driver.query(%T.TABLE, spec.predicates, emptyList(), null, null)",
                entityClass,
            )
            .addStatement("val entities = rows.map { %T.fromRow(it) }", entityClass)
            .addStatement("var count = 0")
            // Per-entity deletes go through the private `deleteLoaded` so
            // they don't re-run the multi-write preflight that already
            // ran above. (Going through public `deleteOrError` would
            // call `checkTransactionRequirement` once per matching row.)
            .addStatement("for (entity in entities) { if (deleteLoaded(entity)) count++ }")
            .addStatement("return count")
            .build()
    }

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
    private fun buildHasPrivacy(name: String): FunSpec =
        FunSpec.builder(name)
            .addModifiers(KModifier.INTERNAL)
            .returns(Boolean::class)
            .addStatement("return true")
            .build()

    private fun buildEvaluateLoadPrivacy(
        schemaName: String,
        entityClass: ClassName,
        loadCtxClass: ClassName,
    ): FunSpec {
        return FunSpec.builder("evaluateLoadPrivacy")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("entity", entityClass)
            .addCode(CodeBlock.builder()
                .addStatement("if (privacy.viewer is %T.PrivacyBypass) return", VIEWER)
                .addStatement("val rules = privacyConfig.loadRules")
                .addStatement("val privacyClient = client.withFixedPrivacyContextForInternalUse(privacy)")
                .addStatement("val ctx = %T(privacy, privacyClient, entity)", loadCtxClass)
                .beginControlFlow("for (rule in rules)")
                .beginControlFlow("when (val decision = rule.run(ctx))")
                .addStatement("is %T.Allow -> return", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> throw %T(%S, %T.LOAD, decision.reason)", PRIVACY_DECISION, PRIVACY_DENIED, schemaName, PRIVACY_OPERATION)
                .addStatement("is %T.Continue -> { }", PRIVACY_DECISION)
                .endControlFlow()
                .endControlFlow()
                // End-of-list for LOAD: deny
                .addStatement("throw %T(%S, %T.LOAD, %S)", PRIVACY_DENIED, schemaName, PRIVACY_OPERATION, "no load rule allowed access")
                .build()
            )
            .build()
    }

    private fun buildEvaluateCreatePrivacy(
        schemaName: String,
        candidateClass: ClassName,
    ): FunSpec {
        val createCtxClass = ClassName(packageName, "${schemaName}CreatePrivacyContext")
        return FunSpec.builder("evaluateCreatePrivacy")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("candidate", candidateClass)
            .addCode(CodeBlock.builder()
                .addStatement("if (privacy.viewer is %T.PrivacyBypass) return", VIEWER)
                .addStatement("val rules = privacyConfig.createRules")
                .addStatement("val privacyClient = client.withFixedPrivacyContextForInternalUse(privacy)")
                .addStatement("val ctx = %T(privacy, privacyClient, candidate)", createCtxClass)
                .beginControlFlow("for (rule in rules)")
                .beginControlFlow("when (val decision = rule.run(ctx))")
                .addStatement("is %T.Allow -> return", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> throw %T(%S, %T.CREATE, decision.reason)", PRIVACY_DECISION, PRIVACY_DENIED, schemaName, PRIVACY_OPERATION)
                .addStatement("is %T.Continue -> { }", PRIVACY_DECISION)
                .endControlFlow()
                .endControlFlow()
                // Fail-closed: no rule allowed → deny.
                .addStatement("throw %T(%S, %T.CREATE, %S)", PRIVACY_DENIED, schemaName, PRIVACY_OPERATION, "no create rule allowed access")
                .build()
            )
            .build()
    }

    private fun buildEvaluateUpdatePrivacy(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
    ): FunSpec {
        val updateCtxClass = ClassName(packageName, "${schemaName}UpdatePrivacyContext")
        val createCtxClass = ClassName(packageName, "${schemaName}CreatePrivacyContext")
        val patchClass = ClassName(packageName, "${schemaName}UpdatePatch")
        val edgeChangesViewClass = ClassName(packageName, "${schemaName}EdgeChangesView")
        return FunSpec.builder("evaluateUpdatePrivacy")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("before", entityClass)
            .addParameter("requestedPatch", patchClass)
            .addParameter("effectivePatch", patchClass)
            .addParameter("candidate", candidateClass)
            .addParameter("edgeChanges", edgeChangesViewClass)
            .addCode(CodeBlock.builder()
                .addStatement("if (privacy.viewer is %T.PrivacyBypass) return", VIEWER)
                .addStatement("val rules = privacyConfig.updateRules")
                .addStatement("val privacyClient = client.withFixedPrivacyContextForInternalUse(privacy)")
                .addStatement(
                    "val ctx = %T(privacy, privacyClient, before, requestedPatch, effectivePatch, candidate, edgeChanges)",
                    updateCtxClass,
                )
                .beginControlFlow("for (rule in rules)")
                .beginControlFlow("when (val decision = rule.run(ctx))")
                .addStatement("is %T.Allow -> return", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> throw %T(%S, %T.UPDATE, decision.reason)", PRIVACY_DECISION, PRIVACY_DENIED, schemaName, PRIVACY_OPERATION)
                .addStatement("is %T.Continue -> { }", PRIVACY_DECISION)
                .endControlFlow()
                .endControlFlow()
                .beginControlFlow("if (privacyConfig.updateDerivesFromCreate)")
                .addStatement("val createCtx = %T(privacy, privacyClient, candidate)", createCtxClass)
                .beginControlFlow("for (rule in privacyConfig.createRules)")
                .beginControlFlow("when (val decision = rule.run(createCtx))")
                .addStatement("is %T.Allow -> return", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> throw %T(%S, %T.UPDATE, decision.reason)", PRIVACY_DECISION, PRIVACY_DENIED, schemaName, PRIVACY_OPERATION)
                .addStatement("is %T.Continue -> { }", PRIVACY_DECISION)
                .endControlFlow()
                .endControlFlow()
                .endControlFlow()
                // Fail-closed: no rule (incl. derived create rules) allowed → deny.
                .addStatement("throw %T(%S, %T.UPDATE, %S)", PRIVACY_DENIED, schemaName, PRIVACY_OPERATION, "no update rule allowed access")
                .build()
            )
            .build()
    }

    private fun buildEvaluateDeletePrivacy(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
    ): FunSpec {
        val deleteCtxClass = ClassName(packageName, "${schemaName}DeletePrivacyContext")
        val createCtxClass = ClassName(packageName, "${schemaName}CreatePrivacyContext")
        return FunSpec.builder("evaluateDeletePrivacy")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("entity", entityClass)
            .addParameter("candidate", candidateClass)
            .addCode(CodeBlock.builder()
                .addStatement("if (privacy.viewer is %T.PrivacyBypass) return", VIEWER)
                .addStatement("val rules = privacyConfig.deleteRules")
                .addStatement("val privacyClient = client.withFixedPrivacyContextForInternalUse(privacy)")
                .addStatement("val ctx = %T(privacy, privacyClient, entity, candidate)", deleteCtxClass)
                .beginControlFlow("for (rule in rules)")
                .beginControlFlow("when (val decision = rule.run(ctx))")
                .addStatement("is %T.Allow -> return", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> throw %T(%S, %T.DELETE, decision.reason)", PRIVACY_DECISION, PRIVACY_DENIED, schemaName, PRIVACY_OPERATION)
                .addStatement("is %T.Continue -> { }", PRIVACY_DECISION)
                .endControlFlow()
                .endControlFlow()
                .beginControlFlow("if (privacyConfig.deleteDerivesFromCreate)")
                .addStatement("val createCtx = %T(privacy, privacyClient, candidate)", createCtxClass)
                .beginControlFlow("for (rule in privacyConfig.createRules)")
                .beginControlFlow("when (val decision = rule.run(createCtx))")
                .addStatement("is %T.Allow -> return", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> throw %T(%S, %T.DELETE, decision.reason)", PRIVACY_DECISION, PRIVACY_DENIED, schemaName, PRIVACY_OPERATION)
                .addStatement("is %T.Continue -> { }", PRIVACY_DECISION)
                .endControlFlow()
                .endControlFlow()
                .endControlFlow()
                // Fail-closed: no rule (incl. derived create rules) allowed → deny.
                .addStatement("throw %T(%S, %T.DELETE, %S)", PRIVACY_DENIED, schemaName, PRIVACY_OPERATION, "no delete rule allowed access")
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
     * `createManyOrError(*blocks): EntResult<List<T>>` — transaction-
     * required structured-result bulk create:
     *
     *  - Outside any transaction → throws
     *    `TransactionRequiredException` at preflight (before any
     *    per-row write). The check is harder than the regular
     *    transactionRequirement: this is the helper's own
     *    "transaction-required" contract, fires regardless of the
     *    client-level `TransactionRequirement` setting.
     *
     *  - Inside a transaction → runs each block's `create().saveOrError()`
     *    in order; the **first per-row `Err` short-circuits the rest** —
     *    no subsequent blocks execute. The returned `Err` carries that
     *    block's error.
     *
     *  - Zero blocks → `Ok(emptyList())` without any preflight or
     *    write, mirroring the existing classify-empty-syntactically
     *    rule from transaction locking.
     *
     * **All-or-nothing is the caller's responsibility.** The helper
     * short-circuits but does not own the transaction — it cannot
     * roll back the rows already written by earlier blocks. Inside a
     * plain `withTransaction { tx -> tx.users.createManyOrError(...) }`,
     * if block 3 fails and the helper returns `Err`, blocks 1 and 2
     * are still committed because the outer `withTransaction` sees a
     * normal `Err` return (not an exception). The idiomatic
     * all-or-nothing composition is:
     *
     * ```
     * client.withTransactionOrError { tx ->
     *   tx.users.createManyOrError(...).bind()  // bind() throws on Err
     * }                                          // → tx rolls back
     * ```
     *
     * where `.bind()` raises `AbortEntResultTransaction` on `Err`,
     * which `withTransactionOrError` catches outside the driver block
     * to trigger rollback.
     *
     * The legacy throwing `createMany(*blocks): List<T>` is REMOVED
     * (not deprecated) per the "don't deprecate, remove" directive.
     */
    private fun buildCreateManyOrError(
        schemaName: String,
        entityClass: ClassName,
        createLambda: LambdaTypeName,
    ): FunSpec {
        val resultType = ENT_RESULT.parameterizedBy(LIST.parameterizedBy(entityClass))
        return FunSpec.builder("createManyOrError")
            .addParameter(
                ParameterSpec.builder("blocks", createLambda)
                    .addModifiers(KModifier.VARARG)
                    .build()
            )
            .returns(resultType)
            .addStatement(
                "if (blocks.isEmpty()) return %T.Ok(emptyList())",
                ENT_RESULT,
            )
            // Hard transaction requirement: createManyOrError's
            // all-or-nothing contract only holds inside a tx. We
            // check driver.inTransaction directly instead of going
            // through checkTransactionRequirement — the latter
            // honors the client-level TransactionRequirement
            // (Optional / RequiredForMultiWrite / RequiredForAllWrites)
            // which can be configured to *not* require a tx, and
            // we can't allow that for this helper.
            .beginControlFlow("if (!driver.inTransaction)")
            .addStatement(
                "throw %T(%S)",
                ClassName("entkt.runtime", "TransactionRequiredException"),
                "$schemaName createManyOrError requires a transaction-scoped client",
            )
            .endControlFlow()
            .addCode(
                CodeBlock.builder()
                    .add("return try {\n")
                    // Short-circuit loop: stop at the first Err so we
                    // don't run subsequent blocks. The pre-fix version
                    // used blocks.map { ... } which ran every block
                    // before checking for an Err — that leaks
                    // intervening successful writes when used inside
                    // a plain withTransaction { } (which commits on
                    // normal Err return).
                    .add("  val out = %T<%T>(blocks.size)\n", ArrayList::class.asClassName(), entityClass)
                    .add("  var firstErr: %T.Err? = null\n", ENT_RESULT)
                    .add("  for (block in blocks) {\n")
                    .add("    val r = create(block).saveOrError()\n")
                    .add("    if (r is %T.Err) { firstErr = r; break }\n", ENT_RESULT)
                    .add("    out.add((r as %T.Ok).value)\n", ENT_RESULT)
                    .add("  }\n")
                    .add("  firstErr ?: %T.Ok(out.toList())\n", ENT_RESULT)
                    .add("} catch (e: %T) {\n", ClassName("entkt.runtime", "EntException"))
                    .add("  %T.Err(e.error)\n", ENT_RESULT)
                    .add("} catch (e: %T) {\n", Exception::class.asClassName())
                    .add(
                        "  %T.Err(%M(driver, e, %S, %T.CREATE))\n",
                        ENT_RESULT,
                        MemberName("entkt.runtime", "classifyDriverError"),
                        schemaName, ENT_OPERATION,
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

    private fun buildEvaluateCreateValidation(
        schemaName: String,
        candidateClass: ClassName,
    ): FunSpec {
        val createCtxClass = ClassName(packageName, "${schemaName}CreateValidationContext")
        return FunSpec.builder("evaluateCreateValidation")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("candidate", candidateClass)
            .addCode(CodeBlock.builder()
                .addStatement("val rules = validationConfig.createRules")
                .addStatement("if (rules.isEmpty()) return")
                .addStatement("val validationClient = client.withFixedPrivacyContextForInternalUse(%T(%T.PrivacyBypass(%S)))", PRIVACY_CONTEXT, VIEWER, "validation read")
                .addStatement("val ctx = %T(validationClient, candidate)", createCtxClass)
                .addStatement("val violations = rules.mapNotNull { rule ->")
                .addStatement("  when (val decision = rule.validate(ctx)) {")
                .addStatement("    is %T.Valid -> null", VALIDATION_DECISION)
                .addStatement("    is %T.Invalid -> decision", VALIDATION_DECISION)
                .addStatement("  }")
                .addStatement("}")
                .addStatement("if (violations.isNotEmpty()) throw %T(%S, violations)", VALIDATION_EXCEPTION, schemaName)
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
            .addCode(CodeBlock.builder()
                .addStatement("val rules = validationConfig.updateRules")
                .addStatement("if (rules.isEmpty() && !validationConfig.updateDerivesFromCreate) return")
                .addStatement("val validationClient = client.withFixedPrivacyContextForInternalUse(%T(%T.PrivacyBypass(%S)))", PRIVACY_CONTEXT, VIEWER, "validation read")
                .addStatement(
                    "val updateCtx = %T(validationClient, before, requestedPatch, effectivePatch, candidate, edgeChanges)",
                    updateCtxClass,
                )
                .addStatement("val violations = mutableListOf<%T.Invalid>()", VALIDATION_DECISION)
                .beginControlFlow("for (rule in rules)")
                .beginControlFlow("when (val decision = rule.validate(updateCtx))")
                .addStatement("is %T.Valid -> { }", VALIDATION_DECISION)
                .addStatement("is %T.Invalid -> violations.add(decision)", VALIDATION_DECISION)
                .endControlFlow()
                .endControlFlow()
                .beginControlFlow("if (validationConfig.updateDerivesFromCreate)")
                .addStatement("val createCtx = %T(validationClient, candidate)", createCtxClass)
                .beginControlFlow("for (rule in validationConfig.createRules)")
                .beginControlFlow("when (val decision = rule.validate(createCtx))")
                .addStatement("is %T.Valid -> { }", VALIDATION_DECISION)
                .addStatement("is %T.Invalid -> violations.add(decision)", VALIDATION_DECISION)
                .endControlFlow()
                .endControlFlow()
                .endControlFlow()
                .addStatement("if (violations.isNotEmpty()) throw %T(%S, violations)", VALIDATION_EXCEPTION, schemaName)
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
            .addCode(CodeBlock.builder()
                .addStatement("val rules = validationConfig.deleteRules")
                .addStatement("if (rules.isEmpty()) return")
                .addStatement("val validationClient = client.withFixedPrivacyContextForInternalUse(%T(%T.PrivacyBypass(%S)))", PRIVACY_CONTEXT, VIEWER, "validation read")
                .addStatement("val ctx = %T(validationClient, entity, candidate)", deleteCtxClass)
                .addStatement("val violations = rules.mapNotNull { rule ->")
                .addStatement("  when (val decision = rule.validate(ctx)) {")
                .addStatement("    is %T.Valid -> null", VALIDATION_DECISION)
                .addStatement("    is %T.Invalid -> decision", VALIDATION_DECISION)
                .addStatement("  }")
                .addStatement("}")
                .addStatement("if (violations.isNotEmpty()) throw %T(%S, violations)", VALIDATION_EXCEPTION, schemaName)
                .build()
            )
            .build()
    }
}
