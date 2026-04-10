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
    fun `converts snake_case fields to camelCase Kotlin properties`() {
        val output = generator.generate("User", User).toString()

        // Kotlin property names use camelCase...
        assert(output.contains("val createdAt: Instant")) { "Should convert created_at to createdAt\n$output" }
        assert(output.contains("val updatedAt: Instant")) { "Should convert updated_at to updatedAt\n$output" }
        // ...but the snake_case raw name survives as the column ref's
        // constructor argument (used as the predicate field name).
        assert(output.contains("ComparableColumn<Instant>(\"created_at\")")) {
            "Column ref should carry the raw snake_case name\n$output"
        }
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

    @Test
    fun `generates create companion method taking DSL lambda`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("companion object")) { "Should have companion object\n$output" }
        assert(output.contains("fun create(block: CarCreate.() -> Unit): CarCreate")) {
            "Should have create method taking DSL lambda\n$output"
        }
        assert(output.contains("CarCreate().apply(block)")) {
            "Should apply block to new builder\n$output"
        }
    }

    @Test
    fun `generates update instance method taking DSL lambda`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun update(block: CarUpdate.() -> Unit): CarUpdate")) {
            "Should have update method taking DSL lambda\n$output"
        }
        assert(output.contains("CarUpdate(this).apply(block)")) {
            "Should pass this to update builder and apply block\n$output"
        }
    }

    @Test
    fun `generates query companion method taking optional DSL lambda`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun query(block: CarQuery.() -> Unit = {}): CarQuery")) {
            "Should have query method with optional DSL lambda\n$output"
        }
        assert(output.contains("CarQuery().apply(block)")) {
            "Should apply block to new query\n$output"
        }
    }

    @Test
    fun `emits typed column refs on the companion for each field`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("val model: StringColumn = StringColumn(\"model\")")) {
            "Should have StringColumn for model\n$output"
        }
        assert(output.contains("val year: ComparableColumn<Int> = ComparableColumn<Int>(\"year\")")) {
            "Should have ComparableColumn<Int> for year\n$output"
        }
        assert(output.contains("val price: NullableComparableColumn<Float> = NullableComparableColumn<Float>(\"price\")")) {
            "Should have NullableComparableColumn<Float> for optional price\n$output"
        }
    }

    @Test
    fun `emits Column (non-comparable) for bool fields`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("val active: Column<Boolean> = Column<Boolean>(\"active\")")) {
            "Should have Column<Boolean> for active\n$output"
        }
    }

    @Test
    fun `column refs use snake_case for the column name`() {
        val output = generator.generate("User", User).toString()

        // Property name is camelCase, but the column name carried into
        // the Predicate should be the raw field name.
        assert(output.contains("val createdAt: ComparableColumn<Instant> = ComparableColumn<Instant>(\"created_at\")")) {
            "Should use snake_case column name\n$output"
        }
    }
}