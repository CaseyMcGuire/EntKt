package entkt.codegen.mutation

import entkt.codegen.apiName
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.columnName
import entkt.codegen.kotlinpoet.codeBlock
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
private val TRANSACTION_REQUIRED_EXCEPTION = ClassName("entkt.runtime.mutation", "TransactionRequiredException")
private val UNSUPPORTED_DRIVER_CAPABILITY_EXCEPTION = ClassName("entkt.runtime.mutation", "UnsupportedDriverCapabilityException")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val OP_CLASS = ClassName("entkt.query", "Op")
private val UUID_CLASS = ClassName("java.util", "UUID")

/**
 * Emits the generated private `executeSave(applyLoadPrivacy)` member —
 * the single update execution pipeline both public terminals
 * (`save()` / `saveAndLoad()`) project. One emitter per generate()
 * call: the constructor captures the naming and field context every
 * phase needs, and [build] runs the phase emitters in save order.
 * Each phase appends to the shared [builder]; the phase sequence in
 * [build] IS the save pipeline, so reordering calls changes the
 * generated semantics — including which [entkt.runtime.result.MutationWriteState]
 * a failure at each position reports. The `_capturedPendingEdges`
 * try/finally bracket and the whole-terminal capture boundary are
 * emitted by [build] itself, so every phase emitter is locally
 * balanced KotlinPoet control flow.
 *
 * Failure classification is positional (see the RFC's capture
 * boundary): decision-returning privacy/validation evaluators produce
 * the typed exceptions; driver-call exceptions route through
 * `_classifyDriverFailure` with a phase-derived fallback; every other
 * exception — hooks, rule bodies, materialization, preflight throws —
 * is foreign and becomes `EntUnexpectedMutationException` with the
 * current `writeState` at the terminal boundary.
 */
