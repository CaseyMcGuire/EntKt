package entkt.codegen

import entkt.codegen.mutation.UpdateGenerator
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.reflect.KClass
import kotlin.test.Test

private class UpdateDefaultEntity : EntSchema("update_default_entities") {
    override fun id() = EntId.int()
    val name = string("name")
    val updatedAt = time("updated_at").updateDefaultNow()
}

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class UpdateGeneratorTest {

    private val generator = UpdateGenerator("com.example.ent")

    @Test
    fun `generates update builder with mutable properties for mutable fields`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("class UserUpdate")) { "Should generate UserUpdate class\n$output" }
        assert(output.contains("var name: String?")) { "Should have name var\n$output" }
        assert(output.contains("var age: Int?")) { "Should have age var\n$output" }
    }

    @Test
    fun `update builder is annotated as DSL scope`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("@EntktDsl")) { "Should be annotated @EntktDsl\n$output" }
    }

    @Test
    fun `excludes immutable fields from mutable properties`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(!output.contains("var createdAt")) { "Should not have mutable createdAt\n$output" }
    }

    @Test
    fun `save preserves immutable fields from entity`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("createdAt = entity.createdAt")) { "Should preserve immutable createdAt\n$output" }
    }

    @Test
    fun `save lowers dirty tracking to FieldPatch entries`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // Required field: Set(this.name!!) when non-null, Unset when
        // the value is null (so a hook can repair `update(id) { name = null }`).
        assert(
            output.contains(
                "name = if (\"name\" in dirtyFields && this.name != null) FieldPatch.Set(this.name!!) else FieldPatch.Unset",
            ),
        ) {
            "Required field should lower leniently — null assignments fall through to Unset\n$output"
        }
        // Nullable field: Set(this.age) — Set(null) is an explicit clear.
        assert(
            output.contains("age = if (\"age\" in dirtyFields) FieldPatch.Set(this.age) else FieldPatch.Unset"),
        ) {
            "Nullable field should lower to FieldPatch.Set(value) / Unset (no !!)\n$output"
        }
        // Candidate folds the effective patch over the loaded `before`.
        assert(output.contains("name = effectivePatch.name.orElse(entity.name)")) {
            "Candidate should fold effective patch over entity via orElse(...)\n$output"
        }
    }

    @Test
    fun `lenient required-null snapshot — Unset hides invalid null, mutation getter exposes it, _checkRequiredNotNull is the safety net`() {
        // This pins an intentional design tradeoff so it isn't quietly
        // regressed:
        //
        //   client.users.update(id) { name = null }   // required field
        //
        // produces dirtyFields = ["name"], this.name = null. The patch
        // model `FieldPatch<String>` for a required field can't carry
        // Set(null), so _buildRequestedPatch() lowers this case as
        // FieldPatch.Unset — making `ctx.patch.name` ambiguous between
        // "untouched" and "dirty + null". The actual null is observable
        // through `ctx.mutation.name` (the throw-on-untouched getter
        // gates on `!in dirtyFields`, not on the value), so a hook can
        // detect and repair via `unsetName()` or `mutation.name = "x"`.
        // If unrepaired, `_checkRequiredNotNull()` returns violations
        // after the hook loop and executeSave maps them to
        // Failed(EntValidationException) via _validationFailed before
        // the canonical patch / privacy / write.
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // (1) Lenient snapshot: required field dirty+null lowers to Unset.
        assert(
            output.contains(
                "name = if (\"name\" in dirtyFields && this.name != null) FieldPatch.Set(this.name!!) else FieldPatch.Unset",
            ),
        ) {
            "Required-field snapshot must guard with `&& this.name != null` so dirty+null falls through to Unset\n$output"
        }

        // (2) Mutation getter throws only on `!in dirtyFields`, NOT on
        //     null value. So dirty+null is observable as null, not a throw.
        assert(
            output.contains(
                "get() { if (\"name\" !in dirtyFields) throw IllegalStateException",
            ),
        ) {
            "Throw-on-untouched getter must gate on dirtyFields, not on value, so dirty+null reads as null\n$output"
        }

        // (3) Safety net: _checkRequiredNotNull returns the violations
        //     for unrepaired dirty+null required fields after the hook
        //     loop; executeSave maps a non-empty list into
        //     Failed(EntValidationException) via _validationFailed —
        //     validation failures are result values, not throws.
        val checkFnIdx = output.indexOf("private fun _checkRequiredNotNull()")
        assert(checkFnIdx != -1) { "Expected generated _checkRequiredNotNull\n$output" }
        val checkFnEnd = output.indexOf("private fun ", checkFnIdx + 1)
        assert(checkFnEnd != -1) { "Couldn't find end of _checkRequiredNotNull body\n$output" }
        val checkFnBody = output.substring(checkFnIdx, checkFnEnd)
        assert(
            checkFnBody.contains(
                "if (\"name\" in dirtyFields && this.name == null) return listOf(ValidationViolation(\"name is required\", field = \"name\"))",
            ),
        ) {
            "_checkRequiredNotNull must return the violation for a dirty+null required field as the safety net\n$checkFnBody"
        }
        assert(
            output.contains(
                "val requiredViolations = _checkRequiredNotNull() if (requiredViolations.isNotEmpty()) return _validationFailed(requiredViolations)",
            ),
        ) {
            "Non-empty required-null violations must return Failed via _validationFailed, not throw\n$output"
        }
    }

    @Test
    fun `required-null check runs after beforeUpdate hooks so a hook can repair`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // The hook loop must complete before _checkRequiredNotNull() fires,
        // so a hook that calls mutation.unsetName() or mutation.name = "x"
        // can repair an explicit `name = null` builder assignment. Anchor
        // the check on the call site (preceded by the hook loop's closing
        // brace) rather than the function declaration. A non-empty result
        // becomes Failed(EntValidationException) via _validationFailed.
        val hookLoop = output.indexOf("for (hook in beforeUpdateHooks)")
        val checkCallSite = output.indexOf(
            "hook(ctx) } val requiredViolations = _checkRequiredNotNull() " +
                "if (requiredViolations.isNotEmpty()) return _validationFailed(requiredViolations)",
        )
        val canonicalPatchPos = output.indexOf("val requestedPatch = _buildRequestedPatch()")
        assert(hookLoop != -1 && checkCallSite != -1 && canonicalPatchPos != -1) {
            "Expected hook loop, required-null check call site, and canonical patch construction\n$output"
        }
        assert(hookLoop < checkCallSite) {
            "_checkRequiredNotNull() must be called after the beforeUpdate hook loop\n$output"
        }
        assert(checkCallSite < canonicalPatchPos) {
            "_checkRequiredNotNull() must be called before the canonical requestedPatch is built\n$output"
        }

        // The lenient snapshot helper must NOT throw on dirty+null required
        // fields — that's what lets the hook see the broken state and fix it.
        // Detect any throw inside _buildRequestedPatch's body; the lenient
        // version emits no throws there.
        val patchFnIdx = output.indexOf("private fun _buildRequestedPatch(): UserUpdatePatch")
        assert(patchFnIdx != -1) { "Expected generated _buildRequestedPatch\n$output" }
        // Find the function body by looking for the next "private fun" or end of class.
        val nextFnIdx = output.indexOf("private fun ", patchFnIdx + 1)
            .let { if (it == -1) output.length else it }
        val patchFnBody = output.substring(patchFnIdx, nextFnIdx)
        assert(!patchFnBody.contains("throw IllegalStateException")) {
            "_buildRequestedPatch() should be lenient (no throw on dirty+null required fields)\n$patchFnBody"
        }

        // The dedicated check must report an unrepaired null persisting
        // post-hooks. Required-field order in the function body depends on
        // schema declaration order, so just check the body contains the
        // expected per-field violation returns. The violations flow into
        // _validationFailed -> Failed(EntValidationException) — no throw.
        val checkFnIdx = output.indexOf("private fun _checkRequiredNotNull()")
        assert(checkFnIdx != -1) { "Expected generated _checkRequiredNotNull\n$output" }
        val checkFnEnd = output.indexOf("private fun ", checkFnIdx + 1)
        assert(checkFnEnd != -1) { "Couldn't find end of _checkRequiredNotNull body\n$output" }
        val checkFnBody = output.substring(checkFnIdx, checkFnEnd)
        assert(
            checkFnBody.contains(
                "if (\"name\" in dirtyFields && this.name == null) return listOf(ValidationViolation(\"name is required\", field = \"name\"))",
            ),
        ) {
            "_checkRequiredNotNull() should return the violation for unrepaired null `name`\n$checkFnBody"
        }
        assert(!checkFnBody.contains("throw ")) {
            "_checkRequiredNotNull() must report via violations, not throw\n$checkFnBody"
        }
    }

    @Test
    fun `update builder has dirtyFields set`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("dirtyFields: MutableSet<String> = mutableSetOf()")) {
            "Should have dirtyFields set\n$output"
        }
    }

    @Test
    fun `mutable property getter throws on untouched read`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // Reading an untouched update property must throw —
        // a default-null getter would collapse Unset and explicit
        // Set(null) into the same observable value, and required-field
        // builders have no current-state value before save(). Hooks
        // should read pending state from `ctx.patch` instead.
        assert(
            output.contains(
                "get() { if (\"name\" !in dirtyFields) throw IllegalStateException(\"name is not set in this update; read ctx.patch.name instead\") return field }",
            ),
        ) {
            "Mutable field getter must throw when the property is not in dirtyFields\n$output"
        }
        // Same for edge FK properties.
        // (User has no edge FKs; check via Pet which has ownerId.)
    }

    // Edge FK property getter throw-on-untouched is asserted in
    // EdgeCodegenTest.`update builder edge FK getter throws on untouched read`,
    // which uses Pet.ownerId — User has no edge FK to test against.

    @Test
    fun `mutable property setter tracks dirty state`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("dirtyFields.add(\"name\")")) {
            "Setting name should add to dirtyFields\n$output"
        }
    }

    @Test
    fun `takes id in constructor`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("id: UUID")) { "Should take id parameter\n$output" }
    }

    @Test
    fun `loads current row internally before hooks`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        // The internal current-row load short-circuits with
        // Failed(EntTargetAbsentException) for missing rows before any
        // hook, privacy, or validation runs.
        val loadPos = output.indexOf("driver.byId(User.TABLE, id)")
        val hookPos = output.indexOf("for (hook in beforeSaveHooks)")
        assert(loadPos != -1) { "save() should load the current row via driver.byId(...)\n$output" }
        assert(hookPos != -1 && loadPos < hookPos) {
            "Internal current-row load should happen before before-hooks\n$output"
        }
    }

    @Test
    fun `assignment-free update is a target-existence check — owner-row load first, absent target is Failed`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // EntNoChangesException is gone: a syntactically empty update no
        // longer short-circuits before the owner-row load. It runs the
        // target-existence check (the owner-row read) plus the pre-write
        // phases like any other update; an absent target is
        // Failed(EntTargetAbsentException("User", EntityKey("id", id))).
        assert(!output.contains("EntNoChangesException")) {
            "EntNoChangesException must be gone from the generated update\n$output"
        }
        val loadPos = output.indexOf("driver.byId(User.TABLE, id)")
        assert(loadPos != -1) { "executeSave should load the current row via driver.byId(...)\n$output" }
        assert(
            output.contains(
                "if (row0 == null) { val exception = EntTargetAbsentException(\"User\", EntityKey(\"id\", id)) " +
                    "client.recordTransactionMutationFailure(exception) " +
                    "return MutationResult.failedForInternalUse(exception) }",
            ),
        ) {
            "Absent update target must be Failed(EntTargetAbsentException) recorded with the tx coordinator\n$output"
        }
        // The single dirtyFields-empty branch sits after the hooks —
        // there is no pre-load syntactic empty check anymore.
        val emptyCount = Regex(Regex.escape("if (dirtyFields.isEmpty()")).findAll(output).count()
        assert(emptyCount == 1) {
            "Expected exactly one dirtyFields-empty branch (post-hooks), got $emptyCount\n$output"
        }
        assert(loadPos < output.indexOf("if (dirtyFields.isEmpty())")) {
            "The empty branch must come after the owner-row load (target-existence check first)\n$output"
        }
    }

    @Test
    fun `beforeUpdate hooks receive a UserUpdateHookContext snapshot per call`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // Each iteration of the beforeUpdate loop builds a fresh
        // snapshot via _buildRequestedPatch() and wraps it in a hook
        // context. The hook receives the private `_mutationView`
        // adapter (not the builder itself), so it can't reach save(),
        // id, entity, or the private patch helpers.
        assert(
            output.contains(
                "for (hook in beforeUpdateHooks) { val snapshot = _buildRequestedPatch() val ctx = UserUpdateHookContext(client, entity, snapshot, pendingEdges, _mutationView) hook(ctx) }",
            ),
        ) {
            "beforeUpdate hooks should receive a per-call snapshot wrapped around _mutationView\n$output"
        }
        // The canonical requestedPatch for privacy/validation is rebuilt
        // after all hooks finish.
        assert(output.contains("val requestedPatch = _buildRequestedPatch()")) {
            "Canonical requestedPatch should be rebuilt after all before hooks\n$output"
        }
    }

    @Test
    fun `unset lives on the private hook-facing view, not the public builder`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // The builder must NOT have unset{Field}() at the class level —
        // that would make `client.users.update(id) { unsetName() }` a
        // valid DSL pattern, contradicting the hook-facing
        // framing. The unset methods live on the private _mutationView
        // adapter so only hooks can reach them via ctx.mutation.
        assert(
            output.contains(
                "private val _mutationView: UserUpdateMutationView = object : UserUpdateMutationView",
            ),
        ) {
            "Should generate a private _mutationView adapter implementing UserUpdateMutationView\n$output"
        }
        assert(
            output.contains("override fun unsetName() { this@UserUpdate.dirtyFields.remove(\"name\") }"),
        ) {
            "unsetName() override should live on the adapter and target the outer builder's dirtyFields\n$output"
        }
        assert(
            output.contains("override fun unsetAge() { this@UserUpdate.dirtyFields.remove(\"age\") }"),
        ) {
            "unsetAge() override should live on the adapter and target the outer builder's dirtyFields\n$output"
        }
    }

    @Test
    fun `update builder does not directly implement UpdateMutationView`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // SchemaUpdate implements the shared Mutation interface but
        // not the hook-facing view — that view is satisfied by the
        // private adapter so unset{Field}() never leaks onto the DSL.
        assert(output.contains("public class UserUpdate") && output.contains(") : UserMutation {")) {
            "UserUpdate should implement only UserMutation at the class level\n$output"
        }
        assert(!output.contains("public class UserUpdate") || !output.contains(") : UserUpdateMutationView {")) {
            "UserUpdate must not implement UserUpdateMutationView directly\n$output"
        }
    }

    @Test
    fun `hook-cleared empty patch runs UPDATE privacy and validation then succeeds without persisting`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // After hooks, if the requested patch is empty (dirtyFields was
        // cleared), generated code builds the unchanged candidate, runs
        // UPDATE privacy on it (real authorization decision against
        // `before`), runs validation, then returns Success(entity) —
        // skipping persist and post-persist (driver write, writeState
        // transition, afterUpdate hooks). The edgeChanges sidecar is
        // computed between the canonical patch build and the empty check.
        val emptyCheck = output.indexOf(
            "val edgeChanges = _buildEdgeChanges(pendingEdges) if (dirtyFields.isEmpty())",
        )
        assert(emptyCheck != -1) {
            "Hook-cleared check must run after _buildRequestedPatch+_buildEdgeChanges and before update defaults\n$output"
        }

        val successPos = output.indexOf("return MutationResult.Success(entity) }")
        assert(successPos != -1 && emptyCheck < successPos) {
            "Hook-cleared branch must end in Success(entity) without persisting\n$output"
        }
        val emptyBlock = output.substring(emptyCheck, successPos)
        assert(emptyBlock.contains("val effectivePatch = requestedPatch")) {
            "Hook-cleared branch must use requested as effective (skip update defaults)\n$output"
        }
        // The privacy call inside the empty branch uses the unchanged
        // candidate (built from `before`) with empty patches; a denial
        // carries writeState = NotPersisted since nothing was written.
        assert(emptyBlock.contains(
            "updateDenialReasonOrNull(privacy, entity, requestedPatch, effectivePatch, candidate, edgeChanges)",
        )) {
            "Hook-cleared branch should run UPDATE privacy on the unchanged candidate with edgeChanges sidecar\n$output"
        }
        assert(emptyBlock.contains(
            "EntMutationPrivacyDeniedException(MutationWriteState.NotPersisted, \"User\", EntOperation.UPDATE, EntityKey(\"id\", id), denialReason)",
        )) {
            "No-op branch denial must be Failed(EntMutationPrivacyDeniedException) with NotPersisted\n$output"
        }
        assert(emptyBlock.contains(
            "evaluateUpdateValidation(entity, requestedPatch, effectivePatch, candidate, edgeChanges)",
        )) {
            "Hook-cleared branch should run validation before succeeding\n$output"
        }
        assert(!emptyBlock.contains("driver.update(")) {
            "Hook-cleared branch must not reach the driver write\n$output"
        }
        assert(!emptyBlock.contains("afterUpdateHooks")) {
            "Hook-cleared branch must skip afterUpdate hooks (post-persist phase)\n$output"
        }
    }

    @Test
    fun `hook-cleared empty patch skips update defaults even when schema has them`() {
        // Regression for the bug where updatedAt = updateDefaultNow() turned
        // every hook-cleared update into a real write: the post-defaults
        // values map was non-empty (it carried the synthetic updatedAt),
        // bypassing the no-op empty branch and writing to the database.
        // (Decision: update-default derivation fires ONLY when a real
        // assignment already exists.)
        val schema = UpdateDefaultEntity()
        finalize(schema)
        val output = generator.generate("UpdateDefaultEntity", schema).toString()
            .replace("\\s+".toRegex(), " ")

        // The hook-cleared branch must run BEFORE emitEffectivePatchConstruction
        // so the updateDefault never gets applied.
        val emptyCheckPos = output.indexOf("if (dirtyFields.isEmpty())")
        val effectivePatchPos = output.indexOf(
            "val effectivePatch = UpdateDefaultEntityUpdatePatch(",
        )
        val driverUpdatePos = output.indexOf("driver.update(UpdateDefaultEntity.TABLE")
        assert(emptyCheckPos != -1 && effectivePatchPos != -1 && driverUpdatePos != -1) {
            "Expected hook-cleared check, effective patch construction, and driver write\n$output"
        }
        // The TOP empty-check is at index 0-ish; we want the SECOND occurrence
        // (after the byId load + hook loop) to come before the effective patch
        // construction. Find it via the canonical preceding marker.
        // _buildEdgeChanges call sits between _buildRequestedPatch
        // and the post-hook empty check.
        val postHookEmptyPos = output.indexOf(
            "val edgeChanges = _buildEdgeChanges(pendingEdges) if (dirtyFields.isEmpty())",
        )
        assert(postHookEmptyPos != -1) { "Expected post-hook empty check\n$output" }
        assert(postHookEmptyPos < effectivePatchPos) {
            "Hook-cleared check must run before update-default application " +
                "(otherwise updatedAt = Set(now) sneaks into values and the write happens)\n$output"
        }
        assert(postHookEmptyPos < driverUpdatePos) {
            "Hook-cleared check must short-circuit before the driver write\n$output"
        }
    }

    @Test
    fun `save and saveAndLoad share one private executeSave path`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // One private execution member parameterized on returned-entity
        // LOAD privacy; the two public terminals are thin projections
        // over it, so pipeline behavior cannot diverge between them.
        assert(output.contains("private fun executeSave(applyLoadPrivacy: Boolean): MutationResult<User>")) {
            "Should generate private executeSave(applyLoadPrivacy) returning MutationResult<User>\n$output"
        }
        assert(
            output.contains(
                "public fun save(): MutationResult<Unit> = when (val result = executeSave(applyLoadPrivacy = false)) " +
                    "{ is MutationResult.Success -> MutationResult.Success(Unit) is MutationResult.Failed -> result }",
            ),
        ) {
            "save() must project executeSave(applyLoadPrivacy = false) into MutationResult<Unit>\n$output"
        }
        assert(
            output.contains(
                "public fun saveAndLoad(): MutationResult<User> = executeSave(applyLoadPrivacy = true)",
            ),
        ) {
            "saveAndLoad() must delegate to executeSave(applyLoadPrivacy = true)\n$output"
        }
        // Failure mapping now lives in the shared helpers: validation
        // failures become Failed(EntValidationException(UPDATE)) via
        // _validationFailed, and driver failures route through
        // _classifyDriverFailure -> driver.classifyMutationException with the
        // UPDATE operation. Every Failed construction site records the
        // failure with the transaction coordinator hook.
        assert(
            output.contains(
                "private fun _validationFailed(violations: List<ValidationViolation>): MutationResult<Nothing> " +
                    "{ val exception = EntValidationException(\"User\", EntOperation.UPDATE, violations) " +
                    "client.recordTransactionMutationFailure(exception) " +
                    "return MutationResult.failedForInternalUse(exception) }",
            ),
        ) {
            "_validationFailed must build Failed(EntValidationException(UPDATE)) and record it\n$output"
        }
        assert(
            output.contains(
                "private fun _classifyDriverFailure(e: Exception, fallback: MutationWriteState): EntMutationException " +
                    "= driver.classifyMutationException(e, \"User\", EntOperation.UPDATE) " +
                    "?: EntUnexpectedMutationException(fallback, e)",
            ),
        ) {
            "_classifyDriverFailure must delegate to driver.classifyMutationException(UPDATE) with the fallback state\n$output"
        }
        val failedCount = Regex(Regex.escape("MutationResult.failedForInternalUse(")).findAll(output).count()
        val recordCount = Regex(Regex.escape("client.recordTransactionMutationFailure(")).findAll(output).count()
        assert(failedCount > 0 && failedCount == recordCount) {
            "Every Failed construction site must record the failure with the tx coordinator " +
                "(failed=$failedCount, recorded=$recordCount)\n$output"
        }
        // The outer capture boundary: CancellationException is rethrown;
        // any other escaping Exception is wrapped with the tracked
        // writeState so callers learn what EntKt knows about the write.
        assert(output.contains("var writeState = MutationWriteState.NotPersisted")) {
            "executeSave must track writeState from NotPersisted\n$output"
        }
        assert(
            output.contains(
                "} catch (e: CancellationException) { throw e } catch (e: Exception) { " +
                    "val exception = EntUnexpectedMutationException(writeState, e) " +
                    "client.recordTransactionMutationFailure(exception) " +
                    "return MutationResult.failedForInternalUse(exception) } }",
            ),
        ) {
            "executeSave's outer boundary must rethrow cancellation and wrap unexpected exceptions with the tracked writeState\n$output"
        }
    }

    @Test
    fun `removed legacy save surface — no saveOrNull saveOrError saveOrThrow or NoChanges`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // The result-variant family is gone. The sole throwing
        // projection anywhere is the getOrThrow() member on the result
        // types, so the builder emits no throwing or
        // nullable terminals of its own.
        assert(!output.contains("fun saveOrNull")) { "saveOrNull must be gone\n$output" }
        assert(!output.contains("fun saveOrError")) { "saveOrError must be gone\n$output" }
        assert(!output.contains("fun saveOrThrow")) { "saveOrThrow must be gone\n$output" }
        assert(!output.contains("EntResult")) { "EntResult must be gone\n$output" }
        assert(!output.contains("EntError")) { "EntError must be gone\n$output" }
        assert(!output.contains("EntNoChangesException")) { "EntNoChangesException must be gone\n$output" }
        assert(!output.contains("classifyDriverError")) { "Old classifyDriverError free function must be gone\n$output" }
    }

    @Test
    fun `implements the mutation interface`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("UserUpdate") && output.contains("UserMutation")) {
            "Should implement UserMutation interface\n$output"
        }
    }

    @Test
    fun `entity is private internal state`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // moved hook access to ctx.before / ctx.mutation, so
        // the `entity` lateinit is now purely internal state populated
        // by save() and read by candidate construction. Keeping it
        // public would let direct callers either crash on uninitialized
        // access or observe a stale (pre-update) row after save().
        assert(output.contains("private lateinit var entity: User")) {
            "entity should be a private lateinit var (internal state populated by save())\n$output"
        }
    }

    @Test
    fun `constructor takes client and hook list parameters`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("client: EntClient")) {
            "Should take client\n$output"
        }
        assert(output.contains("beforeSaveHooks: List<(UserMutation) -> Unit>")) {
            "Should take beforeSaveHooks\n$output"
        }
        assert(output.contains("beforeUpdateHooks: List<(UserUpdateHookContext) -> Unit>")) {
            "beforeUpdate hooks now take a UserUpdateHookContext\n$output"
        }
        assert(output.contains("afterUpdateHooks: List<(User) -> Unit>")) {
            "Should take afterUpdateHooks\n$output"
        }
    }

    @Test
    fun `client is private — hooks read ctx_client and DSL callers already hold it`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        // Update hooks receive ctx.client via the hook context, and
        // the DSL caller necessarily already has `client` in scope
        // (they called `client.users.update(id) { ... }`). Exposing
        // a public client on the update builder added zero capability
        // and surface area we don't want.
        assert(output.contains("private val client: EntClient")) {
            "client should be private on the update builder\n$output"
        }
    }

    @Test
    fun `Pessimistic preflight checks inTransaction before driver capability before readRowForUpdate call`() {
        // Pins the ordering of three things in the generated save():
        //   1. `if (!driver.inTransaction)` → throw TransactionRequiredException
        //   2. `if (!driver.supportsReadRowForUpdate)` → throw UnsupportedDriverCapabilityException
        //   3. `driver.readRowForUpdate(...)` call site
        // The inTransaction guard MUST come before the readRowForUpdate
        // call. Without it, a future refactor that reorders the
        // preflights could let a non-transactional driver hit the
        // root-class throw inside `readRowForUpdate`, surfacing the
        // wrong exception type (IllegalStateException from
        // `requireTransactionForLocking` instead of
        // TransactionRequiredException — and on Postgres root, the lock
        // would have released immediately even if the call had been
        // allowed). PostgresDriver advertises
        // `supportsReadRowForUpdate = true` on its non-transactional
        // root specifically so the capability check passes for
        // valid-driver-family callers; the inTransaction check is
        // what protects the actual call site.
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        val txCheck = output.indexOf("if (!driver.inTransaction)")
        val capabilityCheck = output.indexOf("if (!driver.supportsReadRowForUpdate)")
        val lockCall = output.indexOf("driver.readRowForUpdate(")

        assert(txCheck != -1) { "Should emit !driver.inTransaction guard for Pessimistic\n$output" }
        assert(capabilityCheck != -1) { "Should emit !driver.supportsReadRowForUpdate guard for Pessimistic\n$output" }
        assert(lockCall != -1) { "Should call driver.readRowForUpdate for Pessimistic\n$output" }
        assert(txCheck < lockCall) {
            "!driver.inTransaction guard MUST come before driver.readRowForUpdate(...) — " +
                "future refactors that flip this order surface IllegalStateException instead of " +
                "TransactionRequiredException, and on auto-commit drivers (e.g. Postgres root) the " +
                "lock would release immediately even if the call were allowed.\n$output"
        }
        assert(capabilityCheck < lockCall) {
            "!driver.supportsReadRowForUpdate guard MUST come before driver.readRowForUpdate(...)\n$output"
        }
    }

    @Test
    fun `save calls before hooks before requested patch construction`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        val hookCall = output.indexOf("for (hook in beforeSaveHooks)")
        val patchCtor = output.indexOf("val requestedPatch")
        assert(hookCall != -1 && patchCtor != -1 && hookCall < patchCtor) {
            "Before hooks should run before requested patch construction\n$output"
        }
    }

    @Test
    fun `save calls after hooks after update`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("for (hook in afterUpdateHooks) hook(updatedEntity)")) {
            "Should call afterUpdate hooks\n$output"
        }
    }

    @Test
    fun `save emits validation for mutable validated fields`() {
        val schema = ValidatedEntity()
        finalize(schema)
        val output = generator.generate("ValidatedEntity", schema).toString()

        // Validators run on the unwrapped patched value (`name_v`, etc.).
        assert(output.contains("name_v.length < 3")) {
            "Should emit minLength check on patched value\n$output"
        }
        assert(output.contains("name_v.length > 100")) {
            "Should emit maxLength check on patched value\n$output"
        }
        assert(output.contains("name_v.isEmpty()")) {
            "Should emit notEmpty check on patched value\n$output"
        }
    }

    @Test
    fun `save guards validation by FieldPatch_Set so unset fields do not validate`() {
        val schema = ValidatedEntity()
        finalize(schema)
        val output = generator.generate("ValidatedEntity", schema).toString()

        // Outer guard: only validate when the field is in the effective patch.
        assert(output.contains("if (name_eff is FieldPatch.Set)")) {
            "Should guard required-field validation with `is FieldPatch.Set`\n$output"
        }
        // Nullable field (`nickname`) gets an inner null guard since validators
        // don't apply when the patched value itself is null.
        assert(output.contains("if (nickname_eff is FieldPatch.Set)") && output.contains("if (nickname_v != null)")) {
            "Should guard nullable-field validation with `is FieldPatch.Set` plus inner null check\n$output"
        }
    }

    @Test
    fun `save does not validate immutable fields in update`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        // createdAt is immutable — should not have validation
        assert(!output.contains("createdAt.length")) {
            "Should not validate immutable fields\n$output"
        }
    }

    @Test
    fun `typed enum property uses the Kotlin enum type`() {
        val ticket = Ticket()
        finalize(ticket)
        val output = generator.generate("Ticket", ticket).toString()

        assert(output.contains("var priority: Priority?")) {
            "Should use the Kotlin enum type on the update property\n$output"
        }
    }

    @Test
    fun `typed enum save converts to name for the row map`() {
        val ticket = Ticket()
        finalize(ticket)
        val output = generator.generate("Ticket", ticket).toString()

        // Effective patch entries are unwrapped through `as? FieldPatch.Set`;
        // for enums, the value is converted to its name when added to the
        // driver write set.
        assert(
            output.contains(
                "(effectivePatch.priority as? FieldPatch.Set)?.let { values[\"priority\"] = it.value.name }",
            ) ||
                output.contains(
                    "(effectivePatch.priority as? FieldPatch.Set)?.let { values[\"priority\"] = it.value?.name }",
                ),
        ) {
            "Should convert typed enum patched value to .name in the driver write set\n$output"
        }
    }

    @Test
    fun `validation appears after effective patch and before driver write set`() {
        val schema = ValidatedEntity()
        finalize(schema)
        val output = generator.generate("ValidatedEntity", schema).toString()

        val patchPos = output.indexOf("val effectivePatch")
        val validationPos = output.indexOf("name_v.length < 3")
        val rowMapPos = output.indexOf("val values = mutableMapOf<String, Any?>")
        assert(patchPos != -1 && validationPos != -1 && rowMapPos != -1) {
            "Expected effective patch, validation, and write set markers in output\n$output"
        }
        assert(patchPos < validationPos && validationPos < rowMapPos) {
            "Validation should appear after effective patch and before driver write set\n$output"
        }
    }

    @Test
    fun `updateDefault time field is applied to the effective patch when unset`() {
        val schema = UpdateDefaultEntity()
        finalize(schema)
        val output = generator.generate("UpdateDefaultEntity", schema).toString()
            .replace("\\s+".toRegex(), " ")

        // The effective patch fills in Instant.now() when the requested
        // patch is Unset for an updateDefault field. Explicit caller/hook
        // writes (Set entries) win.
        assert(
            output.contains(
                "updatedAt = if (requestedPatch.updatedAt is FieldPatch.Set) requestedPatch.updatedAt else FieldPatch.Set(Instant.now())",
            ),
        ) {
            "Effective patch should fill in updateDefault when requested is Unset\n$output"
        }
    }

    @Test
    fun `fields without updateDefault pass requested patch entry through untouched`() {
        val schema = UpdateDefaultEntity()
        finalize(schema)
        val output = generator.generate("UpdateDefaultEntity", schema).toString()

        // For fields with no updateDefault, the effective patch entry is
        // exactly the requested patch entry.
        assert(output.contains("name = requestedPatch.name")) {
            "Fields without updateDefault should pass through requested patch entry\n$output"
        }
        // Candidate folds effective patch over loaded entity for those fields.
        assert(output.contains("name = effectivePatch.name.orElse(entity.name)")) {
            "Candidate should fold effective patch over entity via orElse(...) for non-default fields\n$output"
        }
    }

    // NOTE: The old test `updateDefault Now on non-TIME field is rejected` has been
    // removed because the typed builder API now prevents this at compile time —
    // updateDefaultNow() only exists on TimeFieldBuilder.

    // ---------- link-table M2M mutator generation ----------

    @Test
    fun `update builder for schema with throughLink M2M edge gets a nested mutator class`() {
        val (post, tag, postTag, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Nested mutator class is emitted on the Update builder.
        assert(output.contains("public class TagsEdgeMutator internal constructor()")) {
            "Should generate nested TagsEdgeMutator class with internal constructor\n$output"
        }
        // Public property bound on the Update builder.
        assert(output.contains("public val tags: M2MPostUpdate.TagsEdgeMutator = M2MPostUpdate.TagsEdgeMutator()") ||
               output.contains("public val tags: TagsEdgeMutator = TagsEdgeMutator()")) {
            "Should bind `val tags = TagsEdgeMutator()` on the Update builder\n$output"
        }
    }

    @Test
    fun `mutator exposes id-only add remove set with the target id type`() {
        val (post, tag, postTag, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Target id is UUID (from EntId.uuid() on M2MTag). The mutator
        // signatures should use UUID, not Long (the source's id type)
        // and not the M2MTag entity type.
        assert(output.contains("public fun add(id: UUID)")) {
            "Mutator add() should accept the target's id scalar type (UUID)\n$output"
        }
        assert(output.contains("public fun remove(id: UUID)")) {
            "Mutator remove() should accept the target's id scalar type (UUID)\n$output"
        }
        assert(output.contains("public fun `set`(ids: List<UUID>)") ||
               output.contains("public fun set(ids: List<UUID>)")) {
            "Mutator set() should accept List<UUID>\n$output"
        }
        // Compile-time check: no entity-arg overload.
        assert(!output.contains("public fun add(tag: M2MTag)") &&
               !output.contains("public fun add(target: M2MTag)")) {
            "Mutator must not have entity-arg overloads\n$output"
        }
    }

    @Test
    fun `mutator fail-fast rejects mixed replacement and delta at the call site`() {
        val (post, tag, postTag, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // add() / remove() reject if requestedSet was already populated.
        assert(output.contains("public fun add(id: UUID) { if (_requestedSet != null) throw IllegalStateException")) {
            "add() should fail-fast when replacement is already staged\n$output"
        }
        assert(output.contains("public fun remove(id: UUID) { if (_requestedSet != null) throw IllegalStateException")) {
            "remove() should fail-fast when replacement is already staged\n$output"
        }
        // set() rejects if delta state is already populated.
        assert(output.contains("if (_adds.isNotEmpty() || _removes.isNotEmpty()) throw IllegalStateException")) {
            "set() should fail-fast when delta operations are already staged\n$output"
        }
        // Error message names the edge so the call site can identify it.
        assert(output.contains("edge 'tags': cannot mix replacement (set) and delta (add/remove)")) {
            "Mixed-mode error message should name the edge\n$output"
        }
    }

    @Test
    fun `mutator fail-fast rejects same-id mixed-direction delta at the call site`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // link-table M2M helpers same-id mixed-direction rule: add(x) after remove(x)
        // and the reverse both throw at the second call. The two
        // delta sets stay disjoint by construction.
        assert(output.contains("if (_removes.contains(id)) throw IllegalStateException")) {
            "add() should reject when _removes already contains the id\n$output"
        }
        assert(output.contains("if (_adds.contains(id)) throw IllegalStateException")) {
            "remove() should reject when _adds already contains the id\n$output"
        }
        // Error messages name both the edge and the conflicting direction.
        assert(output.contains("edge 'tags': cannot add(id) after remove(id) for the same id")) {
            "add-after-remove message should name the edge and direction\n$output"
        }
        assert(output.contains("edge 'tags': cannot remove(id) after add(id) for the same id")) {
            "remove-after-add message should name the edge and direction\n$output"
        }
    }

    @Test
    fun `set takes a defensive copy so caller-side list mutation cannot change the saved set`() {
        // Defensive `_requestedSet = ids.toList()` guards against the
        // caller passing a MutableList, calling `tags.set(ids)`, then
        // mutating `ids` before save() — which would otherwise
        // silently change the persisted relationship since the
        // mutator aliased the caller's list.
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("_requestedSet = ids.toList()")) {
            "set() must defensively copy the input list, not alias it\n$output"
        }
        assert(!output.contains("_requestedSet = ids \n") &&
               !output.contains("_requestedSet = ids }") &&
               !Regex("""_requestedSet = ids[^.]""").containsMatchIn(output)) {
            "Direct `_requestedSet = ids` (without .toList()) must be gone\n$output"
        }
    }

    @Test
    fun `mutator op log fields are private — downstream codegen consumes them through internal accessors`() {
        // link-table M2M helpers op-log fields locked down to private so
        // same-module application code can't bypass per-call invariants
        // by writing tags._adds.add(...) directly. Downstream codegen
        // (_buildPendingEdgeOps, _checkLinkTableM2MMixedMode, the M2M
        // pending-ops gate) uses the internal accessors instead.
        val (post, tag, postTag, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("private var _requestedSet: List<UUID>?")) {
            "_requestedSet must be private (not internal) to block same-module bypass\n$output"
        }
        assert(output.contains("private val _adds: MutableList<UUID>")) {
            "_adds must be private\n$output"
        }
        assert(output.contains("private val _removes: MutableList<UUID>")) {
            "_removes must be private\n$output"
        }
        // No `internal var/val _requestedSet/_adds/_removes` form should leak.
        assert(!output.contains("internal var _requestedSet") &&
               !output.contains("internal val _adds") &&
               !output.contains("internal val _removes")) {
            "Internal field-visibility form must be gone — the bypass vector\n$output"
        }
        // Three internal accessors: hasOps, snapshotOps, validateInvariants.
        assert(output.contains("internal fun hasOps(): Boolean")) {
            "hasOps() accessor must be internal — wired into M2M preflight gate\n$output"
        }
        assert(output.contains("internal fun snapshotOps(): PendingEdgeOps<UUID>")) {
            "snapshotOps() accessor must be internal — replaces inline field reads in _buildPendingEdgeOps\n$output"
        }
        assert(output.contains("internal fun validateInvariants()")) {
            "validateInvariants() accessor must be internal — replaces inline checks in _checkLinkTableM2MMixedMode\n$output"
        }
    }

    @Test
    fun `throughEntity M2M edge does NOT get a mutator`() {
        val (team, member, membership, names) = makeEntityM2MSchemas()
        val output = generator.generate("M2MTeam", team, names).toString()
            .replace("\\s+".toRegex(), " ")

        // throughEntity edges are mutated through the junction repo, not
        // through direct mutators. Confirm no mutator class is generated.
        assert(!output.contains("EdgeMutator")) {
            "throughEntity M2M edges must not get direct mutator codegen\n$output"
        }
        assert(!output.contains("public val members:")) {
            "throughEntity M2M edges must not get a mutator property on the Update builder\n$output"
        }
    }

    @Test
    fun `update builder without any M2M edges has no mutator scaffolding`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(!output.contains("EdgeMutator")) {
            "Schemas without helper-eligible M2M edges should produce no mutator scaffolding\n$output"
        }
    }

    @Test
    fun `UpdateMutationView is not extended with the mutator property — hooks must not see it`() {
        val (post, tag, postTag, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // The private `_mutationView` adapter implements
        // M2MPostUpdateMutationView. It forwards scalar fields and FKs
        // through `override var ...` and exposes `override fun unsetX()`
        // for each. Hooks read pending edge ops through the // `pendingEdges` sidecar; they must not reach into the mutator,
        // so there must be NO `override var tags` / `override fun unsetTags`
        // in the adapter — the only way the view interface could expose
        // the mutator property.
        assert(output.contains("_mutationView: M2MPostUpdateMutationView")) {
            "Expected _mutationView adapter typed as M2MPostUpdateMutationView\n$output"
        }
        assert(!output.contains("override var tags") &&
               !output.contains("override val tags")) {
            "Hook-facing mutation view must not forward the M2M mutator property `tags`\n$output"
        }
        assert(!output.contains("override fun unsetTags")) {
            "Hook-facing mutation view must not expose unsetTags() — `tags` isn't a patch field\n$output"
        }
    }

    @Test
    fun `two helper-eligible M2M edges on one schema each get their own mutator class`() {
        val (doc, label, docTag, docLabel, names) = makeMultiEdgeSchemas()
        val output = generator.generate("M2MDoc", doc, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Both mutator classes are generated as separate nested classes.
        assert(output.contains("public class TagsEdgeMutator")) {
            "Expected TagsEdgeMutator nested class\n$output"
        }
        assert(output.contains("public class LabelsEdgeMutator")) {
            "Expected LabelsEdgeMutator nested class\n$output"
        }
        // Both properties bound on the Update builder.
        assert(output.contains("public val tags:") && output.contains("TagsEdgeMutator()")) {
            "Expected `val tags = TagsEdgeMutator()`\n$output"
        }
        assert(output.contains("public val labels:") && output.contains("LabelsEdgeMutator()")) {
            "Expected `val labels = LabelsEdgeMutator()`\n$output"
        }
    }

    // ---------- link-table M2M helpers PendingEdgeOps hook surface ----------

    @Test
    fun `update builder emits a _buildPendingEdgeOps that delegates per-edge to snapshotOps`() {
        // with mutator op-log fields private, the aggregator
        // construction no longer reaches into _requestedSet / _adds /
        // _removes directly. Each per-edge entry calls the mutator's
        // internal `snapshotOps()` accessor, which performs the dedup
        // internally and returns the immutable PendingEdgeOps<ID>.
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("private fun _buildPendingEdgeOps(): M2MPostPendingEdgeOps")) {
            "Expected private _buildPendingEdgeOps helper returning the aggregator\n$output"
        }
        assert(output.contains("tags = this.tags.snapshotOps()")) {
            "Per-edge entry must delegate to mutator's snapshotOps() accessor\n$output"
        }
        // No direct field accesses survive in the aggregator construction —
        // those would compile-error against private fields now anyway,
        // but pin it via test for clarity.
        assert(!output.contains("this.tags._requestedSet") &&
               !output.contains("this.tags._adds") &&
               !output.contains("this.tags._removes")) {
            "Aggregator construction must not reach into private op-log fields\n$output"
        }
    }

    @Test
    fun `_buildPendingEdgeOps short-circuits to empty aggregator for schemas without helper-eligible edges`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // Schema has no helper-eligible M2M edges, so the helper just
        // returns the empty aggregator instance. The aggregator type
        // still exists (uniform hook context shape across entities).
        assert(output.contains("private fun _buildPendingEdgeOps(): UserPendingEdgeOps")) {
            "Helper must be emitted even for entities without M2M edges\n$output"
        }
        // KotlinPoet collapses single-`return` block bodies to an
        // expression body, so accept either form.
        assert(output.contains("return UserPendingEdgeOps()") ||
               output.contains("_buildPendingEdgeOps(): UserPendingEdgeOps = UserPendingEdgeOps()")) {
            "Empty-aggregator path should short-circuit to the no-arg constructor\n$output"
        }
    }

    @Test
    fun `save pipeline captures pendingEdges snapshot after owner-row read and before hooks`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Pin the ordering: owner-row load → fromRow → pendingEdges
        // snapshot → beforeSave hooks. Capturing after the load means a
        // missing-row return doesn't waste a snapshot; capturing before
        // hooks means hooks see the snapshot.
        val fromRowIdx = output.indexOf("entity = M2MPost.fromRow(row0)")
        val captureIdx = output.indexOf("val pendingEdges = _buildPendingEdgeOps()")
        val beforeSaveIdx = output.indexOf("for (hook in beforeSaveHooks) hook(_beforeSaveView)")
        assert(fromRowIdx != -1 && captureIdx != -1 && beforeSaveIdx != -1) {
            "Missing one of the pipeline anchors\n$output"
        }
        assert(fromRowIdx < captureIdx) {
            "pendingEdges snapshot must be captured AFTER owner-row load\n$output"
        }
        assert(captureIdx < beforeSaveIdx) {
            "pendingEdges snapshot must be captured BEFORE beforeSave hooks fire\n$output"
        }
    }

    @Test
    fun `beforeSave on M2M-capable update receives the shared-only adapter, not the concrete update builder or update view`() {
        // Two cast-attack vectors are closed:
        //   1. `mutation as ${Schema}Update` — would reach the public
        //      `tags` mutator and corrupt the pendingEdges snapshot/live
        //      contract.
        //   2. `mutation as ${Schema}UpdateMutationView` — would reach
        //      `unsetTitle()` (silently drop a caller's patch entry)
        //      or `pendingEdges` (read-only, informational leak).
        // `_beforeSaveView` implements ONLY ${Schema}Mutation, so both
        // casts fail at runtime — beforeSave hooks see exactly the
        // shared write surface the docs promise. beforeUpdate hooks
        // continue to receive `_mutationView` via ctx.mutation.
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("for (hook in beforeSaveHooks) hook(_beforeSaveView)")) {
            "beforeSave on update saves must receive _beforeSaveView (shared-only adapter)\n$output"
        }
        assert(!output.contains("for (hook in beforeSaveHooks) hook(this)")) {
            "Old `hook(this)` shape must be gone — it exposed the concrete-builder cast attack\n$output"
        }
        assert(!output.contains("for (hook in beforeSaveHooks) hook(_mutationView)")) {
            "Intermediate `hook(_mutationView)` shape must be gone — it exposed the view cast attack\n$output"
        }
    }

    @Test
    fun `beforeSave on non-M2M update also receives the shared-only adapter for uniform behavior`() {
        // Apply the fix uniformly across all update schemas so a
        // schema gaining a throughLink edge later doesn't silently
        // change the hook surface.
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("for (hook in beforeSaveHooks) hook(_beforeSaveView)")) {
            "Non-M2M schemas should also pass the shared-only adapter to beforeSave\n$output"
        }
    }

    @Test
    fun `_beforeSaveView is the shared-only adapter — implements Mutation, omits pendingEdges and unset`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // The adapter is typed as M2MPostMutation (the shared
        // interface), not M2MPostUpdateMutationView.
        assert(output.contains("private val _beforeSaveView: M2MPostMutation = object : M2MPostMutation")) {
            "_beforeSaveView must implement M2MPostMutation directly, not the update view\n$output"
        }
        // Find the adapter block start, then scan a bounded window
        // forward to its closing brace pattern. The adapter must NOT
        // expose pendingEdges or unsetTitle inside its body — those
        // belong to the update view, which the adapter doesn't inherit.
        val viewIdx = output.indexOf("private val _beforeSaveView: M2MPostMutation = object : M2MPostMutation")
        assert(viewIdx != -1) { "Missing _beforeSaveView adapter\n$output" }
        // The adapter body is small (just scalar forwarders); window
        // out to the next `private` declaration after it.
        val nextPrivateIdx = output.indexOf("private", viewIdx + 1).let { if (it == -1) output.length else it }
        val adapterWindow = output.substring(viewIdx, nextPrivateIdx)
        assert(!adapterWindow.contains("pendingEdges")) {
            "_beforeSaveView must not carry pendingEdges (that's update-view-only)\n$adapterWindow"
        }
        assert(!adapterWindow.contains("unsetTitle")) {
            "_beforeSaveView must not carry unsetTitle (that's update-view-only)\n$adapterWindow"
        }
    }

    @Test
    fun `update hook context constructor receives pendingEdges`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // The 5-arg form: (client, entity, snapshot, pendingEdges, _mutationView).
        assert(output.contains("M2MPostUpdateHookContext(client, entity, snapshot, pendingEdges, _mutationView)")) {
            "beforeUpdate hook context should receive pendingEdges as the 4th argument\n$output"
        }
    }

    @Test
    fun `mutation view adapter pendingEdges routes through the captured snapshot, not a fresh rebuild`() {
        // The adapter returns the SAME pendingEdges instance that the
        // hook context received. The previous
        // _buildPendingEdgeOps() rebuild path was structurally
        // equivalent (because hook code can't mutate the op log
        // through the view) but every read paid a fresh allocation
        // and the two surfaces returned different object instances.
        // Routing through _capturedPendingEdges keeps them
        // object-identity equal and forecloses divergence if any
        // future path ever reaches the live mutator post-snapshot.
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains(
            "override val pendingEdges: M2MPostPendingEdgeOps get() = " +
                "this@M2MPostUpdate._capturedPendingEdges",
        )) {
            "Adapter pendingEdges getter must read the captured snapshot field\n$output"
        }
        // Error path when accessed outside save context (field still
        // null at adapter-touch time).
        assert(output.contains("pendingEdges accessed outside a save()")) {
            "Adapter getter should error when the captured snapshot is unset\n$output"
        }
        // The old direct delegation to _buildPendingEdgeOps() in the
        // getter must be gone — that was the divergence vector.
        assert(!output.contains(
            "override val pendingEdges: M2MPostPendingEdgeOps get() = " +
                "this@M2MPostUpdate._buildPendingEdgeOps()",
        )) {
            "Old direct-rebuild adapter getter must be gone\n$output"
        }
    }

    @Test
    fun `update class declares the captured pendingEdges snapshot field, even for non-M2M schemas`() {
        // The adapter is always generated (UpdateMutationView always
        // carries pendingEdges typed against the per-entity aggregator,
        // empty or not), so the field that backs it must always be
        // present too — otherwise the getter wouldn't compile on
        // non-M2M schemas.
        val user = User()
        finalize(user, Car())
        val userOutput = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")
        assert(userOutput.contains("private var _capturedPendingEdges: UserPendingEdgeOps?")) {
            "Non-M2M schemas should also declare _capturedPendingEdges\n$userOutput"
        }

        val (post, _, _, names) = makeLinkM2MSchemas()
        val postOutput = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")
        assert(postOutput.contains("private var _capturedPendingEdges: M2MPostPendingEdgeOps?")) {
            "M2M-capable schemas declare _capturedPendingEdges typed against their aggregator\n$postOutput"
        }
    }

    @Test
    fun `save populates _capturedPendingEdges immediately inside the try block`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // The assignment is the FIRST statement inside the try, so the finally that clears
        // it covers every observable exit. Snapshot value flows into
        // the field and into the hook context constructor —
        // guaranteeing object identity between ctx.pendingEdges and
        // ctx.mutation.pendingEdges.
        assert(output.contains(
            "val pendingEdges = _buildPendingEdgeOps() try { _capturedPendingEdges = pendingEdges",
        )) {
            "save() must enter try { } immediately after the snapshot and assign _capturedPendingEdges first\n$output"
        }
    }

    @Test
    fun `save clears _capturedPendingEdges in a finally that wraps every exit path`() {
        // The try/finally must wrap the entire post-assignment region
        // so all exits clear the field — including every
        // `return MutationResult.failedForInternalUse(...)` (privacy
        // denial, validation, vanished target, driver failure), the
        // no-op `return MutationResult.Success(entity)`, the final
        // `return MutationResult.Success(updatedEntity)`, and any
        // exception escaping hooks toward the outer capture boundary.
        // Without the finally, a hook stashing ctx.mutation could read
        // pendingEdges post-save — contradicting the getter's contract.
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // The finally appears once, after the success return. Kotlin's
        // try/finally semantics cover all other in-block exits (Failed
        // returns, propagating exceptions) without extra structure.
        assert(output.contains("return MutationResult.Success(updatedEntity) } finally { _capturedPendingEdges = null }")) {
            "executeSave must clear _capturedPendingEdges in a finally that follows the success return\n$output"
        }
        // Symmetry guard: also fires for non-M2M schemas, since the
        // _capturedPendingEdges field exists uniformly across all
        // Update classes (per design).
        val user = User()
        finalize(user, Car())
        val userOutput = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")
        assert(userOutput.contains("return MutationResult.Success(updatedEntity) } finally { _capturedPendingEdges = null }")) {
            "Non-M2M schemas must also clear _capturedPendingEdges in finally\n$userOutput"
        }
    }

    // ---------- link-table M2M save-time preflight ----------

    @Test
    fun `update builder emits _hasPendingLinkTableM2MOps that ORs mutator hasOps flags`() {
        val (doc, _, _, _, names) = makeMultiEdgeSchemas()
        val output = generator.generate("M2MDoc", doc, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Two helper-eligible edges → ORed across both mutators.
        assert(output.contains("private fun _hasPendingLinkTableM2MOps(): Boolean")) {
            "Expected _hasPendingLinkTableM2MOps helper\n$output"
        }
        // KotlinPoet collapses single-`return` block bodies to expression
        // bodies — accept either form.
        assert(output.contains("return this.tags.hasOps() || this.labels.hasOps()") ||
               output.contains("_hasPendingLinkTableM2MOps(): Boolean = this.tags.hasOps() || this.labels.hasOps()")) {
            "Expected OR across each helper-eligible mutator's hasOps()\n$output"
        }
    }

    @Test
    fun `update builder emits _hasPendingLinkTableM2MInserts that ORs mutator hasInserts flags`() {
        val (doc, _, _, _, names) = makeMultiEdgeSchemas()
        val output = generator.generate("M2MDoc", doc, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("private fun _hasPendingLinkTableM2MInserts(): Boolean")) {
            "Expected _hasPendingLinkTableM2MInserts helper\n$output"
        }
        assert(output.contains("return this.tags.hasInserts() || this.labels.hasInserts()") ||
               output.contains("_hasPendingLinkTableM2MInserts(): Boolean = this.tags.hasInserts() || this.labels.hasInserts()")) {
            "Expected OR across each helper-eligible mutator's hasInserts()\n$output"
        }
    }

    @Test
    fun `edge mutator exposes hasInserts that is true only for a pending add or set`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Anchor on the function name so this can't accidentally match the
        // hasOps() body (which also starts with `_requestedSet != null ||
        // _adds.isNotEmpty()` but additionally ORs `_removes`).
        assert(output.contains("hasInserts(): Boolean = _requestedSet != null || _adds.isNotEmpty()") ||
               output.contains("hasInserts(): Boolean { return _requestedSet != null || _adds.isNotEmpty() }")) {
            "hasInserts must be true for a pending set or add and exclude remove-only state\n$output"
        }
    }

    @Test
    fun `save emits supportsInsertIgnore preflight gated on pending inserts`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Gated on _hasPendingLinkTableM2MInserts() (not _hasPendingLinkTableM2MOps())
        // so a remove-only save — which never calls insertIgnore — is exempt
        // from the capability requirement.
        assert(output.contains("if (_hasPendingLinkTableM2MInserts() && !driver.supportsInsertIgnore)")) {
            "supportsInsertIgnore preflight must be gated on pending inserts\n$output"
        }
        assert(output.contains("requires a driver with supportsInsertIgnore = true")) {
            "Expected the insertIgnore capability error message\n$output"
        }
    }

    // ---------- symmetric link-table writes canonical relationship locking ----------

    @Test
    fun `update ctor takes a relationshipLocking parameter and property`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("private val relationshipLocking: RelationshipLocking")) {
            "Update class must carry a relationshipLocking property\n$output"
        }
    }

    @Test
    fun `save emits the Canonical supportsRelationshipSerialization preflight`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("if (relationshipLocking == RelationshipLocking.Canonical && !driver.supportsRelationshipSerialization)")) {
            "Expected the Canonical capability preflight\n$output"
        }
        assert(output.contains("requires a driver with supportsRelationshipSerialization = true")) {
            "Expected the Canonical capability error message\n$output"
        }
    }

    @Test
    fun `save takes the canonical relationship lock only under Canonical, before the owner-row read`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Gated on Canonical AND the edge having pending ops; keyed by the
        // canonical (sorted) FK pair so both orientations lock the same key.
        assert(output.contains(
            "if (relationshipLocking == RelationshipLocking.Canonical && (this.tags.hasOps())) { " +
                "driver.serializeRelationship(RelationshipLockKey.canonical(\"m2m_post_tags\", listOf(\"post_id\", \"tag_id\"))) }",
        )) {
            "Expected the guarded canonical relationship lock\n$output"
        }

        // Positioned AFTER the Canonical capability preflight and strictly
        // BEFORE the owner-row read: the owner read FOR-UPDATEs the owner row
        // that the opposite orientation's junction insert FK-checks, so taking
        // the relationship lock first is what makes the two deadlock-free.
        val preflightIdx = output.indexOf("if (relationshipLocking == RelationshipLocking.Canonical && !driver.supportsRelationshipSerialization)")
        val lockIdx = output.indexOf("driver.serializeRelationship(RelationshipLockKey.canonical(\"m2m_post_tags\"")
        val ownerReadIdx = output.indexOf("val row0 = try { if (consistency == UpdateConsistency.Pessimistic)")
        assert(preflightIdx != -1 && lockIdx != -1 && ownerReadIdx != -1) {
            "Missing one of the ordering anchors\n$output"
        }
        assert(preflightIdx < lockIdx && lockIdx < ownerReadIdx) {
            "Relationship lock must sit after the Canonical preflight and before the owner-row read\n$output"
        }
    }

    @Test
    fun `multi-relationship canonical locks are emitted in ascending key order`() {
        val (doc, _, _, _, names) = makeMultiEdgeSchemas()
        val output = generator.generate("M2MDoc", doc, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Junction tables sort m2m_doc_labels < m2m_doc_tags, so the labels
        // lock must be acquired before the tags lock (deadlock-free order).
        val labelsIdx = output.indexOf("RelationshipLockKey.canonical(\"m2m_doc_labels\"")
        val tagsIdx = output.indexOf("RelationshipLockKey.canonical(\"m2m_doc_tags\"")
        assert(labelsIdx != -1 && tagsIdx != -1) {
            "Expected a relationship lock for each junction\n$output"
        }
        assert(labelsIdx < tagsIdx) {
            "Relationship locks must be emitted in ascending canonical-key order\n$output"
        }
    }

    @Test
    fun `two edges to the same junction collapse to one relationship lock`() {
        val (doc, names) = makeDupJunctionSchemas()
        val output = generator.generate("DupJunctionDoc", doc, names).toString()
            .replace("\\s+".toRegex(), " ")

        // the de-dup must produce ONE lock for the shared junction,
        // not one per edge.
        val lockCount = Regex("RelationshipLockKey\\.canonical\\(\"dup_junction_doc_tags\"")
            .findAll(output).count()
        assert(lockCount == 1) {
            "Two edges to one junction must collapse to a single relationship lock; found $lockCount\n$output"
        }
        // The single lock's guard ORs both edges' hasOps().
        assert(output.contains("this.tags.hasOps() || this.moreTags.hasOps()") ||
               output.contains("this.moreTags.hasOps() || this.tags.hasOps()")) {
            "The collapsed lock guard must OR both edges' hasOps()\n$output"
        }
    }

    @Test
    fun `update builder emits _checkLinkTableM2MMixedMode that dispatches per-edge to validateInvariants`() {
        // the mixed-mode invariants live on the mutator
        // itself (`validateInvariants()`) now that the op-log fields
        // are private. The save-preflight helper just dispatches per
        // edge.
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("private fun _checkLinkTableM2MMixedMode()")) {
            "Expected _checkLinkTableM2MMixedMode helper\n$output"
        }
        assert(output.contains("this.tags.validateInvariants()")) {
            "Expected per-edge dispatch to mutator's validateInvariants() accessor\n$output"
        }
        // The actual invariant assertions now live inside the
        // mutator class's validateInvariants() body — pin the error
        // messages there.
        assert(output.contains("edge 'tags': cannot mix replacement (set) and delta (add/remove)")) {
            "Replacement-vs-delta error message must still surface from the mutator\n$output"
        }
        assert(output.contains("edge 'tags': delta add/remove sets overlap")) {
            "Same-id overlap error message must still surface from the mutator\n$output"
        }
    }

    @Test
    fun `save pipeline enforces M2M preflight after Pessimistic preflight and before owner-row read`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // The M2M preflight slots between the Pessimistic preflight
        // (already emitted by transaction locking) and the owner-row read. Pinning
        // ordering by string indices.
        val pessIdx = output.indexOf("if (consistency == UpdateConsistency.Pessimistic)")
        val m2mIdx = output.indexOf("if (_hasPendingLinkTableM2MOps())")
        val readIdx = output.indexOf("val row0 = try { if (consistency == UpdateConsistency.Pessimistic)")
        assert(pessIdx != -1 && m2mIdx != -1 && readIdx != -1) {
            "Missing one of the preflight anchors\n$output"
        }
        assert(pessIdx < m2mIdx) {
            "Pessimistic preflight must come before M2M preflight\n$output"
        }
        assert(m2mIdx < readIdx) {
            "M2M preflight must come before the owner-row read\n$output"
        }
    }

    @Test
    fun `M2M preflight throws TransactionRequiredException then UnsupportedDriverCapabilityException then mixed-mode`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        val m2mIdx = output.indexOf("if (_hasPendingLinkTableM2MOps())")
        assert(m2mIdx != -1) { "Missing M2M preflight block\n$output" }
        val m2mBlock = output.substring(m2mIdx, (m2mIdx + 1500).coerceAtMost(output.length))

        // Order inside the block: inTransaction → capability → mixed-mode.
        val txIdx = m2mBlock.indexOf("if (!driver.inTransaction)")
        val capIdx = m2mBlock.indexOf("if (!driver.supportsReadRowForUpdate && !driver.supportsOwnerEdgeSerialization)")
        val mixedIdx = m2mBlock.indexOf("_checkLinkTableM2MMixedMode()")
        assert(txIdx != -1 && capIdx != -1 && mixedIdx != -1) {
            "Missing one of: tx check, capability check, mixed-mode call\n$m2mBlock"
        }
        assert(txIdx < capIdx) {
            "TransactionRequiredException must fire before the capability check\n$m2mBlock"
        }
        assert(capIdx < mixedIdx) {
            "UnsupportedDriverCapabilityException must fire before mixed-mode defense\n$m2mBlock"
        }

        // Exception types + diagnostic messages.
        assert(m2mBlock.contains(
            "throw TransactionRequiredException(\"M2MPost link-table M2M update requires a transaction-scoped client\")",
        )) {
            "Tx-required message should name the schema and the M2M-update path\n$m2mBlock"
        }
        assert(m2mBlock.contains(
            "throw UnsupportedDriverCapabilityException(\"M2MPost link-table M2M update requires a driver with supportsReadRowForUpdate or supportsOwnerEdgeSerialization\")",
        )) {
            "Capability message should mention both acceptable capabilities\n$m2mBlock"
        }
    }

    @Test
    fun `M2M preflight accepts driver-family fallback — either readRowForUpdate or ownerEdgeSerialization is enough`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // The capability check uses && to require BOTH flags to be
        // false before throwing — i.e. it accepts when EITHER is true.
        // (The plan's "looser" fallback contrasts with the Pessimistic
        // preflight, which strictly requires supportsReadRowForUpdate.)
        assert(output.contains("if (!driver.supportsReadRowForUpdate && !driver.supportsOwnerEdgeSerialization)")) {
            "Capability check should accept either readRowForUpdate or ownerEdgeSerialization\n$output"
        }
        assert(!output.contains("if (!driver.supportsReadRowForUpdate || !driver.supportsOwnerEdgeSerialization)")) {
            "Capability check must NOT require both flags — || would over-constrain the fallback\n$output"
        }
    }

    @Test
    fun `schemas without helper-eligible M2M edges emit no M2M preflight helpers or block`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // No mutator scaffolding → no M2M preflight helpers and no
        // _hasPendingLinkTableM2MOps() gate in save().
        assert(!output.contains("_hasPendingLinkTableM2MOps")) {
            "Schemas without helper-eligible M2M edges should not get the M2M ops helper\n$output"
        }
        assert(!output.contains("_checkLinkTableM2MMixedMode")) {
            "Schemas without helper-eligible M2M edges should not get the mixed-mode helper\n$output"
        }
    }

    @Test
    fun `mixed-mode defense fires per helper-eligible edge — two edges yield two checks`() {
        val (doc, _, _, _, names) = makeMultiEdgeSchemas()
        val output = generator.generate("M2MDoc", doc, names).toString()
            .replace("\\s+".toRegex(), " ")

        // One check per edge, each naming the offending edge.
        assert(output.contains("edge 'tags': cannot mix")) {
            "Defense-in-depth should check the tags edge\n$output"
        }
        assert(output.contains("edge 'labels': cannot mix")) {
            "Defense-in-depth should check the labels edge\n$output"
        }
    }

    @Test
    fun `defense-in-depth also catches same-id overlap between _adds and _removes`() {
        // with the op-log fields now private, the overlap
        // check lives inside the mutator's validateInvariants() body
        // — and the save-preflight _checkLinkTableM2MMixedMode calls
        // it via this.tags.validateInvariants(). Test both: the
        // mutator implements the check, and the preflight dispatches.
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Inside the mutator's validateInvariants():
        assert(output.contains(
            "if ((_adds.toSet() intersect _removes.toSet()).isNotEmpty()) throw IllegalStateException",
        )) {
            "Mutator's validateInvariants() must catch same-id overlap between _adds and _removes\n$output"
        }
        // The named-edge error message.
        assert(output.contains("edge 'tags': delta add/remove sets overlap")) {
            "Overlap-check message should name the edge and the overlap shape\n$output"
        }
    }

    @Test
    fun `multi-edge schemas dispatch validateInvariants per edge in the preflight`() {
        // the save-preflight _checkLinkTableM2MMixedMode
        // calls each mutator's validateInvariants(). Pin the
        // per-edge dispatch.
        val (doc, _, _, _, names) = makeMultiEdgeSchemas()
        val output = generator.generate("M2MDoc", doc, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("this.tags.validateInvariants()")) {
            "Expected per-edge dispatch for tags\n$output"
        }
        assert(output.contains("this.labels.validateInvariants()")) {
            "Expected per-edge dispatch for labels\n$output"
        }
        // Each mutator's body carries its own overlap check, with
        // the edge name in the error message.
        assert(output.contains("edge 'tags': delta add/remove sets overlap")) {
            "Each mutator should embed its named overlap message\n$output"
        }
        assert(output.contains("edge 'labels': delta add/remove sets overlap")) {
            "Each mutator should embed its named overlap message\n$output"
        }
    }

    // ---------- link-table M2M helpers three-way owner-row read + junction reads + EdgeChanges ----------

    @Test
    fun `M2M-capable schema emits three-way owner-row read primitive choice`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Four-arm read, inside the canonical capture boundary:
        //   Pessimistic                            → readRowForUpdate
        //   M2M pending + supportsReadRowForUpdate → readRowForUpdate
        //   M2M pending + !supportsReadRowForUpdate→ serializeOwnerEdgeAndRead
        //   ReadCurrent (no M2M pending)           → byId
        assert(output.contains("val row0 = try { if (consistency == UpdateConsistency.Pessimistic)")) {
            "Pessimistic branch should fire first, inside the capture boundary\n$output"
        }
        assert(output.contains("else if (_hasPendingLinkTableM2MOps() && driver.supportsReadRowForUpdate)")) {
            "M2M+RRFU branch should reuse readRowForUpdate\n$output"
        }
        assert(output.contains("else if (_hasPendingLinkTableM2MOps()) { driver.serializeOwnerEdgeAndRead(M2MPost.TABLE, id) }")) {
            "M2M+!RRFU branch should fall through to serializeOwnerEdgeAndRead\n$output"
        }
        assert(output.contains("else { driver.byId(M2MPost.TABLE, id) }")) {
            "byId fallback path should remain for ReadCurrent without M2M pending\n$output"
        }
        // No null-return exits: a failing read maps through the
        // classifier with NotPersisted, and an absent owner row is
        // Failed(EntTargetAbsentException).
        assert(!output.contains("?: return null")) {
            "Null-return owner-read exits must be gone — failures are MutationResult.Failed\n$output"
        }
        assert(
            output.contains(
                "} catch (e: CancellationException) { throw e } catch (e: Exception) { " +
                    "val classified = _classifyDriverFailure(e, MutationWriteState.NotPersisted) " +
                    "client.recordTransactionMutationFailure(classified) " +
                    "return MutationResult.failedForInternalUse(classified) } " +
                    "if (row0 == null) { val exception = EntTargetAbsentException(\"M2MPost\", EntityKey(\"id\", id))",
            ),
        ) {
            "Owner-read failure must classify with NotPersisted; absent row0 must be Failed(EntTargetAbsentException)\n$output"
        }
    }

    @Test
    fun `schemas without helper-eligible M2M edges keep the existing two-way owner-row read`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // No M2M → only the existing Pessimistic vs ReadCurrent branch.
        // The serializeOwnerEdgeAndRead path must NOT appear and the
        // M2M-pending guard must NOT appear in the owner-row read.
        assert(!output.contains("serializeOwnerEdgeAndRead")) {
            "Non-M2M schemas should not reference serializeOwnerEdgeAndRead\n$output"
        }
        assert(!output.contains("_hasPendingLinkTableM2MOps()")) {
            "Non-M2M schemas should have no M2M ops gate at all\n$output"
        }
    }

    @Test
    fun `_buildEdgeChanges reads junction state per edge and delegates to runtime computeEdgeChanges`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Helper signature: takes the captured pendingEdges snapshot,
        // returns the per-entity EdgeChangesView.
        assert(output.contains(
            "private fun _buildEdgeChanges(pendingEdges: M2MPostPendingEdgeOps): M2MPostEdgeChangesView",
        )) {
            "Expected _buildEdgeChanges helper taking pendingEdges and returning the view\n$output"
        }

        // Per-edge junction read, guarded by per-mutator hasOps() so we
        // skip the round-trip when nothing was staged.
        // Junction-table queries are erased (no entity scope) per the
        // typed query scopes; Predicate.Leaf<Any> renders the same structural
        // data.
        assert(output.contains(
            "val _current_tags: Set<UUID> = if (this.tags.hasOps()) { " +
                "driver.query(\"m2m_post_tags\", listOf(Predicate.Leaf<Any>(\"post_id\", Op.EQ, this.id)), emptyList(), null, null) " +
                ".map { it[\"tag_id\"] as UUID } .toSet() } else emptySet()",
        )) {
            "Expected per-edge junction read that lowers to Predicate.Leaf<Any>(sourceCol, Op.EQ, this.id)\n$output"
        }

        // The aggregator call delegates per-edge to runtime
        // computeEdgeChanges(pendingEdges.tags, _current_tags).
        assert(output.contains(
            "return M2MPostEdgeChangesView( tags = computeEdgeChanges(pendingEdges.tags, _current_tags), )",
        )) {
            "Expected aggregator to delegate per-edge to runtime computeEdgeChanges\n$output"
        }
    }

    @Test
    fun `_buildEdgeChanges short-circuits to empty view when schema has no helper-eligible edges`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // No junction reads, no computeEdgeChanges calls, just an empty view.
        assert(output.contains("private fun _buildEdgeChanges(pendingEdges: UserPendingEdgeOps): UserEdgeChangesView")) {
            "Helper must still be emitted for uniform shape\n$output"
        }
        assert(output.contains("return UserEdgeChangesView()") ||
               output.contains("_buildEdgeChanges(pendingEdges: UserPendingEdgeOps): UserEdgeChangesView = UserEdgeChangesView()")) {
            "Empty path should short-circuit to no-arg view constructor\n$output"
        }
        assert(!output.contains("computeEdgeChanges")) {
            "No-M2M helper must not reference runtime computeEdgeChanges\n$output"
        }
        assert(!output.contains("driver.query")) {
            "No-M2M helper must not emit junction reads\n$output"
        }
    }

    @Test
    fun `save pipeline computes edgeChanges between requestedPatch and the empty-branch check`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        val patchIdx = output.indexOf("val requestedPatch = _buildRequestedPatch()")
        // The junction read inside _buildEdgeChanges is a driver
        // round-trip, so the M2M-capable build sits inside its own
        // capture boundary classifying failures as NotPersisted.
        val edgeChangesIdx = output.indexOf(
            "val edgeChanges = try { _buildEdgeChanges(pendingEdges) } catch (e: CancellationException) { throw e } " +
                "catch (e: Exception) { val classified = _classifyDriverFailure(e, MutationWriteState.NotPersisted)",
        )
        // gates the empty-branch condition on M2M-pending so
        // M2M-only updates proceed past it.
        val emptyIdx = output.indexOf(
            "if (dirtyFields.isEmpty() && !_hasPendingLinkTableM2MOps()) { val effectivePatch = requestedPatch",
        )
        assert(patchIdx != -1 && edgeChangesIdx != -1 && emptyIdx != -1) {
            "Missing one of: requestedPatch / captured edgeChanges build / empty branch\n$output"
        }
        assert(patchIdx < edgeChangesIdx) {
            "edgeChanges must be computed after the canonical requested patch\n$output"
        }
        assert(edgeChangesIdx < emptyIdx) {
            "edgeChanges must be computed BEFORE the empty branch so both branches see it\n$output"
        }
    }

    @Test
    fun `both evaluateUpdate call sites pass edgeChanges through`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // threads edgeChanges into both the empty-branch AND the
        // non-empty branch's privacy + validation evaluations. The
        // privacy evaluator is the decision-returning
        // updateDenialReasonOrNull (String? reason; null = allowed) —
        // replacing the old throw-style evaluateUpdatePrivacy. Both
        // branches evaluate privacy then validation, all with the
        // sidecar: two occurrences of each.
        val privacyCall = "updateDenialReasonOrNull(privacy, entity, requestedPatch, effectivePatch, candidate, edgeChanges)"
        val privacyOccurrences = output.split(privacyCall).size - 1
        assert(privacyOccurrences == 2) {
            "Expected exactly 2 updateDenialReasonOrNull calls with edgeChanges (empty + non-empty branches), got $privacyOccurrences\n$output"
        }
        val validationCall = "evaluateUpdateValidation(entity, requestedPatch, effectivePatch, candidate, edgeChanges)"
        val validationOccurrences = output.split(validationCall).size - 1
        assert(validationOccurrences == 2) {
            "Expected exactly 2 evaluateUpdateValidation calls with edgeChanges (empty + non-empty branches), got $validationOccurrences\n$output"
        }
        // The old throw-style evaluator is gone.
        assert(!output.contains("evaluateUpdatePrivacy")) {
            "Throw-style evaluateUpdatePrivacy must be replaced by updateDenialReasonOrNull\n$output"
        }
    }

    // ---------- link-table M2M helpers junction writes + edge-only owner-UPDATE suppression ----------

    @Test
    fun `M2M-only update proceeds to the junction writes — the sole empty branch gates on M2M pending`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // EntNoChangesException is gone entirely, and with it the
        // top-of-save syntactic empty check. The single remaining
        // dirtyFields-empty branch (post-hooks, no-op Success) gates on
        // `!_hasPendingLinkTableM2MOps()` so an M2M-only update
        // (dirtyFields empty, edge ops staged — the mutators don't
        // touch dirtyFields) falls through to the write section: the
        // owner UPDATE is skipped via the values.isNotEmpty() guard and
        // the junction writes run.
        assert(!output.contains("EntNoChangesException")) {
            "EntNoChangesException must be gone from M2M-capable updates\n$output"
        }
        val gatedEmpty = output.indexOf(
            "if (dirtyFields.isEmpty() && !_hasPendingLinkTableM2MOps()) { val effectivePatch = requestedPatch",
        )
        assert(gatedEmpty != -1) {
            "The no-op empty branch must be gated on `!_hasPendingLinkTableM2MOps()`\n$output"
        }
        val emptyCount = Regex(Regex.escape("if (dirtyFields.isEmpty()")).findAll(output).count()
        assert(emptyCount == 1) {
            "M2M-capable save must have exactly one (gated) dirtyFields-empty branch, got $emptyCount\n$output"
        }
        val junctionWrites = output.indexOf("if (edgeChanges.tags.added.isNotEmpty())")
        assert(junctionWrites != -1 && gatedEmpty < junctionWrites) {
            "M2M-only updates must fall through the gated empty branch into the junction writes\n$output"
        }
        // Schemas WITHOUT helper-eligible M2M keep the unguarded form
        // so non-M2M flow is untouched.
        val user = User()
        finalize(user, Car())
        val userOutput = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")
        assert(userOutput.contains("if (dirtyFields.isEmpty()) {")) {
            "Non-M2M schemas should keep the ungated `if (dirtyFields.isEmpty())` form\n$userOutput"
        }
        assert(!userOutput.contains("_hasPendingLinkTableM2MOps")) {
            "Non-M2M schemas should not reference the M2M pending gate\n$userOutput"
        }
    }

    @Test
    fun `M2M-capable owner UPDATE is guarded on values isNotEmpty — edge-only saves skip the driver write`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // The owner UPDATE only fires when `values` has at least one
        // Set entry. An edge-only save (caller staged M2M ops, no
        // update defaults populated `values`) skips the driver write
        // and reuses `entity` as the after-state. The write sits inside
        // the canonical capture boundary; a vanished target is
        // Failed(EntTargetAbsentException), and a successful write
        // advances writeState before materializing.
        assert(output.contains(
            "val updatedEntity = if (values.isNotEmpty()) { val row = try { driver.update(M2MPost.TABLE, id, values) } " +
                "catch (e: CancellationException) { throw e } " +
                "catch (e: Exception) { val classified = _classifyDriverFailure(e, MutationWriteState.PersistenceUnknown) " +
                "client.recordTransactionMutationFailure(classified) return MutationResult.failedForInternalUse(classified) } " +
                "if (row == null) { val exception = EntTargetAbsentException(\"M2MPost\", EntityKey(\"id\", id)) " +
                "client.recordTransactionMutationFailure(exception) return MutationResult.failedForInternalUse(exception) } " +
                "writeState = postWriteState " +
                "M2MPost.fromRow(row) } else { entity }",
        )) {
            "Owner UPDATE should be guarded on values.isNotEmpty(); edge-only path reuses `entity`\n$output"
        }
    }

    @Test
    fun `non-M2M schemas keep the unconditional owner UPDATE`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // No helper-eligible M2M edges → no `if (values.isNotEmpty())`
        // guard, just the direct driver.update call inside the
        // canonical capture boundary, with the vanished-target check
        // and unconditional materialization from the returned row.
        assert(!output.contains("val updatedEntity = if (values.isNotEmpty())")) {
            "Non-M2M schemas should NOT gate the owner UPDATE\n$output"
        }
        assert(output.contains(
            "val row = try { driver.update(User.TABLE, id, values) } catch (e: CancellationException) { throw e } " +
                "catch (e: Exception) { val classified = _classifyDriverFailure(e, MutationWriteState.PersistenceUnknown) " +
                "client.recordTransactionMutationFailure(classified) return MutationResult.failedForInternalUse(classified) }",
        )) {
            "Non-M2M schemas keep the unconditional owner UPDATE inside the canonical capture boundary\n$output"
        }
        assert(output.contains(
            "if (row == null) { val exception = EntTargetAbsentException(\"User\", EntityKey(\"id\", id)) " +
                "client.recordTransactionMutationFailure(exception) return MutationResult.failedForInternalUse(exception) }",
        )) {
            "A target that vanishes at write time must be Failed(EntTargetAbsentException)\n$output"
        }
        assert(output.contains("val updatedEntity = User.fromRow(row)")) {
            "Non-M2M schemas materialize updatedEntity unconditionally from the UPDATE row\n$output"
        }
    }

    @Test
    fun `junction inserts iterate added per-edge and use sourceCol+targetCol shape — AUTO_LONG junction omits id key`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // The test junction (M2MPostTag) uses EntId.long() = AUTO_LONG,
        // so the driver mints the junction id and the values map only
        // carries the two FK columns.
        assert(output.contains(
            "if (edgeChanges.tags.added.isNotEmpty()) { for (_targetId in edgeChanges.tags.added) { if (driver.insertIgnore(\"m2m_post_tags\", mapOf(\"post_id\" to id, \"tag_id\" to _targetId), conflictColumns = listOf(\"post_id\", \"tag_id\")) != null) junctionWrites = true } }",
        )) {
            "AUTO_LONG junction inserts should use insertIgnore, omit the id key (driver mints), and iterate added\n$output"
        }
    }

    @Test
    fun `junction deletes use one deleteMany per edge with AND-paired source EQ and target IN predicates`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // One round-trip per edge: deleteMany(table, [sourceCol=ownerId, targetCol IN removed]).
        // Junction-table deletes have no entity scope; Predicate.Leaf<Any>.
        assert(output.contains(
            "if (edgeChanges.tags.removed.isNotEmpty()) { if (driver.deleteMany(\"m2m_post_tags\", listOf(Predicate.Leaf<Any>(\"post_id\", Op.EQ, id), Predicate.Leaf<Any>(\"tag_id\", Op.IN, edgeChanges.tags.removed.toList()))) > 0) junctionWrites = true }",
        )) {
            "Deletes should use one deleteMany per edge with AND-paired source EQ + target IN predicates (erased Predicate.Leaf<Any>)\n$output"
        }
    }

    @Test
    fun `CLIENT_UUID junction insert mints id client-side via UUID randomUUID`() {
        val (post, _, _, names) = makeClientUuidJunctionSchemas()
        val output = generator.generate("UuidJunctionPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // CLIENT_UUID junction → values map carries an "id" key with
        // UUID.randomUUID() in addition to the two FK columns.
        assert(output.contains(
            "if (driver.insertIgnore(\"uuid_junction_post_tags\", mapOf(\"id\" to UUID.randomUUID(), \"post_id\" to id, \"tag_id\" to _targetId), conflictColumns = listOf(\"post_id\", \"tag_id\")) != null) junctionWrites = true",
        )) {
            "CLIENT_UUID junction inserts should mint id via UUID.randomUUID() in the values map and use insertIgnore\n$output"
        }
    }

    @Test
    fun `multi-edge schema emits independent junction writes per edge`() {
        val (doc, _, _, _, names) = makeMultiEdgeSchemas()
        val output = generator.generate("M2MDoc", doc, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Each helper-eligible edge gets its own insert + deleteMany
        // pair, scoped by the per-edge junction table and FK columns.
        assert(output.contains("if (driver.insertIgnore(\"m2m_doc_tags\", mapOf(\"doc_id\" to id, \"tag_id\" to _targetId), conflictColumns = listOf(\"doc_id\", \"tag_id\")) != null) junctionWrites = true")) {
            "Expected per-edge insertIgnore for the tags edge\n$output"
        }
        assert(output.contains("if (driver.insertIgnore(\"m2m_doc_labels\", mapOf(\"doc_id\" to id, \"label_id\" to _targetId), conflictColumns = listOf(\"doc_id\", \"label_id\")) != null) junctionWrites = true")) {
            "Expected per-edge insertIgnore for the labels edge\n$output"
        }
        assert(output.contains("driver.deleteMany(\"m2m_doc_tags\"")) {
            "Expected per-edge deleteMany for the tags edge\n$output"
        }
        assert(output.contains("driver.deleteMany(\"m2m_doc_labels\"")) {
            "Expected per-edge deleteMany for the labels edge\n$output"
        }
    }

    @Test
    fun `non-M2M schemas emit no junction writes`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(!output.contains("edgeChanges.")) {
            "Non-M2M schemas should not reference any edgeChanges.{edge}.added/removed accessors\n$output"
        }
        assert(!output.contains("driver.insert(")) {
            "Non-M2M schemas should not emit any driver.insert (the owner UPDATE is driver.update)\n$output"
        }
    }
}

