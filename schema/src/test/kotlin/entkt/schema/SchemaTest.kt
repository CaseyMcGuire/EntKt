package entkt.schema

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Example enums

enum class Role { ADMIN, USER, MODERATOR }

enum class TaskStatus { TODO, IN_PROGRESS, DONE }

// Example schemas using the typed-handles API

class Car : EntSchema("cars") {
    override fun id() = EntId.int()
    val model = string("model")
    val year = int("year")
    val price = float("price").optional()
}

class User : EntSchema("users") {
    override fun id() = EntId.int()
    val name = string("name").minLen(1).maxLen(100)
    val age = int("age").optional().positive()
    val email = string("email").unique().notEmpty().match(Regex(".+@.+\\..+"))
    val role = enum<Role>("role").default(Role.USER)
    val active = bool("active").default(true)
    val createdAt = time("created_at").immutable()

    val cars = hasMany<Car>("cars")

    val byNameEmail = index("idx_name_email", name, email).unique()
    val byCreatedAt = index("idx_created_at", createdAt)
    val byEmailPartial = index("idx_email_partial", email).unique().where("active = true")
}

class Group : EntSchema("groups") {
    override fun id() = EntId.int()
    val name = string("name")

    val users = manyToMany<User>("users")
        .through<UserGroup>(UserGroup::user, UserGroup::group)
}

class UserGroup : EntSchema("user_groups") {
    override fun id() = EntId.int()
    val joinedAt = time("joined_at")

    val userId = int("user_id")
    val groupId = int("group_id")

    val user = belongsTo<User>("user").required().field(userId)
    val group = belongsTo<Group>("group").required().field(groupId)
}

class Task : EntSchema("tasks") {
    override fun id() = EntId.int()
    val title = string("title")
    val status = enum<TaskStatus>("status").default(TaskStatus.TODO)
}

// Mixin replacement: abstract schema with timestamp fields
abstract class TimestampedSchema(tableName: String) : EntSchema(tableName) {
    val createdAt = time("created_at").defaultNow().immutable()
    val updatedAt = time("updated_at").defaultNow().updateDefaultNow()
    val deletedAt = time("deleted_at").nullable()

    val byDeletedAt = index("idx_deleted_at", deletedAt)
}

class Company : TimestampedSchema("companies") {
    override fun id() = EntId.int()
    val name = string("name").unique()

    val employees = hasMany<User>("employees")
}

// Schemas for computed-getter detection tests (must be file-level for forward references)

private class ComputedGetterTarget : EntSchema("targets") {
    override fun id() = EntId.int()
    val items get() = hasMany<ComputedGetterSource>("items")
}

private class ComputedGetterSource : EntSchema("sources") {
    override fun id() = EntId.int()
    val target = belongsTo<ComputedGetterTarget>("target").inverse(ComputedGetterTarget::items)
}

private class M2mSide : EntSchema("sides") {
    override fun id() = EntId.int()
}

private class ComputedGetterJunction : EntSchema("junctions") {
    override fun id() = EntId.int()
    val left get() = belongsTo<M2mSide>("left")
    val right = belongsTo<M2mSide>("right")
}

private class ComputedGetterOwner : EntSchema("owners") {
    override fun id() = EntId.int()
    val sides = manyToMany<M2mSide>("sides")
        .through<ComputedGetterJunction>(ComputedGetterJunction::left, ComputedGetterJunction::right)
}

// Helper to build a finalized schema graph
private fun buildRegistry(vararg schemas: EntSchema): Map<KClass<out EntSchema>, EntSchema> {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
    return registry
}

class SchemaTest {

    // Shared schema instances, finalized once
    private val car = Car()
    private val user = User()
    private val group = Group()
    private val userGroup = UserGroup()
    private val task = Task()
    private val company = Company()

    init {
        buildRegistry(car, user, group, userGroup, task, company)
    }

    @Test
    fun `fields are defined with correct types`() {
        val fields = user.fields()
        assertEquals(6, fields.size)

        val name = fields[0]
        assertEquals("name", name.name)
        assertEquals(FieldType.STRING, name.type)
        assertFalse(name.nullable)

        val age = fields[1]
        assertEquals("age", age.name)
        assertEquals(FieldType.INT, age.type)
        assertTrue(age.nullable)
    }

    @Test
    fun `field modifiers are applied`() {
        val fields = user.fields()

        val email = fields[2]
        assertTrue(email.unique)

        val role = fields[3]
        assertEquals(Role::class, role.enumClass)
        assertEquals(Role.USER, role.default)

        val active = fields[4]
        assertEquals(true, active.default)

        val createdAt = fields[5]
        assertTrue(createdAt.immutable)
    }

    @Test
    fun `edges are defined with correct targets`() {
        val edges = user.edges()
        assertEquals(1, edges.size)

        val carsEdge = edges[0]
        assertEquals("cars", carsEdge.name)
        assertTrue(carsEdge.kind is EdgeKind.HasMany)
        assertTrue(carsEdge.target is Car)
    }

