package entkt.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Example mixins

object TimeMixin : EntMixin {
    override fun fields() = fields {
        time("created_at").default("now").immutable()
        time("updated_at").default("now").updateDefaultNow()
    }
}

object SoftDeleteMixin : EntMixin {
    override fun fields() = fields {
        time("deleted_at").optional().nillable()
    }

    override fun indexes() = indexes {
        index("deleted_at")
    }
}

// Example schemas that demonstrate the API

object Car : EntSchema() {
    override fun fields() = fields {
        string("model")
        int("year")
        float("price").optional()
    }
}

object User : EntSchema() {
    override fun fields() = fields {
        string("name").minLen(1).maxLen(100)
        int("age").optional().positive()
        string("email").unique().notEmpty().match(Regex(".+@.+\\..+"))
        enum("role").values("admin", "user", "moderator").default("user")
        bool("active").default(true)
        time("created_at").immutable()
    }

    override fun edges() = edges {
        to("cars", Car)
    }

    override fun indexes() = indexes {
        index("name", "email").unique()
        index("created_at")
        index("email").unique().where("active = true")
    }
}

object Group : EntSchema() {
    override fun fields() = fields {
        string("name")
    }

    override fun edges() = edges {
        to("users", User).through("user_groups", UserGroup)
    }
}

object UserGroup : EntSchema() {
    override fun fields() = fields {
        time("joined_at")
    }

    override fun edges() = edges {
        to("user", User).unique().required().field("user_id")
        to("group", Group).unique().required().field("group_id")
    }
}

enum class TaskStatus { TODO, IN_PROGRESS, DONE }

object Task : EntSchema() {
    override fun fields() = fields {
        string("title")
        enum<TaskStatus>("status").default(TaskStatus.TODO)
    }
}

object Company : EntSchema() {
    override fun mixins() = listOf(TimeMixin, SoftDeleteMixin)

    override fun fields() = fields {
        string("name").unique()
    }

    override fun edges() = edges {
        to("employees", User)
    }
}

class SchemaTest {

    @Test
    fun `fields are defined with correct types`() {
        val fields = User.fields()
        assertEquals(6, fields.size)

        val name = fields[0]
        assertEquals("name", name.name)
        assertEquals(FieldType.STRING, name.type)
        assertFalse(name.optional)

        val age = fields[1]
        assertEquals("age", age.name)
        assertEquals(FieldType.INT, age.type)
        assertTrue(age.optional)
    }

    @Test
    fun `field modifiers are applied`() {
        val fields = User.fields()

        val email = fields[2]
        assertTrue(email.unique)

        val role = fields[3]
        assertEquals(listOf("admin", "user", "moderator"), role.enumValues)
        assertEquals("user", role.default)

        val active = fields[4]
        assertEquals(true, active.default)

        val createdAt = fields[5]
        assertTrue(createdAt.immutable)
    }

    @Test
    fun `edges are defined with correct targets`() {
        val edges = User.edges()
        assertEquals(1, edges.size)

        val carsEdge = edges[0]
        assertEquals("cars", carsEdge.name)
        assertEquals(EdgeType.TO, carsEdge.type)
        assertEquals(Car, carsEdge.target)
    }

    @Test
    fun `schema with no edges returns empty list`() {
        assertEquals(emptyList(), Car.edges())
    }

    @Test
    fun `validators are attached to fields`() {
        val fields = User.fields()

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
        val indexes = User.indexes()
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
        val indexes = User.indexes()
        val partial = indexes[2]
        assertEquals(listOf("email"), partial.fields)
        assertTrue(partial.unique)
        assertEquals("active = true", partial.where)
    }

    @Test
    fun `non-partial indexes have null where`() {
        val indexes = User.indexes()
        assertNull(indexes[0].where)
        assertNull(indexes[1].where)
    }

    @Test
    fun `schema with no indexes returns empty list`() {
        assertEquals(emptyList(), Car.indexes())
    }