// ---------- link-table M2M helpers test schemas (CLIENT_UUID junction) ----------

private class UuidJunctionPost : EntSchema("uuid_junction_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<UuidJunctionTag>("tags")
        .throughLink<UuidJunctionPostTag>(UuidJunctionPostTag::post, UuidJunctionPostTag::tag)
}
private class UuidJunctionTag : EntSchema("uuid_junction_tags") {
    override fun id() = EntId.long()
}
private class UuidJunctionPostTag : EntSchema("uuid_junction_post_tags") {
    // CLIENT_UUID junction id: caller (or codegen) mints UUID client-side.
    override fun id() = EntId.uuid()
    val post = belongsTo<UuidJunctionPost>("post").onDelete(entkt.schema.OnDelete.CASCADE)
    val tag = belongsTo<UuidJunctionTag>("tag").onDelete(entkt.schema.OnDelete.CASCADE)
    val pair = index("idx_uuid_junction_post_tags_pair", post.fk, tag.fk).unique()
}
private data class ClientUuidJunctionSchemas(
    val post: UuidJunctionPost,
    val tag: UuidJunctionTag,
    val postTag: UuidJunctionPostTag,
    val names: Map<EntSchema, String>,
)
private fun makeClientUuidJunctionSchemas(): ClientUuidJunctionSchemas {
    val post = UuidJunctionPost()
    val tag = UuidJunctionTag()
    val postTag = UuidJunctionPostTag()
    finalize(post, tag, postTag)
    return ClientUuidJunctionSchemas(
        post, tag, postTag,
        mapOf(
            post to "UuidJunctionPost",
            tag to "UuidJunctionTag",
            postTag to "UuidJunctionPostTag",
        ),
    )
}