internal class UpdateSaveEmitter(
    private val packageName: String,
    private val schemaName: String,
    private val clientName: String,
    private val allFields: List<Field>,
    private val edgeFks: List<EdgeFk>,
    private val allEdgeFks: List<EdgeFk>,
    private val helperEligibleEdges: List<HelperEligibleM2M>,
) {
    private val entityClass = ClassName(packageName, schemaName)
    private val patchClass = ClassName(packageName, "${schemaName}UpdatePatch")
    private val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
    private val updateHookCtxClass = ClassName(packageName, "${schemaName}UpdateHookContext")
    private val repoPropName = clientName
    private val mutableFields = allFields.filter { !it.immutable }

    private val builder = FunSpec.builder("executeSave")
        .addModifiers(KModifier.PRIVATE)
        .addParameter("viewerContext", VIEWER_CONTEXT)
        .addParameter("applyLoadPrivacy", BOOLEAN)
        .returns(MUTATION_RESULT.parameterizedBy(entityClass))

    fun build(): FunSpec {
        // ---- Write-state phase local + whole-terminal boundary. The
        // var starts NotPersisted, flips only after the save's SQL has
        // executed, and is what the final catch reports for foreign
        // exceptions — so a hook thrown before the write reports
        // NotPersisted while an afterUpdate hook thrown after an
        // autocommit write reports Committed. ----
        builder.addStatement("var writeState = %T.NotPersisted", MUTATION_WRITE_STATE)
        builder.beginControlFlow("try")
        // Posture snapshot BEFORE persistence but INSIDE the terminal
        // boundary — see CreateGenerator.
        builder.addStatement(
            "val postWriteState = if (driver.inTransaction) %T.TransactionPending else %T.Committed",
            MUTATION_WRITE_STATE, MUTATION_WRITE_STATE,
        )
        emitPreflight()
        emitOwnerRowLoad()
        // ---- Pending edge ops snapshot. Captured once
        // after the owner-row read and before any hook fires. The
        // underlying op log is read-only to hooks (the mutator surface
        // is not on the hook-facing view), so a single snapshot is
        // stable across the whole hook block. Surfaced to update hooks
        // as `ctx.pendingEdges` and also reachable through
        // `ctx.mutation.pendingEdges` — both routed through the same
        // captured value so the two views are object-
        // identity equal, not just structurally equal. ----
        builder.addStatement("val pendingEdges = _buildPendingEdgeOps()")
        // Wrap the post-assignment region
        // in try/finally so every save exit path (typed Failed returns,
        // the no-op Success return, the final Success return, or any
        // exception out of hooks/privacy/validation/driver writes)
        // clears _capturedPendingEdges. A hook that stashes
        // ctx.mutation can then no longer read pendingEdges after the
        // save returns — the adapter's getter throws the "accessed
        // outside a save()" error consistently with its documented
        // contract. The bracket is emitted here, not split across
        // phase emitters, so every emitter below is locally balanced
        // and can be read — or modified — on its own.
        builder.beginControlFlow("try")
        builder.addStatement("_capturedPendingEdges = pendingEdges")
        emitHooks()
        emitPatchConstruction()
        emitPrivacyAndValidation()
        emitOwnerWrite()
        emitJunctionWrites()
        emitAfterHooksAndReturn()
        builder.nextControlFlow("finally")
        builder.addStatement("_capturedPendingEdges = null")
        builder.endControlFlow()
        // ---- Whole-terminal capture boundary: rethrow cancellation,
        // wrap every other ordinary exception as foreign with the
        // current phase state, record on the coordinator, and never
        // catch Throwable (JVM Errors propagate). ----
        builder.nextControlFlow("catch (e: %T)", MUTATION_CANCELLATION_EXCEPTION)
        builder.addStatement("throw e")
        builder.nextControlFlow("catch (e: %T)", KOTLIN_EXCEPTION)
        builder.addCode(
            recordAndReturnFailure(
                CodeBlock.of("%T(writeState, e)", ENT_UNEXPECTED_MUTATION_EXCEPTION),
            ),
        )
        builder.endControlFlow()
        return builder.build()
    }

    /**
     * Preflights, in pipeline order: the transaction requirement,
     * Pessimistic capability checks, the M2M capability + mixed-mode
     * checks, and Canonical relationship lock acquisition. Everything
     * here fires before the owner-row read. Every preflight throw
     * (TransactionRequiredException, UnsupportedDriverCapabilityException,
     * the mixed-mode IllegalStateException) is captured by the
     * terminal boundary as EntUnexpectedMutationException(NotPersisted)
     * — deliberately reversing the old propagate contract.
     *
     * There is no syntactically-empty NoChanges preflight anymore: an
     * assignment-free update proceeds to the owner load so it can
     * report target absence, runs the pre-write phases, and completes
     * as Success without persisting (see emitPatchConstruction's empty
     * branch).
     */
    private fun emitPreflight() {
        // ---- Transaction-requirement preflight. Fires before the
        // owner-row load, hooks, defaults, validation, driver writes
        // when the configured TransactionRequirement isn't satisfied. ----
        builder.addStatement("client.checkTransactionRequirement(%S)", "$schemaName update")

        // ---- Pessimistic preflight: require a transaction
        // and a driver with true row-lock support. Both rejections
        // fire before the owner-row load, hooks, privacy, validation,
        // or driver writes. ----
        builder.beginControlFlow(
            "if (consistency == %T.Pessimistic)",
            UPDATE_CONSISTENCY,
        )
        builder.beginControlFlow("if (!driver.inTransaction)")
        builder.addStatement(
            "throw %T(%S)",
            TRANSACTION_REQUIRED_EXCEPTION,
            "$schemaName Pessimistic update requires a transaction-scoped client",
        )
        builder.endControlFlow()
        builder.beginControlFlow("if (!driver.supportsReadRowForUpdate)")
        builder.addStatement(
            "throw %T(%S)",
            UNSUPPORTED_DRIVER_CAPABILITY_EXCEPTION,
            "$schemaName Pessimistic update requires a driver with supportsReadRowForUpdate = true",
        )
        builder.endControlFlow()
        builder.endControlFlow()

        // ---- M2M preflight. When the schema has any
        // helper-eligible link-table M2M edge AND the caller has staged
        // ops on at least one of them, require a transaction-scoped
        // client and a driver that supports either true row-lock or
        // cooperative owner-edge serialization. After both pass, the
        // defense-in-depth mixed-mode check re-runs the per-call rule
        // against the captured op log. Order matters: a missing
        // transaction surfaces TransactionRequiredException first, not
        // IllegalStateException from a corrupted mixed-mode state. ----
        if (helperEligibleEdges.isNotEmpty()) {
            builder.beginControlFlow("if (_hasPendingLinkTableM2MOps())")
            builder.beginControlFlow("if (!driver.inTransaction)")
            builder.addStatement(
                "throw %T(%S)",
                TRANSACTION_REQUIRED_EXCEPTION,
                "$schemaName link-table M2M update requires a transaction-scoped client",
            )
            builder.endControlFlow()
            builder.beginControlFlow("if (!driver.supportsReadRowForUpdate && !driver.supportsOwnerEdgeSerialization)")
            builder.addStatement(
                "throw %T(%S)",
                UNSUPPORTED_DRIVER_CAPABILITY_EXCEPTION,
                "$schemaName link-table M2M update requires a driver with " +
                    "supportsReadRowForUpdate or supportsOwnerEdgeSerialization",
            )
            builder.endControlFlow()
            // Junction inserts go through driver.insertIgnore for
            // idempotency, so a save that stages any add/set needs the
            // insertIgnore capability. Remove-only saves don't insert and are
            // exempt — hence the gate on _hasPendingLinkTableM2MInserts()
            // rather than _hasPendingLinkTableM2MOps().
            builder.beginControlFlow("if (_hasPendingLinkTableM2MInserts() && !driver.supportsInsertIgnore)")
            builder.addStatement(
                "throw %T(%S)",
                UNSUPPORTED_DRIVER_CAPABILITY_EXCEPTION,
                "$schemaName link-table M2M add/set requires a driver with supportsInsertIgnore = true",
            )
            builder.endControlFlow()
            // Opting into Canonical relationship locking needs a driver
            // that can take the cross-orientation relationship lock. Checked
            // here (before any read/write) so the rejection is never racy.
            builder.beginControlFlow(
                "if (relationshipLocking == %T.Canonical && !driver.supportsRelationshipSerialization)",
                RELATIONSHIP_LOCKING,
            )
            builder.addStatement(
                "throw %T(%S)",
                UNSUPPORTED_DRIVER_CAPABILITY_EXCEPTION,
                "$schemaName relationshipLocking = Canonical requires a driver with " +
                    "supportsRelationshipSerialization = true",
            )
            builder.endControlFlow()
            builder.addStatement("_checkLinkTableM2MMixedMode()")
            builder.endControlFlow()
        }

        // ---- Canonical relationship lock acquisition. When
        // `relationshipLocking == Canonical`, take a cross-orientation lock on
        // every distinct link-table relationship the pending ops touch — keyed
        // by the junction + sorted FK pair, so both orientations contend on the
        // same key. Acquired in ascending canonical-key order (junction table,
        // then sorted FK columns) so a multi-relationship save can't deadlock
        // against a differently-ordered one.
        //
        // Crucially this runs BEFORE the owner-row read below. The owner read
        // takes a `SELECT ... FOR UPDATE` (or owner-edge serialization) on the
        // owner row, and the *opposite* orientation's junction insert FK-checks
        // that very row — so acquiring the owner lock first would let two
        // opposite saves deadlock (owner-lock-then-relationship-lock vs
        // relationship-lock-then-FK-share-lock). Taking the relationship lock
        // first means a contending save holds nothing while it waits for it. ----
        emitCanonicalRelationshipLocks(builder, helperEligibleEdges)
    }

    private fun emitOwnerRowLoad() {
        // ---- Internal current-row load. The primitive choice is per
        // driver, not per consistency mode:
        //
        //   - Pessimistic                                 → readRowForUpdate (true row lock)
        //   - ReadCurrent + M2M pending + RRFU            → readRowForUpdate
        //   - ReadCurrent + M2M pending + !RRFU + OES     → serializeOwnerEdgeAndRead
        //   - ReadCurrent + no M2M                        → byId (no lock)
        //
        // RRFU = supportsReadRowForUpdate, OES = supportsOwnerEdgeSerialization.
        // The M2M preflight above already rejected the case where
        // neither capability is supported when M2M ops are pending, so
        // reaching the else-else branch is safe even without an
        // explicit OES check (we just call serializeOwnerEdgeAndRead).
        //
        // All four paths bypass LOAD privacy. The read is wrapped in
        // the driver-call classification catch with the pre-write
        // NotPersisted fallback; a missing row is
        // Failed(EntTargetAbsentException) — the empty update must
        // still establish whether its target exists — and
        // short-circuits before hooks/privacy/validation run. ----
        val head = codeBlock {
            add("val row0 = try {\n")
            if (helperEligibleEdges.isNotEmpty()) {
                add("  if (consistency == %T.Pessimistic) {\n", UPDATE_CONSISTENCY)
                add("    driver.readRowForUpdate(%T.TABLE, id)\n", entityClass)
                add("  } else if (_hasPendingLinkTableM2MOps() && driver.supportsReadRowForUpdate) {\n")
                add("    driver.readRowForUpdate(%T.TABLE, id)\n", entityClass)
                add("  } else if (_hasPendingLinkTableM2MOps()) {\n")
                add("    driver.serializeOwnerEdgeAndRead(%T.TABLE, id)\n", entityClass)
                add("  } else {\n")
                add("    driver.byId(%T.TABLE, id)\n", entityClass)
                add("  }\n")
            } else {
                // No helper-eligible M2M edges → keep the existing two-way
                // branch unchanged so non-M2M schemas pay no new branches.
                add("  if (consistency == %T.Pessimistic) {\n", UPDATE_CONSISTENCY)
                add("    driver.readRowForUpdate(%T.TABLE, id)\n", entityClass)
                add("  } else {\n")
                add("    driver.byId(%T.TABLE, id)\n", entityClass)
                add("  }\n")
            }
            add(driverCallFailureTail("NotPersisted"))
        }
        builder.addCode(head)
        builder.beginControlFlow("if (row0 == null)")
        builder.addCode(
            recordAndReturnFailure(
                CodeBlock.of(
                    "%T(%S, %T(%S, id))",
                    ENT_TARGET_ABSENT_EXCEPTION, schemaName, MUTATION_ENTITY_KEY, "id",
                ),
            ),
        )
        builder.endControlFlow()
        builder.addStatement("entity = %T.fromRow(row0)", entityClass)
    }

    private fun emitHooks() {
        // ---- beforeSave hooks (shared with create — receive Mutation interface). ----
        // Pass the restricted `_beforeSaveView` adapter which
        // implements ONLY ${Schema}Mutation. Three runtime narrowing
        // properties hold:
        //   - `mutation as ${Schema}Update` fails (the original cast
        //     attack — reach the public tags mutator after the
        //     pendingEdges snapshot was captured),
        //   - `mutation as ${Schema}UpdateMutationView` fails (the
        //     residual cast — call `unsetTitle()` to silently drop
        //     a caller's patch entry, or read `pendingEdges`),
        //   - any other concrete narrowing fails.
        // beforeSave hooks see exactly the shared write surface they
        // were contracted for. beforeUpdate hooks continue to receive
        // `_mutationView` via the hook context, which is the
        // expected surface for patch-clearing and pending-edges
        // inspection. Hook-thrown exceptions are FOREIGN — the
        // terminal boundary wraps them with the current (pre-write)
        // state regardless of the thrown runtime type.
        builder.addStatement("%M(listOf(_beforeSaveView), beforeSaveHooks)", RUN_BATCH_HOOKS_FOR_INTERNAL_USE)

        // ---- beforeUpdate hooks (receive a per-hook context with snapshot). ----
        // `patch` in the context is a snapshot built *before* the hook
        // runs. Within a hook, writes through the mutation view do not
        // change `patch`. After each hook returns, the next iteration
        // builds a fresh snapshot from the current dirty state.
        builder.beginControlFlow("for (hook in beforeUpdateHooks)")
        builder.addStatement("val snapshot = _buildRequestedPatch()")
        builder.addStatement(
            "val beforeSnapshot = %L",
            lifecycleValueSnapshot("entity", allFields, entityClass),
        )
        builder.addStatement(
            "val ctx = %T(client.hookClientScopeForInternalUse, viewerContext, beforeSnapshot, snapshot, pendingEdges, _mutationView)",
            updateHookCtxClass,
        )
        // Rebuild the update context before every hook so later hooks see
        // mutations made by earlier hooks while each hook still enters
        // through the batch contract with a singleton list.
        builder.addStatement("%M(listOf(ctx), listOf(hook))", RUN_BATCH_HOOKS_FOR_INTERNAL_USE)
        builder.endControlFlow()
    }

    /**
     * From dirty state to database write set: required-null check, the
     * canonical requested patch, the EdgeChanges sidecar, the
     * assignment-free no-op branch, update defaults (effective patch),
     * field-level validation of Set entries, and the `values` map.
     */
    private fun emitPatchConstruction() {
        // ---- Required-null check (after hooks, before canonical patch). ----
        // Field-shape and required-edge
        // checks run after beforeUpdate hooks. A hook can repair an
        // explicit `name = null` assignment via `mutation.unsetName()`
        // (removes from dirtyFields) or by reassigning a value;
        // unrepaired assignments become a typed EntValidationException
        // here.
        builder.addStatement("val requiredViolations = _checkRequiredNotNull()")
        builder.addStatement("if (requiredViolations.isNotEmpty()) return·_validationFailed(requiredViolations)")

        // ---- Build the canonical requested patch after all before hooks. ----
        builder.addStatement("val requestedPatch = _buildRequestedPatch()")

        // ---- Read current junction state and compute
        // per-edge EdgeChanges sidecar. The helper short-circuits to an
        // empty aggregator when no mutator has pending ops, so the
        // junction database round-trips only happen when there's work.
        // Built before the no-op branch so privacy/validation rules in
        // both branches see the same EdgeChanges view. The junction
        // read is a pre-write driver call → classification with the
        // NotPersisted fallback. ----
        if (helperEligibleEdges.isNotEmpty()) {
            builder.addCode(
                codeBlock {
                    add("val edgeChanges = try {\n")
                    add("  _buildEdgeChanges(pendingEdges)\n")
                    add(driverCallFailureTail("NotPersisted"))
                },
            )
        } else {
            builder.addStatement("val edgeChanges = _buildEdgeChanges(pendingEdges)")
        }

        // ---- Assignment-free no-op branch (must run BEFORE update defaults). ----
        // Covers both the syntactically-empty `update(id) { }` and the
        // hook-cleared-empty patch: the target's existence has already
        // been established by the owner load (absence was a typed
        // Failed), pre-write privacy AND validation still run against
        // the unchanged after-state candidate (a real authorization /
        // invariant decision against the loaded `before`), and the
        // persist phase plus post-persist callbacks are skipped —
        // completing as Success per the RFC's No-Op Updates rule.
        // Update defaults are deliberately NOT folded here so a
        // default-only "real" write is never synthesized by an
        // assignment-free update.
        //
        // For saveAndLoad, the returned entity is the loaded current
        // row after the ordinary LOAD disclosure check; a denial there
        // uses writeState = NotPersisted because no write occurred.
        //
        // An M2M-only update (caller staged link-table M2M ops but no
        // scalar/FK assignment survived the hooks) is NOT a no-op — it
        // still emits junction writes. Gate this branch on
        // `!_hasPendingLinkTableM2MOps()` so M2M-only updates fall
        // through to the non-empty path, where update defaults can
        // synthesize a scalar UPDATE (e.g. `updatedAt =
        // updateDefaultNow()`) if any apply, and junction writes fire
        // below.
        val emptyBranchCondition = if (helperEligibleEdges.isNotEmpty()) {
            "if (dirtyFields.isEmpty() && !_hasPendingLinkTableM2MOps())"
        } else {
            "if (dirtyFields.isEmpty())"
        }
        builder.beginControlFlow(emptyBranchCondition)
        builder.addStatement("val effectivePatch = requestedPatch")
        emitCandidateConstruction(
            builder,
            candidateClass = candidateClass,
            allFields = allFields,
            edgeFks = allEdgeFks,
        )
        builder.addStatement(
            "val denialReason = client.%L.updateDenialReasonOrNull(viewerContext, entity, requestedPatch, effectivePatch, candidate, edgeChanges)",
            repoPropName,
        )
        builder.beginControlFlow("if (denialReason != null)")
        builder.addCode(
            privacyDeniedFailure(
                writeStateExpr = CodeBlock.of("%T.NotPersisted", MUTATION_WRITE_STATE),
                schemaName = schemaName,
                operationName = "UPDATE",
                entityKeyExpr = CodeBlock.of("%T(%S, id)", MUTATION_ENTITY_KEY, "id"),
                reasonExpr = "denialReason",
            ),
        )
        builder.endControlFlow()
        builder.addStatement(
            "val violations = client.%L.evaluateUpdateValidation(entity, requestedPatch, effectivePatch, candidate, edgeChanges)",
            repoPropName,
        )
        builder.addStatement("if (violations.isNotEmpty()) return·_validationFailed(violations)")
        builder.beginControlFlow("if (applyLoadPrivacy)")
        builder.addStatement("val denial = client.%L.loadDenialOrNull(viewerContext, entity)", repoPropName)
        builder.beginControlFlow("if (denial != null)")
        builder.addCode(
            privacyDeniedFailure(
                writeStateExpr = CodeBlock.of("%T.NotPersisted", MUTATION_WRITE_STATE),
                schemaName = schemaName,
                operationName = "LOAD",
                entityKeyExpr = CodeBlock.of("%T(%S, entity.id)", MUTATION_ENTITY_KEY, "id"),
                reasonExpr = "denial.reason",
            ),
        )
        builder.endControlFlow()
        builder.endControlFlow()
        builder.addStatement("return %T.Success(entity)", MUTATION_RESULT)
        builder.endControlFlow()

        // ---- Apply update defaults to compute the effective patch. ----
        emitEffectivePatchConstruction(
            builder,
            patchClass = patchClass,
            mutableFields = mutableFields,
            edgeFks = edgeFks,
        )

        // ---- Field-level validation on Set entries of the effective patch. ----
        for (field in mutableFields) {
            if (field.validators.isEmpty()) continue
            emitPatchEntryValidation(builder, field)
        }
        for (fk in edgeFks) {
            if (fk.validators.isEmpty()) continue
            // Backing-field validators apply to the FK on update too —
            // run them on Set entries of the effective patch.
            emitFkPatchEntryValidation(builder, fk)
        }

        // ---- Build the database write set from the effective patch. ----
        builder.addStatement("val values = mutableMapOf<String, Any?>()")
        for (field in mutableFields) {
            val prop = field.apiName
            val col = field.columnName
            // .name for enums needs a null-aware call when the enum is nullable.
            if (field.type == FieldType.ENUM && field.nullable) {
                builder.addCode(
                    "(effectivePatch.%L as? %T.Set)?.let { values[%S] = it.value?.name }\n",
                    prop, FIELD_PATCH, col,
                )
            } else if (field.type == FieldType.ENUM) {
                builder.addCode(
                    "(effectivePatch.%L as? %T.Set)?.let { values[%S] = it.value.name }\n",
                    prop, FIELD_PATCH, col,
                )
            } else if (field.type == FieldType.PGVECTOR) {
                // Validate the vector dimension on update (field-named; the
                // driver re-checks defensively at bind).
                val dims = (field.storage as? entkt.schema.ColumnStorage.Native)?.dimensions
                    ?: error("pgvector field '${field.apiName}' missing dimensions metadata")
                val opt = if (field.nullable) "?" else ""
                builder.addCode(
                    "(effectivePatch.%L as? %T.Set)?.let { values[%S] = it.value$opt.also { vec -> require(vec.dimensions == %L) { %S } } }\n",
                    prop, FIELD_PATCH, col, dims, "${field.apiName} expects vector($dims)",
                )
            } else {
                builder.addCode(
                    "(effectivePatch.%L as? %T.Set)?.let { values[%S] = it.value }\n",
                    prop, FIELD_PATCH, col,
                )
            }
        }
        for (fk in edgeFks) {
            builder.addCode(
                "(effectivePatch.%L as? %T.Set)?.let { values[%S] = it.value }\n",
                fk.propertyName, FIELD_PATCH, fk.columnName,
            )
        }
    }

    private fun emitPrivacyAndValidation() {
        // ---- Privacy + validation. ----
        // The no-op branch above already handled the
        // requested-empty-after-hooks case, so reaching here means the
        // save will persist something (a scalar write set and/or
        // junction writes). Both evaluators are decision-returning: a
        // returned Deny / non-empty violation list becomes the typed
        // exception here, while a rule-THROWN exception escapes to the
        // terminal boundary as a foreign failure.
        emitCandidateConstruction(
            builder,
            candidateClass = candidateClass,
            allFields = allFields,
            edgeFks = allEdgeFks,
        )
        builder.addStatement(
            "val denialReason = client.%L.updateDenialReasonOrNull(viewerContext, entity, requestedPatch, effectivePatch, candidate, edgeChanges)",
            repoPropName,
        )
        builder.beginControlFlow("if (denialReason != null)")
        builder.addCode(
            privacyDeniedFailure(
                writeStateExpr = CodeBlock.of("%T.NotPersisted", MUTATION_WRITE_STATE),
                schemaName = schemaName,
                operationName = "UPDATE",
                entityKeyExpr = CodeBlock.of("%T(%S, id)", MUTATION_ENTITY_KEY, "id"),
                reasonExpr = "denialReason",
            ),
        )
        builder.endControlFlow()
        builder.addStatement(
            "val violations = client.%L.evaluateUpdateValidation(entity, requestedPatch, effectivePatch, candidate, edgeChanges)",
            repoPropName,
        )
        builder.addStatement("if (violations.isNotEmpty()) return·_validationFailed(violations)")
    }

    private fun emitOwnerWrite() {
        // ---- Owner-row driver write. After-hooks, the post-write
        // LOAD disclosure, and the return live in
        // emitAfterHooksAndReturn, after the junction writes. The
        // write statement routes exceptions through
        // `_classifyDriverFailure` with the PersistenceUnknown
        // write-phase fallback (never optimistic NotPersisted); a null
        // return means the row vanished between load and write →
        // Failed(EntTargetAbsentException) (hardcoded NotPersisted).
        // After a successful owner write the state flips to
        // TransactionPending / Committed so later foreign failures
        // (afterUpdate hooks, materialization) report the post-write
        // state. ----
        // For M2M-capable schemas, the owner UPDATE is
        // conditional. An edge-only update (caller staged M2M ops,
        // hooks cleared every scalar field, no update defaults apply)
        // produces an empty `values` map — issuing a no-op UPDATE
        // would be wrong: generated code must not issue an empty
        // owner-row update. When values is empty
        // the loaded `before` row IS the after state, since no scalar
        // / FK changes were committed.
        if (helperEligibleEdges.isNotEmpty()) {
            builder.beginControlFlow("val updatedEntity = if (values.isNotEmpty())")
            builder.addCode(
                codeBlock {
                    add("val row = try {\n")
                    add("  driver.update(%T.TABLE, id, values)\n", entityClass)
                    add(driverCallFailureTail("PersistenceUnknown"))
                },
            )
            builder.beginControlFlow("if (row == null)")
            builder.addCode(
                recordAndReturnFailure(
                    CodeBlock.of(
                        "%T(%S, %T(%S, id))",
                        ENT_TARGET_ABSENT_EXCEPTION, schemaName, MUTATION_ENTITY_KEY, "id",
                    ),
                ),
            )
            builder.endControlFlow()
            builder.addStatement(
                "writeState = postWriteState",
            )
            builder.addStatement("%T.fromRow(row)", entityClass)
            builder.nextControlFlow("else")
            // Edge-only path: `entity` is the loaded `before` row, which
            // is also the after-state since no scalar / FK changes were
            // written. Junction writes follow below.
            builder.addStatement("entity")
            builder.endControlFlow()
        } else {
            builder.addCode(
                codeBlock {
                    add("val row = try {\n")
                    add("  driver.update(%T.TABLE, id, values)\n", entityClass)
                    add(driverCallFailureTail("PersistenceUnknown"))
                },
            )
            builder.beginControlFlow("if (row == null)")
            builder.addCode(
                recordAndReturnFailure(
                    CodeBlock.of(
                        "%T(%S, %T(%S, id))",
                        ENT_TARGET_ABSENT_EXCEPTION, schemaName, MUTATION_ENTITY_KEY, "id",
                    ),
                ),
            )
            builder.endControlFlow()
            builder.addStatement(
                "writeState = postWriteState",
            )
            builder.addStatement("val updatedEntity = %T.fromRow(row)", entityClass)
        }
    }

    private fun emitJunctionWrites() {
        // ---- Junction writes. After the owner-row
        // update (or skipped, for edge-only saves), apply the per-edge
        // computed `added` / `removed` deltas from `edgeChanges`.
        // Inserts go one row at a time (junction id minted per-row);
        // deletes go in one `deleteMany` per edge for efficiency.
        // Junction-shape rule 5 (validateThroughLinkJunctions) rejects
        // EXPLICIT junction id strategies, so we only need AUTO_INT /
        // AUTO_LONG (driver mints) and CLIENT_UUID (mint here).
        //
        // The whole junction region shares one classification catch
        // whose fallback is TransactionPending: M2M saves are
        // preflight-guaranteed to run inside a caller-owned
        // transaction, so a mid-junction failure's effect is staged in
        // the still-open transaction (rollback-only marking makes the
        // boundary roll it back) rather than an unknown-commit
        // outcome. After ALL SQL for the save has completed — owner
        // update plus junction writes — the state flips (again, for
        // the edge-only path that skipped the owner write). ----
        if (helperEligibleEdges.isNotEmpty()) {
            // Tracks whether any junction statement actually changed a
            // row: insertIgnore returning null (duplicate pair) and a
            // zero-row delete are not writes. Drives both the staged-
            // failure promotion in the catch below and the post-region
            // state flip — a no-op edge update must stay NotPersisted.
            builder.addStatement("var junctionWrites = false")
            builder.beginControlFlow("try")
            for (edge in helperEligibleEdges) {
                val prop = edge.mutatorPropertyName
                // INSERT each added id. Mint UUID client-side for
                // CLIENT_UUID junctions; AUTO_* junctions let the driver
                // assign the id (no "id" key in the map).
                builder.beginControlFlow("if (edgeChanges.%L.added.isNotEmpty())", prop)
                builder.beginControlFlow("for (_targetId in edgeChanges.%L.added)", prop)
                // Use insertIgnore so re-adding an existing pair from
                // either orientation is an idempotent no-op rather than a
                // unique-constraint error. The conflict target is the FK pair.
                when (edge.junctionIdStrategy) {
                    "CLIENT_UUID" -> builder.addStatement(
                        "if (driver.insertIgnore(%S, mapOf(%S to %T.randomUUID(), %S to id, %S to _targetId), conflictColumns = listOf(%S, %S)) != null) junctionWrites = true",
                        edge.junctionTable, "id", UUID_CLASS,
                        edge.junctionSourceColumn, edge.junctionTargetColumn,
                        edge.junctionSourceColumn, edge.junctionTargetColumn,
                    )
                    "AUTO_INT", "AUTO_LONG" -> builder.addStatement(
                        "if (driver.insertIgnore(%S, mapOf(%S to id, %S to _targetId), conflictColumns = listOf(%S, %S)) != null) junctionWrites = true",
                        edge.junctionTable,
                        edge.junctionSourceColumn, edge.junctionTargetColumn,
                        edge.junctionSourceColumn, edge.junctionTargetColumn,
                    )
                    else -> error(
                        "Unexpected junction id strategy '${edge.junctionIdStrategy}' for " +
                            "M2M edge '${edge.edgeName}' — validateThroughLinkJunctions should " +
                            "have rejected EXPLICIT junctions",
                    )
                }
                builder.endControlFlow()
                builder.endControlFlow()
                // DELETE removed ids in one round-trip per edge. The
                // predicate AND-pair (sourceCol = id, targetCol IN removed)
                // restricts the delete to this owner's junction rows.
                builder.beginControlFlow("if (edgeChanges.%L.removed.isNotEmpty())", prop)
                // Junction-table delete: predicates have no entity
                // scope (junctions are internal storage). Predicate.Leaf<Any>
                // renders the same structural data and erases at the
                // driver boundary.
                builder.addStatement(
                    "if (driver.deleteMany(%S, listOf(%T.Leaf<%T>(%S, %T.EQ, id), %T.Leaf<%T>(%S, %T.IN, edgeChanges.%L.removed.toList()))) > 0) junctionWrites = true",
                    edge.junctionTable,
                    PREDICATE, Any::class.asClassName(), edge.junctionSourceColumn, OP_CLASS,
                    PREDICATE, Any::class.asClassName(), edge.junctionTargetColumn, OP_CLASS, prop,
                )
                builder.endControlFlow()
            }
            builder.nextControlFlow("catch (e: %T)", MUTATION_CANCELLATION_EXCEPTION)
            builder.addStatement("throw e")
            builder.nextControlFlow("catch (e: %T)", KOTLIN_EXCEPTION)
            builder.addStatement(
                "val classified = _classifyDriverFailure(e, %T.TransactionPending)",
                MUTATION_WRITE_STATE,
            )
            // A recognized statement-level failure hardcodes
            // NotPersisted — accurate for the failed statement, but
            // when THIS save already staged the owner update or an
            // earlier junction row, the save's effect is pending in
            // the open transaction, so the result promotes to
            // TransactionPending with the typed failure as cause.
            builder.addCode(
                codeBlock {
                    add(
                        "val reported = if ((writeState != %T.NotPersisted || junctionWrites) && classified.writeState == %T.NotPersisted) {\n",
                        MUTATION_WRITE_STATE, MUTATION_WRITE_STATE,
                    )
                    add("  %T(%T.TransactionPending, classified)\n", ENT_UNEXPECTED_MUTATION_EXCEPTION, MUTATION_WRITE_STATE)
                    add("} else {\n")
                    add("  classified\n")
                    add("}\n")
                },
            )
            builder.addStatement("client.recordTransactionMutationFailure(reported)")
            builder.addStatement("return %T.failedForInternalUse(reported)", MUTATION_RESULT)
            builder.endControlFlow()
            // Flip only when this save actually wrote something: the
            // owner-write path already flipped; a real junction change
            // flips the edge-only path; a no-op edge update (empty
            // values, no junction rows changed) stays NotPersisted.
            builder.addStatement(
                "if (junctionWrites) writeState = postWriteState",
            )
        }
    }

    /**
     * After-hooks, the post-write LOAD disclosure, and the return.
     * The enclosing `_capturedPendingEdges` finally and the terminal
     * capture boundary are emitted by [build], which owns those
     * brackets.
     */
    private fun emitAfterHooksAndReturn() {
        // afterUpdate hook exceptions are foreign and reach the
        // terminal boundary with the flipped post-write state
        // (TransactionPending in a caller-owned transaction, Committed
        // after autocommit SQL).
        builder.addStatement("%M(listOf(updatedEntity), afterUpdateHooks)", RUN_BATCH_HOOKS_FOR_INTERNAL_USE)
        // Post-write LOAD disclosure (saveAndLoad only). The write has
        // already succeeded and is NOT undone by a denial: the typed
        // failure carries the current post-write state with
        // operation = LOAD, so "write happened but you can't see it"
        // is distinguishable from a pre-write UPDATE rejection.
        builder.beginControlFlow("if (applyLoadPrivacy)")
        builder.addStatement("val denial = client.%L.loadDenialOrNull(viewerContext, updatedEntity)", repoPropName)
        builder.beginControlFlow("if (denial != null)")
        builder.addCode(
            privacyDeniedFailure(
                writeStateExpr = CodeBlock.of("writeState"),
                schemaName = schemaName,
                operationName = "LOAD",
                entityKeyExpr = CodeBlock.of("%T(%S, updatedEntity.id)", MUTATION_ENTITY_KEY, "id"),
                reasonExpr = "denial.reason",
            ),
        )
        builder.endControlFlow()
        builder.endControlFlow()
        builder.addStatement("return %T.Success(updatedEntity)", MUTATION_RESULT)
    }
}

