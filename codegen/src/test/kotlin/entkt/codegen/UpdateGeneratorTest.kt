package entkt.codegen

import kotlin.test.Test

class UpdateGeneratorTest {

    private val generator = UpdateGenerator("com.example.ent")

    @Test
    fun `generates update builder with mutable properties for mutable fields`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("class UserUpdate")) { "Should generate UserUpdate class\n$output" }
        assert(output.contains("var name: String?")) { "Should have name var\n$output" }
        assert(output.contains("var age: Int?")) { "Should have age var\n$output" }
    }

    @Test
    fun `update builder is annotated as DSL scope`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("@EntktDsl")) { "Should be annotated @EntktDsl\n$output" }
    }

    @Test
    fun `excludes immutable fields from mutable properties`() {
        val output = generator.generate("User", User).toString()

        assert(!output.contains("var createdAt")) { "Should not have mutable createdAt\n$output" }
    }

    @Test
    fun `save preserves immutable fields from entity`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("createdAt = entity.createdAt")) { "Should preserve immutable createdAt\n$output" }
    }

    @Test
    fun `save uses dirty tracking to distinguish unset from explicit null`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("if (\"name\" in dirtyFields) this.name else entity.name")) {
            "Should check dirtyFields for fallback\n$output"
        }
    }

    @Test
    fun `update builder has dirtyFields set`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("dirtyFields: MutableSet<String> = mutableSetOf()")) {
            "Should have dirtyFields set\n$output"
        }
    }

    @Test
    fun `mutable property setter tracks dirty state`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("dirtyFields.add(\"name\")")) {
            "Setting name should add to dirtyFields\n$output"
        }
    }

    @Test
    fun `takes entity in constructor`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("entity: User")) { "Should take entity parameter\n$output" }
    }

    @Test
    fun `implements the mutation interface`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("UserUpdate") && output.contains("UserMutation")) {
            "Should implement UserMutation interface\n$output"
        }
    }

    @Test
    fun `entity is public for hook access`() {
        val output = generator.generate("User", User).toString()

        // entity should NOT be private — hooks need to inspect current state
        assert(!output.contains("private val entity")) {
            "entity should be public so hooks can access current state\n$output"
        }
        assert(output.contains("val entity: User")) {
            "Should have public entity property\n$output"
        }
    }

    @Test
    fun `constructor takes client and hook list parameters`() {
        val output = generator.generate("User", User).toString()

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
        val output = generator.generate("User", User).toString()

        assert(output.contains("val client: EntClient")) {
            "Should expose client as public property\n$output"
        }
        assert(!output.contains("private val client")) {
            "client should not be private\n$output"
        }
    }

    @Test
    fun `save calls before hooks before fallback`() {
        val output = generator.generate("User", User).toString()

        val hookCall = output.indexOf("beforeSaveHooks")
        val fallback = output.indexOf("in dirtyFields")
        assert(hookCall != -1 && fallback != -1 && hookCall < fallback) {
            "Before hooks should run before fallback resolution\n$output"
        }
    }

    @Test
    fun `save calls after hooks after update`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("for (hook in afterUpdateHooks) hook(updatedEntity)")) {
            "Should call afterUpdate hooks\n$output"
        }
    }

    @Test
    fun `save emits validation for mutable validated fields`() {
        val output = generator.generate("ValidatedEntity", ValidatedEntity).toString()

        assert(output.contains("name.length < 3")) {
            "Should emit minLen check\n$output"
        }
        assert(output.contains("name.length > 100")) {
            "Should emit maxLen check\n$output"
        }
        assert(output.contains("name.isEmpty()")) {
            "Should emit notEmpty check\n$output"
        }
    }

    @Test
    fun `save wraps update validation in null check`() {
        val output = generator.generate("ValidatedEntity", ValidatedEntity).toString()

        // All update locals are nullable, so validation should be null-guarded
        assert(output.contains("if (name != null)")) {
            "Should null-guard validation in update builder\n$output"
        }
        assert(output.contains("if (age != null)")) {
            "Should null-guard numeric validation in update builder\n$output"
        }
    }

    @Test
    fun `save does not validate immutable fields in update`() {
        val output = generator.generate("User", User).toString()

        // createdAt is immutable — should not have validation
        assert(!output.contains("createdAt.length")) {
            "Should not validate immutable fields\n$output"
        }
    }

    @Test
    fun `validation appears after dirty resolution and before row map`() {
        val output = generator.generate("ValidatedEntity", ValidatedEntity).toString()

        val dirtyPos = output.indexOf("in dirtyFields")
        val validationPos = output.indexOf("name.length < 3")
        val rowMapPos = output.indexOf("val values: Map<String, Any?>")
        assert(dirtyPos < validationPos && validationPos < rowMapPos) {
            "Validation should appear after dirty resolution and before row map\n$output"
        }
    }
}