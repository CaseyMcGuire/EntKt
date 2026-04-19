package entkt.runtime

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.schema.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val USER_SCHEMA = EntitySchema(
    table = "users",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata("name", FieldType.STRING, nullable = false),
        ColumnMetadata("age", FieldType.INT, nullable = true),
        ColumnMetadata("active", FieldType.BOOL, nullable = true),
    ),
    edges = mapOf(
        "posts" to EdgeMetadata(
            targetTable = "posts",
            sourceColumn = "id",
            targetColumn = "author_id",
        ),
    ),
)

private val POST_SCHEMA = EntitySchema(
    table = "posts",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata("title", FieldType.STRING, nullable = false),
        ColumnMetadata("published", FieldType.BOOL, nullable = false),
        ColumnMetadata("author_id", FieldType.LONG, nullable = true),
    ),
    edges = mapOf(
        "author" to EdgeMetadata(
            targetTable = "users",
            sourceColumn = "author_id",
            targetColumn = "id",
        ),
    ),
)

// ---------- M2M test schemas ----------

private val GROUP_SCHEMA = EntitySchema(
    table = "groups",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata("name", FieldType.STRING, nullable = false),
    ),
    edges = mapOf(
        "users" to EdgeMetadata(
            targetTable = "users",
            sourceColumn = "id",
            targetColumn = "id",
            junctionTable = "user_groups",
            junctionSourceColumn = "group_id",
            junctionTargetColumn = "user_id",
        ),
    ),
)

private val USER_GROUP_SCHEMA = EntitySchema(
    table = "user_groups",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = listOf(
        ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
        ColumnMetadata("user_id", FieldType.LONG, nullable = false),
        ColumnMetadata("group_id", FieldType.LONG, nullable = false),
    ),
    edges = emptyMap(),
)

// USER_SCHEMA with reverse M2M edge (as codegen would inject)
private val USER_SCHEMA_WITH_M2M = EntitySchema(
    table = "users",
    idColumn = "id",
    idStrategy = IdStrategy.AUTO_LONG,
    columns = USER_SCHEMA.columns,
    edges = USER_SCHEMA.edges + mapOf(
        "groups" to EdgeMetadata(
            targetTable = "groups",
            sourceColumn = "id",
            targetColumn = "id",
            junctionTable = "user_groups",
            junctionSourceColumn = "user_id",
            junctionTargetColumn = "group_id",
        ),
    ),
)

class InMemoryDriverTest {

    private fun fresh(): InMemoryDriver = InMemoryDriver().apply {
        register(USER_SCHEMA)
        register(POST_SCHEMA)
    }

