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

        // Required field: Set(this.name!!) with non-null value, Unset otherwise.
        assert(
            output.contains(
                "name = if (\"name\" in dirtyFields) FieldPatch.Set(this.name!!) else FieldPatch.Unset",
            ),
        ) {
            "Required field should lower to FieldPatch.Set(value!!) / Unset\n$output"
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
        assert(output.contains("beforeUpdateHooks: List<(UserUpdate) -> Unit>")) {
            "Should take beforeUpdateHooks\n$output"
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