// ---------- link-table M2M helpers test schemas ----------

// helper-eligible throughLink (Long-id source, UUID-id target)
private class M2MPost : EntSchema("m2m_posts") {
    override fun id() = EntId.long()
    val title = string("title")
    val tags = manyToMany<M2MTag>("tags")
        .throughLink<M2MPostTag>(M2MPostTag::post, M2MPostTag::tag)
}
private class M2MTag : EntSchema("m2m_tags") {
    override fun id() = EntId.uuid()
    val name = string("name")
}
private class M2MPostTag : EntSchema("m2m_post_tags") {
    override fun id() = EntId.long()
    val post = belongsTo<M2MPost>("post").onDelete(entkt.schema.OnDelete.CASCADE)
    val tag = belongsTo<M2MTag>("tag").onDelete(entkt.schema.OnDelete.CASCADE)
    val pair = index("idx_m2m_post_tags_pair", post.fk, tag.fk).unique()
}

private data class LinkSchemas(
    val post: M2MPost,
    val tag: M2MTag,
    val postTag: M2MPostTag,
    val names: Map<EntSchema, String>,
)
private fun makeLinkM2MSchemas(): LinkSchemas {
    val post = M2MPost()
    val tag = M2MTag()
    val postTag = M2MPostTag()
    finalize(post, tag, postTag)
    return LinkSchemas(
        post, tag, postTag,
        mapOf(post to "M2MPost", tag to "M2MTag", postTag to "M2MPostTag"),
    )
}