    @Test
    fun `schema with no edges returns empty list`() {
        assertEquals(emptyList(), car.edges())
    }

    @Test
    fun `validators are attached to fields`() {
        val fields = user.fields()

        val name = fields[0]
        assertEquals(2, name.validators.size)
        assertEquals("minLen(1)", name.validators[0].name)
        assertEquals("maxLen(100)", name.validators[1].name)

        val age = fields[1]
        assertEquals(1, age.validators.size)
        assertEquals("positive", age.validators[0].name)

        val email = fields[2]
        assertEquals(2, email.validators.size)
        assertEquals("notEmpty", email.validators[0].name)
        assertEquals("match(.+@.+\\..+)", email.validators[1].name)
    }

    @Test
    fun `validators check values correctly`() {
        val minLen = Validators.minLen(3)
        assertTrue(minLen.check("abc"))
        assertFalse(minLen.check("ab"))

        val positive = Validators.positive()
        assertTrue(positive.check(5))
        assertFalse(positive.check(-1))
        assertFalse(positive.check(0))

        val match = Validators.match(Regex(".+@.+\\..+"))
        assertTrue(match.check("user@example.com"))
        assertFalse(match.check("not-an-email"))
    }

    @Test
    fun `indexes are defined with correct fields`() {
        val indexes = user.indexes()
        assertEquals(3, indexes.size)

        val composite = indexes[0]
        assertEquals(listOf("name", "email"), composite.fields)
        assertTrue(composite.unique)

        val single = indexes[1]
        assertEquals(listOf("created_at"), single.fields)
        assertFalse(single.unique)
    }

    @Test
    fun `partial index has where clause`() {
        val indexes = user.indexes()
        val partial = indexes[2]
        assertEquals(listOf("email"), partial.fields)
        assertTrue(partial.unique)
        assertEquals("active = true", partial.where)
    }

    @Test
    fun `non-partial indexes have null where`() {
        val indexes = user.indexes()
        assertNull(indexes[0].where)
        assertNull(indexes[1].where)
    }

    @Test
    fun `schema with no indexes returns empty list`() {
        assertEquals(emptyList(), car.indexes())
    }

    @Test
    fun `inherited timestamp fields are present`() {
        val fields = company.fields()
        // TimestampedSchema contributes 3 fields (created_at, updated_at, deleted_at),
        // plus Company's own 1 field (name) = 4 total
        assertEquals(4, fields.size)

        val createdAt = fields[0]
        assertEquals("created_at", createdAt.name)
        assertTrue(createdAt.immutable)

        val updatedAt = fields[1]
        assertEquals("updated_at", updatedAt.name)
        assertEquals(UpdateDefault.Now, updatedAt.updateDefault)

        val deletedAt = fields[2]
        assertEquals("deleted_at", deletedAt.name)
        assertTrue(deletedAt.nullable)

        val name = fields[3]
        assertEquals("name", name.name)
        assertTrue(name.unique)
    }

    @Test
    fun `inherited indexes are present`() {
        val indexes = company.indexes()
        assertEquals(1, indexes.size)
        assertEquals(listOf("deleted_at"), indexes[0].fields)
    }

    @Test
    fun `edge field exposes foreign key`() {
        val edges = userGroup.edges()
        assertEquals(2, edges.size)

        val userEdge = edges[0]
        assertEquals("user", userEdge.name)
        val userKind = userEdge.kind as EdgeKind.BelongsTo
        assertEquals("user_id", userKind.field)
        assertTrue(userKind.required)

        val groupEdge = edges[1]
        assertEquals("group", groupEdge.name)
        val groupKind = groupEdge.kind as EdgeKind.BelongsTo
        assertEquals("group_id", groupKind.field)
    }

    @Test
    fun `enum field populates enumClass`() {
        val fields = task.fields()
        val status = fields[1]
        assertEquals(FieldType.ENUM, status.type)
        assertEquals(TaskStatus::class, status.enumClass)
        assertEquals(TaskStatus.TODO, status.default)
    }

    @Test
    fun `enum field on User has enumClass set`() {
        val fields = user.fields()
        val role = fields[3]
        assertEquals(FieldType.ENUM, role.type)
        assertEquals(Role::class, role.enumClass)
    }

    @Test
    fun `edge through defines join table`() {
        val edges = group.edges()
        assertEquals(1, edges.size)

        val usersEdge = edges[0]
        assertEquals("users", usersEdge.name)
        assertTrue(usersEdge.target is User)
        val m2m = usersEdge.kind as EdgeKind.ManyToMany
        assertTrue(m2m.through.target is UserGroup)
    }

    @Test
    fun `onDelete accepted on belongsTo edge`() {
        class Owner : EntSchema("owners") { override fun id() = EntId.int() }
        class Pet : EntSchema("pets") {
            override fun id() = EntId.int()
            val owner = belongsTo<Owner>("owner").onDelete(OnDelete.CASCADE)
        }
        val ownerSchema = Owner()
        val petSchema = Pet()
        buildRegistry(ownerSchema, petSchema)

        val edge = petSchema.edges()[0]
        val kind = edge.kind as EdgeKind.BelongsTo
        assertEquals(OnDelete.CASCADE, kind.onDelete)
    }

