package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntMixin
import entkt.schema.EntSchema
import entkt.schema.fields
import entkt.schema.edges
import entkt.schema.indexes
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

    override fun indexes() = indexes {
        index("name", "email").unique()
        index("created_at")
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
    fun `entity companion has no I_O entry points (those live on the repo)`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("companion object")) { "Should still have companion object for column refs\n$output" }
        assert(!output.contains("fun create(")) {
            "create() should live on CarRepo, not on the entity companion\n$output"
        }
        assert(!output.contains("fun query(")) {
            "query() should live on CarRepo, not on the entity companion\n$output"
        }
    }

    @Test
    fun `entity has no instance update method (it lives on the repo)`() {
        val output = generator.generate("Car", Car).toString()

        assert(!output.contains("fun update(")) {
            "update() should live on CarRepo, not on the entity instance\n$output"
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

    @Test
    fun `emits an EdgeRef on the companion for each declared edge`() {
        // User has `to("cars", Car)` — needs the schemaNames map so the
        // generator can resolve the target's class names.
        val schemaNames = mapOf<entkt.schema.EntSchema, String>(User to "User", Car to "Car")
        val output = generator.generate("User", User, schemaNames).toString()

        assert(output.contains("import entkt.query.EdgeRef")) {
            "Should import EdgeRef\n$output"
        }
        assert(output.contains("val cars: EdgeRef<Car, CarQuery> = EdgeRef(\"cars\") { CarQuery(NoopDriver) }")) {
            "Should emit a typed EdgeRef for the cars edge wired to NoopDriver\n$output"
        }
    }

    @Test
    fun `generated SCHEMA includes unique flag on unique columns`() {
        val output = generator.generate("User", User).toString()

        // email is declared .unique() so the generated ColumnMetadata should carry unique = true
        assert(output.contains("unique = true")) {
            "Should emit unique = true for the email column\n$output"
        }
    }

    @Test
    fun `generated SCHEMA includes indexes from the schema DSL`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("IndexMetadata")) {
            "Should emit IndexMetadata entries\n$output"
        }
        assert(output.contains("\"name\", \"email\"")) {
            "Should include the composite unique index fields\n$output"
        }
        assert(output.contains("\"created_at\"")) {
            "Should include the non-unique index field\n$output"
        }
    }

    @Test
    fun `does not emit EdgeRef when target is missing from the schema map`() {
        // Without the schema map the generator can't resolve target
        // class names — it should silently skip EdgeRef emission rather
        // than producing a broken `EdgeRef<???, ???>`.
        val output = generator.generate("User", User).toString()

        assert(!output.contains("EdgeRef")) {
            "Should not emit EdgeRef without schemaNames\n$output"
        }
    }
}