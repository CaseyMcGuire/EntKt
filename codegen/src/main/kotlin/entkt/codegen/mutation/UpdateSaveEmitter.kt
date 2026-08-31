package entkt.codegen.mutation

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
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
private val UPDATE_CONSISTENCY = ClassName("entkt.runtime.mutation", "UpdateConsistency")
private val RELATIONSHIP_LOCKING = ClassName("entkt.runtime.mutation", "RelationshipLocking")
private val RELATIONSHIP_LOCK_KEY = ClassName("entkt.runtime.mutation", "RelationshipLockKey")
private val TRANSACTION_REQUIRED_EXCEPTION =
    ClassName("entkt.runtime.mutation", "TransactionRequiredException")
private val UNSUPPORTED_DRIVER_CAPABILITY_EXCEPTION =
    ClassName("entkt.runtime.mutation", "UnsupportedDriverCapabilityException")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val OP_CLASS = ClassName("entkt.query", "Op")
private val UUID_CLASS = ClassName("java.util", "UUID")
private val UPDATE_MUTATION_SPEC =
    ClassName("entkt.runtime.mutation.execution", "UpdateMutationSpec")
private val UPDATE_MUTATION_EXECUTOR =
    ClassName("entkt.runtime.mutation.execution", "UpdateMutationExecutor")
private val PREPARED_UPDATE =
    ClassName("entkt.runtime.mutation.execution", "PreparedUpdate")
private val UPDATE_PREPARATION =
    ClassName("entkt.runtime.mutation.execution", "UpdatePreparation")
private val UPDATE_PREPARATION_SCOPE =
    ClassName("entkt.runtime.mutation.execution", "UpdatePreparationScope")
