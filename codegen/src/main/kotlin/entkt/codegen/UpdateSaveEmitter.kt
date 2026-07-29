package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.asClassName
import entkt.schema.Field
import entkt.schema.FieldType
import entkt.schema.UpdateDefault

private val FIELD_PATCH = ClassName("entkt.runtime.mutation", "FieldPatch")
private val FIELD_PATCH_OR_ELSE = MemberName("entkt.runtime.mutation", "orElse")
private val ENT_ERROR = ClassName("entkt.runtime.result", "EntError")
private val ENT_OPERATION = ClassName("entkt.runtime.result", "EntOperation")
private val ENT_NO_CHANGES_EXCEPTION = ClassName("entkt.runtime.result", "EntNoChangesException")
private val VALIDATION_EXCEPTION = ClassName("entkt.runtime.validation", "ValidationException")
private val VALIDATION_INVALID = ClassName("entkt.runtime.validation", "ValidationDecision", "Invalid")
private val UPDATE_CONSISTENCY = ClassName("entkt.runtime.mutation", "UpdateConsistency")
private val RELATIONSHIP_LOCKING = ClassName("entkt.runtime.mutation", "RelationshipLocking")
private val RELATIONSHIP_LOCK_KEY = ClassName("entkt.runtime.mutation", "RelationshipLockKey")
private val TRANSACTION_REQUIRED_EXCEPTION = ClassName("entkt.runtime.mutation", "TransactionRequiredException")
private val UNSUPPORTED_DRIVER_CAPABILITY_EXCEPTION = ClassName("entkt.runtime.mutation", "UnsupportedDriverCapabilityException")
private val PREDICATE = ClassName("entkt.query", "Predicate")
private val OP_CLASS = ClassName("entkt.query", "Op")
private val UUID_CLASS = ClassName("java.util", "UUID")

/**
 * Emits the generated `save()` member. One emitter per generate()
 * call: the constructor captures the naming and field context every
 * phase needs, and [build] runs the phase emitters in save order.
 * Each phase appends to the shared [builder]; the phase sequence in
 * [build] IS the save pipeline, so reordering calls changes the
 * generated semantics. The `_capturedPendingEdges` try/finally
 * bracket is emitted by [build] itself, so every phase emitter is
 * locally balanced KotlinPoet control flow.
 */
