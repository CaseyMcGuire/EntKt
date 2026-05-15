package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
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
private val ENT_CLIENT_NAME = "EntClient"
private val PRIVACY_CONTEXT = ClassName("entkt.runtime", "PrivacyContext")
private val PRIVACY_OPERATION = ClassName("entkt.runtime", "PrivacyOperation")
private val PRIVACY_DENIED = ClassName("entkt.runtime", "PrivacyDeniedException")
private val PRIVACY_DECISION = ClassName("entkt.runtime", "PrivacyDecision")
private val VIEWER = ClassName("entkt.runtime", "Viewer")
private val VALIDATION_DECISION = ClassName("entkt.runtime", "ValidationDecision")
private val VALIDATION_EXCEPTION = ClassName("entkt.runtime", "ValidationException")

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
            .addFunction(buildRepoCreate(schema, entityClass, createClass, createLambda))
            .addFunction(
                // Per-save UpdateConsistency override (RFC #4). Defaults
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
                    .addParameter("block", updateLambda)
                    .returns(updateClass)
                    .addStatement(
                        "return %T(driver, client, id, consistency, beforeSaveHooks, beforeUpdateHooks, afterUpdateHooks).apply(block)",
                        updateClass,
                    )
                    .build()
            )
            .addFunction(
                FunSpec.builder("byId")
                    .addParameter("id", idType)
                    .returns(entityClass.copy(nullable = true))
                    .addCode(buildByIdBody(schemaName, entityClass))
                    .build()
            )
            .addFunction(buildDelete(schemaName, entityClass, candidateClass))
            .addFunction(buildDeleteLoaded(entityClass))
            .addFunction(buildDeleteById(schemaName, entityClass, idType, candidateClass))
            .also { builder ->
                if (idStrategyName(schema) != "EXPLICIT") {
                    builder.addFunction(buildCreateMany(entityClass, createLambda))
                }
            }
            .addFunction(buildDeleteMany(schemaName, entityClass, candidateClass))
            .addFunction(buildApplyHooks(entityHooksClass))
            .addFunction(buildCopyHooksFrom(repoClass))
            .addFunction(buildApplyPrivacy(privacyConfigClass))
            .addFunction(buildCopyPrivacyFrom(repoClass))
            .addFunction(buildHasPrivacy("hasLoadPrivacy", "loadRules"))
            .addFunction(buildHasPrivacy("hasCreatePrivacy", "createRules"))
            .addFunction(buildHasPrivacy("hasUpdatePrivacy", "updateRules", "updateDerivesFromCreate"))
            .addFunction(buildHasPrivacy("hasDeletePrivacy", "deleteRules", "deleteDerivesFromCreate"))
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

    private fun buildByIdBody(
        schemaName: String,
        entityClass: ClassName,
    ): CodeBlock {
        val body = CodeBlock.builder()
        body.addStatement("val privacy = client.currentPrivacyContext()")
        body.addStatement("val entity = driver.byId(%T.TABLE, id)?.let { %T.fromRow(it) } ?: return null", entityClass, entityClass)
        body.addStatement("evaluateLoadPrivacy(privacy, entity)")
        body.addStatement("return entity")
        return body.build()
    }

    /**
     * Public `delete(entity)` entry — runs the transaction-requirement
     * preflight, then delegates to [buildDeleteLoaded] for the rest of
     * the pipeline. The internal callers ([buildDeleteById],
     * [buildDeleteMany]) skip this and go straight to `deleteLoaded`
     * after their own preflight to avoid double-checking the
     * requirement.
     */
    private fun buildDelete(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
    ): FunSpec {
        return FunSpec.builder("delete")
            .addParameter("entity", entityClass)
            .returns(Boolean::class)
            // Transaction-requirement preflight (RFC #4) — fires before
            // the privacy context load and any other observable work.
            .addStatement("client.checkTransactionRequirement(%S)", "$schemaName delete")
            .addStatement("return deleteLoaded(entity)")
            .build()
    }

    /**
     * Private internal-only delete pipeline that assumes the caller
     * already ran the transaction-requirement preflight. Used by
     * `delete(entity)` (public, runs preflight first), `deleteById`
     * (runs preflight + byId, then this), and `deleteMany` (runs
     * multi-write preflight + query, then this in a loop). Extracting
     * this avoids the double preflight that `deleteById ->
     * delete(entity)` and `deleteMany -> delete(entity)` would
     * otherwise do.
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

    private fun buildDeleteById(
        schemaName: String,
        entityClass: ClassName,
        idType: com.squareup.kotlinpoet.TypeName,
        candidateClass: ClassName,
    ): FunSpec {
        // deleteById must not call privacy-enforcing byId
        return FunSpec.builder("deleteById")
            .addParameter("id", idType)
            .returns(Boolean::class)
            // Transaction-requirement preflight (RFC #4) — fires before
            // the byId read so a missing-id call under
            // RequiredForAllWrites still throws (instead of silently
            // returning false because the row doesn't exist).
            .addStatement("client.checkTransactionRequirement(%S)", "$schemaName delete")
            .addStatement(
                "val entity = driver.byId(%T.TABLE, id)?.let { %T.fromRow(it) } ?: return false",
                entityClass,
                entityClass,
            )
            // Skip the public `delete(entity)` entry — it would re-run
            // the same preflight we just ran above. Go straight to the
            // private `deleteLoaded` so the per-call observable work
            // (privacy / hooks / driver.delete) happens once.
            .addStatement("return deleteLoaded(entity)")
            .build()
    }

    private fun buildDeleteMany(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
    ): FunSpec {
        // deleteMany queries the driver directly (no LOAD privacy), then delete() per entity
        return FunSpec.builder("deleteMany")
            .addParameter(
                ParameterSpec.builder("predicates", PREDICATE)
                    .addModifiers(KModifier.VARARG)
                    .build(),
            )
            .returns(INT)
            // Transaction-requirement preflight (RFC #4) — fires before
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
            //    transaction.) Mirrors the RFC's "classify before
            //    normalization" rule for empty patches.
            .addStatement(
                "client.checkTransactionRequirement(%S, multiWrite = true)",
                "$schemaName deleteMany",
            )
            .addStatement(
                "val rows = driver.query(%T.TABLE, predicates.toList(), emptyList(), null, null)",
                entityClass,
            )
            .addStatement("val entities = rows.map { %T.fromRow(it) }", entityClass)
            .addStatement("var count = 0")
            // Per-entity deletes go through the private `deleteLoaded` so
            // they don't re-run the multi-write preflight that already
            // ran above. (Going through public `delete(entity)` would
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

    private fun buildHasPrivacy(name: String, field: String, deriveFlag: String? = null): FunSpec {
        val builder = FunSpec.builder(name)
            .addModifiers(KModifier.INTERNAL)
            .returns(Boolean::class)
        if (deriveFlag != null) {
            builder.addStatement("return privacyConfig.%L.isNotEmpty() || privacyConfig.%L", field, deriveFlag)
        } else {
            builder.addStatement("return privacyConfig.%L.isNotEmpty()", field)
        }
        return builder.build()
    }

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
                .addStatement("if (privacy.viewer is %T.System) return", VIEWER)
                .addStatement("val rules = privacyConfig.loadRules")
                .addStatement("if (rules.isEmpty()) return")
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
                .addStatement("if (privacy.viewer is %T.System) return", VIEWER)
                .addStatement("val rules = privacyConfig.createRules")
                .addStatement("if (rules.isEmpty()) return")
                .addStatement("val privacyClient = client.withFixedPrivacyContextForInternalUse(privacy)")
                .addStatement("val ctx = %T(privacy, privacyClient, candidate)", createCtxClass)
                .beginControlFlow("for (rule in rules)")
                .beginControlFlow("when (val decision = rule.run(ctx))")
                .addStatement("is %T.Allow -> return", PRIVACY_DECISION)
                .addStatement("is %T.Deny -> throw %T(%S, %T.CREATE, decision.reason)", PRIVACY_DECISION, PRIVACY_DENIED, schemaName, PRIVACY_OPERATION)
                .addStatement("is %T.Continue -> { }", PRIVACY_DECISION)
                .endControlFlow()
                .endControlFlow()
                // End-of-list for write ops: allow (deny-list style)
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
        return FunSpec.builder("evaluateUpdatePrivacy")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("before", entityClass)
            .addParameter("requestedPatch", patchClass)
            .addParameter("effectivePatch", patchClass)
            .addParameter("candidate", candidateClass)
            .addCode(CodeBlock.builder()
                .addStatement("if (privacy.viewer is %T.System) return", VIEWER)
                .addStatement("val rules = privacyConfig.updateRules")
                .addStatement("if (rules.isEmpty() && !privacyConfig.updateDerivesFromCreate) return")
                .addStatement("val privacyClient = client.withFixedPrivacyContextForInternalUse(privacy)")
                .addStatement(
                    "val ctx = %T(privacy, privacyClient, before, requestedPatch, effectivePatch, candidate)",
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
                .addStatement("if (privacy.viewer is %T.System) return", VIEWER)
                .addStatement("val rules = privacyConfig.deleteRules")
                .addStatement("if (rules.isEmpty() && !privacyConfig.deleteDerivesFromCreate) return")
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

    private fun buildCreateMany(
        entityClass: ClassName,
        createLambda: LambdaTypeName,
    ): FunSpec {
        // Classify by actual write count: 0 or 1 blocks = single-write
        // (matches the per-block create() preflight); 2+ blocks =
        // multi-write so RequiredForMultiWrite fires for the aggregate
        // call even though each per-block create() preflight runs as
        // single-write. RequiredForAllWrites fires either way (the
        // multi-write flag doesn't change that branch). Without this
        // outer preflight, RequiredForMultiWrite would silently
        // accept a 5-block createMany outside a transaction because
        // each delegated `create().save()` looks single-write to its
        // own preflight.
        //
        // Zero-block calls short-circuit *before* the preflight,
        // returning emptyList(). Vararg size is statically known on
        // call entry — no I/O is needed to decide there's nothing to
        // write — so this mirrors how update(id) { } reports NoChanges
        // before the transaction-requirement check (per the RFC's
        // "classify syntactically empty before any other observable
        // work, including transaction requirement checks" rule). The
        // contrast is deliberate against deleteMany(predicate), which
        // requires a query to learn it has no work and therefore
        // classifies by operation shape, not result size.
        return FunSpec.builder("createMany")
            .addParameter(
                ParameterSpec.builder("blocks", createLambda)
                    .addModifiers(KModifier.VARARG)
                    .build()
            )
            .returns(LIST.parameterizedBy(entityClass))
            .addStatement("if (blocks.isEmpty()) return emptyList()")
            .addStatement(
                "client.checkTransactionRequirement(%S, multiWrite = blocks.size > 1)",
                // Operation label intentionally distinct from "create" so
                // diagnostics show the multi-write entry point, not the
                // delegated single-write path that runs per block.
                "${entityClass.simpleName} createMany",
            )
            .addStatement("return blocks.map { create(it).save() }")
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
                .addStatement("val validationClient = client.withFixedPrivacyContextForInternalUse(%T(%T.System))", PRIVACY_CONTEXT, VIEWER)
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
        return FunSpec.builder("evaluateUpdateValidation")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("before", entityClass)
            .addParameter("requestedPatch", patchClass)
            .addParameter("effectivePatch", patchClass)
            .addParameter("candidate", candidateClass)
            .addCode(CodeBlock.builder()
                .addStatement("val rules = validationConfig.updateRules")
                .addStatement("if (rules.isEmpty() && !validationConfig.updateDerivesFromCreate) return")
                .addStatement("val validationClient = client.withFixedPrivacyContextForInternalUse(%T(%T.System))", PRIVACY_CONTEXT, VIEWER)
                .addStatement(
                    "val updateCtx = %T(validationClient, before, requestedPatch, effectivePatch, candidate)",
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
                .addStatement("val validationClient = client.withFixedPrivacyContextForInternalUse(%T(%T.System))", PRIVACY_CONTEXT, VIEWER)
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
