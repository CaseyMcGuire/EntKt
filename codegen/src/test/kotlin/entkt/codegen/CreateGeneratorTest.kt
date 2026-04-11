package entkt.codegen

import entkt.schema.EntSchema
import entkt.schema.fields
import kotlin.test.Test

object Event : EntSchema() {
    override fun fields() = fields {
        string("title")
        time("created_at").default("now").immutable()
    }
}

object ValidatedEntity : EntSchema() {
    override fun fields() = fields {
        string("name").minLen(3).maxLen(100).notEmpty()
        int("age").positive()
        string("nickname").optional().match(Regex("^[a-z]+$"))
    }
}

class CreateGeneratorTest {

    private val generator = CreateGenerator("com.example.ent")

    @Test
    fun `generates create builder with mutable properties for each field`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("class CarCreate")) { "Should generate CarCreate class\n$output" }
        assert(output.contains("var model: String?")) { "Should have model var\n$output" }
        assert(output.contains("var year: Int?")) { "Should have year var\n$output" }
        assert(output.contains("var price: Float?")) { "Should have price var\n$output" }
    }

    @Test
    fun `create builder is annotated as DSL scope`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("@EntktDsl")) { "Should be annotated @EntktDsl\n$output" }
    }

    @Test
    fun `save validates required fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun save(): Car")) { "Should have save method returning entity\n$output" }
        assert(output.contains(""""model is required"""")) { "Should validate model is required\n$output" }
        assert(output.contains(""""year is required"""")) { "Should validate year is required\n$output" }
    }

    @Test
    fun `save does not validate optional fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(!output.contains(""""price is required"""")) { "Should not validate optional price\n$output" }
    }

    @Test
    fun `includes mixin fields as properties`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("var createdAt: Instant?")) { "Should have mixin property\n$output" }
        assert(output.contains("var updatedAt: Instant?")) { "Should have mixin property\n$output" }
    }

    @Test
    fun `implements the mutation interface`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("CarCreate") && output.contains("CarMutation")) {
            "Should implement CarMutation interface\n$output"
        }
    }

    @Test
    fun `constructor takes hook list parameters`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("beforeSaveHooks: List<(CarMutation) -> Unit>")) {
            "Should take beforeSaveHooks\n$output"
        }
        assert(output.contains("beforeCreateHooks: List<(CarCreate) -> Unit>")) {
            "Should take beforeCreateHooks\n$output"
        }
        assert(output.contains("afterCreateHooks: List<(Car) -> Unit>")) {
            "Should take afterCreateHooks\n$output"
        }
    }

    @Test
    fun `save calls before hooks before validation`() {
        val output = generator.generate("Car", Car).toString()

        val hookCall = output.indexOf("beforeSaveHooks")
        val validate = output.indexOf("model is required")
        assert(hookCall != -1 && validate != -1 && hookCall < validate) {
            "Before hooks should run before validation\n$output"
        }
    }

    @Test
    fun `save calls after hooks after insert`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("for (hook in afterCreateHooks) hook(entity)")) {
            "Should call afterCreate hooks\n$output"
        }
    }

    @Test
    fun `save falls back to default literal for fields with a default`() {
        val output = generator.generate("User", User).toString()

        // User.active has .default(true). The constructor param is non-null,
        // so save() must coalesce to the default rather than passing this.active.
        assert(output.contains("active = this.active ?: true")) {
            "Should coalesce to default literal for active\n$output"
        }
        assert(!output.contains(""""active is required"""")) {
            "Should not validate a field with a default\n$output"
        }
    }

    @Test
    fun `save emits Instant_now() for time fields with default now`() {
        val output = generator.generate("Event", Event).toString()

        assert(output.contains("Instant.now()")) {
            "Should emit Instant.now() for time default \"now\"\n$output"
        }
        assert(!output.contains("?: \"now\"")) {
            "Should not emit string literal \"now\" for time default\n$output"
        }
    }

    @Test
    fun `save emits validation checks for string validators`() {
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
        assert(output.contains("name: value must be at least 3 characters")) {
            "Should include validator message\n$output"
        }
    }

    @Test
    fun `save emits validation checks for numeric validators`() {
        val output = generator.generate("ValidatedEntity", ValidatedEntity).toString()

        assert(output.contains("age <= 0")) {
            "Should emit positive check\n$output"
        }
        assert(output.contains("age: value must be positive")) {
            "Should include validator message\n$output"
        }
    }

    @Test
    fun `save wraps optional field validation in null check`() {
        val output = generator.generate("ValidatedEntity", ValidatedEntity).toString()

        // nickname is optional, so validation should be wrapped
        assert(output.contains("if (nickname != null)")) {
            "Should null-guard optional field validation\n$output"
        }
        assert(output.contains("Regex(") && output.contains(".matches(nickname)")) {
            "Should emit match check for optional field\n$output"
        }
    }

    @Test
    fun `save does not emit validation for fields without validators`() {
        val output = generator.generate("Car", Car).toString()

        // Car has no validators, so no validation checks
        assert(!output.contains(".length <")) {
            "Should not emit validation for unvalidated fields\n$output"
        }
        assert(!output.contains(".isEmpty()")) {
            "Should not emit isEmpty for unvalidated fields\n$output"
        }
    }

    @Test
    fun `validation appears after field binding and before row map`() {
        val output = generator.generate("ValidatedEntity", ValidatedEntity).toString()

        val bindingPos = output.indexOf("name is required")
        val validationPos = output.indexOf("name.length < 3")
        val rowMapPos = output.indexOf("val values: Map<String, Any?>")
        assert(bindingPos < validationPos && validationPos < rowMapPos) {
            "Validation should appear after binding and before row map\n$output"
        }
    }
}