private val UPDATE_WRITE_TRACKER =
    ClassName("entkt.runtime.mutation.execution", "UpdateWriteTracker")
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
    val specProperty: PropertySpec,
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
    private val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
    private val edgeChangesClass = ClassName(packageName, "${schemaName}EdgeChangesView")
    private val updateHookContextClass = ClassName(packageName, "${schemaName}UpdateHookContext")
    private val mutableFields = allFields.filterNot { it.immutable }

    fun build(): UpdateSaveArtifacts = UpdateSaveArtifacts(
        preparedStateType = buildPreparedStateType(),
        specProperty = buildSpecProperty(),
        executorProperty = buildExecutorProperty(),
        functions = listOf(
            buildPreflightFunction(),
            buildLoadRowFunction(),
            buildBeginFunction(),
            buildEndFunction(),
            buildBeforeFunction(),
            buildPrepareFunction(),
            buildRelationshipFunction(),
            buildExecuteFunction(),
        ),
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
            .addModifiers(KModifier.PRIVATE, KModifier.DATA)
            .primaryConstructor(constructor.build())
            .addProperties(
                parameters.map { (name, type) ->
                    PropertySpec.builder(name, type).initializer(name).build()
                },
            )
            .build()
    }

    private fun buildSpecProperty(): PropertySpec {
        val specType = UPDATE_MUTATION_SPEC.parameterizedBy(
            preparedStateClass,
            entityClass,
        )
        return property("updateSpec", specType) {
            addModifiers(KModifier.PRIVATE)
            initializer(codeBlock {
                add("%T(\n", UPDATE_MUTATION_SPEC)
                indent()
                add("entity = %T.GeneratedEntityMapping,\n", queryClass)
                add("id = id,\n")
                add("preflight = ::_preflightUpdate,\n")
                add("loadRow = ::_loadUpdateRow,\n")
                add("begin = ::_beginUpdate,\n")
                add("end = ::_endUpdate,\n")
                add("before = ::_runBeforeUpdateHooks,\n")
                add("prepare = ::_prepareUpdate,\n")
                add("relationships = ::_persistUpdateRelationships,\n")
                add("afterUpdate = afterUpdateHooks,\n")
                unindent()
                add(")")
            })
        }
    }

    private fun buildExecutorProperty(): PropertySpec {
        val updateRuleInput = ClassName(packageName, "${schemaName}UpdateRuleInput")
        val createRuleInput = ClassName(packageName, "${schemaName}CreateRuleInput")
        return property(
            "updateExecutor",
            UPDATE_MUTATION_EXECUTOR.parameterizedBy(preparedStateClass),
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
                unindent()
                add(")")
            })
        }
    }

    private fun buildPreflightFunction(): FunSpec = function("_preflightUpdate") {
        addModifiers(KModifier.PRIVATE)
        addCode(codeBlock {
            beginControlFlow("if (consistency == %T.Pessimistic)", UPDATE_CONSISTENCY)
            beginControlFlow("if (!driver.inTransaction)")
            addStatement(
                "throw %T(%S)",
                TRANSACTION_REQUIRED_EXCEPTION,
                "$schemaName Pessimistic update requires a transaction-scoped client",
            )
            endControlFlow()
            beginControlFlow("if (!driver.supportsReadRowForUpdate)")
            addStatement(
                "throw %T(%S)",
                UNSUPPORTED_DRIVER_CAPABILITY_EXCEPTION,
                "$schemaName Pessimistic update requires a driver with supportsReadRowForUpdate = true",
            )
            endControlFlow()
            endControlFlow()

            if (helperEligibleEdges.isNotEmpty()) {
                beginControlFlow("if (draft._hasPendingLinkTableM2MOps())")
                beginControlFlow("if (!driver.inTransaction)")
                addStatement(
                    "throw %T(%S)",
                    TRANSACTION_REQUIRED_EXCEPTION,
                    "$schemaName link-table M2M update requires a transaction-scoped client",
                )
                endControlFlow()
                beginControlFlow("if (!driver.supportsReadRowForUpdate && !driver.supportsOwnerEdgeSerialization)")
                addStatement(
                    "throw %T(%S)",
                    UNSUPPORTED_DRIVER_CAPABILITY_EXCEPTION,
                    "$schemaName link-table M2M update requires a driver with " +
                        "supportsReadRowForUpdate or supportsOwnerEdgeSerialization",
                )
                endControlFlow()
                beginControlFlow("if (draft._hasPendingLinkTableM2MInserts() && !driver.supportsInsertIgnore)")
                addStatement(
                    "throw %T(%S)",
                    UNSUPPORTED_DRIVER_CAPABILITY_EXCEPTION,
                    "$schemaName link-table M2M add/set requires a driver with supportsInsertIgnore = true",
                )
                endControlFlow()
                beginControlFlow(
                    "if (relationshipLocking == %T.Canonical && !driver.supportsRelationshipSerialization)",
                    RELATIONSHIP_LOCKING,
                )
                addStatement(
                    "throw %T(%S)",
                    UNSUPPORTED_DRIVER_CAPABILITY_EXCEPTION,
                    "$schemaName relationshipLocking = Canonical requires a driver with " +
                        "supportsRelationshipSerialization = true",
                )
                endControlFlow()
                addStatement("draft._checkLinkTableM2MMixedMode()")
                endControlFlow()
            }
            emitCanonicalRelationshipLocks(this, helperEligibleEdges, receiver = "draft")
        })
    }

    private fun buildLoadRowFunction(): FunSpec {
        val rowType = MAP.parameterizedBy(STRING, ANY.copy(nullable = true)).copy(nullable = true)
        return function("_loadUpdateRow", rowType) {
            addModifiers(KModifier.PRIVATE)
            if (helperEligibleEdges.isNotEmpty()) {
                addCode(codeBlock {
                    beginControlFlow("return if (consistency == %T.Pessimistic)", UPDATE_CONSISTENCY)
                    addStatement("driver.readRowForUpdate(%T.TABLE, id)", entityClass)
                    nextControlFlow("else if (draft._hasPendingLinkTableM2MOps() && driver.supportsReadRowForUpdate)")
                    addStatement("driver.readRowForUpdate(%T.TABLE, id)", entityClass)
                    nextControlFlow("else if (draft._hasPendingLinkTableM2MOps())")
                    addStatement("driver.serializeOwnerEdgeAndRead(%T.TABLE, id)", entityClass)
                    nextControlFlow("else")
                    addStatement("driver.byId(%T.TABLE, id)", entityClass)
                    endControlFlow()
                })
            } else {
                addCode(codeBlock {
                    beginControlFlow("return if (consistency == %T.Pessimistic)", UPDATE_CONSISTENCY)
                    addStatement("driver.readRowForUpdate(%T.TABLE, id)", entityClass)
                    nextControlFlow("else")
                    addStatement("driver.byId(%T.TABLE, id)", entityClass)
                    endControlFlow()
                })
            }
        }
    }

    private fun buildBeginFunction(): FunSpec = function("_beginUpdate") {
        addModifiers(KModifier.PRIVATE)
        statement("_capturedPendingEdges = draft._buildPendingEdgeOps()")
    }

    private fun buildEndFunction(): FunSpec = function("_endUpdate") {
        addModifiers(KModifier.PRIVATE)
        statement("_capturedPendingEdges = null")
    }

    private fun buildBeforeFunction(): FunSpec = function("_runBeforeUpdateHooks") {
        addModifiers(KModifier.PRIVATE)
        parameter("viewerContext", VIEWER_CONTEXT)
        parameter("before", entityClass)
        statement("entity = before")
        statement(
            "val pendingEdges = checkNotNull(_capturedPendingEdges) { %S }",
            "update pending edges were not captured",
        )
        statement("%M(listOf(_beforeSaveView), beforeSaveHooks)", RUN_BATCH_HOOKS_FOR_INTERNAL_USE)
        beginControlFlow("for (hook in beforeUpdateHooks)")
        statement("val snapshot = draft._buildRequestedPatch(driver)")
        statement("val beforeSnapshot = %L", lifecycleValueSnapshot("entity", allFields, entityClass))
        statement(
            "val ctx = %T(client.hookClientScopeForInternalUse, viewerContext, beforeSnapshot, snapshot, pendingEdges, _mutationView)",
            updateHookContextClass,
        )
        statement("%M(listOf(ctx), listOf(hook))", RUN_BATCH_HOOKS_FOR_INTERNAL_USE)
        endControlFlow()
    }

    private fun buildPrepareFunction(): FunSpec = function(
        "_prepareUpdate",
        UPDATE_PREPARATION.parameterizedBy(preparedStateClass),
    ) {
        addModifiers(KModifier.PRIVATE)
        parameter("before", entityClass)
        parameter("scope", UPDATE_PREPARATION_SCOPE)
        statement("entity = before")
        statement(
            "val pendingEdges = checkNotNull(_capturedPendingEdges) { %S }",
            "update pending edges were not captured",
        )
        statement("val requiredViolations = draft._checkRequiredNotNull()")
        statement(
            "if (requiredViolations.isNotEmpty()) return·%T.Invalid(requiredViolations)",
            UPDATE_PREPARATION,
        )
        statement("val requestedPatch = draft._buildRequestedPatch(driver)")
        if (helperEligibleEdges.isNotEmpty()) {
            statement("val edgeChanges = scope.driverRead { _buildEdgeChanges(pendingEdges) }")
        } else {
            statement("val edgeChanges = _buildEdgeChanges(pendingEdges)")
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

    private fun buildRelationshipFunction(): FunSpec = function("_persistUpdateRelationships") {
        addModifiers(KModifier.PRIVATE)
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
                    "if (driver.insertIgnore(%S, mapOf(%S to %T.randomUUID(), %S to id, %S to _targetId), " +
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
                    "if (driver.insertIgnore(%S, mapOf(%S to id, %S to _targetId), " +
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
                "if (driver.deleteMany(%S, listOf(%T.Leaf<%T>(%S, %T.EQ, id), " +
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
        parameter("applyLoadPrivacy", BOOLEAN)
        addCode(codeBlock {
            add("return updateExecutor.update(\n")
            indent()
            add("viewerContext = viewerContext,\n")
            add("applyLoadPrivacy = applyLoadPrivacy,\n")
            add("spec = updateSpec,\n")
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

private fun emitCanonicalRelationshipLocks(
    builder: CodeBlock.Builder,
    helperEligibleEdges: List<HelperEligibleM2M>,
    receiver: String,
) {
    val groups = helperEligibleEdges.groupBy { edge ->
        edge.junctionTable to listOf(edge.junctionSourceColumn, edge.junctionTargetColumn).sorted()
    }
    val orderedKeys = groups.keys.sortedWith(compareBy({ it.first }, { it.second.joinToString(",") }))
    for (key in orderedKeys) {
        val edges = groups.getValue(key)
        val guard = edges.joinToString(" || ") { "$receiver.${it.mutatorPropertyName}.hasOps()" }
        builder.beginControlFlow(
            "if (relationshipLocking == %T.Canonical && (%L))",
            RELATIONSHIP_LOCKING,
            guard,
        )
        builder.addStatement(
            "driver.serializeRelationship(%T.canonical(%S, listOf(%S, %S)))",
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
                add("  %L = entity.%L,\n", property, property)
            } else {
                add(
                    "  %L = effectivePatch.%L.%M(entity.%L),\n",
                    property,
                    property,
                    FIELD_PATCH_OR_ELSE,
                    property,
                )
            }
        }
        for (fk in edgeFks) {
            if (fk.immutable) {
                add("  %L = entity.%L,\n", fk.propertyName, fk.propertyName)
            } else {
                add(
                    "  %L = effectivePatch.%L.%M(entity.%L),\n",
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