/**
 * Emit the canonical relationship-lock acquisition into the save
 * pipeline. For each distinct link-table relationship (junction +
 * unordered FK pair) touched by a helper-eligible edge, emit a guarded
 * `driver.serializeRelationship(...)` call that fires only when
 * `relationshipLocking == Canonical` and that relationship has pending ops.
 *
 * Two correctness properties:
 *  - **De-dup**: edges resolving to the same canonical key (same junction
 *    + same unordered FK pair) collapse to ONE lock whose guard ORs their
 *    `hasOps()` flags — never two locks on one relationship.
 *  - **Order**: the distinct relationships are emitted in ascending
 *    canonical-key order (junction table, then sorted FK columns) — a
 *    global total order both orientations agree on, so a multi-relationship
 *    save can't deadlock against another ordering.
 *
 * Called BEFORE the owner-row read (which `SELECT ... FOR UPDATE`s the owner
 * row that the opposite orientation's junction insert FK-checks). Taking the
 * relationship lock first means a contending opposite-orientation save holds
 * no row locks while it waits — so the two can't form an
 * owner-lock-vs-FK-share-lock cycle. The lock is an xact lock, held through
 * the junction read-diff-write to commit. No-op for schemas with no
 * helper-eligible edges (the loop body never executes).
 */
