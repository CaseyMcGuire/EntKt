package entkt.codegen.entity

import entkt.codegen.apiName
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.metadata.EdgeFk
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.classType
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.primaryConstructor
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.kotlinpoet.typeAlias
import entkt.codegen.metadata.HelperEligibleM2M
import entkt.codegen.metadata.VIEWER_CONTEXT
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.helperEligibleM2MEdges
import entkt.codegen.metadata.resolvedTypeName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.toTypeName
import entkt.schema.EntSchema
import entkt.schema.Field

private val PRIVACY_RULE = ClassName("entkt.runtime.privacy", "PrivacyRule")
private val BATCH_PRIVACY_RULE = ClassName("entkt.runtime.privacy", "BatchPrivacyRule")
private val ENTITY_POLICY = ClassName("entkt.runtime.privacy", "EntityPolicy")
private val JVM_NAME = ClassName("kotlin.jvm", "JvmName")
private val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
private val FIELD_PATCH = ClassName("entkt.runtime.mutation", "FieldPatch")
private val PENDING_EDGE_OPS = ClassName("entkt.runtime.mutation", "PendingEdgeOps")
private val UPDATE_PENDING_EDGES =
    ClassName("entkt.runtime.mutation", "UpdatePendingEdges")
private val EDGE_CHANGES = ClassName("entkt.runtime.mutation", "EdgeChanges")
private val PRIVACY_ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")
private val RESOLVED_ENTITY_PRIVACY_CONFIG =
    ClassName("entkt.runtime.privacy", "ResolvedEntityPrivacyConfig")