internal class UpdateSaveEmitter(
    private val packageName: String,
    private val schemaName: String,
    private val allFields: List<Field>,
    private val edgeFks: List<EdgeFk>,
    private val allEdgeFks: List<EdgeFk>,
    private val helperEligibleEdges: List<HelperEligibleM2M>,
) {
    private val entityClass = ClassName(packageName, schemaName)
    private val patchClass = ClassName(packageName, "${schemaName}UpdatePatch")
    private val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
    private val updateHookCtxClass = ClassName(packageName, "${schemaName}UpdateHookContext")
    private val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
    private val mutableFields = allFields.filter { !it.immutable }

    private val builder = FunSpec.builder("save")
        .returns(entityClass.copy(nullable = true))

    fun build(): FunSpec {
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
        // in try/finally so every save exit path (return null on
        // missing rows, throw EntNoChangesException, return
        // updatedEntity, or any exception out of hooks/privacy/
        // validation/driver writes) clears _capturedPendingEdges. A
        // hook that stashes ctx.mutation can then no longer read
        // pendingEdges after save returns — the adapter's getter
        // throws the "accessed outside a save()" error consistently
        // with its documented contract. The bracket is emitted here,
        // not split across phase emitters, so every emitter below is
        // locally balanced and can be read — or modified — on its own.
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
        return builder.build()
    }

    /**
     * Preflights, in pipeline order: syntactically-empty NoChanges, the
     * transaction requirement, Pessimistic capability checks, the M2M
     * capability + mixed-mode checks, and Canonical relationship lock
     * acquisition. Everything here fires before the owner-row read.
     */
    private fun emitPreflight() {
        // ---- Syntactically empty patch: NoChanges before owner-row load. ----
        // Reporting NoChanges before the load avoids existence-leaking
        // `update(missingId) {}` calls. saveOrNull throws here too —
        // NoChanges is not "expected absence" per the result variants.
        // This also fires before the transaction-requirement preflight
        // below, matching the pipeline ("syntactically empty
        // update classification — report NoChanges before any other
        // observable work, including transaction requirement checks").
        //
        // An M2M-only update (caller invoked
        // `tags.add(...)` etc. but assigned no scalar/FK fields) has
        // `dirtyFields.isEmpty()` since `dirtyFields` tracks scalar
        // assignments only. Gate this top-of-save NoChanges throw on
        // `!_hasPendingLinkTableM2MOps()` so M2M-only updates proceed
        // to the preflights below. The hook-cleared-empty
        // branch downstream uses the same gate.
        val topEmptyCondition = if (helperEligibleEdges.isNotEmpty()) {
            "if (dirtyFields.isEmpty() && !_hasPendingLinkTableM2MOps())"
        } else {
            "if (dirtyFields.isEmpty())"
        }
        builder.beginControlFlow(topEmptyCondition)
        builder.addStatement(
            "throw %T(%T.NoChanges(%S, %T.UPDATE, id))",
            ENT_NO_CHANGES_EXCEPTION,
            ENT_ERROR,
            schemaName,
            ENT_OPERATION,
        )
        builder.endControlFlow()

        // ---- Transaction-requirement preflight. Throws
        // TransactionRequiredException before the owner-row load,
        // hooks, defaults, validation, driver writes when the
        // configured TransactionRequirement isn't satisfied. ----
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
        // All four paths bypass LOAD privacy; missing rows short-circuit
        // before hooks/privacy/validation run. ----
        if (helperEligibleEdges.isNotEmpty()) {
            builder.beginControlFlow(
                "val row0 = if (consistency == %T.Pessimistic)",
                UPDATE_CONSISTENCY,
            )
            builder.addStatement(
                "driver.readRowForUpdate(%T.TABLE, id) ?: return null",
                entityClass,
            )
            builder.nextControlFlow("else if (_hasPendingLinkTableM2MOps() && driver.supportsReadRowForUpdate)")
            builder.addStatement(
                "driver.readRowForUpdate(%T.TABLE, id) ?: return null",
                entityClass,
            )
            builder.nextControlFlow("else if (_hasPendingLinkTableM2MOps())")
            builder.addStatement(
                "driver.serializeOwnerEdgeAndRead(%T.TABLE, id) ?: return null",
                entityClass,
            )
            builder.nextControlFlow("else")
            builder.addStatement(
                "driver.byId(%T.TABLE, id) ?: return null",
                entityClass,
            )
            builder.endControlFlow()
        } else {
            // No helper-eligible M2M edges → keep the existing two-way
            // branch unchanged so non-M2M schemas pay no new branches.
            builder.beginControlFlow(
                "val row0 = if (consistency == %T.Pessimistic)",
                UPDATE_CONSISTENCY,
            )
            builder.addStatement(
                "driver.readRowForUpdate(%T.TABLE, id) ?: return null",
                entityClass,
            )
            builder.nextControlFlow("else")
            builder.addStatement(
                "driver.byId(%T.TABLE, id) ?: return null",
                entityClass,
            )
            builder.endControlFlow()
        }
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
        // inspection.
        builder.addStatement("for (hook in beforeSaveHooks) hook(_beforeSaveView)")

        // ---- beforeUpdate hooks (receive a per-hook context with snapshot). ----
        // `patch` in the context is a snapshot built *before* the hook
        // runs. Within a hook, writes through the mutation view do not
        // change `patch`. After each hook returns, the next iteration
        // builds a fresh snapshot from the current dirty state.
        builder.beginControlFlow("for (hook in beforeUpdateHooks)")
        builder.addStatement("val snapshot = _buildRequestedPatch()")
        builder.addStatement(
            "val ctx = %T(client, entity, snapshot, pendingEdges, _mutationView)",
            updateHookCtxClass,
        )
        builder.addStatement("hook(ctx)")
        builder.endControlFlow()
    }

    /**
     * From dirty state to database write set: required-null check, the
     * canonical requested patch, the EdgeChanges sidecar, the
     * hook-cleared-empty early exit, update defaults (effective patch),
     * field-level validation of Set entries, and the `values` map.
     */
    private fun emitPatchConstruction() {
        // ---- Required-null check (after hooks, before canonical patch). ----
        // Field-shape and required-edge
        // checks run after beforeUpdate hooks. A hook can repair an
        // explicit `name = null` assignment via `mutation.unsetName()`
        // (removes from dirtyFields) or by reassigning a value;
        // unrepaired assignments throw here.
        builder.addStatement("_checkRequiredNotNull()")

        // ---- Build the canonical requested patch after all before hooks. ----
        builder.addStatement("val requestedPatch = _buildRequestedPatch()")

        // ---- Read current junction state and compute
        // per-edge EdgeChanges sidecar. The helper short-circuits to an
        // empty aggregator when no mutator has pending ops, so the
        // junction database round-trips only happen when there's work.
        // Built before the hook-cleared empty branch so privacy/validation
        // rules in both branches see the same EdgeChanges view. ----
        builder.addStatement("val edgeChanges = _buildEdgeChanges(pendingEdges)")

        // ---- Hook-cleared empty path (must run BEFORE update defaults). ----
        // Hook-cleared empty updates skip update defaults.
        // dirtyFields.isEmpty() here ⇔ requested patch is all Unset.
        // Build an unchanged effective patch (= requested, all Unset),
        // run UPDATE privacy on the unchanged after-state candidate
        // (a real authorization decision against the loaded `before`),
        // then throw NoChanges. Validation, driver write, after-hooks,
        // and returned LOAD privacy are skipped.
        //
        // An M2M-only update (caller cleared all dirty
        // scalar fields via hooks but staged link-table M2M ops) is
        // NOT a no-op — it still emits junction writes. Gate this
        // empty-scalar NoChanges branch on `!_hasPendingLinkTableM2MOps()`
        // so M2M-only updates fall through to the non-empty path,
        // where update defaults can synthesize a scalar UPDATE (e.g.
        // `updatedAt = updateDefaultNow()`) if any apply, and junction
        // writes fire below.
        val emptyBranchCondition = if (helperEligibleEdges.isNotEmpty()) {
            "if (dirtyFields.isEmpty() && !_hasPendingLinkTableM2MOps())"
        } else {
            "if (dirtyFields.isEmpty())"
        }
        builder.beginControlFlow(emptyBranchCondition)
        builder.addStatement("val effectivePatch = requestedPatch")
        builder.addStatement("val privacy = client.currentPrivacyContext()")
        emitCandidateConstruction(
            builder,
            candidateClass = candidateClass,
            allFields = allFields,
            edgeFks = allEdgeFks,
        )
        builder.addStatement(
            "client.%L.evaluateUpdatePrivacy(privacy, entity, requestedPatch, effectivePatch, candidate, edgeChanges)",
            repoPropName,
        )
        builder.addStatement(
            "throw %T(%T.NoChanges(%S, %T.UPDATE, id))",
            ENT_NO_CHANGES_EXCEPTION,
            ENT_ERROR,
            schemaName,
            ENT_OPERATION,
        )
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
            emitPatchEntryValidation(builder, schemaName, field)
        }
        for (fk in edgeFks) {
            if (fk.validators.isEmpty()) continue
            // Backing-field validators apply to the FK on update too —
            // run them on Set entries of the effective patch.
            emitFkPatchEntryValidation(builder, schemaName, fk)
        }

        // ---- Build the database write set from the effective patch. ----
        builder.addStatement("val values = mutableMapOf<String, Any?>()")
        for (field in mutableFields) {
            val prop = toCamelCase(field.name)
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
                    ?: error("pgvector field '${field.name}' missing dimensions metadata")
                val opt = if (field.nullable) "?" else ""
                builder.addCode(
                    "(effectivePatch.%L as? %T.Set)?.let { values[%S] = it.value$opt.also { vec -> require(vec.dimensions == %L) { %S } } }\n",
                    prop, FIELD_PATCH, col, dims, "${field.name} expects vector($dims)",
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
        // The hook-cleared empty branch above already handled the
        // requested-empty-after-hooks case, so reaching here means the
        // requested patch had at least one Set entry.
        builder.addStatement("val privacy = client.currentPrivacyContext()")
        emitCandidateConstruction(
            builder,
            candidateClass = candidateClass,
            allFields = allFields,
            edgeFks = allEdgeFks,
        )
        builder.addStatement(
            "client.%L.evaluateUpdatePrivacy(privacy, entity, requestedPatch, effectivePatch, candidate, edgeChanges)",
            repoPropName,
        )
        builder.addStatement(
            "client.%L.evaluateUpdateValidation(entity, requestedPatch, effectivePatch, candidate, edgeChanges)",
            repoPropName,
        )
    }

    private fun emitOwnerWrite() {
        // ---- Owner-row driver write. After-hooks, the post-write
        // LOAD privacy wrap, and the return live in
        // emitReturnAndCleanup, after the junction writes. ----
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
            builder.addStatement(
                "val row = driver.update(%T.TABLE, id, values) ?: return null",
                entityClass,
            )
            builder.addStatement("%T.fromRow(row)", entityClass)
            builder.nextControlFlow("else")
            // Edge-only path: `entity` is the loaded `before` row, which
            // is also the after-state since no scalar / FK changes were
            // written. Junction writes follow below.
            builder.addStatement("entity")
            builder.endControlFlow()
        } else {
            builder.addStatement(
                "val row = driver.update(%T.TABLE, id, values) ?: return null",
                entityClass,
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
        // AUTO_LONG (driver mints) and CLIENT_UUID (mint here). ----
        if (helperEligibleEdges.isNotEmpty()) {
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
                        "driver.insertIgnore(%S, mapOf(%S to %T.randomUUID(), %S to id, %S to _targetId), conflictColumns = listOf(%S, %S))",
                        edge.junctionTable, "id", UUID_CLASS,
                        edge.junctionSourceColumn, edge.junctionTargetColumn,
                        edge.junctionSourceColumn, edge.junctionTargetColumn,
                    )
                    "AUTO_INT", "AUTO_LONG" -> builder.addStatement(
                        "driver.insertIgnore(%S, mapOf(%S to id, %S to _targetId), conflictColumns = listOf(%S, %S))",
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
                    "driver.deleteMany(%S, listOf(%T.Leaf<%T>(%S, %T.EQ, id), %T.Leaf<%T>(%S, %T.IN, edgeChanges.%L.removed.toList())))",
                    edge.junctionTable,
                    PREDICATE, Any::class.asClassName(), edge.junctionSourceColumn, OP_CLASS,
                    PREDICATE, Any::class.asClassName(), edge.junctionTargetColumn, OP_CLASS, prop,
                )
                builder.endControlFlow()
            }
        }
    }

    /**
     * After-hooks, the post-write LOAD privacy wrap, and the return.
     * The enclosing `_capturedPendingEdges` finally is emitted by
     * [build], which owns that bracket.
     */
    private fun emitAfterHooksAndReturn() {
        builder.addStatement("for (hook in afterUpdateHooks) hook(updatedEntity)")
        // Post-write LOAD privacy check — wrap a denial into the
        // structured EntWriteSucceededLoadDeniedException so
        // saveOrError can distinguish "write happened but you can't
        // see it" (Err(WriteSucceededLoadDenied)) from "write
        // rejected up-front" (Err(PrivacyDenied(UPDATE))). Without
        // the wrap, both would collapse to PrivacyDenied and
        // callers couldn't tell whether the write actually
        // happened.
        builder.beginControlFlow("try")
        builder.addStatement("client.%L.evaluateLoadPrivacy(privacy, updatedEntity)", repoPropName)
        builder.nextControlFlow(
            "catch (e: %T)",
            ClassName("entkt.runtime.privacy", "PrivacyDeniedException"),
        )
        builder.addStatement(
            "throw %T(%T.WriteSucceededLoadDenied(e.entity, %T.UPDATE, updatedEntity.id, e.reason))",
            ClassName("entkt.runtime.result", "EntWriteSucceededLoadDeniedException"),
            ENT_ERROR,
            ENT_OPERATION,
        )
        builder.endControlFlow()
        builder.addStatement("return updatedEntity")
    }
}

