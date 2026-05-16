package entkt.codegen

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
        // If unrepaired, `_checkRequiredNotNull()` throws after the
        // hook loop and before the canonical patch / privacy / write.
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

        // (3) Safety net: _checkRequiredNotNull throws for unrepaired
        //     dirty+null required fields after the hook loop.
        val checkFnIdx = output.indexOf("private fun _checkRequiredNotNull()")
        val checkFnEnd = output.indexOf("public fun save", checkFnIdx)
        assert(checkFnIdx != -1 && checkFnEnd != -1) {
            "Expected generated _checkRequiredNotNull\n$output"
        }
        val checkFnBody = output.substring(checkFnIdx, checkFnEnd)
        // Throws ValidationException so saveOrError wraps it into
        // EntError.ValidationFailed (Phase 12).
        assert(
            checkFnBody.contains(
                "if (\"name\" in dirtyFields && this.name == null) throw ValidationException(\"User\", listOf(ValidationDecision.Invalid(\"name is required\", field = \"name\")))",
            ),
        ) {
            "_checkRequiredNotNull must throw ValidationException for dirty+null required field as the safety net\n$checkFnBody"
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
        // brace) rather than the function declaration.
        val hookLoop = output.indexOf("for (hook in beforeUpdateHooks)")
        val checkCallSite = output.indexOf("hook(ctx) } _checkRequiredNotNull()")
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

        // The dedicated check must throw when an unrepaired null persists
        // post-hooks. Required-field order in the function body depends on
        // schema declaration order, so just check the body contains the
        // expected per-field throws.
        val checkFnIdx = output.indexOf("private fun _checkRequiredNotNull()")
        assert(checkFnIdx != -1) { "Expected generated _checkRequiredNotNull\n$output" }
        val checkFnEnd = output.indexOf("public fun save", checkFnIdx)
        assert(checkFnEnd != -1) { "Couldn't find end of _checkRequiredNotNull body\n$output" }
        val checkFnBody = output.substring(checkFnIdx, checkFnEnd)
        // Throws ValidationException so saveOrError wraps it into
        // EntError.ValidationFailed (Phase 12).
        assert(
            checkFnBody.contains(
                "if (\"name\" in dirtyFields && this.name == null) throw ValidationException(\"User\", listOf(ValidationDecision.Invalid(\"name is required\", field = \"name\")))",
            ),
        ) {
            "_checkRequiredNotNull() should throw ValidationException for unrepaired null `name`\n$checkFnBody"
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

        // Per the RFC: reading an untouched update property must throw —
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

        // The internal current-row load short-circuits with null for missing rows
        // before any hook, privacy, or validation runs.
        val loadPos = output.indexOf("driver.byId(User.TABLE, id)")
        val hookPos = output.indexOf("for (hook in beforeSaveHooks)")
        assert(loadPos != -1) { "save() should load the current row via driver.byId(...)\n$output" }
        assert(hookPos != -1 && loadPos < hookPos) {
            "Internal current-row load should happen before before-hooks\n$output"
        }
    }

    @Test
    fun `syntactically empty update throws NoChanges before owner-row load`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // The dirtyFields-empty check must run before the byId load to
        // avoid leaking owner-row existence on `update(missingId) {}`.
        val emptyCheckPos = output.indexOf("if (dirtyFields.isEmpty())")
        val throwPos = output.indexOf("throw EntNoChangesException(EntError.NoChanges(\"User\", EntOperation.UPDATE, id))")
        val loadPos = output.indexOf("driver.byId(User.TABLE, id)")
        assert(emptyCheckPos != -1) { "save() should check dirtyFields.isEmpty() at the top\n$output" }
        assert(throwPos != -1) { "save() should throw EntNoChangesException for syntactically empty patches\n$output" }
        assert(emptyCheckPos < loadPos) {
            "Empty-patch check must run before driver.byId to avoid existence leaks\n$output"
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
                "for (hook in beforeUpdateHooks) { val snapshot = _buildRequestedPatch() val ctx = UserUpdateHookContext(client, entity, snapshot, _mutationView) hook(ctx) }",
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
        // valid DSL pattern, contradicting the RFC's "hook-facing"
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
    fun `hook-cleared empty patch runs UPDATE privacy then throws NoChanges`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // After hooks, if the requested patch is empty (dirtyFields was
        // cleared), generated code builds the unchanged candidate, runs
        // UPDATE privacy on it (real authorization decision against
        // `before`), then throws NoChanges. Validation/driver/after-hooks/
        // load-privacy are skipped.
        val emptyCheck = output.indexOf("val requestedPatch = _buildRequestedPatch() if (dirtyFields.isEmpty())")
        assert(emptyCheck != -1) {
            "Hook-cleared check must run after _buildRequestedPatch and before update defaults\n$output"
        }

        // The privacy call inside the empty branch uses the unchanged
        // candidate (built from `before`) with empty patches.
        val emptyBlock = output.substring(emptyCheck, (emptyCheck + 700).coerceAtMost(output.length))
        assert(emptyBlock.contains("val effectivePatch = requestedPatch")) {
            "Hook-cleared branch must use requested as effective (skip update defaults)\n$output"
        }
        assert(emptyBlock.contains("evaluateUpdatePrivacy(privacy, entity, requestedPatch, effectivePatch, candidate)")) {
            "Hook-cleared branch should run UPDATE privacy on the unchanged candidate\n$output"
        }
        assert(emptyBlock.contains("throw EntNoChangesException")) {
            "Hook-cleared branch should throw EntNoChangesException after UPDATE privacy\n$output"
        }
    }

    @Test
    fun `hook-cleared empty patch skips update defaults even when schema has them`() {
        // Regression for the bug where updatedAt = updateDefaultNow() turned
        // every hook-cleared update into a real write: the post-defaults
        // values map was non-empty (it carried the synthetic updatedAt),
        // bypassing the NoChanges branch and writing to the database.
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
        val postHookEmptyPos = output.indexOf(
            "val requestedPatch = _buildRequestedPatch() if (dirtyFields.isEmpty())",
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
    fun `generates the full save result-variant trio`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // saveOrNull is an explicit alias for save() — the canonical name
        // per the result-variants RFC's *OrNull convention.
        assert(output.contains("public fun saveOrNull(): User? = save()")) {
            "Should generate saveOrNull() as an explicit alias for save()\n$output"
        }
        // saveOrError wraps EntException (NotFound, NoChanges) into a
        // structured EntResult; other exceptions still propagate per
        // the result-variants RFC's deferred surface.
        assert(output.contains("public fun saveOrError(): EntResult<User>")) {
            "Should generate saveOrError(): EntResult<User>\n$output"
        }
        assert(output.contains("EntResult.Ok(saveOrThrow())")) {
            "saveOrError should call saveOrThrow and wrap the success in Ok\n$output"
        }
        assert(output.contains("catch (e: EntException) { EntResult.Err(e.error) }")) {
            "saveOrError should catch EntException and unwrap to Err(EntError)\n$output"
        }
        // PrivacyDeniedException → EntError.PrivacyDenied (operation
        // converted from PrivacyOperation by name, since EntOperation
        // mirrors it 1:1).
        assert(
            output.contains(
                "catch (e: PrivacyDeniedException) { EntResult.Err(EntError.PrivacyDenied(e.entity, EntOperation.valueOf(e.operation.name), e.reason)) }",
            ),
        ) {
            "saveOrError should wrap PrivacyDeniedException into EntError.PrivacyDenied\n$output"
        }
        // ValidationException → EntError.ValidationFailed (operation
        // is hardcoded UPDATE since ValidationException doesn't carry
        // its own operation field and this is the update generator).
        assert(
            output.contains(
                "catch (e: ValidationException) { EntResult.Err(EntError.ValidationFailed(e.entity, EntOperation.UPDATE, e.violations)) }",
            ),
        ) {
            "saveOrError should wrap ValidationException into EntError.ValidationFailed\n$output"
        }
    }

    @Test
    fun `saveOrThrow throws EntNotFoundException for missing rows`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // saveOrThrow turns the OrNull `null` (from the internal byId
        // returning no row) into a structured EntNotFoundException.
        // KotlinPoet may emit either an expression body or a block body.
        assert(
            output.contains(
                "save() ?: throw EntNotFoundException(EntError.NotFound(\"User\", EntOperation.UPDATE, id))",
            ),
        ) {
            "saveOrThrow should throw EntNotFoundException carrying EntError.NotFound\n$output"
        }
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

        // Phase 4 moved hook access to ctx.before / ctx.mutation, so
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
            "beforeUpdate hooks now take a UserUpdateHookContext (Phase 4)\n$output"
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
            "Should emit minLen check on patched value\n$output"
        }
        assert(output.contains("name_v.length > 100")) {
            "Should emit maxLen check on patched value\n$output"
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

    // ---------- RFC #5 Phase 2: link-table M2M mutator generation ----------

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
    fun `mutator op log fields are internal so the enclosing Update class can read them`() {
        val (post, tag, postTag, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // _requestedSet, _adds, _removes are internal — Phase 5 needs to
        // read them from the enclosing Update builder for EdgeChanges
        // computation. `private` would make them invisible to the outer
        // class.
        assert(output.contains("internal var _requestedSet: List<UUID>?")) {
            "_requestedSet must be internal so the enclosing Update class can read it\n$output"
        }
        assert(output.contains("internal val _adds: MutableList<UUID>")) {
            "_adds must be internal\n$output"
        }
        assert(output.contains("internal val _removes: MutableList<UUID>")) {
            "_removes must be internal\n$output"
        }
        assert(output.contains("internal fun hasOps(): Boolean")) {
            "hasOps() must be internal — wired into Phase 4's M2M preflight\n$output"
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
        // for each. Hooks read pending edge ops through the Phase 3
        // `pendingEdges` sidecar; they must not reach into the mutator,
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
}

// ---------- RFC #5 Phase 2 test schemas ----------

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
// different property names. Mutator naming follows the source edge
// (decision C) so the two don't collide.
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
