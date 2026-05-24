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
        assert(output.contains("beforeCreateHooks: List<(CarCreateHookContext) -> Unit>")) {
            "beforeCreateHooks should be typed against CarCreateHookContext\n$output"
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
        // Validator failures throw ValidationException carrying both
        // the rule's message and the field name; saveOrError wraps this
        // into EntError.ValidationFailed (Phase 12).
        assert(output.contains("Invalid(\"value must be at least 3 characters\", field = \"name\")")) {
            "Should include validator message + field in ValidationDecision.Invalid\n$output"
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
        assert(output.contains("Invalid(\"value must be positive\", field = \"age\")")) {
            "Should include validator message + field in ValidationDecision.Invalid\n$output"
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

    @Test
    fun `string default with dollar sign is escaped (not interpolated as a Kotlin template)`() {
        // Pre-fix: the hand-rolled escaper only handled \ and "; a
        // default like "price is $10" generated `"price is $10"`
        // which the Kotlin compiler reads as a template referencing
        // an identifier `10` (fails to compile) or — worse — a default
        // like "hello $name" would compile and silently reference
        // whatever `name` is in scope. KotlinPoet's %S handles dollar
        // signs by escaping them with `${'$'}` or backslash.
        val schema = object : EntSchema("dollar_defaults") {
            override fun id() = EntId.int()
            val label = string("label").default("price is \$10")
        }
        finalize(schema)
        val output = generator.generate("DollarDefault", schema).toString()

        // The exact escape KotlinPoet chooses is implementation
        // detail — what matters is the raw text "$10" doesn't appear
        // bare inside a double-quoted string literal where it would
        // be misread as a template.
        assert(!output.contains("\"price is \$10\"")) {
            "Default with \$ must be escaped to avoid Kotlin string-template misread\n$output"
        }
    }

    @Test
    fun `string default with embedded newline survives as a valid Kotlin literal`() {
        // Pre-fix: a default containing \n (the actual newline char,
        // not the escape sequence) rendered as a literal newline in
        // the middle of a `"..."` literal in the generated source,
        // which won't compile.
        //
        // KotlinPoet's %S handles this two ways: short multi-line
        // strings often render as `"line\nline"` (escaped newline);
        // longer ones render as `"""line one\nline two""".trimMargin()`
        // (triple-quoted with margin markers). Both compile. Assert
        // the output uses one of those representations and does NOT
        // contain an unterminated `"line one` literal that would only
        // appear if a raw newline broke a single-quoted string.
        val schema = object : EntSchema("newline_defaults") {
            override fun id() = EntId.int()
            val label = string("label").default("line one\nline two")
        }
        finalize(schema)
        val output = generator.generate("NewlineDefault", schema).toString()

        val escapedNewline = output.contains("\\n")
        val triplyQuoted = output.contains("\"\"\"") && output.contains("trimMargin")
        assert(escapedNewline || triplyQuoted) {
            "Default with newline must be escaped (either \\n or triple-quoted + trimMargin); got\n$output"
        }
        // Sanity: the would-be-broken single-quoted form must NOT
        // appear (no `"line one\n` followed by literal newline →
        // unterminated string).
        assert(!output.contains("\"line one\nline two\"")) {
            "Generated source contains a single-quoted string with a raw newline in the middle — invalid Kotlin\n$output"
        }
    }

    @Test
    fun `generates the full save result-variant trio`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // saveOrThrow delegates to saveOrError().getOrThrow() — the
        // RFC's "throwing wraps result" guideline.
        assert(output.contains("public fun saveOrThrow(): Car = saveOrError().getOrThrow()")) {
            "Should generate saveOrThrow as a wrapper over saveOrError\n$output"
        }

        // saveOrError returns EntResult<Car> and wraps a successful save in Ok.
        assert(output.contains("public fun saveOrError(): EntResult<Car>")) {
            "Should generate saveOrError returning EntResult<Car>\n$output"
        }
        assert(output.contains("EntResult.Ok(save())")) {
            "Should wrap successful save in Ok\n$output"
        }
        // KotlinPoet may line-wrap inside arg lists; check call shapes
        // without anchoring on specific whitespace.
        assert(output.contains("catch (e: PrivacyDeniedException)")) {
            "Should catch PrivacyDeniedException\n$output"
        }
        assert(output.contains("EntError.PrivacyDenied(e.entity, EntOperation.valueOf(e.operation.name),")) {
            "Should map PrivacyDeniedException into EntError.PrivacyDenied\n$output"
        }
        assert(output.contains("catch (e: ValidationException)")) {
            "Should catch ValidationException\n$output"
        }
        assert(output.contains("EntError.ValidationFailed(e.entity, EntOperation.CREATE, e.violations.map {")) {
            "Should map ValidationException into ValidationFailed with CREATE operation\n$output"
        }
        assert(output.contains("it.toValidationViolation() }")) {
            "Should bridge each Invalid via toValidationViolation()\n$output"
        }
        // Any other EntException (e.g. NoChanges from a composed save)
        // — carry through via e.error.
        assert(output.contains("catch (e: EntException)")) {
            "Should catch the generic EntException base class\n$output"
        }
        assert(output.contains("EntResult.Err(e.error)")) {
            "Should pass EntException's error through unchanged\n$output"
        }
        // Exception catch-all routes through classifyDriverError so the
        // driver-level classifier (Phase 2) produces ConstraintViolation
        // for SQLSTATE 23xxx or DriverFailure as the fallback.
        assert(output.contains("catch (e: Exception)")) {
            "Should have a catch-all for Exception\n$output"
        }
        assert(output.contains("classifyDriverError(driver, e, \"Car\", EntOperation.CREATE)")) {
            "Should route uncaught Exception through classifyDriverError with CREATE operation\n$output"
        }
    }

    // ──────────────────────────────────────────────────────────────
    // RFC 08: private hook-facing adapters on the create builder
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `RFC 08 — create builder emits private _beforeSaveView adapter implementing Mutation only`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("private val _beforeSaveView: CarMutation")) {
            "Should declare a private _beforeSaveView property typed as CarMutation\n$output"
        }
        // Adapter is an anonymous `object : CarMutation { ... }`; it
        // must NOT implement CarCreateMutationView or CarCreate.
        assert(output.contains("object : CarMutation {")) {
            "_beforeSaveView should be an anonymous Mutation implementation\n$output"
        }
        // Forwarder for mutable scalar field.
        assert(output.contains("override var model: String?") &&
            output.contains("this@CarCreate.model")) {
            "_beforeSaveView should forward `model` through to the outer CarCreate\n$output"
        }
    }

    @Test
    fun `RFC 08 — create builder emits private _createMutationView adapter implementing CreateMutationView only`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("private val _createMutationView: CarCreateMutationView")) {
            "Should declare a private _createMutationView property typed as CarCreateMutationView\n$output"
        }
        assert(output.contains("object : CarCreateMutationView {")) {
            "_createMutationView should be an anonymous CreateMutationView implementation\n$output"
        }
        assert(output.contains("this@CarCreate.")) {
            "_createMutationView's forwarders should reference this@CarCreate\n$output"
        }
    }

    @Test
    fun `RFC 08 — beforeSave hook receives _beforeSaveView, not 'this'`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("for (hook in beforeSaveHooks) hook(_beforeSaveView)")) {
            "save() should pass _beforeSaveView to beforeSave hooks, not `this`\n$output"
        }
        assert(!output.contains("for (hook in beforeSaveHooks) hook(this)")) {
            "save() must NOT pass `this` to beforeSave hooks — RFC 08 contract\n$output"
        }
    }

    @Test
    fun `RFC 08 — beforeCreate CreateHookContext wraps _createMutationView, not 'this'`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("CarCreateHookContext(client, _createMutationView)")) {
            "CreateHookContext should wrap _createMutationView, not `this`\n$output"
        }
        assert(!output.contains("CarCreateHookContext(client, this)")) {
            "CreateHookContext must NOT wrap `this` — RFC 08 contract\n$output"
        }
    }

    @Test
    fun `RFC 08 — _beforeSaveView omits immutable FK forwarders (Mutation excludes them)`() {
        // Regression for the override-nothing bug: ${Entity}Mutation
        // only declares mutable FKs; immutable FKs live on
        // ${Entity}CreateMutationView. The _beforeSaveView adapter
        // implements Mutation, so emitting `override var ownerId`
        // for an immutable FK on the Mutation impl would fail
        // Kotlin compilation with "overrides nothing."
        //
        // Defined via an inline local schema with an immutable
        // field-backed FK so the test owns its fixtures.
        class ImmFkParent : EntSchema("imm_parents") {
            override fun id() = EntId.long()
        }
        class ImmFkChild : EntSchema("imm_children") {
            override fun id() = EntId.long()
            val name = string("name")
            val ownerId = long("owner_id").immutable()
            val owner = belongsTo<ImmFkParent>("owner").field(ownerId)
        }
        val parent = ImmFkParent()
        val child = ImmFkChild()
        val registry = mapOf<KClass<out EntSchema>, EntSchema>(
            parent::class to parent,
            child::class to child,
        )
        parent.finalize(registry)
        child.finalize(registry)
        val schemaNames = mapOf<EntSchema, String>(parent to "ImmFkParent", child to "ImmFkChild")
        val output = generator.generate("ImmFkChild", child, schemaNames).toString()
            .replace("\\s+".toRegex(), " ")

        // The _createMutationView adapter SHOULD have `ownerId` —
        // immutables are create-only writable, so they're part of
        // ${Entity}CreateMutationView's surface.
        val createViewBlock = output.substringAfter("private val _createMutationView: ImmFkChildCreateMutationView")
            .substringBefore("public fun save")
        assert(createViewBlock.contains("override var ownerId")) {
            "_createMutationView should forward `ownerId` (immutable FKs live on CreateMutationView)\n$output"
        }

        // The _beforeSaveView adapter MUST NOT have `ownerId` —
        // Mutation doesn't declare immutable FKs.
        val beforeSaveBlock = output.substringAfter("private val _beforeSaveView: ImmFkChildMutation")
            .substringBefore("private val _createMutationView")
        assert(!beforeSaveBlock.contains("override var ownerId")) {
            "_beforeSaveView must NOT forward immutable FK `ownerId` — Mutation doesn't declare it\n$output"
        }
        // The mutable scalar should still be there.
        assert(beforeSaveBlock.contains("override var name")) {
            "_beforeSaveView should still forward mutable scalars\n$output"
        }
    }

    @Test
    fun `RFC 08 — concrete Create still implements CreateMutationView (smallest change)`() {
        // Per RFC 08 §"Relationship between ${Entity}Create and
        // ${Entity}CreateMutationView", the class hierarchy is
        // unchanged — only the runtime object handed to hooks
        // changes. Non-hook code that upcasts a builder to either
        // view interface keeps working.
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("public class CarCreate( ") &&
            output.contains(") : CarCreateMutationView {")) {
            "CarCreate should still implement CarCreateMutationView at the class level\n$output"
        }
    }
}
