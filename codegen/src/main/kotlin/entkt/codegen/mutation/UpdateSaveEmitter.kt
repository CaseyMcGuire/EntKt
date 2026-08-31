package entkt.codegen.mutation

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.apiName
import entkt.codegen.columnName
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.property
import entkt.codegen.kotlinpoet.statement
import entkt.codegen.lifecyclePatchSnapshot
import entkt.codegen.lifecycleValueSnapshot
import entkt.codegen.metadata.EdgeFk
import entkt.codegen.metadata.HelperEligibleM2M
import entkt.codegen.metadata.VIEWER_CONTEXT
import entkt.schema.Field
import entkt.schema.FieldType
import entkt.schema.UpdateDefault

private val FIELD_PATCH = ClassName("entkt.runtime.mutation", "FieldPatch")
private val FIELD_PATCH_OR_ELSE = MemberName("entkt.runtime.mutation", "orElse")
private val RELATIONSHIP_LOCK_KEY = ClassName("entkt.runtime.mutation", "RelationshipLockKey")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val OP_CLASS = ClassName("entkt.query", "Op")
private val UUID_CLASS = ClassName("java.util", "UUID")
private val UPDATE_MUTATION_EXECUTOR =
    ClassName("entkt.runtime.mutation.execution", "UpdateMutationExecutor")
private val UPDATE_MUTATION_REQUEST =
    ClassName("entkt.runtime.mutation", "UpdateMutationRequest")
private val PREPARED_UPDATE =
    ClassName("entkt.runtime.mutation.execution", "PreparedUpdate")
private val UPDATE_PREPARATION =
    ClassName("entkt.runtime.mutation.execution", "UpdatePreparation")
private val UPDATE_PREPARATION_SCOPE =
    ClassName("entkt.runtime.mutation.execution", "UpdatePreparationScope")
private val UPDATE_WRITE_TRACKER =
    ClassName("entkt.runtime.mutation.execution", "UpdateWriteTracker")
private val UPDATE_RELATIONSHIP_REQUIREMENTS =
    ClassName("entkt.runtime.mutation.execution", "UpdateRelationshipRequirements")
private val MUTATION_PRIVACY_EVALUATOR_FACTORY =
    MemberName("entkt.runtime.privacy", "mutationPrivacyEvaluatorForInternalUse")
private val PRIVACY_DECISION_EVALUATOR_FACTORY =
    MemberName("entkt.runtime.privacy", "privacyDecisionEvaluatorForInternalUse")
private val MUTATION_VALIDATION_EVALUATOR_FACTORY =
    MemberName("entkt.runtime.validation", "mutationValidationEvaluatorForInternalUse")
private val VALIDATION_DECISION_EVALUATOR_FACTORY =
    MemberName("entkt.runtime.validation", "validationDecisionEvaluatorForInternalUse")
private val SNAPSHOT_EDGE_CHANGES =
    MemberName("entkt.runtime.mutation", "snapshotEdgeChangesForInternalUse")

/** Schema-specific artifacts around the reusable runtime UPDATE lifecycle. */
internal data class UpdateSaveArtifacts(
    val preparedStateType: TypeSpec,
    val executorProperty: PropertySpec,
    val functions: List<FunSpec>,
)