/**
 * Emit the canonical relationship-lock acquisition into save().
 * For each distinct link-table relationship (junction + unordered FK pair)
 * touched by a helper-eligible edge, emit a guarded
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
 * default applied; explicit caller/hook writes always win.
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
    val code = CodeBlock.builder()
    code.add("val effectivePatch = %T(\n", patchClass)
    for (field in mutableFields) {
        val prop = toCamelCase(field.name)
        if (field.updateDefault != null) {
            code.add(
                "  %L = if (requestedPatch.%L is %T.Set) requestedPatch.%L else %T.Set(%L),\n",
                prop, prop, FIELD_PATCH, prop, FIELD_PATCH, updateDefaultCodeBlock(field),
            )
        } else {
            code.add("  %L = requestedPatch.%L,\n", prop, prop)
        }
    }
    for (fk in edgeFks) {
        code.add("  %L = requestedPatch.%L,\n", fk.propertyName, fk.propertyName)
    }
    code.add(")\n")
    builder.addCode(code.build())
}

/**
 * Apply field validators to a Set entry of the effective patch. The
 * value is non-null for required fields; for nullable fields, the
 * validators only run when the patched value is non-null (validators
 * don't validate null on nullable fields).
 */
private fun emitPatchEntryValidation(builder: FunSpec.Builder, schemaName: String, field: Field) {
    val prop = toCamelCase(field.name)
    val localName = "${prop}_eff"
    builder.addStatement("val %L = effectivePatch.%L", localName, prop)
    builder.beginControlFlow("if (%L is %T.Set)", localName, FIELD_PATCH)
    if (field.nullable) {
        builder.addStatement("val %L_v = %L.value", prop, localName)
        builder.beginControlFlow("if (%L_v != null)", prop)
        emitFieldValidation(builder, schemaName, "${prop}_v", field.name, field.validators, nullable = false)
        builder.endControlFlow()
    } else {
        builder.addStatement("val %L_v = %L.value", prop, localName)
        emitFieldValidation(builder, schemaName, "${prop}_v", field.name, field.validators, nullable = false)
    }
    builder.endControlFlow()
}

