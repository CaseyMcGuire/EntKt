package entkt.codegen

import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntId
import entkt.schema.EntSchema
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

enum class Priority { LOW, MEDIUM, HIGH }
enum class Category { BUG, FEATURE }

class Car : EntSchema("cars") {
    override fun id() = EntId.int()
    val model = string("model")
    val year = int("year")
    val price = float("price").optional()

    val user = belongsTo<User>("user").inverse(User::cars)
}

class Ticket : EntSchema("tickets") {
    override fun id() = EntId.int()
    val title = string("title")
    val priority = enum<Priority>("priority")
    val category = enum<Category>("category")
}

class User : EntSchema("users") {
    override fun id() = EntId.uuid()

    val createdAt = time("created_at").immutable()
    val updatedAt = time("updated_at")
    val name = string("name")
    val age = int("age").optional()
    val email = string("email").unique()
    val active = bool("active").default(true)

    val cars = hasMany<Car>("cars")

    val idxCreatedAt = index("idx_created_at", createdAt)
    val idxNameEmail = index("idx_name_email", name, email).unique()
    val idxEmailActive = index("idx_email_active", email).where("active = true")
}

// Test helper schemas for edge tests that need named file-level classes
// (reified type params can't reference anonymous/local types from other anonymous objects)

private class IdxParentSchema : EntSchema("parents") {
    override fun id() = EntId.int()
    val name = string("name")
}

private class IdxChildSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val title = string("title")
    val author = belongsTo<IdxParentSchema>("author")
    val byAuthor = index("idx_author", author.fk)
}

private class CollisionParentSchema : EntSchema("parents") {
    override fun id() = EntId.int()
    val name = string("name")
}

private class CollisionChildSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val ownerId = int("owner_id")
    val owner = belongsTo<CollisionParentSchema>("owner")
}

private class EdgeCommentTargetSchema : EntSchema("authors") {
    override fun id() = EntId.long()
    val title = string("title")
}

private class EdgeCommentSourceSchema : EntSchema("posts") {
    override fun id() = EntId.int()
    val name = string("name")
    val author = belongsTo<EdgeCommentTargetSchema>("author").comment("The author of this post")
}

private class CommentPostSchema : EntSchema("posts") {
    override fun id() = EntId.int()
    val title = string("title")
    val author = belongsTo<CommentAuthorSchema>("author").inverse(CommentAuthorSchema::posts)
}

