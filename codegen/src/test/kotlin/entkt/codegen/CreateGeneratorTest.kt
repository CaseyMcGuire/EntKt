package entkt.codegen

import entkt.codegen.mutation.CreateGenerator
import entkt.codegen.mutation.buildClassifyDriverFailureHelper
import entkt.codegen.mutation.driverCallFailureTail
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Session : EntSchema("sessions", clientName = "sessions") {
    override fun id() = EntId.string()
    val token by string("token")
}

private class CreateEvent : EntSchema("events", clientName = "createEvents") {
    override fun id() = EntId.int()
    val title by string("title")
    val createdAt by time("created_at").defaultNow().immutable()
}

private enum class CreateStatus { LOW, MEDIUM, HIGH }
private enum class OtherCreateStatus { PENDING, ACCEPTED }

private class DefaultedCreateEnum : EntSchema(
    "defaulted_enum_entities",
    clientName = "defaultedCreateEnums",
) {
    override fun id() = EntId.int()
    val priority by enum<CreateStatus>("priority").default(CreateStatus.LOW)
}

class ValidatedEntity : EntSchema("validated_entities", clientName = "validatedEntities") {
    override fun id() = EntId.int()
    val name by string("name").minLength(3).maxLength(100).notEmpty()
    val age by int("age").positive()
    val nickname by string("nickname").nullable().match(Regex("^[a-z]+$"))
    val code by string("code").match(Regex("^[a-z]+$", RegexOption.IGNORE_CASE))
}

private fun finalizeCreateSchemas(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class CreateGeneratorTest {
    private val generator = CreateGenerator("com.example.ent")

    @Test
    fun `generates a state-only create draft with assignment tracking`() {
        val car = Car()
        finalizeCreateSchemas(car, User())

        val output = generator.generate("Car", car).toString()

        assertTrue(output.contains("class CarCreateDraft"), output)
        assertTrue(output.contains("var model: String? = null"), output)
        assertTrue(output.contains("assignedFields.mark(Car.model)"), output)
        assertTrue(output.contains("fun isSet(column: ColumnReference<Car>)"), output)
        assertTrue(output.contains("@EntktDsl"), output)
    }

    @Test
    fun `draft does not own clients lifecycle configuration or save terminals`() {
        val car = Car()
        finalizeCreateSchemas(car, User())

        val output = generator.generate("Car", car).toString()

        assertTrue(!output.contains("EntClient"), output)
        assertTrue(!output.contains("MutationEvaluator"), output)
        assertTrue(!output.contains("beforeSaveHooks"), output)
        assertTrue(!output.contains("fun save("), output)
        assertTrue(!output.contains("CreateMutationInput"), output)
    }

    @Test
    fun `required values and validation are resolved by the repo specification`() {
        val schema = ValidatedEntity()
        finalizeCreateSchemas(schema)

        val generatedResolver = resolve("ValidatedEntity", schema)
        val output = generatedResolver
            .replace("\\s+".toRegex(), " ")

        assertTrue(output.contains("val _entktValueName = this.name ?: return"), output)
        assertTrue(output.contains("\"name is required\", field = \"name\""), output)
        assertTrue(output.contains("_entktValueName.length < 3"), output)
        assertTrue(output.contains("_entktValueName.length > 100"), output)
        assertTrue(output.contains("_entktValueAge <= 0"), output)
        assertTrue(output.contains("if (_entktValueNickname != null)"), output)
        assertTrue(output.contains("setOf(kotlin.text.RegexOption.IGNORE_CASE)"), output)
        assertTrue(output.indexOf("val _entktValueName") < output.indexOf("val values:"), output)
        assertTrue(!generatedResolver.contains(Regex("return\\s*\\n")), generatedResolver)
    }

    @Test
    fun `defaults distinguish an omitted field from explicit null`() {
        val user = User()
        finalizeCreateSchemas(user, Car())

        val output = resolve("User", user)
            .replace("\\s+".toRegex(), " ")

        assertTrue(
            output.contains(
                "val _entktValueActive = if (isSet(com.example.ent.User.active)) this.active ?: return",
            ),
            output,
        )
        assertTrue(output.contains("else true"), output)
    }

    @Test
    fun `time and enum defaults retain their typed expressions`() {
        val event = CreateEvent()
        val enumSchema = DefaultedCreateEnum()
        finalizeCreateSchemas(event, enumSchema)

        val eventOutput = resolve("CreateEvent", event)
        val enumOutput = resolve("DefaultedCreateEnum", enumSchema)

        assertTrue(eventOutput.contains("Instant.now()"), eventOutput)
        assertTrue(enumOutput.contains("CreateStatus.LOW"), enumOutput)
        assertTrue(enumOutput.contains("_entktValuePriority.name"), enumOutput)
    }

    @Test
    fun `wrong enum defaults remain a schema error`() {
        val schema = object : EntSchema("wrong_enum", clientName = "wrongEnums") {
            override fun id() = EntId.int()
            val status by enum<CreateStatus>("status").default(OtherCreateStatus.PENDING)
        }
        finalizeCreateSchemas(schema)
        assertFailsWith<IllegalArgumentException> {
            resolve("WrongEnum", schema)
        }
    }

    @Test
    fun `explicit ids live on the draft and enter the storage row`() {
        val session = Session()
        finalizeCreateSchemas(session)

        val draft = generator.generate("Session", session).toString()
        val resolver = resolve("Session", session)

        assertTrue(draft.contains("class SessionCreateDraft @EntktInternal constructor("), draft)
        assertTrue(draft.contains("internal val id: String,"), draft)
        assertTrue(resolver.contains("\"id\" to id"), resolver)
    }

    @Test
    fun `shared driver failure emitters support an operation-specific classifier name`() {
        val helper = buildClassifyDriverFailureHelper(
            schemaName = "Car",
            operationName = "DELETE",
            helperName = "_classifyDeleteDriverFailure",
        ).toString()
        val tail = driverCallFailureTail(
            fallbackStateName = "PersistenceUnknown",
            classifierName = "_classifyDeleteDriverFailure",
        ).toString()

        assertTrue(helper.contains("fun _classifyDeleteDriverFailure"), helper)
        assertTrue(helper.contains("EntOperation.DELETE"), helper)
        assertTrue(tail.contains("_classifyDeleteDriverFailure(e"), tail)
    }

    private fun resolve(schemaName: String, schema: EntSchema): String =
        generator.buildResolveFunction(
            schemaName = schemaName,
            schema = schema,
            schemaNames = mapOf(schema to schemaName),
        ).toString()
}
