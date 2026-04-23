package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.fields
import kotlin.test.Test
import kotlin.test.assertFailsWith

object Session : EntSchema() {
    override fun id() = EntId.string()
    override fun fields() = fields {
        string("token")
    }
}

object Event : EntSchema() {
    override fun fields() = fields {
        string("title")
        time("created_at").default("now").immutable()
    }
}

enum class Status { LOW, MEDIUM, HIGH }
enum class OtherStatus { PENDING, ACCEPTED }

object DefaultedEnumEntity : EntSchema() {
    override fun fields() = fields {
        enum<Status>("priority").default(Status.LOW)
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
        assert(!output.contains("fun save(): Car?")) { "save() should return non-nullable Car\n$output" }
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
    fun `constructor takes client and hook list parameters`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("client: EntClient")) {
            "Should take client\n$output"
        }
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
    fun `exposes client as public property`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("val client: EntClient")) {
            "Should expose client as public property\n$output"
        }
        assert(!output.contains("private val client")) {
            "client should not be private\n$output"
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
    fun `typed enum property uses the Kotlin enum type`() {
        val output = generator.generate("Ticket", Ticket).toString()

        assert(output.contains("var priority: Priority?")) {
            "Should use the Kotlin enum type on the builder property\n$output"
        }
    }

    @Test
    fun `typed enum save converts to name for the row map`() {
        val output = generator.generate("Ticket", Ticket).toString()

        assert(output.contains("\"priority\" to priority.name")) {
            "Should convert typed enum to .name in the row map\n$output"
        }
    }

    @Test
    fun `untyped enum save uses value directly`() {
        val output = generator.generate("Ticket", Ticket).toString()

        assert(output.contains("\"category\" to category")) {
            "Untyped enum should be put directly in the row map\n$output"
        }
        assert(!output.contains("\"category\" to category.name")) {
            "Untyped enum should not use .name\n$output"
        }
    }

    @Test
    fun `typed enum default emits enum constant reference`() {
        val output = generator.generate("DefaultedEnumEntity", DefaultedEnumEntity).toString()

        assert(output.contains("this.priority ?: Status.LOW")) {
            "Should coalesce to the enum constant for typed enum default\n$output"
        }
        assert(!output.contains("this.priority ?: \"LOW\"")) {
            "Should not emit string literal for typed enum default\n$output"
        }
    }

    @Test
    fun `typed enum default rejects constant from wrong enum class`() {
        val wrongDefault = object : EntSchema() {
            override fun fields() = fields {
                enum<Status>("priority").default(OtherStatus.PENDING)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            generator.generate("WrongDefault", wrongDefault)
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

    @Test
    fun `explicit id strategy adds id as constructor parameter`() {
        val output = generator.generate("Session", Session).toString()

        assert(output.contains("id: String")) {
            "Should have id as constructor parameter\n$output"
        }
        assert(!output.contains("var id: String?")) {
            "Should not have nullable mutable id property\n$output"
        }
    }

    @Test
    fun `explicit id strategy save includes id in values map`() {
        val output = generator.generate("Session", Session).toString()

        assert(output.contains(""""id" to id""")) {
            "Should include id in the row values map\n$output"
        }
    }

    @Test
    fun `storageKey overrides column name in row map`() {
        val output = generator.generate("StorageKeyEntity", StorageKeyEntity).toString()

        assert(output.contains(""""full_name" to displayName""")) {
            "Row map should use storageKey as the key\n$output"
        }
        assert(!output.contains(""""display_name" to""")) {
            "Row map should NOT use field name when storageKey is set\n$output"
        }
    }
}