/**
 * Emits per-entity privacy infrastructure:
 *
 * - `{Entity}PrivacyConfig` — internal mutable config holding rule lists
 * - `{Entity}PrivacyScope` — DSL scope for declaring rules per operation
 * - `{Entity}PolicyScope` — outer scope passed to [EntityPolicy.configure]
 * - `{Entity}WriteCandidate` — snapshot of writable fields for write rules
 * - `{Entity}{Op}PrivacyRule` and `{Entity}{Op}BatchPrivacyRule` — typealiases for each operation's rule types
 *
 * CREATE, UPDATE, and DELETE rules share the lifecycle inputs emitted by
 * `LifecycleRuleInputGenerator` with validation rules.
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
        // Privacy rule contexts get the stable read-only client plus the caller's explicit
        // viewerContext — rule writes are compile errors, and nested rule reads
        // pass that context to their terminals. The concrete type makes the
        // viewer-scoped read posture visible in helper signatures.
        val readClientClass = ClassName(packageName, "ReadOnlyEntClient")
        val configClass = ClassName(packageName, "${schemaName}PrivacyConfig")
        val privacyScopeClass = ClassName(packageName, "${schemaName}PrivacyScope")
        val policyScopeClass = ClassName(packageName, "${schemaName}PolicyScope")
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        val patchClass = ClassName(packageName, "${schemaName}UpdatePatch")
        val pendingEdgeOpsClass = ClassName(packageName, "${schemaName}PendingEdgeOps")
        val edgeChangesViewClass = ClassName(packageName, "${schemaName}EdgeChangesView")

        // Backing FK columns flow through `edgeFks` for write candidates
        // and update patches so their type/nullability come from the
        // relationship, not the scalar field declaration.
        val fields = scalarFields(schema)
        val edgeFks = computeEdgeFks(schema, schemaNames)

        // Helper-eligible link-table M2M edges. Each
        // contributes a typed `PendingEdgeOps<ID>` field on the per-entity
        // aggregator surfaced through the before-update hook state. Empty
        // list → empty aggregator (uniform hook-state shape across entities).
        val helperEligibleEdges = helperEligibleM2MEdges(schema, schemaNames)

        // Operation input class names. The runtime PrivacyRuleContext holds
        // privacy/client state once for the whole evaluation phase.
        val updateInput = ClassName(packageName, "${schemaName}UpdateRuleInput")
        val deleteInput = ClassName(packageName, "${schemaName}DeleteRuleInput")

        // Rule typealiases
        val loadRule = "${schemaName}LoadPrivacyRule"
        val createRule = "${schemaName}CreatePrivacyRule"
        val updateRule = "${schemaName}UpdatePrivacyRule"
        val deleteRule = "${schemaName}DeletePrivacyRule"
        val loadBatchRule = "${schemaName}LoadBatchPrivacyRule"
        val createBatchRule = "${schemaName}CreateBatchPrivacyRule"
        val updateBatchRule = "${schemaName}UpdateBatchPrivacyRule"
        val deleteBatchRule = "${schemaName}DeleteBatchPrivacyRule"

        return kotlinFile(packageName, "${schemaName}Privacy") {
            typeAlias(loadRule, PRIVACY_RULE.parameterizedBy(readClientClass, entityClass)) {
                addKdoc(
                    "LOAD rules receive the original entity without defensive copies.\n" +
                        "Treat the entity and all nested values as read-only.\n",
                )
            }
            typeAlias(createRule, PRIVACY_RULE.parameterizedBy(readClientClass, candidateClass)) {
                addKdoc(
                    "CREATE rules receive the prepared write candidate without per-rule defensive copies.\n" +
                        "Treat the candidate and all nested values as read-only.\n",
                )
            }
            typeAlias(updateRule, PRIVACY_RULE.parameterizedBy(readClientClass, updateInput))
            typeAlias(deleteRule, PRIVACY_RULE.parameterizedBy(readClientClass, deleteInput))
            typeAlias(loadBatchRule, BATCH_PRIVACY_RULE.parameterizedBy(readClientClass, entityClass)) {
                addKdoc(
                    "Batch LOAD rules receive the original entities without defensive copies.\n" +
                        "Treat the entities and all nested values as read-only.\n",
                )
            }
            typeAlias(createBatchRule, BATCH_PRIVACY_RULE.parameterizedBy(readClientClass, candidateClass)) {
                addKdoc(
                    "Batch CREATE rules receive prepared write candidates without per-rule defensive copies.\n" +
                        "Treat the candidates and all nested values as read-only.\n",
                )
            }
            typeAlias(updateBatchRule, BATCH_PRIVACY_RULE.parameterizedBy(readClientClass, updateInput))
            typeAlias(deleteBatchRule, BATCH_PRIVACY_RULE.parameterizedBy(readClientClass, deleteInput))

        // WriteCandidate
            addType(buildWriteCandidate(candidateClass, fields, edgeFks))

        // UpdatePatch
            addType(buildUpdatePatch(patchClass, fields, edgeFks))

        // PendingEdgeOps aggregator. One typed
        // `PendingEdgeOps<TargetIdType>` per helper-eligible M2M edge,
        // exposed read-only on the before-update hook state. Schemas without
        // helper-eligible M2M edges still
        // get a type — a no-fields class — so hook authors can write
        // `ctx.pendingEdges` without entity-conditional types.
            addType(
                buildPendingEdgeOpsAggregator(
                    pendingEdgeOpsClass,
                    entityClass,
                    helperEligibleEdges,
                ),
            )

        // EdgeChangesView aggregator.
        // One typed `EdgeChanges<TargetIdType>` per helper-eligible M2M
        // edge — the privacy/validation sidecar that surfaces both
        // caller intent and computed database delta. Same empty-class
        // fallback for schemas without helper-eligible edges so the
        // privacy/validation item shape is uniform.
            addType(buildEdgeChangesViewAggregator(edgeChangesViewClass, helperEligibleEdges))

        // PrivacyConfig
            addType(
            buildPrivacyConfig(
                configClass,
                ClassName(packageName, loadBatchRule),
                ClassName(packageName, createBatchRule),
                ClassName(packageName, updateBatchRule),
                ClassName(packageName, deleteBatchRule),
            ),
            )

        // PrivacyScope
            addType(
            buildPrivacyScope(
                privacyScopeClass,
                configClass,
                ClassName(packageName, loadRule),
                ClassName(packageName, createRule),
                ClassName(packageName, updateRule),
                ClassName(packageName, deleteRule),
                ClassName(packageName, loadBatchRule),
                ClassName(packageName, createBatchRule),
                ClassName(packageName, updateBatchRule),
                ClassName(packageName, deleteBatchRule),
            ),
            )

        // PolicyScope
            addType(buildPolicyScope(schemaName, policyScopeClass, privacyScopeClass, configClass))
        }
    }

    /** Immutable generated value whose constructor parameters are also properties. */
    private fun immutableValueType(
        type: ClassName,
        members: List<Pair<String, com.squareup.kotlinpoet.TypeName>>,
    ): TypeSpec = classType(type) {
        if (members.isNotEmpty()) {
            addModifiers(KModifier.DATA)
            primaryConstructor {
                for ((name, memberType) in members) parameter(name, memberType)
            }
            for ((name, memberType) in members) {
                property(name, memberType) { initializer(name) }
            }
        }
    }

    private fun buildWriteCandidate(
        candidateClass: ClassName,
        fields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): TypeSpec {
        val members = buildList {
            fields.forEach { field ->
                add(field.apiName to field.resolvedTypeName().copy(nullable = field.nullable))
            }
            edgeFks.forEach { fk ->
                add(fk.propertyName to fk.idType.toTypeName().copy(nullable = !fk.required))
            }
        }
        return immutableValueType(candidateClass, members)
    }

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
        val members = buildList {
            for (field in fields) {
            if (field.immutable) continue
            val propName = field.apiName
            val valueType = field.resolvedTypeName().copy(nullable = field.nullable)
            val patchType = FIELD_PATCH.parameterizedBy(valueType)
                add(propName to patchType)
            }
            for (fk in edgeFks) {
            // Immutable FKs can't be patched on update — they're
            // create-only — so omit them from the patch type to mirror
            // the immutable-scalar skip above.
            if (fk.immutable) continue
            val valueType = fk.idType.toTypeName().copy(nullable = !fk.required)
            val patchType = FIELD_PATCH.parameterizedBy(valueType)
                add(fk.propertyName to patchType)
            }
        }
        if (members.isEmpty()) return classType(patchClass) {}
        return classType(patchClass) {
            addModifiers(KModifier.DATA)
            primaryConstructor {
                for ((name, type) in members) {
                    parameter(name, type) { defaultValue("%T.Unset", FIELD_PATCH) }
                }
            }
            for ((name, type) in members) {
                property(name, type) { initializer(name) }
            }
        }
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
     * before-update hook state can carry a non-null `pendingEdges` field
     * with a uniform shape across entities.
     */
    private fun buildPendingEdgeOpsAggregator(
        aggregatorClass: ClassName,
        entityClass: ClassName,
        helperEligibleEdges: List<HelperEligibleM2M>,
    ): TypeSpec {
        if (helperEligibleEdges.isEmpty()) {
            return classType(aggregatorClass) {
                addSuperinterface(UPDATE_PENDING_EDGES.parameterizedBy(entityClass))
                primaryConstructor {}
            }
        }
        return classType(aggregatorClass) {
            addModifiers(KModifier.DATA)
            addSuperinterface(UPDATE_PENDING_EDGES.parameterizedBy(entityClass))
            primaryConstructor {
                for (edge in helperEligibleEdges) {
                    parameter(
                        edge.mutatorPropertyName,
                        PENDING_EDGE_OPS.parameterizedBy(edge.targetIdTypeName),
                    ) { defaultValue("%T()", PENDING_EDGE_OPS) }
                }
            }
            for (edge in helperEligibleEdges) {
                property(
                    edge.mutatorPropertyName,
                    PENDING_EDGE_OPS.parameterizedBy(edge.targetIdTypeName),
                ) { initializer(edge.mutatorPropertyName) }
            }
        }
    }

    /**
     * Per-entity aggregator of computed `EdgeChanges<TargetIdType>`
     * surfaced on update privacy and validation items. Mirrors
     * [buildPendingEdgeOpsAggregator]'s shape but
     * carries the full [EdgeChanges] (caller intent + computed
     * `added`/`removed` deltas) per edge. Empty class for schemas with
     * zero helper-eligible M2M edges so the item shape is uniform.
     */
    private fun buildEdgeChangesViewAggregator(
        aggregatorClass: ClassName,
        helperEligibleEdges: List<HelperEligibleM2M>,
    ): TypeSpec {
        if (helperEligibleEdges.isEmpty()) {
            return classType(aggregatorClass) { primaryConstructor {} }
        }
        return classType(aggregatorClass) {
            addModifiers(KModifier.DATA)
            primaryConstructor {
                for (edge in helperEligibleEdges) {
                    parameter(
                        edge.mutatorPropertyName,
                        EDGE_CHANGES.parameterizedBy(edge.targetIdTypeName),
                    ) { defaultValue("%T()", EDGE_CHANGES) }
                }
            }
            for (edge in helperEligibleEdges) {
                property(
                    edge.mutatorPropertyName,
                    EDGE_CHANGES.parameterizedBy(edge.targetIdTypeName),
                ) { initializer(edge.mutatorPropertyName) }
            }
        }
    }

    private fun buildPrivacyConfig(
        configClass: ClassName,
        loadRuleType: ClassName,
        createRuleType: ClassName,
        updateRuleType: ClassName,
        deleteRuleType: ClassName,
    ): TypeSpec {
        return classType(configClass) {
            for ((name, ruleType) in listOf(
                "loadRules" to loadRuleType,
                "createRules" to createRuleType,
                "updateRules" to updateRuleType,
                "deleteRules" to deleteRuleType,
            )) {
                property(name, MUTABLE_LIST.parameterizedBy(ruleType)) {
                    initializer("mutableListOf()")
                }
            }
            for (name in listOf("updateDerivesFromCreate", "deleteDerivesFromCreate")) {
                property(name, Boolean::class.asClassName()) {
                    mutable(true)
                    initializer("false")
                }
            }
            val resolvedType = RESOLVED_ENTITY_PRIVACY_CONFIG.parameterizedBy(
                loadRuleType,
                createRuleType,
                updateRuleType,
                deleteRuleType,
            )
            function("resolveForInternalUse", resolvedType) {
                addAnnotation(PRIVACY_ENTKT_INTERNAL)
                addModifiers(KModifier.INTERNAL)
                statement(
                    "return %T(\n" +
                        "  loadRules = loadRules,\n" +
                        "  createRules = createRules,\n" +
                        "  updateRules = updateRules,\n" +
                        "  deleteRules = deleteRules,\n" +
                        "  updateDerivesFromCreate = updateDerivesFromCreate,\n" +
                        "  deleteDerivesFromCreate = deleteDerivesFromCreate,\n" +
                        ")",
                    resolvedType,
                )
            }
        }
    }

    private fun buildPrivacyScope(
        scopeClass: ClassName,
        configClass: ClassName,
        loadRuleType: ClassName,
        createRuleType: ClassName,
        updateRuleType: ClassName,
        deleteRuleType: ClassName,
        loadBatchRuleType: ClassName,
        createBatchRuleType: ClassName,
        updateBatchRuleType: ClassName,
        deleteBatchRuleType: ClassName,
    ): TypeSpec {
        return classType(scopeClass) {
            primaryConstructor {
                addModifiers(KModifier.INTERNAL)
                parameter("config", configClass)
            }
            property("config", configClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("config")
            }
            addRuleFunctions("load", loadRuleType, loadBatchRuleType)
            addRuleFunctions("create", createRuleType, createBatchRuleType)
            addRuleFunctions("update", updateRuleType, updateBatchRuleType)
            addRuleFunctions("delete", deleteRuleType, deleteBatchRuleType)
            function("updateDerivesFromCreate") {
                statement("config.updateDerivesFromCreate = true")
            }
            function("deleteDerivesFromCreate") {
                statement("config.deleteDerivesFromCreate = true")
            }
        }
    }

    /** Emit scalar and batch-rule overloads for one privacy operation. */
    private fun TypeSpec.Builder.addRuleFunctions(
        operation: String,
        ruleType: ClassName,
        batchRuleType: ClassName,
    ) {
        function(operation) {
            parameter("rules", ruleType) { addModifiers(KModifier.VARARG) }
            statement("config.%LRules.addAll(rules)", operation)
        }
        function(operation) {
            addAnnotation(annotation(JVM_NAME) { addMember("%S", "${operation}BatchRule") })
            parameter("rule", batchRuleType)
            statement("config.%LRules.add(rule)", operation)
        }
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
        return classType(policyScopeClass) {
            primaryConstructor {
                addModifiers(KModifier.INTERNAL)
                parameter("privacyConfig", configClass)
                parameter("validationConfig", validationConfigClass)
            }
            property("privacyConfig", configClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("privacyConfig")
            }
            property("validationConfig", validationConfigClass) {
                addModifiers(KModifier.PRIVATE)
                initializer("validationConfig")
            }
            function("privacy") {
                parameter("block", privacyBlockLambda)
                statement("%T(privacyConfig).apply(block)", privacyScopeClass)
            }
            function("validation") {
                parameter("block", validationBlockLambda)
                statement("%T(validationConfig).apply(block)", validationScopeClass)
            }
        }
    }
}