    @Test
    fun `insert assigns auto-long ids and returns the persisted row`() {
        val driver = fresh()
        val row = driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))

        assertEquals(1L, row["id"], "First insert should get id=1")
        assertEquals("Alice", row["name"])

        val second = driver.insert("users", mapOf("name" to "Bob", "age" to 25, "active" to true))
        assertEquals(2L, second["id"], "Second insert should get id=2")
    }

    @Test
    fun `byId returns null for missing rows and the persisted row otherwise`() {
        val driver = fresh()
        val inserted = driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))

        assertEquals(inserted, driver.byId("users", inserted["id"]!!))
        assertNull(driver.byId("users", 9999L))
    }

    @Test
    fun `update merges values and never rewrites the id`() {
        val driver = fresh()
        val inserted = driver.insert(
            "users",
            mapOf<String, Any?>("name" to "Alice", "age" to 30, "active" to true),
        )

        val updated = driver.update(
            "users",
            inserted["id"]!!,
            mapOf<String, Any?>("id" to 9999L, "age" to 31),
        )

        assertNotNull(updated)
        assertEquals(inserted["id"], updated["id"], "id must be preserved through update")
        assertEquals(31, updated["age"])
        assertEquals("Alice", updated["name"], "untouched columns survive")
    }

    @Test
    fun `update returns null when the row is gone`() {
        val driver = fresh()
        assertNull(driver.update("users", 42L, mapOf("name" to "Ghost")))
    }

    @Test
    fun `query filters with leaf predicates`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf("name" to "Bob", "age" to 17, "active" to true))
        driver.insert("users", mapOf("name" to "Carol", "age" to 65, "active" to false))

        val adults = driver.query(
            table = "users",
            predicates = listOf(Predicate.Leaf("age", Op.GTE, 18)),
            orderBy = emptyList(),
            limit = null,
            offset = null,
        )
        assertEquals(setOf("Alice", "Carol"), adults.map { it["name"] }.toSet())
    }

    @Test
    fun `query honors orderBy, limit, and offset`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Carol", "age" to 65, "active" to true))
        driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf("name" to "Bob", "age" to 25, "active" to true))

        val sortedByAgeDesc = driver.query(
            "users",
            predicates = emptyList(),
            orderBy = listOf(OrderField("age", OrderDirection.DESC)),
            limit = 2,
            offset = 1,
        )
        // age order desc: Carol(65), Alice(30), Bob(25). offset=1 → Alice, Bob.
        assertEquals(listOf("Alice", "Bob"), sortedByAgeDesc.map { it["name"] })
    }

    @Test
    fun `query handles compound and or predicates`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf("name" to "Bob", "age" to 70, "active" to false))
        driver.insert("users", mapOf("name" to "Carol", "age" to 17, "active" to true))

        // active AND (age >= 65 OR name == "Alice")
        val pred = Predicate.And(
            Predicate.Leaf("active", Op.EQ, true),
            Predicate.Or(
                Predicate.Leaf("age", Op.GTE, 65),
                Predicate.Leaf("name", Op.EQ, "Alice"),
            ),
        )
        val rows = driver.query("users", listOf(pred), emptyList(), null, null)
        assertEquals(setOf("Alice"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `query handles HasEdgeWith forward direction`() {
        // Users who have at least one published post.
        val driver = fresh()
        val alice = driver.insert("users", mapOf("name" to "Alice"))
        val bob = driver.insert("users", mapOf("name" to "Bob"))

        driver.insert("posts", mapOf("title" to "hi", "published" to true, "author_id" to alice["id"]))
        driver.insert("posts", mapOf("title" to "draft", "published" to false, "author_id" to bob["id"]))

        val pred = Predicate.HasEdgeWith(
            edge = "posts",
            inner = Predicate.Leaf("published", Op.EQ, true),
        )
        val rows = driver.query("users", listOf(pred), emptyList(), null, null)
        assertEquals(setOf("Alice"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `query handles HasEdge from the from-side`() {
        // Posts whose author exists at all (always true here, but the
        // join machinery still has to find the user).
        val driver = fresh()
        val alice = driver.insert("users", mapOf("name" to "Alice"))
        driver.insert("posts", mapOf("title" to "hi", "published" to true, "author_id" to alice["id"]))

        val rows = driver.query(
            "posts",
            listOf(Predicate.HasEdge("author")),
            emptyList(),
            null,
            null,
        )
        assertEquals(1, rows.size)
    }

    @Test
    fun `delete removes the row and returns true`() {
        val driver = fresh()
        val row = driver.insert("users", mapOf("name" to "Alice"))

        assertTrue(driver.delete("users", row["id"]!!))
        assertNull(driver.byId("users", row["id"]!!))
        assertEquals(false, driver.delete("users", row["id"]!!), "second delete is a no-op")
    }

    @Test
    fun `unregistered table is rejected loudly`() {
        val driver = InMemoryDriver()
        assertFailsWith<IllegalStateException> { driver.insert("nope", emptyMap()) }
    }

    // ---------- Transactions ----------

    @Test
    fun `withTransaction commits on success`() {
        val driver = fresh()
        driver.withTransaction { tx ->
            tx.insert("users", mapOf("name" to "Alice"))
            tx.insert("users", mapOf("name" to "Bob"))
        }
        val rows = driver.query("users", emptyList(), emptyList(), null, null)
        assertEquals(setOf("Alice", "Bob"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `withTransaction rolls back on exception`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Pre-existing"))

        assertFailsWith<IllegalStateException> {
            driver.withTransaction { tx ->
                tx.insert("users", mapOf("name" to "Alice"))
                tx.insert("users", mapOf("name" to "Bob"))
                error("boom")
            }
        }
        val rows = driver.query("users", emptyList(), emptyList(), null, null)
        assertEquals(listOf("Pre-existing"), rows.map { it["name"] })
    }

    @Test
    fun `withTransaction rollback restores id counters`() {
        val driver = fresh()
        assertFailsWith<IllegalStateException> {
            driver.withTransaction { tx ->
                tx.insert("users", mapOf("name" to "Ghost"))  // id=1
                error("boom")
            }
        }
        // After rollback, the next insert should get id=1 again.
        val row = driver.insert("users", mapOf("name" to "Real"))
        assertEquals(1L, row["id"])
    }

    @Test
    fun `nested withTransaction reuses the same transaction`() {
        val driver = fresh()
        driver.withTransaction { outer ->
            outer.insert("users", mapOf("name" to "Alice"))
            outer.withTransaction { inner ->
                inner.insert("users", mapOf("name" to "Bob"))
            }
        }
        val rows = driver.query("users", emptyList(), emptyList(), null, null)
        assertEquals(setOf("Alice", "Bob"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `transaction driver throws after block returns including register`() {
        val driver = fresh()
        var captured: Driver? = null
        driver.withTransaction { tx ->
            captured = tx
        }
        assertFailsWith<IllegalStateException> {
            captured!!.insert("users", mapOf("name" to "Late"))
        }
        assertFailsWith<IllegalStateException> {
            captured!!.register(USER_SCHEMA)
        }
    }

    @Test
    fun `rollback removes tables first registered inside the transaction`() {
        val driver = InMemoryDriver()
        driver.register(USER_SCHEMA)

        val newSchema = EntitySchema(
            table = "tags",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("label", FieldType.STRING, nullable = false),
            ),
            edges = emptyMap(),
        )

        assertFailsWith<IllegalStateException> {
            driver.withTransaction { tx ->
                tx.register(newSchema)
                tx.insert("tags", mapOf("label" to "kotlin"))
                error("boom")
            }
        }
        // The "tags" table should not exist after rollback.
        assertFailsWith<IllegalStateException> {
            driver.query("tags", emptyList(), emptyList(), null, null)
        }
        // Pre-existing "users" table should be unaffected.
        driver.insert("users", mapOf("name" to "Alice"))
        assertEquals(1L, driver.byId("users", 1L)!!["id"])
    }

    // ---------- M2M edge predicates ----------

    private fun freshM2M(): InMemoryDriver = InMemoryDriver().apply {
        register(USER_SCHEMA_WITH_M2M)
        register(POST_SCHEMA)
        register(GROUP_SCHEMA)
        register(USER_GROUP_SCHEMA)
    }

    @Test
    fun `HasEdge through junction table`() {
        val driver = freshM2M()
        val alice = driver.insert("users", mapOf("name" to "Alice"))
        val bob = driver.insert("users", mapOf("name" to "Bob"))
        val group = driver.insert("groups", mapOf("name" to "Admins"))

        // Only Alice is in the group.
        driver.insert("user_groups", mapOf("user_id" to alice["id"], "group_id" to group["id"]))

        // Query groups that have any users.
        val rows = driver.query(
            "groups",
            listOf(Predicate.HasEdge("users")),
            emptyList(), null, null,
        )
        assertEquals(1, rows.size)
        assertEquals("Admins", rows.single()["name"])
    }

    @Test
    fun `HasEdgeWith through junction table with inner predicate`() {
        val driver = freshM2M()
        val alice = driver.insert("users", mapOf("name" to "Alice", "age" to 30))
        val bob = driver.insert("users", mapOf("name" to "Bob", "age" to 17))
        val admins = driver.insert("groups", mapOf("name" to "Admins"))
        val interns = driver.insert("groups", mapOf("name" to "Interns"))

        driver.insert("user_groups", mapOf("user_id" to alice["id"], "group_id" to admins["id"]))
        driver.insert("user_groups", mapOf("user_id" to bob["id"], "group_id" to interns["id"]))

        // Groups that have a user with age >= 18.
        val rows = driver.query(
            "groups",
            listOf(Predicate.HasEdgeWith("users", Predicate.Leaf("age", Op.GTE, 18))),
            emptyList(), null, null,
        )
        assertEquals(setOf("Admins"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `reverse M2M HasEdge from target side`() {
        val driver = freshM2M()
        val alice = driver.insert("users", mapOf("name" to "Alice"))
        val bob = driver.insert("users", mapOf("name" to "Bob"))
        val group = driver.insert("groups", mapOf("name" to "Admins"))

        // Only Alice is in a group.
        driver.insert("user_groups", mapOf("user_id" to alice["id"], "group_id" to group["id"]))

        // Query users that belong to any group.
        val rows = driver.query(
            "users",
            listOf(Predicate.HasEdge("groups")),
            emptyList(), null, null,
        )
        assertEquals(setOf("Alice"), rows.map { it["name"] }.toSet())
    }

    @Test
    fun `reverse M2M HasEdgeWith with inner predicate`() {
        val driver = freshM2M()
        val alice = driver.insert("users", mapOf("name" to "Alice"))
        val bob = driver.insert("users", mapOf("name" to "Bob"))
        val admins = driver.insert("groups", mapOf("name" to "Admins"))
        val guests = driver.insert("groups", mapOf("name" to "Guests"))

        driver.insert("user_groups", mapOf("user_id" to alice["id"], "group_id" to admins["id"]))
        driver.insert("user_groups", mapOf("user_id" to bob["id"], "group_id" to guests["id"]))

        // Users who belong to a group named "Admins".
        val rows = driver.query(
            "users",
            listOf(Predicate.HasEdgeWith("groups", Predicate.Leaf("name", Op.EQ, "Admins"))),
            emptyList(), null, null,
        )
        assertEquals(setOf("Alice"), rows.map { it["name"] }.toSet())
    }

    // ---------- count ----------

    @Test
    fun `count returns total rows with no predicates`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice"))
        driver.insert("users", mapOf("name" to "Bob"))
        driver.insert("users", mapOf("name" to "Carol"))

        assertEquals(3L, driver.count("users", emptyList()))
    }

    @Test
    fun `count returns zero for empty table`() {
        val driver = fresh()
        assertEquals(0L, driver.count("users", emptyList()))
    }

    @Test
    fun `count filters with predicates`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf("name" to "Bob", "age" to 17, "active" to true))
        driver.insert("users", mapOf("name" to "Carol", "age" to 65, "active" to false))

        val count = driver.count("users", listOf(Predicate.Leaf("active", Op.EQ, true)))
        assertEquals(2L, count)
    }

    @Test
    fun `count works with compound predicates`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf("name" to "Bob", "age" to 70, "active" to false))
        driver.insert("users", mapOf("name" to "Carol", "age" to 17, "active" to true))

        val pred = Predicate.And(
            Predicate.Leaf("active", Op.EQ, true),
            Predicate.Leaf("age", Op.GTE, 18),
        )
        assertEquals(1L, driver.count("users", listOf(pred)))
    }

    // ---------- exists ----------

    @Test
    fun `exists returns true when rows match`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "active" to true))

        assertTrue(driver.exists("users", listOf(Predicate.Leaf("active", Op.EQ, true))))
    }

    @Test
    fun `exists returns false for empty table`() {
        val driver = fresh()
        assertEquals(false, driver.exists("users", emptyList()))
    }

    @Test
    fun `exists returns false when no rows match`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "active" to true))

        assertEquals(false, driver.exists("users", listOf(Predicate.Leaf("active", Op.EQ, false))))
    }

    @Test
    fun `exists with no predicates returns true when table has rows`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice"))

        assertTrue(driver.exists("users", emptyList()))
    }
}