private class CommentAuthorSchema : EntSchema("authors") {
    override fun id() = EntId.int()
    val name = string("name")
    val posts = hasMany<CommentPostSchema>("posts").comment("All posts authored by this user")
}

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class EntityGeneratorTest {

    private val generator = EntityGenerator("com.example.ent")

    @Test
    fun `generates entity data class with fields`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("data class Car")) { "Should generate data class\n$output" }
        assert(output.contains("val id: Int")) { "Should have int id\n$output" }
        assert(output.contains("val model: String")) { "Should have model field\n$output" }
        assert(output.contains("val year: Int")) { "Should have year field\n$output" }
        assert(output.contains("val price: Float?")) { "Should have nullable price\n$output" }
    }

    @Test
    fun `generates entity with UUID id`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("val id: UUID")) { "Should have UUID id\n$output" }
    }

    @Test
    fun `generates entity with inherited fields`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("val createdAt: Instant")) { "Should have createdAt\n$output" }
        assert(output.contains("val updatedAt: Instant")) { "Should have updatedAt\n$output" }
    }

    @Test
    fun `converts snake_case fields to camelCase Kotlin properties`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

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
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("val age: Int? = null")) { "Should have nullable age with default null\n$output" }
    }

    @Test
    fun `generates correct package`() {
        val car = Car()
        finalize(car, User())
        val file = generator.generate("Car", car)
        assertEquals("com.example.ent", file.packageName)
    }

    @Test
    fun `entity companion has no I_O entry points (those live on the repo)`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

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
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(!output.contains("fun update(")) {
            "update() should live on CarRepo, not on the entity instance\n$output"
        }
    }

    @Test
    fun `emits typed column refs on the companion for each field`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

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
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("val active: Column<Boolean> = Column<Boolean>(\"active\")")) {
            "Should have Column<Boolean> for active\n$output"
        }
    }

    @Test
    fun `column refs use snake_case for the column name`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        // Property name is camelCase, but the column name carried into
        // the Predicate should be the raw field name.
        assert(output.contains("val createdAt: ComparableColumn<Instant> = ComparableColumn<Instant>(\"created_at\")")) {
            "Should use snake_case column name\n$output"
        }
    }

    @Test
    fun `emits an EdgeRef on the companion for each declared edge`() {
        // User has `hasMany<Car>("cars")` — needs the schemaNames map so the
        // generator can resolve the target's class names.
        val user = User()
        val car = Car()
        finalize(user, car)
        val schemaNames = mapOf<EntSchema, String>(user to "User", car to "Car")
        val output = generator.generate("User", user, schemaNames).toString()

        assert(output.contains("import entkt.query.EdgeRef")) {
            "Should import EdgeRef\n$output"
        }
        assert(output.contains("val cars: EdgeRef<Car, CarQuery> = EdgeRef(\"cars\") { CarQuery(NoopDriver) }")) {
            "Should emit a typed EdgeRef for the cars edge wired to NoopDriver\n$output"
        }
    }

    @Test
    fun `generated SCHEMA includes unique flag on unique columns`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        // email is declared .unique() so the generated ColumnMetadata should carry unique = true
        assert(output.contains("unique = true")) {
            "Should emit unique = true for the email column\n$output"
        }
    }

    @Test
    fun `generated SCHEMA includes indexes from the schema`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("IndexMetadata")) {
            "Should emit IndexMetadata entries\n$output"
        }
        assert(output.contains("\"name\", \"email\"")) {
            "Should include the composite unique index from User\n$output"
        }
        // created_at index comes from the schema directly
        assert(output.contains("\"created_at\"")) {
            "Should include the created_at index\n$output"
        }
    }

    @Test
    fun `generated SCHEMA includes where clause for partial indexes`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(Regex("""where\s*=\s*"active = true"""").containsMatchIn(output)) {
            "Should emit where clause for partial index\n$output"
        }
    }

    @Test
    fun `does not emit EdgeRef when target is missing from the schema map`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(!output.contains("EdgeRef")) {
            "Should not emit EdgeRef without schemaNames\n$output"
        }
    }

    @Test
    fun `entity with edges gets an Edges inner data class`() {
        val user = User()
        val car = Car()
        finalize(user, car)
        val schemaNames = mapOf<EntSchema, String>(user to "User", car to "Car")
        val output = generator.generate("User", user, schemaNames).toString()

        assert(output.contains("data class Edges")) {
            "Should generate inner Edges data class\n$output"
        }
        assert(output.contains("val edges: Edges = Edges()")) {
            "Should have edges property with default\n$output"
        }
    }

    @Test
    fun `Edges class has list property for to-many edges`() {
        val user = User()
        val car = Car()
        finalize(user, car)
        val schemaNames = mapOf<EntSchema, String>(user to "User", car to "Car")
        val output = generator.generate("User", user, schemaNames).toString()

        assert(output.contains("val cars: List<Car>? = null")) {
            "To-many edge should produce nullable list property\n$output"
        }
    }

    @Test
    fun `entity without edges does not get Edges class`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(!output.contains("data class Edges")) {
            "Entity with no edges should not have Edges class\n$output"
        }
        assert(!output.contains("val edges:")) {
            "Entity with no edges should not have edges property\n$output"
        }
    }

    @Test
    fun `typed enum field emits the Kotlin enum type on the entity`() {
        val ticket = Ticket()
        finalize(ticket)
        val output = generator.generate("Ticket", ticket).toString()

        assert(output.contains("val priority: Priority")) {
            "Should use the Kotlin enum type\n$output"
        }
    }

    @Test
    fun `typed enum column ref uses EnumColumn`() {
        val ticket = Ticket()
        finalize(ticket)
        val output = generator.generate("Ticket", ticket).toString()

        assert(output.contains("val priority: EnumColumn<Priority> = EnumColumn<Priority>(\"priority\")")) {
            "Should emit EnumColumn parameterized with the enum class\n$output"
        }
    }

    @Test
    fun `typed enum fromRow uses valueOf`() {
        val ticket = Ticket()
        finalize(ticket)
        val output = generator.generate("Ticket", ticket).toString()

        assert(output.contains("priority = Priority.valueOf(row[\"priority\"] as String)")) {
            "Should convert via valueOf in fromRow\n$output"
        }
    }

    @Test
    fun `second typed enum also uses enum type and EnumColumn`() {
        val ticket = Ticket()
        finalize(ticket)
        val output = generator.generate("Ticket", ticket).toString()

        assert(output.contains("val category: Category")) {
            "Second typed enum should use the Kotlin enum type\n$output"
        }
        assert(output.contains("val category: EnumColumn<Category> = EnumColumn<Category>(\"category\")")) {
            "Second typed enum should use EnumColumn\n$output"
        }
    }

    @Test
    fun `fromRow does not reference edges`() {
        val user = User()
        val car = Car()
        finalize(user, car)
        val schemaNames = mapOf<EntSchema, String>(user to "User", car to "Car")
        val output = generator.generate("User", user, schemaNames).toString()

        val fromRowBlock = output.substringAfter("fun fromRow").substringBefore("}")
        assert(!fromRowBlock.contains("edges")) {
            "fromRow should not reference edges — it uses the default\n$fromRowBlock"
        }
    }

    @Test
    fun `index can target synthesized edge FK column`() {
        val parent = IdxParentSchema()
        val child = IdxChildSchema()
        finalize(parent, child)
        val schemaNames = mapOf<EntSchema, String>(parent to "Parent", child to "Child")
        // Should not throw — author_id is a synthesized edge FK column
        val output = generator.generate("Child", child, schemaNames).toString()
        assert(output.contains("IndexMetadata(columns = listOf(\"author_id\")")) {
            "Should emit IndexMetadata for the .fk index on synthesized FK column\n$output"
        }
    }

    // NOTE: The old test `index referencing nonexistent field fails at codegen time`
    // has been removed because the typed-handles API uses FieldHandle references
    // for indexes, which catches invalid field references at compile time rather
    // than at codegen time.

    @Test
    fun `field colliding with synthesized edge FK column is rejected`() {
        val parent = CollisionParentSchema()
        val child = CollisionChildSchema()
        finalize(parent, child)
        val schemaNames = mapOf<EntSchema, String>(parent to "Parent", child to "Child")
        val error = kotlin.test.assertFailsWith<IllegalStateException> {
            generator.generate("Child", child, schemaNames)
        }
        assert(error.message!!.contains("owner_id")) {
            "Error should mention the colliding column\n${error.message}"
        }
    }

    @Test
    fun `sensitive field is redacted in generated toString`() {
        val schema = object : EntSchema("accounts") {
            override fun id() = EntId.int()
            val name = string("name")
            val password = string("password").sensitive()
        }
        finalize(schema)
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
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(!output.contains("override fun toString()")) {
            "Should not generate toString when no fields are sensitive\n$output"
        }
    }

    @Test
    fun `field comment emits KDoc on entity property`() {
        val schema = object : EntSchema("commenteds") {
            override fun id() = EntId.int()
            val name = string("name").comment("The user's display name")
        }
        finalize(schema)
        val output = generator.generate("Commented", schema).toString()

        assert(output.contains("The user's display name")) {
            "Should emit field comment as KDoc\n$output"
        }
    }

    @Test
    fun `edge comment emits KDoc on Edges property`() {
        val author = CommentAuthorSchema()
        val post = CommentPostSchema()
        finalize(author, post)
        val schemaNames = mapOf<EntSchema, String>(author to "Author", post to "Post")
        val output = generator.generate("Author", author, schemaNames).toString()

        assert(output.contains("All posts authored by this user")) {
            "Should emit edge comment as KDoc\n$output"
        }
    }

    @Test
    fun `no KDoc when field has no comment`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // Car.model has no comment — should not have any KDoc markers
        val modelLine = output.lines().indexOfFirst { "val model:" in it }
        val prevLine = output.lines().getOrNull(modelLine - 1) ?: ""
        assert(!prevLine.contains("/**") && !prevLine.contains("*/")) {
            "Should not emit KDoc for uncommented fields\n$output"
        }
    }

    @Test
    fun `field comment appears in generated SCHEMA ColumnMetadata`() {
        val schema = object : EntSchema("commenteds") {
            override fun id() = EntId.int()
            val name = string("name").comment("The user's display name")
        }
        finalize(schema)
        val output = generator.generate("Commented", schema).toString()

        assert(output.contains("""comment = "The user's display name"""")) {
            "ColumnMetadata should carry field comment\n$output"
        }
    }

    @Test
    fun `edge comment appears in generated SCHEMA EdgeMetadata`() {
        val target = EdgeCommentTargetSchema()
        val source = EdgeCommentSourceSchema()
        finalize(source, target)
        val schemaNames = mapOf<EntSchema, String>(source to "Post", target to "Author")
        val output = generator.generate("Post", source, schemaNames).toString()

        assert(output.contains("""comment = "The author of this post"""")) {
            "EdgeMetadata should carry edge comment\n$output"
        }
    }

    @Test
    fun `no comment field in ColumnMetadata when field has no comment`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // ColumnMetadata for "model" should not have a comment parameter
        val schemaBlock = output.substringAfter("SCHEMA")
        val modelMeta = schemaBlock.substringAfter("""name = "model"""").substringBefore("),")
        assert(!modelMeta.contains("comment =")) {
            "ColumnMetadata should omit comment when not set\n$output"
        }
    }
}
