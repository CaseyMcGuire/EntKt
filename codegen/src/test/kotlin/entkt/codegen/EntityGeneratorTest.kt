package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntMixin
import entkt.schema.EntSchema
import entkt.schema.fields
import entkt.schema.edges
import entkt.schema.indexes
import kotlin.test.Test
import kotlin.test.assertEquals

enum class Priority { LOW, MEDIUM, HIGH }
enum class Category { BUG, FEATURE }

object StorageKeyEntity : EntSchema() {
    override fun fields() = fields {
        string("display_name").storageKey("full_name")
        int("score")
    }
}

object Car : EntSchema() {
    override fun fields() = fields {
        string("model")
        int("year")
        float("price").optional()
    }
}

object Ticket : EntSchema() {
    override fun fields() = fields {
        string("title")
        enum<Priority>("priority")
        enum<Category>("category")
    }
}

object TimeMixin : EntMixin {
    override fun fields() = fields {
        time("created_at").immutable()
        time("updated_at")
    }

    override fun indexes() = indexes {
        index("created_at")
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
        index("email").where("active = true")
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
    fun `generated SCHEMA includes indexes from the schema and its mixins`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("IndexMetadata")) {
            "Should emit IndexMetadata entries\n$output"
        }
        assert(output.contains("\"name\", \"email\"")) {
            "Should include the composite unique index from User.indexes()\n$output"
        }
        // created_at index comes from TimeMixin.indexes(), not User — verifies
        // mixin indexes are merged into the generated schema.
        assert(output.contains("\"created_at\"")) {
            "Should include the index declared by TimeMixin\n$output"
        }
    }

    @Test
    fun `generated SCHEMA includes where clause for partial indexes`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("""where = "active = true"""")) {
            "Should emit where clause for partial index\n$output"
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

    @Test
    fun `entity with edges gets an Edges inner data class`() {
        val schemaNames = mapOf<entkt.schema.EntSchema, String>(User to "User", Car to "Car")
        val output = generator.generate("User", User, schemaNames).toString()

        assert(output.contains("data class Edges")) {
            "Should generate inner Edges data class\n$output"
        }
        assert(output.contains("val edges: Edges = Edges()")) {
            "Should have edges property with default\n$output"
        }
    }

    @Test
    fun `Edges class has list property for to-many edges`() {
        val schemaNames = mapOf<entkt.schema.EntSchema, String>(User to "User", Car to "Car")
        val output = generator.generate("User", User, schemaNames).toString()

        assert(output.contains("val cars: List<Car>? = null")) {
            "To-many edge should produce nullable list property\n$output"
        }
    }

    @Test
    fun `entity without edges does not get Edges class`() {
        val output = generator.generate("Car", Car).toString()

        assert(!output.contains("data class Edges")) {
            "Entity with no edges should not have Edges class\n$output"
        }
        assert(!output.contains("val edges:")) {
            "Entity with no edges should not have edges property\n$output"
        }
    }

    @Test
    fun `typed enum field emits the Kotlin enum type on the entity`() {
        val output = generator.generate("Ticket", Ticket).toString()

        assert(output.contains("val priority: Priority")) {
            "Should use the Kotlin enum type\n$output"
        }
    }

    @Test
    fun `typed enum column ref uses EnumColumn`() {
        val output = generator.generate("Ticket", Ticket).toString()

        assert(output.contains("val priority: EnumColumn<Priority> = EnumColumn<Priority>(\"priority\")")) {
            "Should emit EnumColumn parameterized with the enum class\n$output"
        }
    }

    @Test
    fun `typed enum fromRow uses valueOf`() {
        val output = generator.generate("Ticket", Ticket).toString()

        assert(output.contains("priority = Priority.valueOf(row[\"priority\"] as String)")) {
            "Should convert via valueOf in fromRow\n$output"
        }
    }

    @Test
    fun `second typed enum also uses enum type and EnumColumn`() {
        val output = generator.generate("Ticket", Ticket).toString()

        assert(output.contains("val category: Category")) {
            "Second typed enum should use the Kotlin enum type\n$output"
        }
        assert(output.contains("val category: EnumColumn<Category> = EnumColumn<Category>(\"category\")")) {
            "Second typed enum should use EnumColumn\n$output"
        }
    }

    @Test
    fun `fromRow does not reference edges`() {
        val schemaNames = mapOf<entkt.schema.EntSchema, String>(User to "User", Car to "Car")
        val output = generator.generate("User", User, schemaNames).toString()

        val fromRowBlock = output.substringAfter("fun fromRow").substringBefore("}")
        assert(!fromRowBlock.contains("edges")) {
            "fromRow should not reference edges — it uses the default\n$fromRowBlock"
        }
    }

    @Test
    fun `storageKey overrides the column name in fromRow`() {
        val output = generator.generate("StorageKeyEntity", StorageKeyEntity).toString()

        assert(output.contains("""row["full_name"]""")) {
            "fromRow should use storageKey as the row key\n$output"
        }
        assert(!output.contains("""row["display_name"]""")) {
            "fromRow should NOT use the field name when storageKey is set\n$output"
        }
    }

    @Test
    fun `storageKey overrides the column name in column refs`() {
        val output = generator.generate("StorageKeyEntity", StorageKeyEntity).toString()

        assert(output.contains("""StringColumn("full_name")""")) {
            "Column ref should use storageKey\n$output"
        }
    }

    @Test
    fun `storageKey property name still uses the field name`() {
        val output = generator.generate("StorageKeyEntity", StorageKeyEntity).toString()

        assert(output.contains("val displayName: String")) {
            "Property name should be derived from field name, not storageKey\n$output"
        }
    }

    @Test
    fun `storageKey overrides column name in ColumnMetadata`() {
        val output = generator.generate("StorageKeyEntity", StorageKeyEntity).toString()

        assert(output.contains("""name = "full_name"""")) {
            "ColumnMetadata should use storageKey\n$output"
        }
        assert(!output.contains("""name = "display_name"""")) {
            "ColumnMetadata should NOT use field name when storageKey is set\n$output"
        }
    }

    @Test
    fun `storageKey overrides column name in index metadata`() {
        val indexed = object : EntSchema() {
            override fun fields() = fields {
                string("display_name").storageKey("full_name")
                int("score")
            }
            override fun indexes() = indexes {
                index("display_name", "score").unique()
            }
        }
        val output = generator.generate("IndexedStorageKey", indexed).toString()

        assert(output.contains(""""full_name", "score"""")) {
            "IndexMetadata should use storageKey for indexed columns\n$output"
        }
        assert(!output.contains(""""display_name", "score"""")) {
            "IndexMetadata should NOT use field name when storageKey is set\n$output"
        }
    }

    @Test
    fun `index referencing nonexistent field fails at codegen time`() {
        val schema = object : EntSchema() {
            override fun fields() = fields {
                string("name")
            }
            override fun indexes() = indexes {
                index("name", "emial")
            }
        }
        val error = kotlin.test.assertFailsWith<IllegalStateException> {
            generator.generate("BadIndex", schema)
        }
        assert(error.message!!.contains("emial")) {
            "Error should mention the bad field name\n${error.message}"
        }
    }

    @Test
    fun `sensitive field is redacted in generated toString`() {
        val schema = object : EntSchema() {
            override fun fields() = fields {
                string("name")
                string("password").sensitive()
            }
        }
        val output = generator.generate("Account", schema).toString()

        assert(output.contains("override fun toString(): String")) {
            "Should generate explicit toString override\n$output"
        }
        assert(output.contains("password=***")) {
            "Sensitive field should be redacted\n$output"
        }
        assert(output.contains("\$name")) {
            "Non-sensitive field should use interpolation\n$output"
        }
        assert(!output.contains("\$password")) {
            "Sensitive field should not be interpolated\n$output"
        }
    }

    @Test
    fun `no toString override when no sensitive fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(!output.contains("override fun toString()")) {
            "Should not generate toString when no fields are sensitive\n$output"
        }
    }

    @Test
    fun `field comment emits KDoc on entity property`() {
        val schema = object : EntSchema() {
            override fun fields() = fields {
                string("name").comment("The user's display name")
            }
        }
        val output = generator.generate("Commented", schema).toString()

        assert(output.contains("The user's display name")) {
            "Should emit field comment as KDoc\n$output"
        }
    }

    @Test
    fun `edge comment emits KDoc on Edges property`() {
        val target = object : EntSchema() {
            override fun fields() = fields { string("title") }
        }
        val schema = object : EntSchema() {
            override fun fields() = fields { string("name") }
            override fun edges() = edges {
                to("posts", target).comment("All posts authored by this user")
            }
        }
        val schemaNames = mapOf(schema to "Author", target to "Post")
        val output = generator.generate("Author", schema, schemaNames).toString()

        assert(output.contains("All posts authored by this user")) {
            "Should emit edge comment as KDoc\n$output"
        }
    }

    @Test
    fun `no KDoc when field has no comment`() {
        val output = generator.generate("Car", Car).toString()

        // Car.model has no comment — should not have any KDoc markers
        val modelLine = output.lines().indexOfFirst { "val model:" in it }
        val prevLine = output.lines().getOrNull(modelLine - 1) ?: ""
        assert(!prevLine.contains("/**") && !prevLine.contains("*/")) {
            "Should not emit KDoc for uncommented fields\n$output"
        }
    }

    @Test
    fun `field comment appears in generated SCHEMA ColumnMetadata`() {
        val schema = object : EntSchema() {
            override fun fields() = fields {
                string("name").comment("The user's display name")
            }
        }
        val output = generator.generate("Commented", schema).toString()

        assert(output.contains("""comment = "The user's display name"""")) {
            "ColumnMetadata should carry field comment\n$output"
        }
    }

    @Test
    fun `edge comment appears in generated SCHEMA EdgeMetadata`() {
        val target = object : EntSchema() {
            override fun id() = EntId.long()
            override fun fields() = fields { string("title") }
        }
        val schema = object : EntSchema() {
            override fun fields() = fields { string("name") }
            override fun edges() = edges {
                from("author", target).unique().comment("The author of this post")
            }
        }
        val schemaNames = mapOf(schema to "Post", target to "Author")
        val output = generator.generate("Post", schema, schemaNames).toString()

        assert(output.contains("""comment = "The author of this post"""")) {
            "EdgeMetadata should carry edge comment\n$output"
        }
    }

    @Test
    fun `no comment field in ColumnMetadata when field has no comment`() {
        val output = generator.generate("Car", Car).toString()

        // ColumnMetadata for "model" should not have a comment parameter
        val schemaBlock = output.substringAfter("SCHEMA")
        val modelMeta = schemaBlock.substringAfter("""name = "model"""").substringBefore("),")
        assert(!modelMeta.contains("comment =")) {
            "ColumnMetadata should omit comment when not set\n$output"
        }
    }
}