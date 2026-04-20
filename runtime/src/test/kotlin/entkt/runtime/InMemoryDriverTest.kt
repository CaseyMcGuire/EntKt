package entkt.runtime

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.schema.FieldType
import entkt.schema.OnDelete
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

    // ---------- upsert ----------

    @Test
    fun `upsert inserts when no conflict exists`() {
        val driver = fresh()
        val result = driver.upsert(
            "users",
            mapOf("name" to "Alice", "age" to 30),
            listOf("name"),
        )

        assertTrue(result.inserted, "Should report as inserted")
        assertEquals("Alice", result.row["name"])
        assertEquals(30, result.row["age"])
        assertNotNull(result.row["id"], "Should have minted an id")
    }

    @Test
    fun `upsert updates non-conflict columns on conflict`() {
        val driver = fresh()
        val first = driver.upsert(
            "users",
            mapOf("name" to "Alice", "age" to 30),
            listOf("name"),
        )

        val second = driver.upsert(
            "users",
            mapOf("name" to "Alice", "age" to 31),
            listOf("name"),
        )

        assertTrue(first.inserted, "First should be an insert")
        assertTrue(!second.inserted, "Second should be an update")
        assertEquals(first.row["id"], second.row["id"], "Should keep existing id")
        assertEquals(31, second.row["age"], "Should update age")
        assertEquals(1, driver.query("users", emptyList(), emptyList(), null, null).size,
            "Should still have one row")
    }

    @Test
    fun `upsert preserves conflict column values`() {
        val driver = fresh()
        driver.upsert("users", mapOf("name" to "Alice", "age" to 30), listOf("name"))
        val result = driver.upsert("users", mapOf("name" to "Alice", "age" to 99), listOf("name"))

        assertEquals("Alice", result.row["name"], "Conflict column should be preserved")
    }

    @Test
    fun `upsert preserves immutable columns on conflict`() {
        val driver = fresh()
        val first = driver.upsert(
            "users",
            mapOf("name" to "Alice", "age" to 30, "active" to true),
            conflictColumns = listOf("name"),
            immutableColumns = listOf("active"),
        )

        val second = driver.upsert(
            "users",
            mapOf("name" to "Alice", "age" to 31, "active" to false),
            conflictColumns = listOf("name"),
            immutableColumns = listOf("active"),
        )

        assertEquals(first.row["id"], second.row["id"], "Should keep existing id")
        assertEquals(31, second.row["age"], "Should update mutable column")
        assertEquals(true, second.row["active"], "Should preserve immutable column")
    }

    @Test
    fun `upsert rejects empty conflict columns`() {
        val driver = fresh()
        assertFailsWith<IllegalArgumentException> {
            driver.upsert("users", mapOf("name" to "Alice"), emptyList())
        }
    }

    // ---------- insertMany ----------

    @Test
    fun `insertMany inserts multiple rows and assigns ids`() {
        val driver = fresh()
        val rows = driver.insertMany(
            "users",
            listOf(
                mapOf("name" to "Alice", "age" to 30),
                mapOf("name" to "Bob", "age" to 25),
                mapOf("name" to "Carol", "age" to 40),
            ),
        )

        assertEquals(3, rows.size)
        assertEquals("Alice", rows[0]["name"])
        assertEquals("Bob", rows[1]["name"])
        assertEquals("Carol", rows[2]["name"])
        // Ids should be unique and sequential
        assertEquals(1L, rows[0]["id"])
        assertEquals(2L, rows[1]["id"])
        assertEquals(3L, rows[2]["id"])
    }

    @Test
    fun `insertMany with empty list returns empty`() {
        val driver = fresh()
        assertEquals(emptyList(), driver.insertMany("users", emptyList()))
    }

    @Test
    fun `insertMany rows are queryable`() {
        val driver = fresh()
        driver.insertMany("users", listOf(
            mapOf("name" to "Alice"),
            mapOf("name" to "Bob"),
        ))

        val all = driver.query("users", emptyList(), emptyList(), null, null)
        assertEquals(2, all.size)
    }

    @Test
    fun `insertMany with sparse maps preserves all columns`() {
        val driver = fresh()
        val rows = driver.insertMany("users", listOf(
            mapOf("name" to "Alice"),
            mapOf("name" to "Bob", "age" to 25),
        ))

        assertEquals(2, rows.size)
        assertNull(rows[0]["age"], "First row should have null age")
        assertEquals(25, rows[1]["age"], "Second row should keep its age")
    }

    // ---------- updateMany ----------

    @Test
    fun `updateMany updates matching rows and returns count`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "age" to 30, "active" to true))
        driver.insert("users", mapOf("name" to "Bob", "age" to 17, "active" to true))
        driver.insert("users", mapOf("name" to "Carol", "age" to 65, "active" to false))

        val count = driver.updateMany(
            "users",
            mapOf("active" to false),
            listOf(Predicate.Leaf("age", Op.LT, 18)),
        )

        assertEquals(1, count)
        val bob = driver.query(
            "users",
            listOf(Predicate.Leaf("name", Op.EQ, "Bob")),
            emptyList(), null, null,
        ).single()
        assertEquals(false, bob["active"])
    }

    @Test
    fun `updateMany with no predicates updates all rows`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "active" to true))
        driver.insert("users", mapOf("name" to "Bob", "active" to true))

        val count = driver.updateMany("users", mapOf("active" to false), emptyList())
        assertEquals(2, count)
    }

    @Test
    fun `updateMany never rewrites the id`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice"))

        driver.updateMany("users", mapOf("id" to 999L, "name" to "Updated"), emptyList())

        val row = driver.query("users", emptyList(), emptyList(), null, null).single()
        assertEquals(1L, row["id"])
        assertEquals("Updated", row["name"])
    }

    @Test
    fun `updateMany returns zero when no rows match`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice"))

        val count = driver.updateMany(
            "users",
            mapOf("name" to "Updated"),
            listOf(Predicate.Leaf("name", Op.EQ, "Nobody")),
        )
        assertEquals(0, count)
    }

    @Test
    fun `updateMany with empty values returns zero`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "active" to true))
        driver.insert("users", mapOf("name" to "Bob", "active" to true))

        val count = driver.updateMany("users", emptyMap(), emptyList())
        assertEquals(0, count, "No columns to update means zero rows updated")
    }

    @Test
    fun `updateMany with only id column returns zero`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice"))

        val count = driver.updateMany("users", mapOf("id" to 999L), emptyList())
        assertEquals(0, count, "Id-only values means no actual update")
    }

    // ---------- deleteMany ----------

    @Test
    fun `deleteMany deletes matching rows and returns count`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice", "age" to 30))
        driver.insert("users", mapOf("name" to "Bob", "age" to 17))
        driver.insert("users", mapOf("name" to "Carol", "age" to 65))

        val count = driver.deleteMany(
            "users",
            listOf(Predicate.Leaf("age", Op.LT, 18)),
        )

        assertEquals(1, count)
        val remaining = driver.query("users", emptyList(), emptyList(), null, null)
        assertEquals(2, remaining.size)
        assertEquals(setOf("Alice", "Carol"), remaining.map { it["name"] }.toSet())
    }

    @Test
    fun `deleteMany with no predicates deletes all rows`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice"))
        driver.insert("users", mapOf("name" to "Bob"))

        val count = driver.deleteMany("users", emptyList())
        assertEquals(2, count)
        assertEquals(0, driver.query("users", emptyList(), emptyList(), null, null).size)
    }

    @Test
    fun `deleteMany returns zero when no rows match`() {
        val driver = fresh()
        driver.insert("users", mapOf("name" to "Alice"))

        val count = driver.deleteMany(
            "users",
            listOf(Predicate.Leaf("name", Op.EQ, "Nobody")),
        )
        assertEquals(0, count)
        assertEquals(1, driver.query("users", emptyList(), emptyList(), null, null).size)
    }

    // ---------- Referential actions (ON DELETE) ----------

    private val CASCADE_PARENT = EntitySchema(
        table = "parents",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
        ),
        edges = emptyMap(),
    )

    private val CASCADE_CHILD = EntitySchema(
        table = "children",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
            ColumnMetadata(
                "parent_id", FieldType.LONG, nullable = false,
                references = ForeignKeyRef("parents", "id", OnDelete.CASCADE),
            ),
        ),
        edges = emptyMap(),
    )

    private val SET_NULL_CHILD = EntitySchema(
        table = "sn_children",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
            ColumnMetadata(
                "parent_id", FieldType.LONG, nullable = true,
                references = ForeignKeyRef("parents", "id", OnDelete.SET_NULL),
            ),
        ),
        edges = emptyMap(),
    )

    private val RESTRICT_CHILD = EntitySchema(
        table = "restrict_children",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
            ColumnMetadata(
                "parent_id", FieldType.LONG, nullable = false,
                references = ForeignKeyRef("parents", "id", OnDelete.RESTRICT),
            ),
        ),
        edges = emptyMap(),
    )

    private fun freshReferential(vararg childSchemas: EntitySchema): InMemoryDriver = InMemoryDriver().apply {
        register(CASCADE_PARENT)
        for (schema in childSchemas) register(schema)
    }

    @Test
    fun `cascade delete removes child rows`() {
        val driver = freshReferential(CASCADE_CHILD)
        val parent = driver.insert("parents", mapOf("name" to "P1"))
        driver.insert("children", mapOf("name" to "C1", "parent_id" to parent["id"]))
        driver.insert("children", mapOf("name" to "C2", "parent_id" to parent["id"]))

        driver.delete("parents", parent["id"]!!)

        assertEquals(0, driver.query("children", emptyList(), emptyList(), null, null).size)
    }

    @Test
    fun `cascade delete does not remove unrelated child rows`() {
        val driver = freshReferential(CASCADE_CHILD)
        val p1 = driver.insert("parents", mapOf("name" to "P1"))
        val p2 = driver.insert("parents", mapOf("name" to "P2"))
        driver.insert("children", mapOf("name" to "C1", "parent_id" to p1["id"]))
        driver.insert("children", mapOf("name" to "C2", "parent_id" to p2["id"]))

        driver.delete("parents", p1["id"]!!)

        val remaining = driver.query("children", emptyList(), emptyList(), null, null)
        assertEquals(1, remaining.size)
        assertEquals("C2", remaining.single()["name"])
    }

    @Test
    fun `set null nulls FK on child rows`() {
        val driver = freshReferential(SET_NULL_CHILD)
        val parent = driver.insert("parents", mapOf("name" to "P1"))
        driver.insert("sn_children", mapOf("name" to "C1", "parent_id" to parent["id"]))

        driver.delete("parents", parent["id"]!!)

        val children = driver.query("sn_children", emptyList(), emptyList(), null, null)
        assertEquals(1, children.size)
        assertNull(children.single()["parent_id"])
    }

    @Test
    fun `restrict prevents delete when children exist`() {
        val driver = freshReferential(RESTRICT_CHILD)
        val parent = driver.insert("parents", mapOf("name" to "P1"))
        driver.insert("restrict_children", mapOf("name" to "C1", "parent_id" to parent["id"]))

        assertFailsWith<IllegalStateException> {
            driver.delete("parents", parent["id"]!!)
        }
        // Parent should still exist
        assertNotNull(driver.byId("parents", parent["id"]!!))
    }

    @Test
    fun `restrict allows delete when no children reference the row`() {
        val driver = freshReferential(RESTRICT_CHILD)
        val p1 = driver.insert("parents", mapOf("name" to "P1"))
        val p2 = driver.insert("parents", mapOf("name" to "P2"))
        driver.insert("restrict_children", mapOf("name" to "C1", "parent_id" to p2["id"]))

        // p1 has no children — should be deletable
        assertTrue(driver.delete("parents", p1["id"]!!))
    }

    @Test
    fun `cascade delete chains through multiple levels`() {
        // grandparent → parent (CASCADE) → child (CASCADE)
        val grandparentSchema = EntitySchema(
            table = "grandparents",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("name", FieldType.STRING, nullable = false),
            ),
            edges = emptyMap(),
        )
        val midSchema = EntitySchema(
            table = "mid",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("name", FieldType.STRING, nullable = false),
                ColumnMetadata(
                    "gp_id", FieldType.LONG, nullable = false,
                    references = ForeignKeyRef("grandparents", "id", OnDelete.CASCADE),
                ),
            ),
            edges = emptyMap(),
        )
        val leafSchema = EntitySchema(
            table = "leaves",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("name", FieldType.STRING, nullable = false),
                ColumnMetadata(
                    "mid_id", FieldType.LONG, nullable = false,
                    references = ForeignKeyRef("mid", "id", OnDelete.CASCADE),
                ),
            ),
            edges = emptyMap(),
        )

        val driver = InMemoryDriver().apply {
            register(grandparentSchema)
            register(midSchema)
            register(leafSchema)
        }

        val gp = driver.insert("grandparents", mapOf("name" to "GP"))
        val mid = driver.insert("mid", mapOf("name" to "M", "gp_id" to gp["id"]))
        driver.insert("leaves", mapOf("name" to "L", "mid_id" to mid["id"]))

        driver.delete("grandparents", gp["id"]!!)

        assertEquals(0, driver.query("mid", emptyList(), emptyList(), null, null).size)
        assertEquals(0, driver.query("leaves", emptyList(), emptyList(), null, null).size)
    }

    @Test
    fun `default onDelete infers from nullability`() {
        // No explicit onDelete — nullable FK defaults to SET_NULL, required defaults to RESTRICT
        val nullableChild = EntitySchema(
            table = "opt_children",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata(
                    "parent_id", FieldType.LONG, nullable = true,
                    references = ForeignKeyRef("parents", "id"),
                ),
            ),
            edges = emptyMap(),
        )
        val requiredChild = EntitySchema(
            table = "req_children",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata(
                    "parent_id", FieldType.LONG, nullable = false,
                    references = ForeignKeyRef("parents", "id"),
                ),
            ),
            edges = emptyMap(),
        )
        val driver = InMemoryDriver().apply {
            register(CASCADE_PARENT)
            register(nullableChild)
            register(requiredChild)
        }

        val p1 = driver.insert("parents", mapOf("name" to "P1"))
        val p2 = driver.insert("parents", mapOf("name" to "P2"))
        driver.insert("opt_children", mapOf("parent_id" to p1["id"]))
        driver.insert("req_children", mapOf("parent_id" to p2["id"]))

        // Nullable FK → SET_NULL
        driver.delete("parents", p1["id"]!!)
        val optChild = driver.query("opt_children", emptyList(), emptyList(), null, null).single()
        assertNull(optChild["parent_id"])

        // Required FK → RESTRICT
        assertFailsWith<IllegalStateException> {
            driver.delete("parents", p2["id"]!!)
        }
    }

    @Test
    fun `deleteMany applies cascade`() {
        val driver = freshReferential(CASCADE_CHILD)
        val p1 = driver.insert("parents", mapOf("name" to "P1"))
        val p2 = driver.insert("parents", mapOf("name" to "P2"))
        driver.insert("children", mapOf("name" to "C1", "parent_id" to p1["id"]))
        driver.insert("children", mapOf("name" to "C2", "parent_id" to p2["id"]))

        driver.deleteMany("parents", emptyList())

        assertEquals(0, driver.query("children", emptyList(), emptyList(), null, null).size)
    }

    @Test
    fun `self-referential cascade does not stack overflow`() {
        val selfRefSchema = EntitySchema(
            table = "nodes",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("name", FieldType.STRING, nullable = false),
                ColumnMetadata(
                    "parent_id", FieldType.LONG, nullable = true,
                    references = ForeignKeyRef("nodes", "id", OnDelete.CASCADE),
                ),
            ),
            edges = emptyMap(),
        )
        val driver = InMemoryDriver().apply { register(selfRefSchema) }
        val root = driver.insert("nodes", mapOf("name" to "root"))
        val child = driver.insert("nodes", mapOf("name" to "child", "parent_id" to root["id"]))
        // Self-referential: child points back at itself
        driver.insert("nodes", mapOf("name" to "self", "parent_id" to child["id"]))

        // Should not stack overflow
        driver.delete("nodes", root["id"]!!)
        assertEquals(0, driver.query("nodes", emptyList(), emptyList(), null, null).size)
    }

    @Test
    fun `set null on non-nullable FK is rejected`() {
        val badSchema = EntitySchema(
            table = "bad_children",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata(
                    "parent_id", FieldType.LONG, nullable = false,
                    references = ForeignKeyRef("parents", "id", OnDelete.SET_NULL),
                ),
            ),
            edges = emptyMap(),
        )
        val driver = InMemoryDriver().apply {
            register(CASCADE_PARENT)
            register(badSchema)
        }
        val parent = driver.insert("parents", mapOf("name" to "P1"))
        driver.insert("bad_children", mapOf("parent_id" to parent["id"]))

        assertFailsWith<IllegalStateException> {
            driver.delete("parents", parent["id"]!!)
        }
    }

    @Test
    fun `cascade delete matches on referenced column, not primary key`() {
        // Parent has a "code" column; child FK references parents.code, not parents.id
        val codedParent = EntitySchema(
            table = "coded_parents",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("code", FieldType.STRING, nullable = false, unique = true),
            ),
            edges = emptyMap(),
        )
        val codedChild = EntitySchema(
            table = "coded_children",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata(
                    "parent_code", FieldType.STRING, nullable = false,
                    references = ForeignKeyRef("coded_parents", "code", OnDelete.CASCADE),
                ),
            ),
            edges = emptyMap(),
        )

        val driver = InMemoryDriver().apply {
            register(codedParent)
            register(codedChild)
        }
        val p1 = driver.insert("coded_parents", mapOf("code" to "A"))
        val p2 = driver.insert("coded_parents", mapOf("code" to "B"))
        driver.insert("coded_children", mapOf("parent_code" to "A"))
        driver.insert("coded_children", mapOf("parent_code" to "B"))

        driver.delete("coded_parents", p1["id"]!!)

        val remaining = driver.query("coded_children", emptyList(), emptyList(), null, null)
        assertEquals(1, remaining.size)
        assertEquals("B", remaining.single()["parent_code"])
    }
}