private fun emitCanonicalRelationshipLocks(
    builder: FunSpec.Builder,
    helperEligibleEdges: List<HelperEligibleM2M>,
) {
    if (helperEligibleEdges.isEmpty()) return
    // Canonical key: junction table + the FK pair sorted into canonical
    // order (matches RelationshipLockKey.canonical), so both orientations
    // of the same link table group together and lock the same key.
    val groups = helperEligibleEdges.groupBy { edge ->
        edge.junctionTable to listOf(edge.junctionSourceColumn, edge.junctionTargetColumn).sorted()
    }
    val orderedKeys = groups.keys.sortedWith(
        compareBy({ it.first }, { it.second.joinToString(",") }),
    )
    for (key in orderedKeys) {
        val edgesInGroup = groups.getValue(key)
        val opsGuard = edgesInGroup.joinToString(" || ") { "this.${it.mutatorPropertyName}.hasOps()" }
        builder.beginControlFlow(
            "if (relationshipLocking == %T.Canonical && (%L))",
            RELATIONSHIP_LOCKING, opsGuard,
        )
        builder.addStatement(
            "driver.serializeRelationship(%T.canonical(%S, listOf(%S, %S)))",
            RELATIONSHIP_LOCK_KEY, key.first, key.second[0], key.second[1],
        )
        builder.endControlFlow()
    }
}

