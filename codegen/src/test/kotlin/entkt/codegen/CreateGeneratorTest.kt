package entkt.codegen

import entkt.codegen.mutation.CreateGenerator
import entkt.codegen.mutation.buildClassifyDriverFailureHelper
import entkt.codegen.mutation.driverCallFailureTail
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Session : EntSchema("sessions", clientName = "sessions") {
    override fun id() = EntId.string()
    val token by string("token")
}

class Event : EntSchema("events", clientName = "events") {
    override fun id() = EntId.int()
    val title by string("title")
    val createdAt by time("created_at").defaultNow().immutable()
}

enum class Status { LOW, MEDIUM, HIGH }
enum class OtherStatus { PENDING, ACCEPTED }

class DefaultedEnumEntity : EntSchema("defaulted_enum_entities", clientName = "defaultedEnumEntities") {
    override fun id() = EntId.int()
    val priority by enum<Status>("priority").default(Status.LOW)
}

class ValidatedEntity : EntSchema("validated_entities", clientName = "validatedEntities") {
    override fun id() = EntId.int()
    val name by string("name").minLength(3).maxLength(100).notEmpty()
    val age by int("age").positive()
    val nickname by string("nickname").nullable().match(Regex("^[a-z]+$"))
    val code by string("code").match(Regex("^[a-z]+$", RegexOption.IGNORE_CASE))
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
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("public fun save(): MutationResult<Unit>")) {
            "save() should return MutationResult<Unit>\n$output"
        }
        // A missing required field short-circuits into a typed
        // validation Failed via the shared _validationFailed helper —
        // it does not throw.
        assert(
            output.contains(
                """val _entktValueModel = this.model ?: return _validationFailed(listOf(ValidationViolation("model is required", field = "model")))""",
            ),
        ) { "Should return validation Failed when model is missing\n$output" }
        assert(
            output.contains(
                """val _entktValueYear = this.year ?: return _validationFailed(listOf(ValidationViolation("year is required", field = "year")))""",
            ),
        ) { "Should return validation Failed when year is missing\n$output" }
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
        assert(output.contains("beforeSaveHooks: List<BatchHook<CarMutation>>")) {
            "Should take beforeSaveHooks\n$output"
        }
        assert(output.contains("beforeCreateHooks: List<BatchHook<CarCreateHookContext>>")) {
            "beforeCreateHooks should be typed against CarCreateHookContext\n$output"
        }
        assert(output.contains("afterCreateHooks: List<BatchHook<Car>>")) {
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

        assert(output.contains("runBatchHooksForInternalUse(listOf(entity), afterCreateHooks)")) {
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
        assert(output.contains("val _entktValueActive = this.active ?: true")) {
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
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("_entktValueName.length > 100")) {
            "Should emit maxLength check\n$output"
        }
        assert(output.contains("_entktValueName.isEmpty()")) {
            "Should emit notEmpty check\n$output"
        }
        // A failed validator returns MutationResult.Failed carrying a
        // typed EntValidationException (via _validationFailed) with the
        // rule's message and the field name — it does not throw.
        assert(
            output.contains(
                """if (_entktValueName.length < 3) return _validationFailed(listOf(ValidationViolation("value must be at least 3 characters", field = "name")))""",
            ),
        ) {
            "minLength failure should return validation Failed with message + field\n$output"
        }
    }

    @Test
    fun `save emits validation checks for numeric validators`() {
        val schema = ValidatedEntity()
        finalize(schema)
        val output = generator.generate("ValidatedEntity", schema).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                """if (_entktValueAge <= 0) return _validationFailed(listOf(ValidationViolation("value must be positive", field = "age")))""",
            ),
        ) {
            "positive failure should return validation Failed with message + field\n$output"
        }
    }

    @Test
    fun `save wraps optional field validation in null check`() {
        val schema = ValidatedEntity()
        finalize(schema)
        val output = generator.generate("ValidatedEntity", schema).toString()

        // nickname is optional, so validation should be wrapped
        assert(output.contains("if (_entktValueNickname != null)")) {
            "Should null-guard optional field validation\n$output"
        }
        assert(output.contains("Regex(") && output.contains(".matches(_entktValueNickname)")) {
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

        assert(output.contains("\"priority\" to _entktValuePriority.name")) {
            "Should convert typed enum to .name in the row map\n$output"
        }
    }

    @Test
    fun `second typed enum save also converts to name`() {
        val ticket = Ticket()
        finalize(ticket)
        val output = generator.generate("Ticket", ticket).toString()

        assert(output.contains("\"category\" to _entktValueCategory.name")) {
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
        val wrongDefault = object : EntSchema("wrong_defaults", clientName = "wrongDefaults") {
            override fun id() = EntId.int()
            val priority by enum<Status>("priority").default(OtherStatus.PENDING)
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
        val validationPos = output.indexOf("_entktValueName.length < 3")
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
        val nicknameRegex = regexLines.find { it.contains("_entktValueNickname") }
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
        val schema = object : EntSchema("nullable_defaults", clientName = "nullableDefaults") {
            override fun id() = EntId.int()
            val nickname by string("nickname").nullable().default("anonymous")
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
        val schema = object : EntSchema("dollar_defaults", clientName = "dollarDefaults") {
            override fun id() = EntId.int()
            val label by string("label").default("price is \$10")
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
        val schema = object : EntSchema("newline_defaults", clientName = "newlineDefaults") {
            override fun id() = EntId.int()
            val label by string("label").default("line one\nline two")
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
    fun `save and saveAndLoad share one executeSaveForInternalUse path`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // One internal member owns the entire create pipeline; the two
        // public terminals only differ in whether returned-entity LOAD
        // privacy applies.
        assert(output.contains("internal fun executeSaveForInternalUse(applyLoadPrivacy: Boolean): MutationResult<Car>")) {
            "Should generate the internal executeSaveForInternalUse pipeline\n$output"
        }
        // save(): MutationResult<Unit> — no entity disclosure, so no
        // LOAD privacy; Success(entity) is projected to Success(Unit).
        assert(
            output.contains(
                "public fun save(): MutationResult<Unit> = when (val result = " +
                    "executeSaveForInternalUse(applyLoadPrivacy = false)) " +
                    "{ is MutationResult.Success -> MutationResult.Success(Unit) is MutationResult.Failed -> result }",
            ),
        ) {
            "save() should delegate to executeSaveForInternalUse(applyLoadPrivacy = false)\n$output"
        }
        // saveAndLoad(): MutationResult<Car> — same path with LOAD
        // privacy applied to the materialized row.
        assert(output.contains("public fun saveAndLoad(): MutationResult<Car> = executeSaveForInternalUse(applyLoadPrivacy = true)")) {
            "saveAndLoad() should delegate to executeSaveForInternalUse(applyLoadPrivacy = true)\n$output"
        }
        // CREATE privacy denial maps to a typed
        // EntMutationPrivacyDeniedException Failed (pre-insert, so
        // NotPersisted and no entity key).
        assert(output.contains("val denialReason = client.cars.createDenialReasonOrNull(privacy, candidate)")) {
            "Should consult the repo's createDenialReasonOrNull\n$output"
        }
        assert(
            output.contains(
                """EntMutationPrivacyDeniedException(MutationWriteState.NotPersisted, "Car", EntOperation.CREATE, null, denialReason)""",
            ),
        ) {
            "CREATE denial should map to EntMutationPrivacyDeniedException(NotPersisted, CREATE)\n$output"
        }
        // Driver insert failures are classified into typed
        // EntMutationExceptions at the insert site (CancellationException
        // rethrown), recorded on the transaction coordinator, and
        // returned as Failed.
        assert(
            output.contains(
                "val row = try { driver.insert(Car.TABLE, values) } " +
                    "catch (e: CancellationException) { throw e } catch (e: Exception) " +
                    "{ val classified = _classifyDriverFailure(e, MutationWriteState.PersistenceUnknown) " +
                    "client.recordTransactionMutationFailure(classified) " +
                    "return MutationResult.failedForInternalUse(classified) }",
            ),
        ) {
            "Insert failures should classify + record + return Failed\n$output"
        }
        assert(output.contains("""driver.classifyMutationException(e, "Car", EntOperation.CREATE)""")) {
            "_classifyDriverFailure should route through driver.classifyMutationException with CREATE\n$output"
        }
        // The terminal boundary captures any other foreign failure as
        // EntUnexpectedMutationException carrying the tracked writeState.
        assert(
            output.contains(
                "} catch (e: CancellationException) { throw e } catch (e: Exception) " +
                    "{ val exception = EntUnexpectedMutationException(writeState, e) " +
                    "client.recordTransactionMutationFailure(exception) " +
                    "return MutationResult.failedForInternalUse(exception) }",
            ),
        ) {
            "Terminal boundary should capture foreign failures as EntUnexpectedMutationException\n$output"
        }
        // Validation failures share the _validationFailed helper, which
        // records the failure and returns Failed(EntValidationException).
        assert(
            output.contains(
                "private fun _validationFailed(violations: List<ValidationViolation>): MutationResult<Nothing> " +
                    """{ val exception = EntValidationException("Car", EntOperation.CREATE, violations) """ +
                    "client.recordTransactionMutationFailure(exception) " +
                    "return MutationResult.failedForInternalUse(exception) }",
            ),
        ) {
            "_validationFailed should build EntValidationException(CREATE) and return Failed\n$output"
        }
        // Removed legacy surface: no result-variant trio, no EntResult /
        // EntError bridging.
        for (legacy in listOf("saveOrError", "saveOrThrow", "saveOrNull", "EntResult", "EntError")) {
            assert(!output.contains(legacy)) {
                "Removed legacy member '$legacy' must not be emitted\n$output"
            }
        }
    }

    @Test
    fun `create preparation is a guarded pure seam shared by scalar and batch execution`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "internal fun prepareForInternalUse(): MutationResult<PreparedCreate<CarWriteCandidate>>",
            ),
        ) {
            "Preparation should return the named runtime carrier\n$output"
        }
        assert(output.contains("return MutationResult.Success(PreparedCreate(values, candidate))")) {
            "Preparation should keep the normalized row and matching candidate together\n$output"
        }
        assert(output.contains("internal fun beforeSaveHookValueForInternalUse(): CarMutation = _beforeSaveView")) {
            "Should expose the restricted beforeSave hook value through a guarded seam\n$output"
        }
        assert(
            output.contains(
                "internal fun beforeCreateHookValueForInternalUse(): CarCreateHookContext = " +
                    "CarCreateHookContext(client.hookClientScopeForInternalUse, _createMutationView)",
            ),
        ) {
            "Should expose the restricted beforeCreate hook value through a guarded seam\n$output"
        }
        assert(
            output.contains(
                "internal fun configureForCreateManyForInternalUse(block: CarCreate.() -> Unit, " +
                    "managedSaveFailures: MutableList<EntMutationException>): MutationResult<CarCreate>",
            ),
        ) {
            "createMany should apply each block through the guarded configuration seam\n$output"
        }
        assert(
            output.contains(
                "if (_managedByCreateMany) { val existing = _managedSaveFailure " +
                    "val exception = if (existing != null) { existing } else { " +
                    "val created = EntUnexpectedMutationException(MutationWriteState.NotPersisted, " +
                    "IllegalStateException(\"A builder managed by createMany cannot be persisted " +
                    "independently; let createMany execute the batch\"))",
            ),
        ) {
            "The scalar terminal should reject recursive persistence from a createMany block\n$output"
        }

        val preparation = output
            .substringAfter("internal fun prepareForInternalUse()")
            .substringBefore("internal fun executeSaveForInternalUse")
        assert(preparation.contains("val values: Map<String, Any?>")) {
            "Preparation should build the driver row map\n$output"
        }
        assert(preparation.contains("val candidate = CarWriteCandidate(")) {
            "Preparation should build the matching write candidate\n$output"
        }
        for (forbidden in listOf(
            "runBatchHooksForInternalUse",
            "currentPrivacyContext",
            "createDenialReasonOrNull",
            "evaluateCreateValidation",
            "driver.",
        )) {
            assert(!preparation.contains(forbidden)) {
                "Preparation must not perform lifecycle or I/O work ('$forbidden')\n$preparation"
            }
        }

        val execution = output.substringAfter("internal fun executeSaveForInternalUse")
        val orderedStages = listOf(
            "checkTransactionRequirement",
            "beforeSaveHookValueForInternalUse()",
            "beforeCreateHookValueForInternalUse()",
            "prepareForInternalUse()",
            "currentPrivacyContext()",
            "createDenialReasonOrNull",
            "evaluateCreateValidation",
            "driver.insert",
            "afterCreateHooks",
            "loadDenialOrNull",
        )
        val positions = orderedStages.map(execution::indexOf)
        assert(positions.all { it >= 0 } && positions.zipWithNext().all { (left, right) -> left < right }) {
            "Scalar execution should preserve the established lifecycle order: ${orderedStages.zip(positions)}\n$output"
        }
    }

    @Test
    fun `shared driver failure emitters support an operation-specific classifier name`() {
        val helper = buildClassifyDriverFailureHelper(
            schemaName = "Car",
            operationName = "CREATE",
            helperName = "_classifyCreateDriverFailure",
        ).toString()
        val tail = driverCallFailureTail(
            fallbackStateName = "PersistenceUnknown",
            classifierName = "_classifyCreateDriverFailure",
        ).toString()

        assert(helper.contains("fun _classifyCreateDriverFailure(")) {
            "Helper should use the requested generated member name\n$helper"
        }
        assert(
            tail.contains("_classifyCreateDriverFailure(e,") &&
                tail.contains("MutationWriteState.PersistenceUnknown"),
        ) {
            "Driver-call tail should invoke the matching requested classifier\n$tail"
        }
    }

    // ──────────────────────────────────────────────────────────────
    // create-hook adapter: private hook-facing adapters on the create builder
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `create-hook adapter — create builder emits private _beforeSaveView adapter implementing Mutation only`() {
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
    fun `create-hook adapter — create builder emits private _createMutationView adapter implementing CreateMutationView only`() {
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
    fun `create-hook adapter — beforeSave hook receives _beforeSaveView, not 'this'`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("runBatchHooksForInternalUse(listOf(beforeSaveHookValueForInternalUse()), beforeSaveHooks)")) {
            "save() should obtain the restricted beforeSave value through the shared seam\n$output"
        }
        assert(!output.contains("runBatchHooksForInternalUse(listOf(this), beforeSaveHooks)")) {
            "save() must NOT pass `this` to beforeSave hooks — create-hook adapter contract\n$output"
        }
    }

    @Test
    fun `create-hook adapter — beforeCreate CreateHookContext wraps _createMutationView, not 'this'`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("runBatchHooksForInternalUse(listOf(beforeCreateHookValueForInternalUse()), beforeCreateHooks)")) {
            "save() should obtain the restricted beforeCreate value through the shared seam\n$output"
        }
        assert(output.contains("CarCreateHookContext(client.hookClientScopeForInternalUse, _createMutationView)")) {
            "The shared hook-value seam should wrap _createMutationView, not `this`\n$output"
        }
        assert(!output.contains("CarCreateHookContext(client, this)")) {
            "CreateHookContext must NOT wrap `this` — create-hook adapter contract\n$output"
        }
    }

    @Test
    fun `create-hook adapter — _beforeSaveView omits immutable FK forwarders (Mutation excludes them)`() {
        // Regression for the override-nothing bug: ${Entity}Mutation
        // only declares mutable FKs; immutable FKs live on
        // ${Entity}CreateMutationView. The _beforeSaveView adapter
        // implements Mutation, so emitting `override var ownerId`
        // for an immutable FK on the Mutation impl would fail
        // Kotlin compilation with "overrides nothing."
        //
        // Defined via an inline local schema with an immutable
        // field-backed FK so the test owns its fixtures.
        class ImmFkParent : EntSchema("imm_parents", clientName = "immFkParents") {
            override fun id() = EntId.long()
        }
        class ImmFkChild : EntSchema("imm_children", clientName = "immFkChilds") {
            override fun id() = EntId.long()
            val name by string("name")
            val ownerId by long("owner_id").immutable()
            val owner by belongsTo<ImmFkParent>("owner").field(ownerId)
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
    fun `field-backed FK names cannot collide with generated snapshot locals`() {
        // The generator detaches mutable create payloads into locals named
        // `_entktPrepared{Field}` and appends `_` when a field-backed FK
        // property already claims that name.
        //
        // Under the declaration-name contract that collision is
        // unreachable: declaration names must be lower-camel identifiers,
        // so the framework's `_` prefix is reserved and the schema is
        // rejected at binding — long before codegen picks a local name.
        // The generator's suffix logic stays as defence in depth for
        // future generated-vs-generated collisions.
        val err = assertFailsWith<IllegalArgumentException> {
            object : EntSchema("snapshot_children", clientName = "snapshotChildren") {
                override fun id() = EntId.long()
                val payload by bytes("payload")
                val _entktPreparedPayload by uuid("owner_id")
            }
        }
        assertTrue(
            err.message!!.contains("not a valid generated API name") &&
                err.message!!.contains("_entktPreparedPayload"),
            "expected an identifier diagnostic, got: ${err.message}",
        )
    }

    @Test
    fun `create-hook adapter — concrete Create still implements CreateMutationView (smallest change)`() {
        // The class hierarchy is unchanged; only the runtime object
        // handed to hooks changes. Non-hook code that upcasts a
        // builder to either
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
