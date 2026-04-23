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
 * `update(entity)`, and `byId` accessors. Its `init` block registers the
 * entity's [entkt.runtime.EntitySchema] so the driver knows the table
 * layout before any other call lands, and every builder it hands back is
 * constructed with the same driver reference.
 *
 * Hooks are applied from the client's hooks DSL via [applyHooks] at
 * construction time, and inherited by transactional repos via
 * [copyHooksFrom].
 */
class RepoGenerator(
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
        val beforeSaveHookLambda = LambdaTypeName.get(parameters = arrayOf(mutationClass), returnType = UNIT)
        val beforeCreateHookLambda = LambdaTypeName.get(parameters = arrayOf(createClass), returnType = UNIT)
        val afterCreateHookLambda = LambdaTypeName.get(parameters = arrayOf(entityClass), returnType = UNIT)
        val beforeUpdateHookLambda = LambdaTypeName.get(parameters = arrayOf(updateClass), returnType = UNIT)
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
            .addFunction(buildRepoUpsert(schema, entityClass, createClass, createLambda))
            .addFunction(
                FunSpec.builder("update")
                    .addParameter("entity", entityClass)
                    .addParameter("block", updateLambda)
                    .returns(updateClass)
                    .addStatement(
                        "return %T(driver, client, entity, beforeSaveHooks, beforeUpdateHooks, afterUpdateHooks).apply(block)",
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

    private fun buildDelete(
        schemaName: String,
        entityClass: ClassName,
        candidateClass: ClassName,
    ): FunSpec {
        return FunSpec.builder("delete")
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
            .addStatement(
                "val entity = driver.byId(%T.TABLE, id)?.let { %T.fromRow(it) } ?: return false",
                entityClass,
                entityClass,
            )
            .addStatement("return delete(entity)")
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
            .addStatement(
                "val rows = driver.query(%T.TABLE, predicates.toList(), emptyList(), null, null)",
                entityClass,
            )
            .addStatement("val entities = rows.map { %T.fromRow(it) }", entityClass)
            .addStatement("var count = 0")
            .addStatement("for (entity in entities) { if (delete(entity)) count++ }")
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
        return FunSpec.builder("evaluateUpdatePrivacy")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("privacy", PRIVACY_CONTEXT)
            .addParameter("before", entityClass)
            .addParameter("candidate", candidateClass)
            .addCode(CodeBlock.builder()
                .addStatement("if (privacy.viewer is %T.System) return", VIEWER)
                .addStatement("val rules = privacyConfig.updateRules")
                .addStatement("if (rules.isEmpty() && !privacyConfig.updateDerivesFromCreate) return")
                .addStatement("val privacyClient = client.withFixedPrivacyContextForInternalUse(privacy)")
                .addStatement("val ctx = %T(privacy, privacyClient, before, candidate)", updateCtxClass)
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
        val fields = schema.fields() + schema.mixins().flatMap { it.fields() }
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

    private fun buildRepoUpsert(
        schema: EntSchema,
        entityClass: ClassName,
        createClass: ClassName,
        createLambda: LambdaTypeName,
    ): FunSpec {
        val idStrategy = idStrategyName(schema)
        val columnClass = ClassName("entkt.query", "Column")
        val builder = FunSpec.builder("upsert")
        if (idStrategy == "EXPLICIT") {
            builder.addParameter("id", schema.id().type.toTypeName())
        }
        builder.addParameter(
                ParameterSpec.builder(
                    "onConflict",
                    columnClass.parameterizedBy(STAR),
                ).addModifiers(KModifier.VARARG).build(),
            )
            .addParameter("block", createLambda)
            .returns(entityClass)
        val createArgs = if (idStrategy == "EXPLICIT") {
            "driver, client, beforeSaveHooks, beforeCreateHooks, afterCreateHooks, afterUpdateHooks, id = id"
        } else {
            "driver, client, beforeSaveHooks, beforeCreateHooks, afterCreateHooks, afterUpdateHooks"
        }
        builder.addStatement(
            "return %T($createArgs).apply(block).upsert(*onConflict)",
            createClass,
        )
        return builder.build()
    }

    private fun buildCreateMany(
        entityClass: ClassName,
        createLambda: LambdaTypeName,
    ): FunSpec {
        return FunSpec.builder("createMany")
            .addParameter(
                ParameterSpec.builder("blocks", createLambda)
                    .addModifiers(KModifier.VARARG)
                    .build()
            )
            .returns(LIST.parameterizedBy(entityClass))
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
        return FunSpec.builder("evaluateUpdateValidation")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("before", entityClass)
            .addParameter("candidate", candidateClass)
            .addCode(CodeBlock.builder()
                .addStatement("val rules = validationConfig.updateRules")
                .addStatement("if (rules.isEmpty() && !validationConfig.updateDerivesFromCreate) return")
                .addStatement("val validationClient = client.withFixedPrivacyContextForInternalUse(%T(%T.System))", PRIVACY_CONTEXT, VIEWER)
                .addStatement("val updateCtx = %T(validationClient, before, candidate)", updateCtxClass)
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
