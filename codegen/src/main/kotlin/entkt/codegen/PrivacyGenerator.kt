package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import entkt.schema.EntSchema
import entkt.schema.Field

private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val PRIVACY_RULE = ClassName("entkt.runtime.privacy", "PrivacyRule")
private val ENTITY_POLICY = ClassName("entkt.runtime.privacy", "EntityPolicy")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
private val FIELD_PATCH = ClassName("entkt.runtime.mutation", "FieldPatch")
private val PENDING_EDGE_OPS = ClassName("entkt.runtime.mutation", "PendingEdgeOps")
private val EDGE_CHANGES = ClassName("entkt.runtime.mutation", "EdgeChanges")

/**
 * Emits per-entity privacy infrastructure:
 *
 * - `{Entity}PrivacyConfig` — internal mutable config holding rule lists
 * - `{Entity}PrivacyScope` — DSL scope for declaring rules per operation
 * - `{Entity}PolicyScope` — outer scope passed to [EntityPolicy.configure]
 * - `{Entity}WriteCandidate` — snapshot of writable fields for write rules
 * - `{Entity}{Op}PrivacyContext` — context classes for each operation
 * - `{Entity}{Op}PrivacyRule` — typealiases for each operation's rule type
 */
internal class PrivacyGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val entityClass = ClassName(packageName, schemaName)
        val clientClass = ClassName(packageName, "EntClient")
        val configClass = ClassName(packageName, "${schemaName}PrivacyConfig")
        val privacyScopeClass = ClassName(packageName, "${schemaName}PrivacyScope")
        val policyScopeClass = ClassName(packageName, "${schemaName}PolicyScope")
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        val patchClass = ClassName(packageName, "${schemaName}UpdatePatch")
        val updateMutationViewClass = ClassName(packageName, "${schemaName}UpdateMutationView")
        val updateHookCtxClass = ClassName(packageName, "${schemaName}UpdateHookContext")
        val createMutationViewClass = ClassName(packageName, "${schemaName}CreateMutationView")
        val createHookCtxClass = ClassName(packageName, "${schemaName}CreateHookContext")
        val pendingEdgeOpsClass = ClassName(packageName, "${schemaName}PendingEdgeOps")
        val edgeChangesViewClass = ClassName(packageName, "${schemaName}EdgeChangesView")

        // Backing FK columns flow through `edgeFks` for write candidates
        // and update patches so their type/nullability come from the
        // relationship, not the scalar field declaration.
        val fields = scalarFields(schema)
        val edgeFks = computeEdgeFks(schema, schemaNames)

        // Helper-eligible link-table M2M edges. Each
        // contributes a typed `PendingEdgeOps<ID>` field on the per-entity
        // aggregator surfaced through the update hook context and the
        // update mutation view. Empty list → empty aggregator (uniform
        // hook context shape across entities).
        val helperEligibleEdges = helperEligibleM2MEdges(schema, schemaNames)

        val fileBuilder = FileSpec.builder(packageName, "${schemaName}Privacy")

        // Operation context class names
        val loadCtx = ClassName(packageName, "${schemaName}LoadPrivacyContext")
        val createCtx = ClassName(packageName, "${schemaName}CreatePrivacyContext")
        val updateCtx = ClassName(packageName, "${schemaName}UpdatePrivacyContext")
        val deleteCtx = ClassName(packageName, "${schemaName}DeletePrivacyContext")

        // Rule typealiases
        val loadRule = "${schemaName}LoadPrivacyRule"
        val createRule = "${schemaName}CreatePrivacyRule"
        val updateRule = "${schemaName}UpdatePrivacyRule"
        val deleteRule = "${schemaName}DeletePrivacyRule"

        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(loadRule, PRIVACY_RULE.parameterizedBy(loadCtx)).build(),
        )
        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(createRule, PRIVACY_RULE.parameterizedBy(createCtx)).build(),
        )
        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(updateRule, PRIVACY_RULE.parameterizedBy(updateCtx)).build(),
        )
        fileBuilder.addTypeAlias(
            TypeAliasSpec.builder(deleteRule, PRIVACY_RULE.parameterizedBy(deleteCtx)).build(),
        )

        // Operation context data classes
        fileBuilder.addType(buildLoadContext(schemaName, entityClass, clientClass, loadCtx))
        fileBuilder.addType(buildCreateContext(schemaName, clientClass, candidateClass, createCtx))
        fileBuilder.addType(
            buildUpdateContext(
                schemaName, entityClass, clientClass, candidateClass, patchClass,
                edgeChangesViewClass, updateCtx,
            ),
        )
        fileBuilder.addType(buildDeleteContext(schemaName, entityClass, clientClass, candidateClass, deleteCtx))

        // WriteCandidate
        fileBuilder.addType(buildWriteCandidate(schemaName, candidateClass, fields, edgeFks))

        // UpdatePatch
        fileBuilder.addType(buildUpdatePatch(patchClass, fields, edgeFks))

        // PendingEdgeOps aggregator. One typed
        // `PendingEdgeOps<TargetIdType>` per helper-eligible M2M edge,
        // exposed read-only on the update hook context and the update
        // mutation view. Schemas without helper-eligible M2M edges still
        // get a type — a no-fields class — so hook authors can write
        // `ctx.pendingEdges` without entity-conditional types.
        fileBuilder.addType(buildPendingEdgeOpsAggregator(pendingEdgeOpsClass, helperEligibleEdges))

        // EdgeChangesView aggregator.
        // One typed `EdgeChanges<TargetIdType>` per helper-eligible M2M
        // edge — the privacy/validation sidecar that surfaces both
        // caller intent and computed database delta. Same empty-class
        // fallback for schemas without helper-eligible edges so the
        // privacy/validation context shape is uniform.
        fileBuilder.addType(buildEdgeChangesViewAggregator(edgeChangesViewClass, helperEligibleEdges))

        // UpdateHookContext (received by beforeUpdate hooks)
        fileBuilder.addType(
            buildUpdateHookContext(
                ctxClass = updateHookCtxClass,
                clientClass = clientClass,
                entityClass = entityClass,
                patchClass = patchClass,
                mutationClass = updateMutationViewClass,
                pendingEdgesClass = pendingEdgeOpsClass,
            ),
        )

        // CreateHookContext (received by beforeCreate hooks). Mirrors
        // the update side: a restricted writable view plus `client` so
        // hooks can query the DB. The view hides the concrete builder's
        // save()/driver/hook-list surface.
        fileBuilder.addType(
            buildCreateHookContext(
                ctxClass = createHookCtxClass,
                clientClass = clientClass,
                mutationClass = createMutationViewClass,
            ),
        )

        // PrivacyConfig
        fileBuilder.addType(
            buildPrivacyConfig(
                configClass,
                ClassName(packageName, loadRule),
                ClassName(packageName, createRule),
                ClassName(packageName, updateRule),
                ClassName(packageName, deleteRule),
            ),
        )

        // PrivacyScope
        fileBuilder.addType(
            buildPrivacyScope(
                privacyScopeClass,
                configClass,
                ClassName(packageName, loadRule),
                ClassName(packageName, createRule),
                ClassName(packageName, updateRule),
                ClassName(packageName, deleteRule),
            ),
        )

        // PolicyScope
        fileBuilder.addType(buildPolicyScope(schemaName, policyScopeClass, privacyScopeClass, configClass))

        return fileBuilder.build()
    }

    private fun buildLoadContext(
        schemaName: String,
        entityClass: ClassName,
        clientClass: ClassName,
        ctxClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(ctxClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("privacy", PRIVACY_CONTEXT)
                .addParameter("client", clientClass)
                .addParameter("entity", entityClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("privacy", PRIVACY_CONTEXT).initializer("privacy").build())
        .addProperty(PropertySpec.builder("client", clientClass).initializer("client").build())
        .addProperty(PropertySpec.builder("entity", entityClass).initializer("entity").build())
        .build()

    private fun buildCreateContext(
        schemaName: String,
        clientClass: ClassName,
        candidateClass: ClassName,
        ctxClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(ctxClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("privacy", PRIVACY_CONTEXT)
                .addParameter("client", clientClass)
                .addParameter("candidate", candidateClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("privacy", PRIVACY_CONTEXT).initializer("privacy").build())
        .addProperty(PropertySpec.builder("client", clientClass).initializer("client").build())
        .addProperty(PropertySpec.builder("candidate", candidateClass).initializer("candidate").build())
        .build()

    private fun buildUpdateContext(
        schemaName: String,
        entityClass: ClassName,
        clientClass: ClassName,
        candidateClass: ClassName,
        patchClass: ClassName,
        edgeChangesViewClass: ClassName,
        ctxClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(ctxClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("privacy", PRIVACY_CONTEXT)
                .addParameter("client", clientClass)
                .addParameter("before", entityClass)
                .addParameter("requestedPatch", patchClass)
                .addParameter("effectivePatch", patchClass)
                .addParameter("candidate", candidateClass)
                .addParameter("edgeChanges", edgeChangesViewClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("privacy", PRIVACY_CONTEXT).initializer("privacy").build())
        .addProperty(PropertySpec.builder("client", clientClass).initializer("client").build())
        .addProperty(PropertySpec.builder("before", entityClass).initializer("before").build())
        .addProperty(PropertySpec.builder("requestedPatch", patchClass).initializer("requestedPatch").build())
        .addProperty(PropertySpec.builder("effectivePatch", patchClass).initializer("effectivePatch").build())
        .addProperty(PropertySpec.builder("candidate", candidateClass).initializer("candidate").build())
        .addProperty(PropertySpec.builder("edgeChanges", edgeChangesViewClass).initializer("edgeChanges").build())
        .build()

    private fun buildDeleteContext(
        schemaName: String,
        entityClass: ClassName,
        clientClass: ClassName,
        candidateClass: ClassName,
        ctxClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(ctxClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("privacy", PRIVACY_CONTEXT)
                .addParameter("client", clientClass)
                .addParameter("entity", entityClass)
                .addParameter("candidate", candidateClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("privacy", PRIVACY_CONTEXT).initializer("privacy").build())
        .addProperty(PropertySpec.builder("client", clientClass).initializer("client").build())
        .addProperty(PropertySpec.builder("entity", entityClass).initializer("entity").build())
        .addProperty(PropertySpec.builder("candidate", candidateClass).initializer("candidate").build())
        .build()

    private fun buildWriteCandidate(
        schemaName: String,
        candidateClass: ClassName,
        fields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): TypeSpec {
        val ctor = FunSpec.constructorBuilder()
        val props = mutableListOf<PropertySpec>()

        for (field in fields) {
            val propName = toCamelCase(field.name)
            val typeName = field.resolvedTypeName().copy(nullable = field.nullable)
            ctor.addParameter(propName, typeName)
            props.add(PropertySpec.builder(propName, typeName).initializer(propName).build())
        }
        for (fk in edgeFks) {
            val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
            ctor.addParameter(fk.propertyName, typeName)
            props.add(PropertySpec.builder(fk.propertyName, typeName).initializer(fk.propertyName).build())
        }

        if (props.isEmpty()) {
            return TypeSpec.classBuilder(candidateClass).build()
        }
        return TypeSpec.classBuilder(candidateClass)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(ctor.build())
            .addProperties(props)
            .build()
    }

    /**
     * Hook context for `beforeUpdate` hooks. Carries the loaded `before`
     * row, a snapshot of the requested patch accumulated up to this
     * hook, and a restricted writable mutation view.
     * `patch` is a snapshot — writes through `mutation` do not change
     * `patch` within the same hook; later hooks see those writes
     * through their own snapshots.
     *
     * `mutation` is typed as `${schemaName}UpdateMutationView`, which
     * exposes only the field/FK setters and `unset{Field}()` methods.
     * The full update builder's `save()`, the loaded `entity` lateinit,
     * the owner `id`, and the private patch helpers are not visible to
     * hooks — that prevents reentrancy and other out-of-contract use.
     */
    private fun buildUpdateHookContext(
        ctxClass: ClassName,
        clientClass: ClassName,
        entityClass: ClassName,
        patchClass: ClassName,
        mutationClass: ClassName,
        pendingEdgesClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(ctxClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("client", clientClass)
                .addParameter("before", entityClass)
                .addParameter("patch", patchClass)
                .addParameter("pendingEdges", pendingEdgesClass)
                .addParameter("mutation", mutationClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("client", clientClass).initializer("client").build())
        .addProperty(PropertySpec.builder("before", entityClass).initializer("before").build())
        .addProperty(PropertySpec.builder("patch", patchClass).initializer("patch").build())
        .addProperty(PropertySpec.builder("pendingEdges", pendingEdgesClass).initializer("pendingEdges").build())
        .addProperty(PropertySpec.builder("mutation", mutationClass).initializer("mutation").build())
        .build()

    /**
     * Hook context for `beforeCreate` hooks. Carries the restricted
     * writable [mutationClass] view plus the [clientClass] reference for
     * DB queries. Creates have no `before` row and no patch model, so
     * the context is narrower than [buildUpdateHookContext].
     */
    private fun buildCreateHookContext(
        ctxClass: ClassName,
        clientClass: ClassName,
        mutationClass: ClassName,
    ): TypeSpec = TypeSpec.classBuilder(ctxClass)
        .addModifiers(KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("client", clientClass)
                .addParameter("mutation", mutationClass)
                .build(),
        )
        .addProperty(PropertySpec.builder("client", clientClass).initializer("client").build())
        .addProperty(PropertySpec.builder("mutation", mutationClass).initializer("mutation").build())
        .build()

    /**
     * Per-entity update patch type. Each mutable field and edge FK is a
     * `FieldPatch<T>` defaulting to `Unset`. Immutable fields are excluded
     * because the generated update path never writes them. Privacy and
     * validation rules see both the requested patch (caller/hook intent)
     * and the effective patch (after framework update defaults).
     */
    private fun buildUpdatePatch(
        patchClass: ClassName,
        fields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): TypeSpec {
        val ctor = FunSpec.constructorBuilder()
        val props = mutableListOf<PropertySpec>()

        for (field in fields) {
            if (field.immutable) continue
            val propName = toCamelCase(field.name)
            val valueType = field.resolvedTypeName().copy(nullable = field.nullable)
            val patchType = FIELD_PATCH.parameterizedBy(valueType)
            ctor.addParameter(
                ParameterSpec.builder(propName, patchType)
                    .defaultValue("%T.Unset", FIELD_PATCH)
                    .build(),
            )
            props.add(PropertySpec.builder(propName, patchType).initializer(propName).build())
        }
        for (fk in edgeFks) {
            // Immutable FKs can't be patched on update — they're
            // create-only — so omit them from the patch type to mirror
            // the immutable-scalar skip above.
            if (fk.immutable) continue
            val valueType = fk.idType.toTypeName().copy(nullable = !fk.required)
            val patchType = FIELD_PATCH.parameterizedBy(valueType)
            ctor.addParameter(
                ParameterSpec.builder(fk.propertyName, patchType)
                    .defaultValue("%T.Unset", FIELD_PATCH)
                    .build(),
            )
            props.add(PropertySpec.builder(fk.propertyName, patchType).initializer(fk.propertyName).build())
        }

        if (props.isEmpty()) {
            return TypeSpec.classBuilder(patchClass).build()
        }
        return TypeSpec.classBuilder(patchClass)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(ctor.build())
            .addProperties(props)
            .build()
    }

    /**
     * Per-entity aggregator of pending link-table M2M edge ops surfaced
     * read-only to update hooks. One typed
     * `PendingEdgeOps<TargetIdType>` field per helper-eligible edge,
     * defaulting to an empty instance so callers can construct the
     * aggregator with no overrides.
     *
     * For schemas with zero helper-eligible M2M edges, the aggregator
     * is an empty class (`class ${Schema}PendingEdgeOps`) — `data class`
     * doesn't allow zero parameters, but the type still exists so the
     * update hook context can carry a non-null `pendingEdges` field
     * with a uniform shape across entities.
     */
    private fun buildPendingEdgeOpsAggregator(
        aggregatorClass: ClassName,
        helperEligibleEdges: List<HelperEligibleM2M>,
    ): TypeSpec {
        if (helperEligibleEdges.isEmpty()) {
            return TypeSpec.classBuilder(aggregatorClass)
                .primaryConstructor(FunSpec.constructorBuilder().build())
                .build()
        }
        val ctor = FunSpec.constructorBuilder()
        val props = mutableListOf<PropertySpec>()
        for (edge in helperEligibleEdges) {
            val pendingEdgeOpsType = PENDING_EDGE_OPS.parameterizedBy(edge.targetIdTypeName)
            ctor.addParameter(
                ParameterSpec.builder(edge.mutatorPropertyName, pendingEdgeOpsType)
                    .defaultValue("%T()", PENDING_EDGE_OPS)
                    .build(),
            )
            props.add(
                PropertySpec.builder(edge.mutatorPropertyName, pendingEdgeOpsType)
                    .initializer(edge.mutatorPropertyName)
                    .build(),
            )
        }
        return TypeSpec.classBuilder(aggregatorClass)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(ctor.build())
            .addProperties(props)
            .build()
    }

    /**
     * Per-entity aggregator of computed `EdgeChanges<TargetIdType>`
     * surfaced on update privacy and validation contexts. Mirrors
     * [buildPendingEdgeOpsAggregator]'s shape but
     * carries the full [EdgeChanges] (caller intent + computed
     * `added`/`removed` deltas) per edge. Empty class for schemas with
     * zero helper-eligible M2M edges so the context shape is uniform.
     */
    private fun buildEdgeChangesViewAggregator(
        aggregatorClass: ClassName,
        helperEligibleEdges: List<HelperEligibleM2M>,
    ): TypeSpec {
        if (helperEligibleEdges.isEmpty()) {
            return TypeSpec.classBuilder(aggregatorClass)
                .primaryConstructor(FunSpec.constructorBuilder().build())
                .build()
        }
        val ctor = FunSpec.constructorBuilder()
        val props = mutableListOf<PropertySpec>()
        for (edge in helperEligibleEdges) {
            val edgeChangesType = EDGE_CHANGES.parameterizedBy(edge.targetIdTypeName)
            ctor.addParameter(
                ParameterSpec.builder(edge.mutatorPropertyName, edgeChangesType)
                    .defaultValue("%T()", EDGE_CHANGES)
                    .build(),
            )
            props.add(
                PropertySpec.builder(edge.mutatorPropertyName, edgeChangesType)
                    .initializer(edge.mutatorPropertyName)
                    .build(),
            )
        }
        return TypeSpec.classBuilder(aggregatorClass)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(ctor.build())
            .addProperties(props)
            .build()
    }

    private fun buildPrivacyConfig(
        configClass: ClassName,
        loadRuleType: ClassName,
        createRuleType: ClassName,
        updateRuleType: ClassName,
        deleteRuleType: ClassName,
    ): TypeSpec {
        return TypeSpec.classBuilder(configClass)
            .addProperty(
                PropertySpec.builder("loadRules", MUTABLE_LIST.parameterizedBy(loadRuleType))
                    .initializer("mutableListOf()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("createRules", MUTABLE_LIST.parameterizedBy(createRuleType))
                    .initializer("mutableListOf()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("updateRules", MUTABLE_LIST.parameterizedBy(updateRuleType))
                    .initializer("mutableListOf()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("deleteRules", MUTABLE_LIST.parameterizedBy(deleteRuleType))
                    .initializer("mutableListOf()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("updateDerivesFromCreate", Boolean::class)
                    .mutable(true)
                    .initializer("false")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("deleteDerivesFromCreate", Boolean::class)
                    .mutable(true)
                    .initializer("false")
                    .build(),
            )
            .build()
    }

    private fun buildPrivacyScope(
        scopeClass: ClassName,
        configClass: ClassName,
        loadRuleType: ClassName,
        createRuleType: ClassName,
        updateRuleType: ClassName,
        deleteRuleType: ClassName,
    ): TypeSpec {
        return TypeSpec.classBuilder(scopeClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("config", configClass)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("config", configClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("config")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("load")
                    .addParameter("rules", loadRuleType, KModifier.VARARG)
                    .addStatement("config.loadRules.addAll(rules)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("create")
                    .addParameter("rules", createRuleType, KModifier.VARARG)
                    .addStatement("config.createRules.addAll(rules)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("update")
                    .addParameter("rules", updateRuleType, KModifier.VARARG)
                    .addStatement("config.updateRules.addAll(rules)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("delete")
                    .addParameter("rules", deleteRuleType, KModifier.VARARG)
                    .addStatement("config.deleteRules.addAll(rules)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("updateDerivesFromCreate")
                    .addStatement("config.updateDerivesFromCreate = true")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("deleteDerivesFromCreate")
                    .addStatement("config.deleteDerivesFromCreate = true")
                    .build(),
            )
            .build()
    }

    private fun buildPolicyScope(
        schemaName: String,
        policyScopeClass: ClassName,
        privacyScopeClass: ClassName,
        configClass: ClassName,
    ): TypeSpec {
        val validationConfigClass = ClassName(packageName, "${schemaName}ValidationConfig")
        val validationScopeClass = ClassName(packageName, "${schemaName}ValidationScope")
        val privacyBlockLambda = com.squareup.kotlinpoet.LambdaTypeName.get(
            receiver = privacyScopeClass,
            returnType = UNIT,
        )
        val validationBlockLambda = com.squareup.kotlinpoet.LambdaTypeName.get(
            receiver = validationScopeClass,
            returnType = UNIT,
        )
        return TypeSpec.classBuilder(policyScopeClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("privacyConfig", configClass)
                    .addParameter("validationConfig", validationConfigClass)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("privacyConfig", configClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("privacyConfig")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("validationConfig", validationConfigClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("validationConfig")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("privacy")
                    .addParameter("block", privacyBlockLambda)
                    .addStatement("%T(privacyConfig).apply(block)", privacyScopeClass)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("validation")
                    .addParameter("block", validationBlockLambda)
                    .addStatement("%T(validationConfig).apply(block)", validationScopeClass)
                    .build(),
            )
            .build()
    }
}