// throughEntity (must NOT get mutator codegen)
private class M2MTeam : EntSchema("m2m_teams") {
    override fun id() = EntId.long()
    val members = manyToMany<M2MMember>("members")
        .throughEntity<M2MTeamMembership>(M2MTeamMembership::team, M2MTeamMembership::member)
}
private class M2MMember : EntSchema("m2m_members") {
    override fun id() = EntId.long()
}
private class M2MTeamMembership : EntSchema("m2m_memberships") {
    override fun id() = EntId.long()
    val joinedAt = time("joined_at")
    val team = belongsTo<M2MTeam>("team")
    val member = belongsTo<M2MMember>("member")
}
private data class EntitySchemas(
    val team: M2MTeam,
    val member: M2MMember,
    val membership: M2MTeamMembership,
    val names: Map<EntSchema, String>,
)
private fun makeEntityM2MSchemas(): EntitySchemas {
    val team = M2MTeam()
    val member = M2MMember()
    val membership = M2MTeamMembership()
    finalize(team, member, membership)
    return EntitySchemas(
        team, member, membership,
        mapOf(team to "M2MTeam", member to "M2MMember", membership to "M2MTeamMembership"),
    )
}

// Two helper-eligible throughLink edges on one source — same target type,
// different property names. Mutator naming follows the source edge so
// the two don't collide.
private class M2MDoc : EntSchema("m2m_docs") {
    override fun id() = EntId.long()
    val tags = manyToMany<M2MLabel>("tags")
        .throughLink<M2MDocTag>(M2MDocTag::doc, M2MDocTag::tag)
    val labels = manyToMany<M2MLabel>("labels")
        .throughLink<M2MDocLabel>(M2MDocLabel::doc, M2MDocLabel::label)
}
private class M2MLabel : EntSchema("m2m_labels") {
    override fun id() = EntId.long()
}
private class M2MDocTag : EntSchema("m2m_doc_tags") {
    override fun id() = EntId.long()
    val doc = belongsTo<M2MDoc>("doc").onDelete(entkt.schema.OnDelete.CASCADE)
    val tag = belongsTo<M2MLabel>("tag").onDelete(entkt.schema.OnDelete.CASCADE)
    val pair = index("idx_m2m_doc_tags_pair", doc.fk, tag.fk).unique()
}
private class M2MDocLabel : EntSchema("m2m_doc_labels") {
    override fun id() = EntId.long()
    val doc = belongsTo<M2MDoc>("doc").onDelete(entkt.schema.OnDelete.CASCADE)
    val label = belongsTo<M2MLabel>("label").onDelete(entkt.schema.OnDelete.CASCADE)
    val pair = index("idx_m2m_doc_labels_pair", doc.fk, label.fk).unique()
}
private data class MultiEdgeSchemas(
    val doc: M2MDoc,
    val label: M2MLabel,
    val docTag: M2MDocTag,
    val docLabel: M2MDocLabel,
    val names: Map<EntSchema, String>,
)
private fun makeMultiEdgeSchemas(): MultiEdgeSchemas {
    val doc = M2MDoc()
    val label = M2MLabel()
    val docTag = M2MDocTag()
    val docLabel = M2MDocLabel()
    finalize(doc, label, docTag, docLabel)
    return MultiEdgeSchemas(
        doc, label, docTag, docLabel,
        mapOf(
            doc to "M2MDoc",
            label to "M2MLabel",
            docTag to "M2MDocTag",
            docLabel to "M2MDocLabel",
        ),
    )
}

