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
        assert(
            checkFnBody.contains(
                "if (\"name\" in dirtyFields && this.name == null) throw IllegalStateException(\"name is required\")",
            ),
        ) {
            "_checkRequiredNotNull() should throw IllegalStateException for unrepaired null `name`\n$checkFnBody"
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
        // context. Writes through `mutation` mutate the underlying
        // builder; the next iteration sees them via a fresh snapshot.
        assert(
            output.contains(
                "for (hook in beforeUpdateHooks) { val snapshot = _buildRequestedPatch() val ctx = UserUpdateHookContext(client, entity, snapshot, this) hook(ctx) }",
            ),
        ) {
            "beforeUpdate hooks should receive a per-call snapshot in a UserUpdateHookContext\n$output"
        }
        // The canonical requestedPatch for privacy/validation is rebuilt
        // after all hooks finish.
        assert(output.contains("val requestedPatch = _buildRequestedPatch()")) {
            "Canonical requestedPatch should be rebuilt after all before hooks\n$output"
        }
    }

    @Test
    fun `unset methods clear builder-requested entries`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // Hooks reach unset via the mutation view to remove a pending
        // patch entry — distinct from Set(null) for nullable fields.
        // The methods are overrides because UserUpdate implements
        // UserUpdateMutationView, which declares them abstract.
        assert(output.contains("override fun unsetName() { dirtyFields.remove(\"name\") }")) {
            "Should generate unsetName() that removes from dirtyFields\n$output"
        }
        assert(output.contains("override fun unsetAge() { dirtyFields.remove(\"age\") }")) {
            "Should generate unsetAge() that removes from dirtyFields\n$output"
        }
    }

    @Test
    fun `update builder implements the restricted UpdateMutationView`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // The builder satisfies the hook-facing view (and transitively
        // the shared Mutation interface). The hook context's `mutation`
        // slot is typed as the view, so hooks can't reach save() / id /
        // entity / private patch helpers through it.
        assert(output.contains("public class UserUpdate") && output.contains(": UserUpdateMutationView")) {
            "UserUpdate should implement UserUpdateMutationView\n$output"
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
    fun `entity is public for hook access`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        // entity should NOT be private — hooks need to inspect current state
        assert(!output.contains("private val entity") && !output.contains("private lateinit var entity")) {
            "entity should be public so hooks can access current state\n$output"
        }
        // Now lateinit because the row is loaded inside save(), not passed in.
        assert(output.contains("lateinit var entity: User")) {
            "Should have public lateinit entity property populated by save()\n$output"
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
    fun `exposes client as public property`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("val client: EntClient")) {
            "Should expose client as public property\n$output"
        }
        assert(!output.contains("private val client")) {
            "client should not be private\n$output"
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
}
