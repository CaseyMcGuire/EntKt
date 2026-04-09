package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntMixin
import entkt.schema.EntSchema
import entkt.schema.fields
import entkt.schema.edges
import kotlin.test.Test
import kotlin.test.assertEquals

object Car : EntSchema() {
    override fun fields() = fields {
        string("model")
        int("year")
        float("price").optional()
    }
}

object TimeMixin : EntMixin {
    override fun fields() = fields {
        time("created_at").immutable()
        time("updated_at")
    }
}

object User : EntSchema() {
    override fun id() = EntId.uuid()

    override fun mixins() = listOf(TimeMixin)

    override fun fields() = fields {
        string("name")
        int("age").optional()
        string("email").unique()
        bool("active").default(true)
    }

    override fun edges() = edges {
        to("cars", Car)
    }
}

class EntityGeneratorTest {

    private val generator = EntityGenerator("com.example.ent")

    @Test
    fun `generates entity data class with fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("data class Car")) { "Should generate data class\n$output" }
        assert(output.contains("val id: Int")) { "Should have int id\n$output" }
        assert(output.contains("val model: String")) { "Should have model field\n$output" }
        assert(output.contains("val year: Int")) { "Should have year field\n$output" }
        assert(output.contains("val price: Float?")) { "Should have nullable price\n$output" }
    }

    @Test
    fun `generates entity with UUID id`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("val id: UUID")) { "Should have UUID id\n$output" }
    }

    @Test
    fun `generates entity with mixin fields`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("val createdAt: Instant")) { "Should have createdAt from mixin\n$output" }
        assert(output.contains("val updatedAt: Instant")) { "Should have updatedAt from mixin\n$output" }
    }

    @Test
    fun `converts snake_case fields to camelCase`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("createdAt")) { "Should convert created_at to createdAt\n$output" }
        assert(output.contains("updatedAt")) { "Should convert updated_at to updatedAt\n$output" }
        assert(!output.contains("created_at")) { "Should not have snake_case\n$output" }
    }

    @Test
    fun `optional fields are nullable with default null`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("val age: Int? = null")) { "Should have nullable age with default null\n$output" }
    }

    @Test
    fun `generates correct package`() {
        val file = generator.generate("Car", Car)
        assertEquals("com.example.ent", file.packageName)
    }
}