    @Test
    fun `mixins contribute fields`() {
        val mixins = Company.mixins()
        assertEquals(2, mixins.size)

        val timeFields = mixins[0].fields()
        assertEquals(2, timeFields.size)
        assertEquals("created_at", timeFields[0].name)
        assertEquals("updated_at", timeFields[1].name)
        assertEquals(UpdateDefault.Now, timeFields[1].updateDefault)

        val softDeleteFields = mixins[1].fields()
        assertEquals(1, softDeleteFields.size)
        assertEquals("deleted_at", softDeleteFields[0].name)
        assertTrue(softDeleteFields[0].optional)
        assertTrue(softDeleteFields[0].nillable)
    }

    @Test
    fun `mixins contribute indexes`() {
        val mixinIndexes = Company.mixins().flatMap { it.indexes() }
        assertEquals(1, mixinIndexes.size)
        assertEquals(listOf("deleted_at"), mixinIndexes[0].fields)
    }

    @Test
    fun `schema with no mixins returns empty list`() {
        assertEquals(emptyList(), Car.mixins())
    }

    @Test
    fun `edge field exposes foreign key`() {
        val edges = UserGroup.edges()
        assertEquals(2, edges.size)

        val userEdge = edges[0]
        assertEquals("user", userEdge.name)
        assertEquals("user_id", userEdge.field)
        assertTrue(userEdge.unique)
        assertTrue(userEdge.required)

        val groupEdge = edges[1]
        assertEquals("group", groupEdge.name)
        assertEquals("group_id", groupEdge.field)
    }

    @Test
    fun `typed enum populates enumClass and enumValues`() {
        val fields = Task.fields()
        val status = fields[1]
        assertEquals(FieldType.ENUM, status.type)
        assertEquals(TaskStatus::class, status.enumClass)
        assertEquals(listOf("TODO", "IN_PROGRESS", "DONE"), status.enumValues)
        assertEquals(TaskStatus.TODO, status.default)
    }

    @Test
    fun `untyped enum has null enumClass`() {
        val fields = User.fields()
        val role = fields[3]
        assertEquals(FieldType.ENUM, role.type)
        assertNull(role.enumClass)
        assertEquals(listOf("admin", "user", "moderator"), role.enumValues)
    }

    @Test
    fun `edge through defines join table`() {
        val edges = Group.edges()
        assertEquals(1, edges.size)

        val usersEdge = edges[0]
        assertEquals("users", usersEdge.name)
        assertEquals(User, usersEdge.target)
        assertNotNull(usersEdge.through)
        assertEquals("user_groups", usersEdge.through!!.name)
        assertEquals(UserGroup, usersEdge.through!!.target)
    }

    @Test
    fun `onDelete rejected on non-unique edge`() {
        assertFailsWith<IllegalArgumentException> {
            EdgeBuilder("cars", EdgeType.TO, Car)
                .onDelete(OnDelete.CASCADE)
                .build()
        }
    }

    @Test
    fun `onDelete rejected on through edge`() {
        assertFailsWith<IllegalArgumentException> {
            EdgeBuilder("users", EdgeType.TO, User)
                .unique()
                .through("user_groups", UserGroup)
                .onDelete(OnDelete.CASCADE)
                .build()
        }
    }

    @Test
    fun `onDelete accepted on unique edge without through`() {
        val edge = EdgeBuilder("owner", EdgeType.TO, User)
            .unique()
            .onDelete(OnDelete.CASCADE)
            .build()
        assertEquals(OnDelete.CASCADE, edge.onDelete)
    }

    @Test
    fun `onDelete SET_NULL rejected on required edge`() {
        assertFailsWith<IllegalArgumentException> {
            EdgeBuilder("owner", EdgeType.TO, User)
                .unique()
                .required()
                .onDelete(OnDelete.SET_NULL)
                .build()
        }
    }

    @Test
    fun `onDelete SET_NULL accepted on non-required unique edge`() {
        val edge = EdgeBuilder("owner", EdgeType.TO, User)
            .unique()
            .onDelete(OnDelete.SET_NULL)
            .build()
        assertEquals(OnDelete.SET_NULL, edge.onDelete)
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
}