/**
 * Apply backing-field validators to a Set entry of the effective
 * patch for an FK. Mirrors [emitPatchEntryValidation] for scalars,
 * keyed off [EdgeFk.required] (whose nullability follows the
 * relationship) rather than scalar `field.nullable`.
 */
private fun emitFkPatchEntryValidation(builder: FunSpec.Builder, schemaName: String, fk: EdgeFk) {
    val prop = fk.propertyName
    val localName = "${prop}_eff"
    builder.addStatement("val %L = effectivePatch.%L", localName, prop)
    builder.beginControlFlow("if (%L is %T.Set)", localName, FIELD_PATCH)
    if (!fk.required) {
        builder.addStatement("val %L_v = %L.value", prop, localName)
        builder.beginControlFlow("if (%L_v != null)", prop)
        emitFieldValidation(builder, schemaName, "${prop}_v", fk.columnName, fk.validators, nullable = false)
        builder.endControlFlow()
    } else {
        builder.addStatement("val %L_v = %L.value", prop, localName)
        emitFieldValidation(builder, schemaName, "${prop}_v", fk.columnName, fk.validators, nullable = false)
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
    val code = CodeBlock.builder()
    code.add("val candidate = %T(\n", candidateClass)
    for (field in allFields) {
        val prop = toCamelCase(field.name)
        if (field.immutable) {
            code.add("  %L = entity.%L,\n", prop, prop)
        } else {
            code.add(
                "  %L = effectivePatch.%L.%M(entity.%L),\n",
                prop, prop, FIELD_PATCH_OR_ELSE, prop,
            )
        }
    }
    for (fk in edgeFks) {
        if (fk.immutable) {
            // Immutable FKs are never in the patch — pull the
            // unchanged value straight from the loaded `before` row.
            code.add("  %L = entity.%L,\n", fk.propertyName, fk.propertyName)
        } else {
            code.add(
                "  %L = effectivePatch.%L.%M(entity.%L),\n",
                fk.propertyName, fk.propertyName, FIELD_PATCH_OR_ELSE, fk.propertyName,
            )
        }
    }
    code.add(")\n")
    builder.addCode(code.build())
}

private fun updateDefaultCodeBlock(field: Field): CodeBlock {
    return when (field.updateDefault!!) {
        is UpdateDefault.Now -> {
            require(field.type == FieldType.TIME) {
                "Field '${field.name}' has UpdateDefault.Now but type is ${field.type} — updateDefault is only valid on TIME fields"
            }
            CodeBlock.of("%T.now()", ClassName("java.time", "Instant"))
        }
    }
}