/**
 * Apply framework update defaults to produce `effectivePatch`. Fields
 * with `updateDefault` whose requested entry is `Unset` get the
 * default applied; explicit caller/hook writes always win. Emitted
 * only on the non-empty path — the assignment-free no-op branch skips
 * derivation so a default-only "real" write is never synthesized.
 */
private fun emitEffectivePatchConstruction(
    builder: FunSpec.Builder,
    patchClass: ClassName,
    mutableFields: List<Field>,
    edgeFks: List<EdgeFk>,
) {
    val hasUpdateDefaults = mutableFields.any { it.updateDefault != null }
    if (!hasUpdateDefaults) {
        // No defaults to apply — the effective patch equals the requested patch.
        builder.addStatement("val effectivePatch = requestedPatch")
        return
    }
    val code = codeBlock {
        add("val effectivePatch = %T(\n", patchClass)
        for (field in mutableFields) {
            val prop = field.apiName
            if (field.updateDefault != null) {
                add(
                    "  %L = if (requestedPatch.%L is %T.Set) requestedPatch.%L else %T.Set(%L),\n",
                    prop, prop, FIELD_PATCH, prop, FIELD_PATCH, updateDefaultCodeBlock(field),
                )
            } else {
                add("  %L = requestedPatch.%L,\n", prop, prop)
            }
        }
        for (fk in edgeFks) {
            add("  %L = requestedPatch.%L,\n", fk.propertyName, fk.propertyName)
        }
        add(")\n")
    }
    builder.addCode(code)
}

