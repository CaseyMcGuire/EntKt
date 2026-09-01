package entkt.codegen

import entkt.codegen.mutation.UpdateGenerator
import entkt.codegen.client.RepoGenerator
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.reflect.KClass
import kotlin.test.Test

private class UpdateDefaultEntity : EntSchema("update_default_entities", clientName = "updateDefaultEntities") {
    override fun id() = EntId.int()
    val name by string("name")
    val updatedAt by time("updated_at").updateDefaultNow()
}

private class LongUpdateAssignmentEntity : EntSchema(
    "long_update_assignment_entities",
    clientName = "longUpdateAssignmentEntities",
) {
    override fun id() = EntId.int()
    val allowExternalSources by bool("allow_external_sources")
}

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class UpdateGeneratorTest {

    private val generator = UpdateGenerator("com.example.ent")

    @Test
    fun `long owner value assignment never wraps before equals`() {
        val schema = LongUpdateAssignmentEntity()
        finalize(schema)
        val output = generator.generate("LongUpdateAssignmentEntity", schema).toString()

        assert(
            output.contains(
                "values[\"allow_external_sources\"] = it.value",
            ),
        ) {
            "Generated assignment must keep its equals operator with the statement\n$output"
        }
    }

    @Test
    fun `generates update draft with mutable properties for mutable fields`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("class UserUpdateDraft")) { "Should generate UserUpdateDraft class\n$output" }
        assert(output.contains("var name: String?")) { "Should have name var\n$output" }
        assert(output.contains("var age: Int?")) { "Should have age var\n$output" }
    }

    @Test
    fun `update draft is annotated as DSL scope`() {
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

        assert(output.contains("createdAt = before.createdAt")) { "Should preserve immutable createdAt\n$output" }
    }

    @Test
    fun `save lowers dirty tracking to FieldPatch entries`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // Hook state preserves an explicit null so a hook can repair it.
        assert(
            output.contains(
                "name = if (\"name\" in dirtyFields) FieldPatch.Set(this.name) else FieldPatch.Unset",
            ),
        ) {
            "Required field should preserve explicit null in hook state\n$output"
        }
        // Nullable field: Set(this.age) — Set(null) is an explicit clear.
        assert(
            output.contains("age = if (\"age\" in dirtyFields) FieldPatch.Set(this.age) else FieldPatch.Unset"),
        ) {
            "Nullable field should lower to FieldPatch.Set(value) / Unset (no !!)\n$output"
        }
        // Candidate folds the effective patch over the loaded `before`.
        assert(output.contains("name = effectivePatch.name.orElse(before.name)")) {
            "Candidate should fold effective patch over entity via orElse(...)\n$output"
        }
    }

    @Test
    fun `required null remains explicit through hooks and is validated before canonical patch`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "name = if (\"name\" in dirtyFields) FieldPatch.Set(this.name) else FieldPatch.Unset",
            ),
        )
        assert(
            output.contains(
                "if (state.name is FieldPatch.Set && state.name.value == null) return listOf(ValidationViolation(\"name is required\", field = \"name\"))",
            ),
        )
        assert(
            output.contains(
                "val requiredViolations = requiredHookStateViolations(hookState) if (requiredViolations.isNotEmpty()) return UpdatePreparation.Invalid(requiredViolations) val requestedPatch = buildRequestedPatch(hookState)",
            ),
        )
    }

    @Test
    fun `generated beforeUpdate values let hooks repair required-null assignments`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("converter = UpdateHookStateConverter(client)"))
        assert(
            output.contains(
                "override fun toBeforeUpdateState( viewerContext: ViewerContext, before: User, pendingEdges: UserPendingEdgeOps, beforeSaveState: UserBeforeSaveState, ): UserBeforeUpdateState = UserBeforeUpdateState(",
            ),
        )
        assert(output.contains("name = beforeSaveState.name"))

        val checkCallSite = output.indexOf("val requiredViolations = requiredHookStateViolations(hookState)")
        val canonicalPatchPos = output.indexOf("val requestedPatch = buildRequestedPatch(hookState)")
        assert(checkCallSite != -1 && canonicalPatchPos != -1) {
            "Expected required-null check call site and canonical patch construction\n$output"
        }
        assert(checkCallSite < canonicalPatchPos) {
            "_checkRequiredNotNull() must be called before the canonical requestedPatch is built\n$output"
        }

        assert(output.contains("is FieldPatch.Set -> FieldPatch.Set(checkNotNull(entry.value))"))
    }

    @Test
    fun `update draft has dirtyFields set`() {
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
                "get() { if (\"name\" !in dirtyFields) throw IllegalStateException(\"name is not set in this update\") return field }",
            ),
        ) {
            "Mutable field getter must throw when the property is not in dirtyFields\n$output"
        }
        // Same for edge FK properties.
        // (User has no edge FKs; check via Pet which has ownerId.)
    }

    // Edge FK property getter throw-on-untouched is asserted in
    // EdgeCodegenTest.`update draft edge FK getter throws on untouched read`,
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
    fun `draft and stable adapter retain no mutation request`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("class UserUpdateDraft @EntktInternal constructor()")) {
            "The draft should contain only configurable update state\n$output"
        }
        assert(!output.contains("private val request: UpdateMutationRequest<UserUpdateDraft>")) {
            "The stateless schema adapter must not retain a request between executions\n$output"
        }
        assert(!output.contains("private val id:") &&
            !output.contains("private val consistency:") &&
            !output.contains("private val relationshipLocking:")) {
            "The schema adapter should not copy request fields into redundant properties\n$output"
        }
    }

    @Test
    fun `passes the request to the runtime executor`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("request = request") &&
            output.contains("entity = UserQuery.GeneratedEntityMapping")) {
            "The generated adapter should pass the request and entity mapping to UpdateMutationExecutor\n$output"
        }
        assert(!output.contains("UpdateMutationSpec")) {
            "UpdateMutationSpec should be removed from generated updates\n$output"
        }
        assert(!output.contains("driver.byId(User.TABLE") &&
            !output.contains("driver.readRowForUpdate(User.TABLE")) {
            "Owner-row selection belongs to UpdateMutationExecutor\n$output"
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
        // phases like any other update; runtime owns the typed absent result.
        assert(!output.contains("EntNoChangesException")) {
            "EntNoChangesException must be gone from the generated update\n$output"
        }
        assert(output.contains("updateExecutor.update(") &&
            output.contains("request = request")) {
            "the generated adapter should delegate the complete request to UpdateMutationExecutor\n$output"
        }
        assert(!output.contains("_loadUpdateRow") && !output.contains("loadRow =")) {
            "generated update code should not retain an owner-row callback\n$output"
        }
        assert(!output.contains("EntTargetAbsentException(")) {
            "generated update code should not duplicate runtime target handling\n$output"
        }
        val emptyCount = Regex(Regex.escape("if (!hasFieldAssignments)")).findAll(output).count()
        assert(emptyCount == 1) {
            "Expected exactly one dirtyFields-empty branch (post-hooks), got $emptyCount\n$output"
        }
    }

    @Test
    fun `beforeUpdate hooks receive immutable state converted from beforeSave state`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("override fun toBeforeSaveState(draft: UserUpdateDraft): UserBeforeSaveState = draft._buildBeforeSaveState()"))
        assert(output.contains("override fun toBeforeUpdateState("))
        assert(output.contains("UserBeforeUpdateState("))
        assert(output.contains("name = beforeSaveState.name"))
        assert(output.contains("val requestedPatch = buildRequestedPatch(hookState)"))
        assert(!output.contains("MutationView") && !output.contains("runFresh"))
    }

    @Test
    fun `unset is absent from the mutable draft and represented by immutable hook state`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(!output.contains("fun unsetName()") && !output.contains("_unsetFieldForInternalUse"))
        assert(output.contains("UserBeforeUpdateState"))
    }

    @Test
    fun `update draft implements only its runtime draft contract`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains("public class UserUpdateDraft") &&
                output.contains(") : UpdateMutationDraft<User> {"),
        ) {
            "UserUpdateDraft should implement only UpdateMutationDraft<User>\n$output"
        }
        assert(!output.contains("MutationView") && !output.contains("UserMutation"))
    }

    @Test
    fun `hook-cleared empty patch prepares a no-op state for runtime rule evaluation`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        val emptyCheck = output.indexOf(
            "val edgeChanges = _buildEdgeChanges(request.id, pendingEdges) if (!hasFieldAssignments)",
        )
        assert(emptyCheck != -1) {
            "Hook-cleared check must run after _buildRequestedPatch+_buildEdgeChanges and before update defaults\n$output"
        }

        val readyPos = output.indexOf("isNoOp = true", emptyCheck)
        assert(readyPos > emptyCheck) { "Hook-cleared branch must prepare isNoOp = true\n$output" }
        val emptyBlock = output.substring(emptyCheck, readyPos)
        assert(emptyBlock.contains("val effectivePatch = requestedPatch")) {
            "Hook-cleared branch must use requested as effective (skip update defaults)\n$output"
        }
        assert(emptyBlock.contains("state = PreparedState(") && emptyBlock.contains("values = emptyMap()")) {
            "No-op preparation should retain the unchanged rule state without owner values\n$output"
        }
        assert(output.contains("privacyEvaluator = mutationPrivacyEvaluatorForInternalUse(") &&
            output.contains("validationEvaluator = mutationValidationEvaluatorForInternalUse(")) {
            "updateExecutor should pass the no-op state through runtime privacy and validation evaluators\n$output"
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
        val emptyCheckPos = output.indexOf("if (!hasFieldAssignments)")
        val effectivePatchPos = output.indexOf(
            "val effectivePatch = UpdateDefaultEntityUpdatePatch(",
        )
        assert(emptyCheckPos != -1 && effectivePatchPos != -1) {
            "Expected hook-cleared check and effective patch construction\n$output"
        }
        // The TOP empty-check is at index 0-ish; we want the SECOND occurrence
        // (after the byId load + hook loop) to come before the effective patch
        // construction. Find it via the canonical preceding marker.
        // _buildEdgeChanges call sits between _buildRequestedPatch
        // and the post-hook empty check.
        val postHookEmptyPos = output.indexOf(
            "val edgeChanges = _buildEdgeChanges(request.id, pendingEdges) if (!hasFieldAssignments)",
        )
        assert(postHookEmptyPos != -1) { "Expected post-hook empty check\n$output" }
        assert(postHookEmptyPos < effectivePatchPos) {
            "Hook-cleared check must run before update-default application " +
                "(otherwise updatedAt = Set(now) sneaks into values and the write happens)\n$output"
        }
        assert(!output.contains("driver.update(")) {
            "owner persistence belongs entirely to UpdateMutationExecutor\n$output"
        }
    }

    @Test
    fun `generated update code delegates one execution path to the runtime operation`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("public fun execute( viewerContext: ViewerContext, request: UpdateMutationRequest<UserUpdateDraft>, applyLoadPrivacy: Boolean, ): MutationResult<User> = updateExecutor.update(")) {
            "The stateless adapter should delegate each explicit request to UpdateMutationExecutor\n$output"
        }
        assert(!output.contains("class Execution")) { "Generated updates should have no per-request execution holder\n$output" }
        assert(!output.contains("public fun save(") && !output.contains("public fun saveAndLoad(")) {
            "Save terminals belong to the generic runtime PendingUpdateMutation, not the generated draft\n$output"
        }
        assert(!output.contains("_validationFailed") && !output.contains("_classifyDriverFailure") &&
            !output.contains("MutationResult.failedForInternalUse")) {
            "failure construction and classification should not be generated\n$output"
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
    fun `implements the update draft contract without a mutable hook interface`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("UpdateMutationDraft<User>"))
        assert(!output.contains("UserMutation"))
    }

    @Test
    fun `loaded entity remains an explicit method input`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(!output.contains("lateinit var entity") &&
            output.contains("name = effectivePatch.name.orElse(before.name)")) {
            "Generated update code should use the loaded before parameter without mutable entity state\n$output"
        }
    }

    @Test
    fun `adapter takes client and typed hook runners`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("client: EntClient")) {
            "Should take client\n$output"
        }
        assert(output.contains("beforeSaveHookRunner: MutationHookRunner<UserBeforeSaveState>")) {
            "Should take a typed beforeSave runner\n$output"
        }
        assert(output.contains("beforeUpdateHookRunner: MutationHookRunner<UserBeforeUpdateState>")) {
            "Should take a typed beforeUpdate runner\n$output"
        }
        assert(output.contains("afterUpdateHookRunner: HookRunner<User>")) {
            "Should take a typed afterUpdate runner\n$output"
        }
    }

    @Test
    fun `client is private on the stable adapter`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        // Update hooks receive ctx.client via the hook context, and
        // the DSL caller necessarily already has `client` in scope
        // (they called `client.users.update(id) { ... }`). Exposing
        // a public client on the update draft would add zero capability
        // and surface area we don't want.
        assert(output.contains("private val client: EntClient")) {
            "client should be private on the update adapter\n$output"
        }
    }

    @Test
    fun `Pessimistic lifecycle behavior is not emitted into the schema adapter`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(!output.contains("UpdateConsistency.Pessimistic") &&
            !output.contains("supportsReadRowForUpdate") &&
            !output.contains("readRowForUpdate(")) {
            "Pessimistic validation and row selection belong to UpdateMutationExecutor\n$output"
        }
    }

    @Test
    fun `stable adapter supplies one runtime hook lifecycle to the executor`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "hooks = UpdateMutationHooks( converter = UpdateHookStateConverter(client), beforeSave = beforeSaveHookRunner, beforeUpdate = beforeUpdateHookRunner, afterUpdate = afterUpdateHookRunner, ),",
            ),
        ) {
            "The generated adapter should inject one hook lifecycle into the executor\n$output"
        }
        assert(output.contains("private class UpdateHookStateConverter(")) {
            "Update adapters should generate one schema-specific state converter implementation\n$output"
        }
        assert(!output.contains("ValueFactory")) {
            "Generated update wiring should not use callback factories\n$output"
        }
    }

    @Test
    fun `stable adapter supplies after hooks to the runtime executor`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("afterUpdate = afterUpdateHookRunner")) {
            "The generated adapter should inject the afterUpdate runner into runtime hooks\n$output"
        }
        assert(!output.contains("override fun runAfterUpdate(")) {
            "Generated code should not implement afterUpdate execution\n$output"
        }
        assert(!output.contains("runBatchHooksForInternalUse(listOf(updatedEntity)")) {
            "generated code should not execute afterUpdate hooks itself\n$output"
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
        assert(output.contains("name = effectivePatch.name.orElse(before.name)")) {
            "Candidate should fold effective patch over entity via orElse(...) for non-default fields\n$output"
        }
    }

    // NOTE: The old test `updateDefault Now on non-TIME field is rejected` has been
    // removed because the typed builder API now prevents this at compile time —
    // updateDefaultNow() only exists on TimeFieldBuilder.

    // ---------- link-table M2M mutator generation ----------

    @Test
    fun `update draft for schema with throughLink M2M edge gets a nested mutator class`() {
        val (post, tag, postTag, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Nested mutator class is emitted on the update draft.
        assert(output.contains("public class TagsEdgeMutator internal constructor()")) {
            "Should generate nested TagsEdgeMutator class with internal constructor\n$output"
        }
        // Public property bound on the update draft.
        assert(output.contains("public val tags: M2MPostUpdateDraft.TagsEdgeMutator = M2MPostUpdateDraft.TagsEdgeMutator()") ||
               output.contains("public val tags: TagsEdgeMutator = TagsEdgeMutator()")) {
            "Should bind `val tags = TagsEdgeMutator()` on the update draft\n$output"
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
        // uses the internal snapshot and pending-operation accessors.
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
        assert(output.contains("internal fun hasOps(): Boolean")) {
            "hasOps() accessor must be internal — wired into relationship requirements\n$output"
        }
        assert(output.contains("internal fun snapshotOps(): PendingEdgeOps<UUID>")) {
            "snapshotOps() accessor must be internal — replaces inline field reads in _buildPendingEdgeOps\n$output"
        }
        assert(
            output.contains(
                "requestedSet = _requestedSet?.let { immutableSetSnapshotForInternalUse(it) }",
            ) && output.contains("requestedAdds = immutableSetSnapshotForInternalUse(_adds)") &&
                output.contains("requestedRemoves = immutableSetSnapshotForInternalUse(_removes)"),
        ) {
            "Hook-facing pending edge sets must be detached and JVM-unmodifiable\n$output"
        }
        assert(!output.contains("validateInvariants") && !output.contains("_checkLinkTableM2MMixedMode")) {
            "Per-call invariant enforcement should not grow a second save-time callback\n$output"
        }
    }

    @Test
    fun `update rule items receive fresh immutable edge change snapshots`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator
            .generate("M2MPost", post, names)
            .toString()
            .replace("\\s+".toRegex(), " ")

        val snapshot =
            "state.edgeChanges.copy( tags = snapshotEdgeChangesForInternalUse(state.edgeChanges.tags), )"
        assert(output.contains("M2MPostUpdateRuleInput") &&
            Regex(Regex.escape(snapshot)).findAll(output).count() >= 2) {
            "Every UPDATE rule input should detach edge-change sets for privacy and validation\n$output"
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
            "throughEntity M2M edges must not get a mutator property on the update draft\n$output"
        }
    }

    @Test
    fun `update draft without any M2M edges has no mutator scaffolding`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(!output.contains("EdgeMutator")) {
            "Schemas without helper-eligible M2M edges should produce no mutator scaffolding\n$output"
        }
    }

    @Test
    fun `beforeUpdate hook state exposes pending edge snapshots but not edge mutators`() {
        val (post, tag, postTag, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("M2MPostBeforeUpdateState("))
        assert(output.contains("pendingEdges = pendingEdges"))
        assert(!output.contains("tags = beforeSaveState.tags")) {
            "Edge mutators must not be lowered into hook assignment state\n$output"
        }
        assert(!output.contains("MutationView"))
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
        // Both properties bound on the update draft.
        assert(output.contains("public val tags:") && output.contains("TagsEdgeMutator()")) {
            "Expected `val tags = TagsEdgeMutator()`\n$output"
        }
        assert(output.contains("public val labels:") && output.contains("LabelsEdgeMutator()")) {
            "Expected `val labels = LabelsEdgeMutator()`\n$output"
        }
    }

    // ---------- link-table M2M helpers PendingEdgeOps hook surface ----------

    @Test
    fun `update draft emits a _buildPendingEdgeOps that delegates per-edge to snapshotOps`() {
        // with mutator op-log fields private, the aggregator
        // construction no longer reaches into _requestedSet / _adds /
        // _removes directly. Each per-edge entry calls the mutator's
        // internal `snapshotOps()` accessor, which performs the dedup
        // internally and returns the immutable PendingEdgeOps<ID>.
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("internal fun _buildPendingEdgeOps(): M2MPostPendingEdgeOps")) {
            "Expected internal _buildPendingEdgeOps helper returning the aggregator\n$output"
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
        assert(output.contains("internal fun _buildPendingEdgeOps(): UserPendingEdgeOps")) {
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
    fun `update adapter implements the typed runtime adapter contract`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("UpdateMutationAdapter<M2MPostUpdateDraft, M2MPost, M2MPostPendingEdgeOps, M2MPostUpdateAdapter.PreparedState, M2MPostBeforeUpdateState>") &&
            output.contains("override fun capturePendingEdges(") &&
            output.contains("override fun prepare(")) {
            "The generated adapter should implement the typed runtime lifecycle contract\n$output"
        }
        assert(output.contains("UpdateMutationHookStateConverter<M2MPostUpdateDraft, M2MPost, M2MPostPendingEdgeOps, M2MPostBeforeSaveState, M2MPostBeforeUpdateState>") &&
            output.contains("converter = UpdateHookStateConverter(client)")) {
            "The runtime hook lifecycle should receive a typed schema-specific state converter\n$output"
        }
        assert(!output.contains("beforeSaveValueFactory") && !output.contains("beforeUpdateValueFactory")) {
            "The update adapter should not wire hook inputs through callbacks\n$output"
        }
        assert(!output.contains("override fun runBeforeHooks(")) {
            "The schema adapter should not own before-hook execution\n$output"
        }
        assert(!output.contains("UpdateMutationSpec") && !output.contains("capturePendingEdges = ::")) {
            "Lifecycle behavior should not be bundled into a spec or method-reference callback list\n$output"
        }
    }

    @Test
    fun `beforeSave on M2M-capable update receives field state without edge mutators`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("override fun toBeforeSaveState(draft: M2MPostUpdateDraft): M2MPostBeforeSaveState = draft._buildBeforeSaveState()"))
        val stateFunction = output.substring(
            output.indexOf("internal fun _buildBeforeSaveState"),
            output.indexOf("internal fun _buildPendingEdgeOps"),
        )
        assert(!stateFunction.contains("pendingEdges") && !stateFunction.contains("tags"))
    }

    @Test
    fun `beforeSave on non-M2M update uses the same immutable state adapter`() {
        // Apply the fix uniformly across all update schemas so a
        // schema gaining a throughLink edge later doesn't silently
        // change the hook surface.
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("override fun toBeforeSaveState(draft: UserUpdateDraft): UserBeforeSaveState = draft._buildBeforeSaveState()"))
    }

    @Test
    fun `_buildBeforeSaveState contains only shared field assignments`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        val start = output.indexOf("internal fun _buildBeforeSaveState")
        val end = output.indexOf("internal fun _buildPendingEdgeOps", start)
        assert(start != -1 && end != -1)
        val stateFunction = output.substring(start, end)
        assert(stateFunction.contains("title = if (\"title\" in dirtyFields)"))
        assert(!stateFunction.contains("pendingEdges") && !stateFunction.contains("unsetTitle"))
    }

    @Test
    fun `beforeUpdate state constructor receives pendingEdges`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("M2MPostBeforeUpdateState("))
        assert(output.contains("pendingEdges = pendingEdges"))
    }

    @Test
    fun `beforeUpdate state receives the runtime-owned pending-edge snapshot`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("pendingEdges = pendingEdges"))
        assert(!output.contains("MutationView") && !output.contains("pendingEdgesSnapshot"))
    }

    @Test
    fun `update class carries no mutable pending-edge snapshot field`() {
        val user = User()
        finalize(user, Car())
        val userOutput = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")
        assert(!userOutput.contains("_capturedPendingEdges")) {
            "Non-M2M schemas should not store pending-edge lifecycle state\n$userOutput"
        }

        val (post, _, _, names) = makeLinkM2MSchemas()
        val postOutput = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")
        assert(!postOutput.contains("_capturedPendingEdges")) {
            "M2M-capable schemas should not store pending-edge lifecycle state\n$postOutput"
        }
    }

    @Test
    fun `capture adapter returns pending edges to the runtime`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("override fun capturePendingEdges(draft: M2MPostUpdateDraft): M2MPostPendingEdgeOps = draft._buildPendingEdgeOps()")) {
            "the capture adapter should return the snapshot to runtime\n$output"
        }
        assert(output.contains("adapter = this")) {
            "the executor should receive the typed adapter once at construction\n$output"
        }
    }

    @Test
    fun `generated update has no pending-edge cleanup callback`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(!output.contains("_endUpdate") && !output.contains("end = ::")) {
            "Runtime-local pending edges should require no cleanup callback\n$output"
        }
        val user = User()
        finalize(user, Car())
        val userOutput = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")
        assert(!userOutput.contains("_endUpdate") && !userOutput.contains("_capturedPendingEdges")) {
            "Non-M2M schemas should also have no cleanup machinery\n$userOutput"
        }
    }

    // ---------- link-table M2M runtime requirements ----------

    @Test
    fun `update draft emits _hasPendingLinkTableM2MOps that ORs mutator hasOps flags`() {
        val (doc, _, _, _, names) = makeMultiEdgeSchemas()
        val output = generator.generate("M2MDoc", doc, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Two helper-eligible edges → ORed across both mutators.
        assert(output.contains("internal fun _hasPendingLinkTableM2MOps(): Boolean")) {
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
    fun `update draft emits _hasPendingLinkTableM2MInserts that ORs mutator hasInserts flags`() {
        val (doc, _, _, _, names) = makeMultiEdgeSchemas()
        val output = generator.generate("M2MDoc", doc, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("internal fun _hasPendingLinkTableM2MInserts(): Boolean")) {
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
    fun `relationship requirements report whether pending writes need insertIgnore`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("requiresInsertIgnore = draft._hasPendingLinkTableM2MInserts()")) {
            "Generated requirements must distinguish add/set from remove-only writes\n$output"
        }
        assert(!output.contains("supportsInsertIgnore")) {
            "Driver capability validation belongs to UpdateMutationExecutor\n$output"
        }
    }

    // ---------- symmetric link-table writes canonical relationship locking ----------

    @Test
    fun `typed adapter exposes relationship requirements to the executor`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("override fun relationshipRequirements(draft: M2MPostUpdateDraft): UpdateRelationshipRequirements")) {
            "The typed adapter must expose schema-specific requirements from the request draft\n$output"
        }
        assert(!output.contains("private val relationshipLocking:") &&
            !output.contains("UpdateMutationSpec")) {
            "Per-request relationship state must not be copied or stored\n$output"
        }
    }

    @Test
    fun `generated requirements contain canonical relationship keys`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains(
            "canonicalLockKeys += RelationshipLockKey.canonical(\"m2m_post_tags\", listOf(\"post_id\", \"tag_id\"))",
        )) {
            "Expected the schema-specific canonical relationship key\n$output"
        }
        assert(!output.contains("supportsRelationshipSerialization")) {
            "Canonical capability validation belongs to UpdateMutationExecutor\n$output"
        }
    }

    @Test
    fun `relationship requirements guard canonical keys by pending edge operations`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains(
            "if (draft.tags.hasOps()) { canonicalLockKeys += " +
                "RelationshipLockKey.canonical(\"m2m_post_tags\", listOf(\"post_id\", \"tag_id\")) }",
        )) {
            "Expected the canonical key to be included only for a changed edge\n$output"
        }
        assert(!output.contains("driver.serializeRelationship(") && !output.contains("_loadUpdateRow")) {
            "Lock acquisition and owner-row loading belong to UpdateMutationExecutor\n$output"
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
        assert(output.contains("draft.tags.hasOps() || draft.moreTags.hasOps()") ||
               output.contains("draft.moreTags.hasOps() || draft.tags.hasOps()")) {
            "The collapsed lock guard must OR both edges' hasOps()\n$output"
        }
    }

    @Test
    fun `mixed-mode invariants stay at the mutator call sites without a save callback`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("edge 'tags': cannot mix replacement (set) and delta (add/remove)")) {
            "Replacement-vs-delta checks must remain on add/remove/set\n$output"
        }
        assert(output.contains("edge 'tags': cannot add(id) after remove(id) for the same id") &&
            output.contains("edge 'tags': cannot remove(id) after add(id) for the same id")) {
            "Same-id mixed-direction checks must remain on add/remove\n$output"
        }
        assert(!output.contains("validateInvariants") && !output.contains("_checkLinkTableM2MMixedMode")) {
            "Generated updates should not retain a redundant save-time validation callback\n$output"
        }
    }

    @Test
    fun `relationship requirements live on the typed adapter instead of a spec`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("override fun relationshipRequirements(draft: M2MPostUpdateDraft): UpdateRelationshipRequirements")) {
            "The typed adapter should derive per-request relationship requirements\n$output"
        }
        assert(!output.contains("UpdateMutationSpec") &&
            !output.contains("preflight") &&
            !output.contains("loadRow")) {
            "Runtime inputs and lifecycle policy must not be stored in generated state\n$output"
        }
    }

    @Test
    fun `relationship requirements contain only schema-specific facts`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("hasPendingWrites = draft._hasPendingLinkTableM2MOps()") &&
            output.contains("requiresInsertIgnore = draft._hasPendingLinkTableM2MInserts()") &&
            output.contains("canonicalLockKeys = canonicalLockKeys")) {
            "Generated requirements should describe pending writes, inserts, and canonical keys\n$output"
        }
        assert(!output.contains("TransactionRequiredException") &&
            !output.contains("UnsupportedDriverCapabilityException") &&
            !output.contains("supportsOwnerEdgeSerialization")) {
            "Transaction and driver capability policy belongs to UpdateMutationExecutor\n$output"
        }
    }

    @Test
    fun `schemas without helper-eligible M2M edges emit no relationship requirements helper`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // No mutator scaffolding means there is nothing schema-specific
        // to report to the runtime executor.
        assert(!output.contains("_hasPendingLinkTableM2MOps")) {
            "Schemas without helper-eligible M2M edges should not get the M2M ops helper\n$output"
        }
        assert(!output.contains("_updateRelationshipRequirements")) {
            "Schemas without helper-eligible M2M edges should use the executor default requirements\n$output"
        }
    }

    @Test
    fun `multi-edge schemas retain per-edge mutator call-site checks`() {
        val (doc, _, _, _, names) = makeMultiEdgeSchemas()
        val output = generator.generate("M2MDoc", doc, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("edge 'tags': cannot mix")) {
            "The tags mutator should enforce mixed-mode calls\n$output"
        }
        assert(output.contains("edge 'labels': cannot mix")) {
            "The labels mutator should enforce mixed-mode calls\n$output"
        }
    }

    // ---------- link-table M2M helpers three-way owner-row read + junction reads + EdgeChanges ----------

    @Test
    fun `M2M-capable schema delegates owner-row selection to runtime`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("override fun relationshipRequirements(draft: M2MPostUpdateDraft): UpdateRelationshipRequirements")) {
            "M2M adapters should expose schema-specific requirements to runtime\n$output"
        }
        assert(!output.contains("readRowForUpdate(") &&
            !output.contains("serializeOwnerEdgeAndRead(") &&
            !output.contains("driver.byId(")) {
            "The generated adapter should not select an owner-row read primitive\n$output"
        }
        assert(!output.contains("_classifyDriverFailure") &&
            !output.contains("EntTargetAbsentException(")) {
            "failure classification and target-absence handling belong to UpdateMutationExecutor\n$output"
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
            "private fun _buildEdgeChanges(id: Any, pendingEdges: M2MPostPendingEdgeOps): M2MPostEdgeChangesView",
        )) {
            "Expected _buildEdgeChanges helper taking pendingEdges and returning the view\n$output"
        }

        // Per-edge junction read, guarded by per-mutator hasOps() so we
        // skip the round-trip when nothing was staged.
        // Junction-table queries are erased (no entity scope) per the
        // typed query scopes; Predicate.Leaf<Any> renders the same structural
        // data.
        assert(output.contains(
            "val _current_tags: Set<UUID> = if (pendingEdges.tags.hasChanges) { " +
                "driver.query(\"m2m_post_tags\", listOf(Predicate.Leaf<Any>(\"post_id\", Op.EQ, id)), emptyList(), null, null) " +
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
        assert(output.contains("private fun _buildEdgeChanges(id: Any, pendingEdges: UserPendingEdgeOps): UserEdgeChangesView")) {
            "Helper must still be emitted for uniform shape\n$output"
        }
        assert(output.contains("return UserEdgeChangesView()") ||
               output.contains("_buildEdgeChanges(id: Any, pendingEdges: UserPendingEdgeOps): UserEdgeChangesView = UserEdgeChangesView()")) {
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
    fun `prepare adapter computes edgeChanges between requestedPatch and the empty-branch check`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        val patchIdx = output.indexOf("val requestedPatch = buildRequestedPatch(hookState)")
        // The runtime scope provides the driver-read capture boundary.
        val edgeChangesIdx = output.indexOf(
            "val edgeChanges = scope.driverRead { _buildEdgeChanges(request.id, pendingEdges) }",
        )
        // gates the empty-branch condition on M2M-pending so
        // M2M-only updates proceed past it.
        val emptyIdx = output.indexOf(
            "if (!hasFieldAssignments && !request.draft._hasPendingLinkTableM2MOps()) { val effectivePatch = requestedPatch",
        )
        assert(patchIdx != -1 && edgeChangesIdx != -1 && emptyIdx != -1) {
            "Missing one of: requestedPatch / scoped edgeChanges build / empty branch\n$output"
        }
        assert(patchIdx < edgeChangesIdx) {
            "edgeChanges must be computed after the canonical requested patch\n$output"
        }
        assert(edgeChangesIdx < emptyIdx) {
            "edgeChanges must be computed BEFORE the empty branch so both branches see it\n$output"
        }
    }

    @Test
    fun `privacy and validation adapters both receive edgeChanges`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        val snapshot = "snapshotEdgeChangesForInternalUse(state.edgeChanges.tags)"
        val occurrences = output.split(snapshot).size - 1
        assert(occurrences == 2) {
            "Expected one detached edgeChanges snapshot for privacy and one for validation, got $occurrences\n$output"
        }
        assert(Regex(Regex.escape("M2MPostUpdateRuleInput(")).findAll(output).count() == 2) {
            "Both rule adapters should materialize the shared typed input from PreparedState\n$output"
        }
        assert(!output.contains("updateDenialReasonOrNull") &&
            !output.contains("evaluateUpdateValidation")) {
            "generated code should delegate rule evaluation to the runtime phases\n$output"
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
            "if (!hasFieldAssignments && !request.draft._hasPendingLinkTableM2MOps()) { val effectivePatch = requestedPatch",
        )
        assert(gatedEmpty != -1) {
            "The no-op empty branch must be gated on `!_hasPendingLinkTableM2MOps()`\n$output"
        }
        val emptyCount = Regex(Regex.escape("if (!hasFieldAssignments")).findAll(output).count()
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
        assert(userOutput.contains("if (!hasFieldAssignments) {")) {
            "Non-M2M schemas should keep the field-assignment-only no-op check\n$userOutput"
        }
        assert(!userOutput.contains("_hasPendingLinkTableM2MOps")) {
            "Non-M2M schemas should not reference the M2M pending gate\n$userOutput"
        }
    }

    @Test
    fun `M2M-capable preparation delegates owner and relationship writes to runtime`() {
        val (post, _, _, names) = makeLinkM2MSchemas()
        val output = generator.generate("M2MPost", post, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("values = values, isNoOp = false") &&
            output.contains("override fun persistRelationships(")) {
            "the schema adapter should supply owner values and relationship persistence separately\n$output"
        }
        assert(!output.contains("driver.update(")) {
            "UpdateMutationExecutor should own the conditional owner write\n$output"
        }
    }

    @Test
    fun `non-M2M schemas use the same runtime owner-write contract`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("values = values, isNoOp = false") &&
            output.contains("override fun persistRelationships(")) {
            "non-M2M adapters should pass prepared values through the uniform runtime contract\n$output"
        }
        assert(!output.contains("driver.update(") &&
            !output.contains("EntTargetAbsentException(")) {
            "owner persistence and vanished-target handling belong to UpdateMutationExecutor\n$output"
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
            "if (edgeChanges.tags.added.isNotEmpty()) { for (_targetId in edgeChanges.tags.added) { if (driver.insertIgnore(\"m2m_post_tags\", mapOf(\"post_id\" to request.id, \"tag_id\" to _targetId), conflictColumns = listOf(\"post_id\", \"tag_id\")) != null) writes.markWritten() } }",
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
            "if (edgeChanges.tags.removed.isNotEmpty()) { if (driver.deleteMany(\"m2m_post_tags\", listOf(Predicate.Leaf<Any>(\"post_id\", Op.EQ, request.id), Predicate.Leaf<Any>(\"tag_id\", Op.IN, edgeChanges.tags.removed.toList()))) > 0) writes.markWritten() }",
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
            "if (driver.insertIgnore(\"uuid_junction_post_tags\", mapOf(\"id\" to UUID.randomUUID(), \"post_id\" to request.id, \"tag_id\" to _targetId), conflictColumns = listOf(\"post_id\", \"tag_id\")) != null) writes.markWritten()",
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
        assert(output.contains("if (driver.insertIgnore(\"m2m_doc_tags\", mapOf(\"doc_id\" to request.id, \"tag_id\" to _targetId), conflictColumns = listOf(\"doc_id\", \"tag_id\")) != null) writes.markWritten()")) {
            "Expected per-edge insertIgnore for the tags edge\n$output"
        }
        assert(output.contains("if (driver.insertIgnore(\"m2m_doc_labels\", mapOf(\"doc_id\" to request.id, \"label_id\" to _targetId), conflictColumns = listOf(\"doc_id\", \"label_id\")) != null) writes.markWritten()")) {
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

private class UuidJunctionPost : EntSchema("uuid_junction_posts", clientName = "uuidJunctionPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<UuidJunctionTag>("tags")
        .throughLink<UuidJunctionPostTag>(UuidJunctionPostTag::post, UuidJunctionPostTag::tag)
}
private class UuidJunctionTag : EntSchema("uuid_junction_tags", clientName = "uuidJunctionTags") {
    override fun id() = EntId.long()
}
private class UuidJunctionPostTag : EntSchema("uuid_junction_post_tags", clientName = "uuidJunctionPostTags") {
    // CLIENT_UUID junction id: caller (or codegen) mints UUID client-side.
    override fun id() = EntId.uuid()
    val post by belongsTo<UuidJunctionPost>("post").onDelete(entkt.schema.OnDelete.CASCADE)
    val tag by belongsTo<UuidJunctionTag>("tag").onDelete(entkt.schema.OnDelete.CASCADE)
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
private class M2MPost : EntSchema("m2m_posts", clientName = "m2MPosts") {
    override fun id() = EntId.long()
    val title by string("title")
    val tags by manyToMany<M2MTag>("tags")
        .throughLink<M2MPostTag>(M2MPostTag::post, M2MPostTag::tag)
}
private class M2MTag : EntSchema("m2m_tags", clientName = "m2MTags") {
    override fun id() = EntId.uuid()
    val name by string("name")
}
private class M2MPostTag : EntSchema("m2m_post_tags", clientName = "m2MPostTags") {
    override fun id() = EntId.long()
    val post by belongsTo<M2MPost>("post").onDelete(entkt.schema.OnDelete.CASCADE)
    val tag by belongsTo<M2MTag>("tag").onDelete(entkt.schema.OnDelete.CASCADE)
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
private class M2MTeam : EntSchema("m2m_teams", clientName = "m2MTeams") {
    override fun id() = EntId.long()
    val members by manyToMany<M2MMember>("members")
        .throughEntity<M2MTeamMembership>(M2MTeamMembership::team, M2MTeamMembership::member)
}
private class M2MMember : EntSchema("m2m_members", clientName = "m2MMembers") {
    override fun id() = EntId.long()
}
private class M2MTeamMembership : EntSchema("m2m_memberships", clientName = "m2MTeamMemberships") {
    override fun id() = EntId.long()
    val joinedAt by time("joined_at")
    val team by belongsTo<M2MTeam>("team")
    val member by belongsTo<M2MMember>("member")
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
private class M2MDoc : EntSchema("m2m_docs", clientName = "m2MDocs") {
    override fun id() = EntId.long()
    val tags by manyToMany<M2MLabel>("tags")
        .throughLink<M2MDocTag>(M2MDocTag::doc, M2MDocTag::tag)
    val labels by manyToMany<M2MLabel>("labels")
        .throughLink<M2MDocLabel>(M2MDocLabel::doc, M2MDocLabel::label)
}
private class M2MLabel : EntSchema("m2m_labels", clientName = "m2MLabels") {
    override fun id() = EntId.long()
}
private class M2MDocTag : EntSchema("m2m_doc_tags", clientName = "m2MDocTags") {
    override fun id() = EntId.long()
    val doc by belongsTo<M2MDoc>("doc").onDelete(entkt.schema.OnDelete.CASCADE)
    val tag by belongsTo<M2MLabel>("tag").onDelete(entkt.schema.OnDelete.CASCADE)
    val pair = index("idx_m2m_doc_tags_pair", doc.fk, tag.fk).unique()
}
private class M2MDocLabel : EntSchema("m2m_doc_labels", clientName = "m2MDocLabels") {
    override fun id() = EntId.long()
    val doc by belongsTo<M2MDoc>("doc").onDelete(entkt.schema.OnDelete.CASCADE)
    val label by belongsTo<M2MLabel>("label").onDelete(entkt.schema.OnDelete.CASCADE)
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
private class DupJunctionDoc : EntSchema("dup_junction_docs", clientName = "dupJunctionDocs") {
    override fun id() = EntId.long()
    val tags by manyToMany<DupJunctionTag>("tags")
        .throughLink<DupJunctionDocTag>(DupJunctionDocTag::doc, DupJunctionDocTag::tag)
    val moreTags by manyToMany<DupJunctionTag>("more_tags")
        .throughLink<DupJunctionDocTag>(DupJunctionDocTag::doc, DupJunctionDocTag::tag)
}
private class DupJunctionTag : EntSchema("dup_junction_tags", clientName = "dupJunctionTags") {
    override fun id() = EntId.long()
}
private class DupJunctionDocTag : EntSchema("dup_junction_doc_tags", clientName = "dupJunctionDocTags") {
    override fun id() = EntId.long()
    val doc by belongsTo<DupJunctionDoc>("doc").onDelete(entkt.schema.OnDelete.CASCADE)
    val tag by belongsTo<DupJunctionTag>("tag").onDelete(entkt.schema.OnDelete.CASCADE)
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
