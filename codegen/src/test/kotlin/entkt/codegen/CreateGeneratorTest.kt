package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertFailsWith

class Session : EntSchema("sessions") {
    override fun id() = EntId.string()
    val token = string("token")
}

class Event : EntSchema("events") {
    override fun id() = EntId.int()
    val title = string("title")
    val createdAt = time("created_at").defaultNow().immutable()
}

enum class Status { LOW, MEDIUM, HIGH }
enum class OtherStatus { PENDING, ACCEPTED }

class DefaultedEnumEntity : EntSchema("defaulted_enum_entities") {
    override fun id() = EntId.int()
    val priority = enum<Status>("priority").default(Status.LOW)
}

class ValidatedEntity : EntSchema("validated_entities") {
    override fun id() = EntId.int()
    val name = string("name").minLen(3).maxLen(100).notEmpty()
    val age = int("age").positive()
    val nickname = string("nickname").optional().match(Regex("^[a-z]+$"))
    val code = string("code").match(Regex("^[a-z]+$", RegexOption.IGNORE_CASE))
}

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class CreateGeneratorTest {

    private val generator = CreateGenerator("com.example.ent")

    @Test
    fun `generates create builder with mutable properties for each field`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("class CarCreate")) { "Should generate CarCreate class\n$output" }
        assert(output.contains("var model: String?")) { "Should have model var\n$output" }
        assert(output.contains("var year: Int?")) { "Should have year var\n$output" }
        assert(output.contains("var price: Float?")) { "Should have price var\n$output" }
    }

    @Test
    fun `create builder is annotated as DSL scope`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("@EntktDsl")) { "Should be annotated @EntktDsl\n$output" }
    }

    @Test
    fun `save validates required fields`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("fun save(): Car")) { "Should have save method returning entity\n$output" }
        assert(!output.contains("fun save(): Car?")) { "save() should return non-nullable Car\n$output" }
        assert(output.contains(""""model is required"""")) { "Should validate model is required\n$output" }
        assert(output.contains(""""year is required"""")) { "Should validate year is required\n$output" }
    }

    @Test
    fun `save does not validate optional fields`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(!output.contains(""""price is required"""")) { "Should not validate optional price\n$output" }
    }

    @Test
    fun `includes time fields as properties`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("var createdAt: Instant?")) { "Should have createdAt property\n$output" }
        assert(output.contains("var updatedAt: Instant?")) { "Should have updatedAt property\n$output" }
    }

    @Test
    fun `implements the mutation interface`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("CarCreate") && output.contains("CarMutation")) {
            "Should implement CarMutation interface\n$output"
        }
    }

    @Test
    fun `constructor takes client and hook list parameters`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

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
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("val client: EntClient")) {
            "Should expose client as public property\n$output"
        }
        assert(!output.contains("private val client")) {
            "client should not be private\n$output"
        }
    }

    @Test
    fun `save calls before hooks before validation`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        val hookCall = output.indexOf("beforeSaveHooks")
        val validate = output.indexOf("model is required")
        assert(hookCall != -1 && validate != -1 && hookCall < validate) {
            "Before hooks should run before validation\n$output"
        }
    }

    @Test
    fun `save calls after hooks after insert`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("for (hook in afterCreateHooks) hook(entity)")) {
            "Should call afterCreate hooks\n$output"
        }
    }

    @Test
    fun `save falls back to default literal for fields with a default`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

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
        val event = Event()
        finalize(event)
        val output = generator.generate("Event", event).toString()

        assert(output.contains("Instant.now()")) {
            "Should emit Instant.now() for time default \"now\"\n$output"
        }
        assert(!output.contains("?: \"now\"")) {
            "Should not emit string literal \"now\" for time default\n$output"
        }
    }

    @Test
    fun `save emits validation checks for string validators`() {
        val schema = ValidatedEntity()
        finalize(schema)
        val output = generator.generate("ValidatedEntity", schema).toString()

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
        val schema = ValidatedEntity()
        finalize(schema)
        val output = generator.generate("ValidatedEntity", schema).toString()

        assert(output.contains("age <= 0")) {
            "Should emit positive check\n$output"
        }
        assert(output.contains("age: value must be positive")) {
            "Should include validator message\n$output"
        }
    }

    @Test
    fun `save wraps optional field validation in null check`() {
        val schema = ValidatedEntity()
        finalize(schema)
        val output = generator.generate("ValidatedEntity", schema).toString()

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
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

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
        val ticket = Ticket()
        finalize(ticket)
        val output = generator.generate("Ticket", ticket).toString()

        assert(output.contains("var priority: Priority?")) {
            "Should use the Kotlin enum type on the builder property\n$output"
        }
    }

    @Test
    fun `typed enum save converts to name for the row map`() {
        val ticket = Ticket()
        finalize(ticket)
        val output = generator.generate("Ticket", ticket).toString()

        assert(output.contains("\"priority\" to priority.name")) {
            "Should convert typed enum to .name in the row map\n$output"
        }
    }

    @Test
    fun `second typed enum save also converts to name`() {
        val ticket = Ticket()
        finalize(ticket)
        val output = generator.generate("Ticket", ticket).toString()

        assert(output.contains("\"category\" to category.name")) {
            "Second typed enum should also use .name in the row map\n$output"
        }
    }

    @Test
    fun `typed enum default emits enum constant reference`() {
        val schema = DefaultedEnumEntity()
        finalize(schema)
        val output = generator.generate("DefaultedEnumEntity", schema).toString()

        assert(output.contains("this.priority ?: Status.LOW")) {
            "Should coalesce to the enum constant for typed enum default\n$output"
        }
        assert(!output.contains("this.priority ?: \"LOW\"")) {
            "Should not emit string literal for typed enum default\n$output"
        }
    }

    @Test
    fun `typed enum default rejects constant from wrong enum class`() {
        val wrongDefault = object : EntSchema("wrong_defaults") {
            override fun id() = EntId.int()
            val priority = enum<Status>("priority").default(OtherStatus.PENDING)
        }
        finalize(wrongDefault)
        assertFailsWith<IllegalArgumentException> {
            generator.generate("WrongDefault", wrongDefault)
        }
    }

    @Test
    fun `validation appears after field binding and before row map`() {
        val schema = ValidatedEntity()
        finalize(schema)
        val output = generator.generate("ValidatedEntity", schema).toString()

        val bindingPos = output.indexOf("name is required")
        val validationPos = output.indexOf("name.length < 3")
        val rowMapPos = output.indexOf("val values: Map<String, Any?>")
        assert(bindingPos < validationPos && validationPos < rowMapPos) {
            "Validation should appear after binding and before row map\n$output"
        }
    }

    @Test
    fun `save emits regex options when present`() {
        val schema = ValidatedEntity()
        finalize(schema)
        val output = generator.generate("ValidatedEntity", schema).toString()

        assert(output.contains("RegexOption.IGNORE_CASE")) {
            "Should emit RegexOption when regex has flags\n$output"
        }
        assert(output.contains("setOf(RegexOption.IGNORE_CASE)")) {
            "Should wrap options in setOf()\n$output"
        }
    }

    @Test
    fun `save emits plain Regex when no options`() {
        val schema = ValidatedEntity()
        finalize(schema)
        val output = generator.generate("ValidatedEntity", schema).toString()

        // nickname uses Regex("^[a-z]+$") with no options — should not have setOf()
        val regexLines = output.lines().filter { it.contains("Regex(") }
        val nicknameRegex = regexLines.find { it.contains("nickname") }
        assert(nicknameRegex != null && !nicknameRegex.contains("setOf")) {
            "Should emit plain Regex() for pattern without options\n$output"
        }
    }

    @Test
    fun `explicit id strategy adds id as constructor parameter`() {
        val session = Session()
        finalize(session)
        val output = generator.generate("Session", session).toString()

        assert(output.contains("id: String")) {
            "Should have id as constructor parameter\n$output"
        }
        assert(!output.contains("var id: String?")) {
            "Should not have nullable mutable id property\n$output"
        }
    }

    @Test
    fun `explicit id strategy save includes id in values map`() {
        val session = Session()
        finalize(session)
        val output = generator.generate("Session", session).toString()

        assert(output.contains(""""id" to id""")) {
            "Should include id in the row values map\n$output"
        }
    }

    @Test
    fun `nullable field with default uses the default when omitted`() {
        val schema = object : EntSchema("nullable_defaults") {
            override fun id() = EntId.int()
            val nickname = string("nickname").nullable().default("anonymous")
        }
        finalize(schema)
        val output = generator.generate("NullableDefault", schema).toString()

        assert(output.contains("""this.nickname ?: "anonymous"""")) {
            "Should coalesce nullable field to default\n$output"
        }
        assert(!output.contains(""""nickname is required"""")) {
            "Should not validate nullable field as required\n$output"
        }
    }
}