    @Test
    fun `onDelete SET_NULL rejected on required edge`() {
        class Owner : EntSchema("owners") { override fun id() = EntId.int() }
        class Pet : EntSchema("pets") {
            override fun id() = EntId.int()
            val owner = belongsTo<Owner>("owner")
                .required()
                .onDelete(OnDelete.SET_NULL)
        }
        val ownerSchema = Owner()
        val petSchema = Pet()
        buildRegistry(ownerSchema, petSchema)

        assertFailsWith<IllegalStateException> {
            petSchema.edges()
        }
    }

    @Test
    fun `onDelete SET_NULL accepted on non-required edge`() {
        class Owner : EntSchema("owners") { override fun id() = EntId.int() }
        class Pet : EntSchema("pets") {
            override fun id() = EntId.int()
            val owner = belongsTo<Owner>("owner").onDelete(OnDelete.SET_NULL)
        }
        val ownerSchema = Owner()
        val petSchema = Pet()
        buildRegistry(ownerSchema, petSchema)

        val edge = petSchema.edges()[0]
        val kind = edge.kind as EdgeKind.BelongsTo
        assertEquals(OnDelete.SET_NULL, kind.onDelete)
    }

    @Test
    fun `manyToMany requires through`() {
        assertFailsWith<IllegalStateException> {
            ManyToManyBuilder<Group>("groups", Group::class).build()
        }
    }

    @Test
    fun `immutable field with updateDefaultNow is rejected`() {
        assertFailsWith<IllegalStateException> {
            TimeFieldBuilder("updated_at")
                .immutable()
                .updateDefaultNow()
                .build()
        }
    }

    @Test
    fun `empty index is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            IndexBuilder("idx_empty", emptyList()).build()
        }
    }

    @Test
    fun `duplicate semantic indexes are rejected`() {
        class Duped : EntSchema("duped") {
            override fun id() = EntId.int()
            val email = string("email")
            val a = index("idx_a", email)
            val b = index("idx_b", email)
        }
        val schema = Duped()
        buildRegistry(schema)
        assertFailsWith<IllegalArgumentException> {
            schema.indexes()
        }
    }

    @Test
    fun `same columns with different uniqueness are allowed`() {
        class Allowed : EntSchema("allowed") {
            override fun id() = EntId.int()
            val email = string("email")
            val a = index("idx_a", email)
            val b = index("idx_b", email).unique()
        }
        val schema = Allowed()
        buildRegistry(schema)
        assertEquals(2, schema.indexes().size)
    }

    @Test
    fun `same columns with different where clauses are allowed`() {
        class Allowed : EntSchema("allowed") {
            override fun id() = EntId.int()
            val email = string("email")
            val a = index("idx_a", email).where("active = true")
            val b = index("idx_b", email).where("deleted_at IS NULL")
        }
        val schema = Allowed()
        buildRegistry(schema)
        assertEquals(2, schema.indexes().size)
    }

    @Test
    fun `typed enum default rejects constant from wrong enum class`() {
        assertFailsWith<IllegalArgumentException> {
            EnumFieldBuilder("priority").apply {
                setEnumClass(TaskStatus::class)
                default(OnDelete.CASCADE) // wrong enum class
            }.build()
        }
    }

    @Test
    fun `typed enum default accepts constant from correct enum class`() {
        val field = EnumFieldBuilder("priority").apply {
            setEnumClass(TaskStatus::class)
            default(TaskStatus.TODO)
        }.build()
        assertEquals(TaskStatus.TODO, field.default)
    }

    @Test
    fun `schema finalize can only be called once`() {
        class Solo : EntSchema("solos") { override fun id() = EntId.int() }
        val solo = Solo()
        buildRegistry(solo)

        assertFailsWith<IllegalStateException> {
            buildRegistry(solo)
        }
    }

    @Test
    fun `edges cannot be accessed before finalization`() {
        class Unfinalized : EntSchema("unfinalized") {
            override fun id() = EntId.int()
            val something = hasMany<Car>("something")
        }
        val schema = Unfinalized()

        assertFailsWith<IllegalStateException> {
            schema.edges()
        }
    }

    @Test
    fun `computed getter inverse is rejected during finalization`() {
        val err = assertFailsWith<IllegalStateException> {
            buildRegistry(ComputedGetterTarget(), ComputedGetterSource())
        }
        assertContains(err.message!!, "computed getter")
    }

    @Test
    fun `computed getter through is rejected during finalization`() {
        val err = assertFailsWith<IllegalStateException> {
            buildRegistry(M2mSide(), ComputedGetterJunction(), ComputedGetterOwner())
        }
        assertContains(err.message!!, "computed getter")
    }
}