/**
 * Apply field validators to a Set entry of the effective patch. The
 * value is non-null for required fields; for nullable fields, the
 * validators only run when the patched value is non-null (validators
 * don't validate null on nullable fields).
 */
private fun emitPatchEntryValidation(builder: FunSpec.Builder, field: Field) {
    val prop = field.apiName
    val localName = "${prop}_eff"
    builder.addStatement("val %L = effectivePatch.%L", localName, prop)
    builder.beginControlFlow("if (%L is %T.Set)", localName, FIELD_PATCH)
    if (field.nullable) {
        builder.addStatement("val %L_v = %L.value", prop, localName)
        builder.beginControlFlow("if (%L_v != null)", prop)
        emitFieldValidation(builder, "${prop}_v", prop, field.validators, nullable = false)
        builder.endControlFlow()
    } else {
        builder.addStatement("val %L_v = %L.value", prop, localName)
        emitFieldValidation(builder, "${prop}_v", prop, field.validators, nullable = false)
    }
    builder.endControlFlow()
}

/**
 * Apply backing-field validators to a Set entry of the effective
 * patch for an FK. Mirrors [emitPatchEntryValidation] for scalars,
 * keyed off [EdgeFk.required] (whose nullability follows the
 * relationship) rather than scalar `field.nullable`.
 */