/** Emits only schema-specific UPDATE adapters; runtime owns lifecycle sequencing. */
internal class UpdateSaveEmitter(
    private val packageName: String,
    private val schemaName: String,
    private val clientName: String,
    private val preparedStateClass: ClassName,
    private val allFields: List<Field>,
    private val edgeFks: List<EdgeFk>,
    private val allEdgeFks: List<EdgeFk>,
    private val helperEligibleEdges: List<HelperEligibleM2M>,
) {
    private val entityClass = ClassName(packageName, schemaName)
    private val queryClass = ClassName(packageName, "${schemaName}Query")
    private val patchClass = ClassName(packageName, "${schemaName}UpdatePatch")
    private val draftClass = ClassName(packageName, "${schemaName}UpdateDraft")
    private val pendingEdgesClass = ClassName(packageName, "${schemaName}PendingEdgeOps")
    private val mutationClass = ClassName(packageName, "${schemaName}Mutation")
    private val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
    private val edgeChangesClass = ClassName(packageName, "${schemaName}EdgeChangesView")
    private val updateHookContextClass = ClassName(packageName, "${schemaName}UpdateHookContext")
    private val mutableFields = allFields.filterNot { it.immutable }

    fun build(): UpdateSaveArtifacts = UpdateSaveArtifacts(
        preparedStateType = buildPreparedStateType(),
        executorProperty = buildExecutorProperty(),
        functions = buildList {
            if (helperEligibleEdges.isNotEmpty()) {
                add(buildRelationshipRequirementsFunction())
            }
            add(buildCapturePendingEdgesFunction())
            add(buildPrepareFunction())
            add(buildRelationshipFunction())
            add(buildExecuteFunction())
        },
    )

    private fun buildPreparedStateType(): TypeSpec {
        val parameters = listOf(
            "before" to entityClass,
            "requestedPatch" to patchClass,
            "effectivePatch" to patchClass,
            "candidate" to candidateClass,
            "edgeChanges" to edgeChangesClass,
        )
        val constructor = FunSpec.constructorBuilder()
        parameters.forEach { (name, type) -> constructor.addParameter(name, type) }
        return TypeSpec.classBuilder(preparedStateClass.simpleName)
            .addModifiers(KModifier.INTERNAL, KModifier.DATA)
            .primaryConstructor(constructor.build())
            .addProperties(
                parameters.map { (name, type) ->
                    PropertySpec.builder(name, type).initializer(name).build()
                },
            )
            .build()
    }

    private fun buildExecutorProperty(): PropertySpec {
        val updateRuleInput = ClassName(packageName, "${schemaName}UpdateRuleInput")
        val createRuleInput = ClassName(packageName, "${schemaName}CreateRuleInput")
        return property(
            "updateExecutor",
            UPDATE_MUTATION_EXECUTOR.parameterizedBy(
                draftClass,
                entityClass,
                pendingEdgesClass,
                preparedStateClass,
                mutationClass,
                updateHookContextClass,
            ),
        ) {
            addModifiers(KModifier.PRIVATE)
            initializer(codeBlock {
                add("%T(\n", UPDATE_MUTATION_EXECUTOR)
                indent()
                add("driver = driver,\n")
                add("mutationRuntime = client,\n")
                add("privacyEvaluator = %M(\n", MUTATION_PRIVACY_EVALUATOR_FACTORY)
                indent()
                add("lifecycle = %S,\n", "$schemaName UPDATE privacy")
                add("unresolvedReason = %S,\n", "no update rule allowed access")
                add("rules = client.%L.privacyConfig.updateRules,\n", clientName)
                add("ruleClientProvider = { client.readOnlyClient },\n")
                add("freshItem = { state: %T -> %T(\n", preparedStateClass, updateRuleInput)
                indent()
                add("%L,\n", lifecycleValueSnapshot("state.before", allFields, entityClass))
                add("%L,\n", lifecyclePatchSnapshot("state.requestedPatch", allFields, entityClass))
                add("%L,\n", lifecyclePatchSnapshot("state.effectivePatch", allFields, entityClass))
                add("%L,\n", lifecycleValueSnapshot("state.candidate", allFields, entityClass))
                add("%L,\n", edgeChangesSnapshot("state.edgeChanges"))
                unindent()
                add(") },\n")
                add("fallback = if (client.%L.privacyConfig.updateDerivesFromCreate) {\n", clientName)
                indent()
                add("%M(\n", PRIVACY_DECISION_EVALUATOR_FACTORY)
                indent()
                add("lifecycle = %S,\n", "$schemaName UPDATE privacy")
                add("rules = client.%L.privacyConfig.createRules,\n", clientName)
                add("ruleClientProvider = { client.readOnlyClient },\n")
                add(
                    "freshItem = { state: %T -> %T(%L) },\n",
                    preparedStateClass,
                    createRuleInput,
                    lifecycleValueSnapshot("state.candidate", allFields, entityClass),
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
                add("lifecycle = %S,\n", "$schemaName UPDATE validation")
                add("rules = client.%L.validationConfig.updateRules,\n", clientName)
                add("ruleClientProvider = { client.readOnlyClient },\n")
                add("freshItem = { state: %T -> %T(\n", preparedStateClass, updateRuleInput)
                indent()
                add("%L,\n", lifecycleValueSnapshot("state.before", allFields, entityClass))
                add("%L,\n", lifecyclePatchSnapshot("state.requestedPatch", allFields, entityClass))
                add("%L,\n", lifecyclePatchSnapshot("state.effectivePatch", allFields, entityClass))
                add("%L,\n", lifecycleValueSnapshot("state.candidate", allFields, entityClass))
                add("%L,\n", edgeChangesSnapshot("state.edgeChanges"))
                unindent()
                add(") },\n")
                add("additional = if (client.%L.validationConfig.updateDerivesFromCreate) {\n", clientName)
                indent()
                add("%M(\n", VALIDATION_DECISION_EVALUATOR_FACTORY)
                indent()
                add("lifecycle = %S,\n", "$schemaName UPDATE validation")
                add("rules = client.%L.validationConfig.createRules,\n", clientName)
                add("ruleClientProvider = { client.readOnlyClient },\n")
                add(
                    "freshItem = { state: %T -> %T(%L) },\n",
                    preparedStateClass,
                    createRuleInput,
                    lifecycleValueSnapshot("state.candidate", allFields, entityClass),
                )
                unindent()
                add(")\n")
                unindent()
                add("} else {\n")
                add("  null\n")
                add("},\n")
                unindent()
                add("),\n")
                add("adapter = this,\n")
                add("hookInputConverter = HookInputConverter(driver, client),\n")
                add("beforeSaveHookRunner = beforeSaveHookRunner,\n")
                add("beforeUpdateHookRunner = beforeUpdateHookRunner,\n")
                add("afterUpdateHookRunner = afterUpdateHookRunner,\n")
                unindent()
                add(")")
            })
        }
    }

    private fun buildRelationshipRequirementsFunction(): FunSpec = function(
        "relationshipRequirements",
        UPDATE_RELATIONSHIP_REQUIREMENTS,
    ) {
        addModifiers(KModifier.OVERRIDE)
        parameter("draft", draftClass)
        statement("val canonicalLockKeys = mutableListOf<%T>()", RELATIONSHIP_LOCK_KEY)
        emitCanonicalRelationshipLockKeys(
            builder = this,
            helperEligibleEdges = helperEligibleEdges,
            receiver = "draft",
            destination = "canonicalLockKeys",
        )
        addCode(codeBlock {
            add("return %T(\n", UPDATE_RELATIONSHIP_REQUIREMENTS)
            indent()
            add("hasPendingWrites = draft._hasPendingLinkTableM2MOps(),\n")
            add("requiresInsertIgnore = draft._hasPendingLinkTableM2MInserts(),\n")
            add("canonicalLockKeys = canonicalLockKeys,\n")
            unindent()
            add(")\n")
        })
    }

    private fun buildCapturePendingEdgesFunction(): FunSpec = function(
        "capturePendingEdges",
        pendingEdgesClass,
    ) {
        addModifiers(KModifier.OVERRIDE)
        parameter("draft", draftClass)
        statement("return draft._buildPendingEdgeOps()")
    }

    private fun buildPrepareFunction(): FunSpec = function(
        "prepare",
        UPDATE_PREPARATION.parameterizedBy(preparedStateClass),
    ) {
        addModifiers(KModifier.OVERRIDE)
        parameter("request", UPDATE_MUTATION_REQUEST.parameterizedBy(draftClass))
        parameter("before", entityClass)
        parameter("pendingEdges", pendingEdgesClass)
        parameter("scope", UPDATE_PREPARATION_SCOPE)
        statement("val draft = request.draft")
        statement("val requiredViolations = draft._checkRequiredNotNull()")
        statement(
            "if (requiredViolations.isNotEmpty()) return·%T.Invalid(requiredViolations)",
            UPDATE_PREPARATION,
        )
        statement("val requestedPatch = draft._buildRequestedPatch(driver)")
        if (helperEligibleEdges.isNotEmpty()) {
            statement("val edgeChanges = scope.driverRead { _buildEdgeChanges(request.id, pendingEdges) }")
        } else {
            statement("val edgeChanges = _buildEdgeChanges(request.id, pendingEdges)")
        }

        val emptyCondition = if (helperEligibleEdges.isEmpty()) {
            "!draft._hasFieldAssignments()"
        } else {
            "!draft._hasFieldAssignments() && !draft._hasPendingLinkTableM2MOps()"
        }
        beginControlFlow("if ($emptyCondition)")
        statement("val effectivePatch = requestedPatch")
        emitCandidateConstruction(this, candidateClass, allFields, allEdgeFks)
        addPreparedReturn(this, isNoOp = true)
        endControlFlow()

        emitEffectivePatchConstruction(this, patchClass, mutableFields, edgeFks)
        mutableFields.filter { it.validators.isNotEmpty() }.forEach {
            emitPatchEntryValidation(this, it)
        }
        edgeFks.filter { it.validators.isNotEmpty() }.forEach {
            emitFkPatchEntryValidation(this, it)
        }
        statement("val values = mutableMapOf<String, Any?>()")
        emitOwnerValues(this)
        emitCandidateConstruction(this, candidateClass, allFields, allEdgeFks)
        addPreparedReturn(this, isNoOp = false)
    }

    private fun addPreparedReturn(builder: FunSpec.Builder, isNoOp: Boolean) {
        builder.addCode(codeBlock {
            add("return %T.Ready(\n", UPDATE_PREPARATION)
            indent()
            add("%T(\n", PREPARED_UPDATE)
            indent()
            add("state = %T(\n", preparedStateClass)
            indent()
            add("before = before,\n")
            add("requestedPatch = requestedPatch,\n")
            add("effectivePatch = effectivePatch,\n")
            add("candidate = candidate,\n")
            add("edgeChanges = edgeChanges,\n")
            unindent()
            add("),\n")
            if (isNoOp) {
                add("values = emptyMap(),\n")
            } else {
                add("values = values,\n")
            }
            add("isNoOp = %L,\n", isNoOp)
            unindent()
            add("),\n")
            unindent()
            add(")\n")
        })
    }

    private fun emitOwnerValues(builder: FunSpec.Builder) {
        for (field in mutableFields) {
            val property = field.apiName
            val column = field.columnName
            when {
                field.type == FieldType.ENUM && field.nullable -> builder.addCode(
                    "(effectivePatch.%L as? %T.Set)?.let { values[%S]·=·it.value?.name }\n",
                    property,
                    FIELD_PATCH,
                    column,
                )
                field.type == FieldType.ENUM -> builder.addCode(
                    "(effectivePatch.%L as? %T.Set)?.let { values[%S]·=·it.value.name }\n",
                    property,
                    FIELD_PATCH,
                    column,
                )
                field.type == FieldType.PGVECTOR -> {
                    val dimensions = (field.storage as? entkt.schema.ColumnStorage.Native)?.dimensions
                        ?: error("pgvector field '${field.apiName}' missing dimensions metadata")
                    val nullableAccess = if (field.nullable) "?" else ""
                    builder.addCode(
                        "(effectivePatch.%L as? %T.Set)?.let { values[%S]·=·" +
                            "it.value$nullableAccess.also { vec -> require(vec.dimensions == %L) { %S } } }\n",
                        property,
                        FIELD_PATCH,
                        column,
                        dimensions,
                        "${field.apiName} expects vector($dimensions)",
                    )
                }
                else -> builder.addCode(
                    "(effectivePatch.%L as? %T.Set)?.let { values[%S]·=·it.value }\n",
                    property,
                    FIELD_PATCH,
                    column,
                )
            }
        }
        for (fk in edgeFks) {
            builder.addCode(
                "(effectivePatch.%L as? %T.Set)?.let { values[%S]·=·it.value }\n",
                fk.propertyName,
                FIELD_PATCH,
                fk.columnName,
            )
        }
    }

    private fun buildRelationshipFunction(): FunSpec = function("persistRelationships") {
        addModifiers(KModifier.OVERRIDE)
        parameter("request", UPDATE_MUTATION_REQUEST.parameterizedBy(draftClass))
        parameter("state", preparedStateClass)
        parameter("writes", UPDATE_WRITE_TRACKER)
        if (helperEligibleEdges.isEmpty()) return@function

        statement("val edgeChanges = state.edgeChanges")
        for (edge in helperEligibleEdges) {
            val property = edge.mutatorPropertyName
            beginControlFlow("if (edgeChanges.%L.added.isNotEmpty())", property)
            beginControlFlow("for (_targetId in edgeChanges.%L.added)", property)
            when (edge.junctionIdStrategy) {
                "CLIENT_UUID" -> statement(
                    "if (driver.insertIgnore(%S, mapOf(%S to %T.randomUUID(), %S to request.id, %S to _targetId), " +
                        "conflictColumns = listOf(%S, %S)) != null) writes.markWritten()",
                    edge.junctionTable,
                    "id",
                    UUID_CLASS,
                    edge.junctionSourceColumn,
                    edge.junctionTargetColumn,
                    edge.junctionSourceColumn,
                    edge.junctionTargetColumn,
                )
                "AUTO_INT", "AUTO_LONG" -> statement(
                    "if (driver.insertIgnore(%S, mapOf(%S to request.id, %S to _targetId), " +
                        "conflictColumns = listOf(%S, %S)) != null) writes.markWritten()",
                    edge.junctionTable,
                    edge.junctionSourceColumn,
                    edge.junctionTargetColumn,
                    edge.junctionSourceColumn,
                    edge.junctionTargetColumn,
                )
                else -> error(
                    "Unexpected junction id strategy '${edge.junctionIdStrategy}' for " +
                        "M2M edge '${edge.edgeName}'",
                )
            }
            endControlFlow()
            endControlFlow()
            beginControlFlow("if (edgeChanges.%L.removed.isNotEmpty())", property)
            statement(
                "if (driver.deleteMany(%S, listOf(%T.Leaf<%T>(%S, %T.EQ, request.id), " +
                    "%T.Leaf<%T>(%S, %T.IN, edgeChanges.%L.removed.toList()))) > 0) writes.markWritten()",
                edge.junctionTable,
                PREDICATE,
                Any::class.asClassName(),
                edge.junctionSourceColumn,
                OP_CLASS,
                PREDICATE,
                Any::class.asClassName(),
                edge.junctionTargetColumn,
                OP_CLASS,
                property,
            )
            endControlFlow()
        }
    }

    private fun buildExecuteFunction(): FunSpec = function(
        "execute",
        MUTATION_RESULT.parameterizedBy(entityClass),
    ) {
        parameter("viewerContext", VIEWER_CONTEXT)
        parameter("request", UPDATE_MUTATION_REQUEST.parameterizedBy(draftClass))
        parameter("applyLoadPrivacy", BOOLEAN)
        addCode(codeBlock {
            add("return updateExecutor.update(\n")
            indent()
            add("viewerContext = viewerContext,\n")
            add("request = request,\n")
            add("entity = %T.GeneratedEntityMapping,\n", queryClass)
            add("applyLoadPrivacy = applyLoadPrivacy,\n")
            unindent()
            add(")\n")
        })
    }

    private fun edgeChangesSnapshot(source: String): CodeBlock {
        if (helperEligibleEdges.isEmpty()) return CodeBlock.of("%L", source)
        return codeBlock {
            add("%L.copy(\n", source)
            helperEligibleEdges.forEach { edge ->
                add(
                    "  %L = %M(%L.%L),\n",
                    edge.mutatorPropertyName,
                    SNAPSHOT_EDGE_CHANGES,
                    source,
                    edge.mutatorPropertyName,
                )
            }
            add(")")
        }
    }
}

private fun emitCanonicalRelationshipLockKeys(
    builder: FunSpec.Builder,
    helperEligibleEdges: List<HelperEligibleM2M>,
    receiver: String,
    destination: String,
) {
    val groups = helperEligibleEdges.groupBy { edge ->
        edge.junctionTable to listOf(edge.junctionSourceColumn, edge.junctionTargetColumn).sorted()
    }
    val orderedKeys = groups.keys.sortedWith(compareBy({ it.first }, { it.second.joinToString(",") }))
    for (key in orderedKeys) {
        val edges = groups.getValue(key)
        val guard = edges.joinToString(" || ") { "$receiver.${it.mutatorPropertyName}.hasOps()" }
        builder.beginControlFlow("if (%L)", guard)
        builder.addStatement(
            "%L += %T.canonical(%S, listOf(%S, %S))",
            destination,
            RELATIONSHIP_LOCK_KEY,
            key.first,
            key.second[0],
            key.second[1],
        )
        builder.endControlFlow()
    }
}

private fun emitEffectivePatchConstruction(
    builder: FunSpec.Builder,
    patchClass: ClassName,
    mutableFields: List<Field>,
    edgeFks: List<EdgeFk>,
) {
    if (mutableFields.none { it.updateDefault != null }) {
        builder.addStatement("val effectivePatch = requestedPatch")
        return
    }
    builder.addCode(codeBlock {
        add("val effectivePatch = %T(\n", patchClass)
        for (field in mutableFields) {
            val property = field.apiName
            if (field.updateDefault == null) {
                add("  %L = requestedPatch.%L,\n", property, property)
            } else {
                add(
                    "  %L = if (requestedPatch.%L is %T.Set) requestedPatch.%L else %T.Set(%L),\n",
                    property,
                    property,
                    FIELD_PATCH,
                    property,
                    FIELD_PATCH,
                    updateDefaultCodeBlock(field),
                )
            }
        }
        edgeFks.forEach { fk ->
            add("  %L = requestedPatch.%L,\n", fk.propertyName, fk.propertyName)
        }
        add(")\n")
    })
}

private fun emitPatchEntryValidation(builder: FunSpec.Builder, field: Field) {
    val property = field.apiName
    val local = "${property}_eff"
    builder.addStatement("val %L = effectivePatch.%L", local, property)
    builder.beginControlFlow("if (%L is %T.Set)", local, FIELD_PATCH)
    builder.addStatement("val %L_v = %L.value", property, local)
    if (field.nullable) builder.beginControlFlow("if (%L_v != null)", property)
    emitFieldValidation(
        builder,
        "${property}_v",
        property,
        field.validators,
        nullable = false,
        invalidPreparationType = UPDATE_PREPARATION,
    )
    if (field.nullable) builder.endControlFlow()
    builder.endControlFlow()
}

private fun emitFkPatchEntryValidation(builder: FunSpec.Builder, fk: EdgeFk) {
    val property = fk.propertyName
    val local = "${property}_eff"
    builder.addStatement("val %L = effectivePatch.%L", local, property)
    builder.beginControlFlow("if (%L is %T.Set)", local, FIELD_PATCH)
    builder.addStatement("val %L_v = %L.value", property, local)
    if (!fk.required) builder.beginControlFlow("if (%L_v != null)", property)
    emitFieldValidation(
        builder,
        "${property}_v",
        property,
        fk.validators,
        nullable = false,
        invalidPreparationType = UPDATE_PREPARATION,
    )
    if (!fk.required) builder.endControlFlow()
    builder.endControlFlow()
}

private fun emitCandidateConstruction(
    builder: FunSpec.Builder,
    candidateClass: ClassName,
    allFields: List<Field>,
    edgeFks: List<EdgeFk>,
) {
    builder.addCode(codeBlock {
        add("val candidate = %T(\n", candidateClass)
        for (field in allFields) {
            val property = field.apiName
            if (field.immutable) {
                add("  %L = before.%L,\n", property, property)
            } else {
                add(
                    "  %L = effectivePatch.%L.%M(before.%L),\n",
                    property,
                    property,
                    FIELD_PATCH_OR_ELSE,
                    property,
                )
            }
        }
        for (fk in edgeFks) {
            if (fk.immutable) {
                add("  %L = before.%L,\n", fk.propertyName, fk.propertyName)
            } else {
                add(
                    "  %L = effectivePatch.%L.%M(before.%L),\n",
                    fk.propertyName,
                    fk.propertyName,
                    FIELD_PATCH_OR_ELSE,
                    fk.propertyName,
                )
            }
        }
        add(")\n")
    })
}

private fun updateDefaultCodeBlock(field: Field): CodeBlock = when (field.updateDefault!!) {
    is UpdateDefault.Now -> {
        require(field.type == FieldType.TIME) {
            "Field '${field.apiName}' has UpdateDefault.Now but type is ${field.type}"
        }
        CodeBlock.of("%T.now()", ClassName("java.time", "Instant"))
    }
}