// Two helper-eligible edges to the *same* junction with the same FK pair.
// Contrived (a full-codegen run would reject the same-orientation alias), but
// it lets the UpdateGenerator unit test exercise the canonical-lock de-dup:
// both edges resolve to one canonical key, so save() must take exactly one
// relationship lock whose guard ORs both mutators.
private class DupJunctionDoc : EntSchema("dup_junction_docs") {
    override fun id() = EntId.long()
    val tags = manyToMany<DupJunctionTag>("tags")
        .throughLink<DupJunctionDocTag>(DupJunctionDocTag::doc, DupJunctionDocTag::tag)
    val moreTags = manyToMany<DupJunctionTag>("more_tags")
        .throughLink<DupJunctionDocTag>(DupJunctionDocTag::doc, DupJunctionDocTag::tag)
}
private class DupJunctionTag : EntSchema("dup_junction_tags") {
    override fun id() = EntId.long()
}
private class DupJunctionDocTag : EntSchema("dup_junction_doc_tags") {
    override fun id() = EntId.long()
    val doc = belongsTo<DupJunctionDoc>("doc").onDelete(entkt.schema.OnDelete.CASCADE)
    val tag = belongsTo<DupJunctionTag>("tag").onDelete(entkt.schema.OnDelete.CASCADE)
    val pair = index("idx_dup_junction_doc_tags_pair", doc.fk, tag.fk).unique()
}
private data class DupJunctionSchemas(
    val doc: DupJunctionDoc,
    val names: Map<EntSchema, String>,
)
private fun makeDupJunctionSchemas(): DupJunctionSchemas {
    val doc = DupJunctionDoc()
    val tag = DupJunctionTag()
    val docTag = DupJunctionDocTag()
    finalize(doc, tag, docTag)
    return DupJunctionSchemas(
        doc,
        mapOf(doc to "DupJunctionDoc", tag to "DupJunctionTag", docTag to "DupJunctionDocTag"),
    )
}