private fun emitFkPatchEntryValidation(builder: FunSpec.Builder, fk: EdgeFk) {
    val prop = fk.propertyName
    val localName = "${prop}_eff"
    builder.addStatement("val %L = effectivePatch.%L", localName, prop)
    builder.beginControlFlow("if (%L is %T.Set)", localName, FIELD_PATCH)
    if (!fk.required) {
        builder.addStatement("val %L_v = %L.value", prop, localName)
        builder.beginControlFlow("if (%L_v != null)", prop)
        emitFieldValidation(builder, "${prop}_v", fk.propertyName, fk.validators, nullable = false)
        builder.endControlFlow()
    } else {
        builder.addStatement("val %L_v = %L.value", prop, localName)
        emitFieldValidation(builder, "${prop}_v", fk.propertyName, fk.validators, nullable = false)
    }
    builder.endControlFlow()
}

/**
 * Build the full after-state write candidate by folding the
 * effective patch onto the loaded `before` entity. Immutable fields
 * always come from `before` (they're not in the patch). Edge FKs
 * and mutable fields use `effectivePatch.foo.orElse(entity.foo)`.
 */
private fun emitCandidateConstruction(
    builder: FunSpec.Builder,
    candidateClass: ClassName,
    allFields: List<Field>,
    edgeFks: List<EdgeFk>,
) {
    val code = codeBlock {
        add("val candidate = %T(\n", candidateClass)
        for (field in allFields) {
            val prop = field.apiName
            if (field.immutable) {
                add("  %L = entity.%L,\n", prop, prop)
            } else {
                add(
                    "  %L = effectivePatch.%L.%M(entity.%L),\n",
                    prop, prop, FIELD_PATCH_OR_ELSE, prop,
                )
            }
        }
        for (fk in edgeFks) {
            if (fk.immutable) {
                // Immutable FKs are never in the patch — pull the
                // unchanged value straight from the loaded `before` row.
                add("  %L = entity.%L,\n", fk.propertyName, fk.propertyName)
            } else {
                add(
                    "  %L = effectivePatch.%L.%M(entity.%L),\n",
                    fk.propertyName, fk.propertyName, FIELD_PATCH_OR_ELSE, fk.propertyName,
                )
            }
        }
        add(")\n")
    }
    builder.addCode(code)
}

private fun updateDefaultCodeBlock(field: Field): CodeBlock {
    return when (field.updateDefault!!) {
        is UpdateDefault.Now -> {
            require(field.type == FieldType.TIME) {
                "Field '${field.apiName}' has UpdateDefault.Now but type is ${field.type} — updateDefault is only valid on TIME fields"
            }
            CodeBlock.of("%T.now()", ClassName("java.time", "Instant"))
        }
    }
}
