package entkt.migrations

import java.io.StringReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaDifferTest {

    private val differ = SchemaDiffer()

    // ---- Helpers ----

    private fun table(
        name: String,
        columns: List<NormalizedColumn> = emptyList(),
        indexes: List<NormalizedIndex> = emptyList(),
        foreignKeys: List<NormalizedForeignKey> = emptyList(),
    ) = NormalizedTable(name, columns, indexes, foreignKeys)

    private fun col(
        name: String,
        sqlType: String = "text",
        nullable: Boolean = false,
        primaryKey: Boolean = false,
    ) = NormalizedColumn(name, sqlType, nullable, primaryKey)

    private fun idx(
        columns: List<String>,
        unique: Boolean = false,
        storageKey: String? = null,
        where: String? = null,
    ) = NormalizedIndex(columns, unique, storageKey, where)

    private fun fk(
        column: String,
        targetTable: String,
        targetColumn: String = "id",
        columnNullable: Boolean = false,
    ) = NormalizedForeignKey(column, targetTable, targetColumn, columnNullable)

    private fun schema(vararg tables: NormalizedTable) =
        NormalizedSchema(tables.associateBy { it.name })

    // ---- Tests ----

    @Test
    fun `empty to desired creates all tables`() {
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("name")),
            ),
        )
        val result = differ.diff(desired, NormalizedSchema(emptyMap()))

        val createOps = result.ops.filterIsInstance<MigrationOp.CreateTable>()
        assertEquals(1, createOps.size)
        assertEquals("users", createOps[0].table.name)
        assertTrue(result.manual.isEmpty())
    }

    @Test
    fun `same schema produces empty diff`() {
        val s = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("name"))),
        )
        val result = differ.diff(s, s)

        assertTrue(result.ops.isEmpty())
        assertTrue(result.manual.isEmpty())
    }

    @Test
    fun `add nullable column is auto op`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true))),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("bio", "text", nullable = true)),
            ),
        )
        val result = differ.diff(desired, current)

        val addCols = result.ops.filterIsInstance<MigrationOp.AddColumn>()
        assertEquals(1, addCols.size)
        assertEquals("bio", addCols[0].column.name)
        assertTrue(addCols[0].column.nullable)
        assertTrue(result.manual.isEmpty())
    }

    @Test
    fun `add non-null column is manual op`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true))),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("name", "text", nullable = false)),
            ),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.isEmpty())
        val manualAdds = result.manual.filterIsInstance<MigrationOp.AddColumn>()
        assertEquals(1, manualAdds.size)
        assertEquals("name", manualAdds[0].column.name)
    }

    @Test
    fun `add primary key column to existing table is manual`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true))),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("id2", "integer", primaryKey = true)),
            ),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.isEmpty())
        val manualAdds = result.manual.filterIsInstance<MigrationOp.AddColumn>()
        assertEquals(1, manualAdds.size)
        assertTrue(manualAdds[0].column.primaryKey)
    }

    @Test
    fun `adding PK to existing column is manual`() {
        val current = schema(
            table("users", columns = listOf(
                col("id", "serial", primaryKey = true),
                col("email"),
            )),
        )
        val desired = schema(
            table("users", columns = listOf(
                col("id", "serial", primaryKey = true),
                col("email", primaryKey = true),
            )),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.isEmpty())
        val pkChanges = result.manual.filterIsInstance<MigrationOp.AlterPrimaryKey>()
        assertEquals(1, pkChanges.size)
        assertEquals("email", pkChanges[0].columnName)
        assertTrue(pkChanges[0].added)
    }

    @Test
    fun `removing PK from existing column is manual`() {
        val current = schema(
            table("users", columns = listOf(
                col("id", "serial", primaryKey = true),
                col("email", primaryKey = true),
            )),
        )
        val desired = schema(
            table("users", columns = listOf(
                col("id", "serial", primaryKey = true),
                col("email"),
            )),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.isEmpty())
        val pkChanges = result.manual.filterIsInstance<MigrationOp.AlterPrimaryKey>()
        assertEquals(1, pkChanges.size)
        assertEquals("email", pkChanges[0].columnName)
        assertFalse(pkChanges[0].added)
    }

    @Test
    fun `add index is auto op`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("email"))),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true)),
            ),
        )
        val result = differ.diff(desired, current)

        val addIdxs = result.ops.filterIsInstance<MigrationOp.AddIndex>()
        assertEquals(1, addIdxs.size)
        assertEquals(listOf("email"), addIdxs[0].index.columns)
        assertTrue(addIdxs[0].index.unique)
    }

    @Test
    fun `add FK is auto op`() {
        val current = schema(
            table("posts", columns = listOf(col("id", "serial", primaryKey = true), col("author_id", "integer"))),
        )
        val desired = schema(
            table(
                "posts",
                columns = listOf(col("id", "serial", primaryKey = true), col("author_id", "integer")),
                foreignKeys = listOf(fk("author_id", "users")),
            ),
        )
        val result = differ.diff(desired, current)

        val addFks = result.ops.filterIsInstance<MigrationOp.AddForeignKey>()
        assertEquals(1, addFks.size)
        assertEquals("author_id", addFks[0].fk.column)
        assertEquals("users", addFks[0].fk.targetTable)
    }

    @Test
    fun `drop table is manual`() {
        val current = schema(
            table("old_table", columns = listOf(col("id", "serial", primaryKey = true))),
        )
        val result = differ.diff(NormalizedSchema(emptyMap()), current)

        assertTrue(result.ops.isEmpty())
        val drops = result.manual.filterIsInstance<MigrationOp.DropTable>()
        assertEquals(1, drops.size)
        assertEquals("old_table", drops[0].tableName)
    }

    @Test
    fun `drop column is manual`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("legacy"))),
        )
        val desired = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true))),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.isEmpty())
        val drops = result.manual.filterIsInstance<MigrationOp.DropColumn>()
        assertEquals(1, drops.size)
        assertEquals("legacy", drops[0].columnName)
    }

    @Test
    fun `type change is manual`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("age", "integer"))),
        )
        val desired = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("age", "bigint"))),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.isEmpty())
        val alters = result.manual.filterIsInstance<MigrationOp.AlterColumnType>()
        assertEquals(1, alters.size)
        assertEquals("integer", alters[0].oldType)
        assertEquals("bigint", alters[0].newType)
    }

    @Test
    fun `nullability change is manual`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("name", nullable = false))),
        )
        val desired = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("name", nullable = true))),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.isEmpty())
        val nullChanges = result.manual.filterIsInstance<MigrationOp.DropColumnNotNull>()
        assertEquals(1, nullChanges.size)
    }

    @Test
    fun `set not null is manual`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("name", nullable = true))),
        )
        val desired = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("name", nullable = false))),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.isEmpty())
        val setNotNull = result.manual.filterIsInstance<MigrationOp.SetColumnNotNull>()
        assertEquals(1, setNotNull.size)
    }

    @Test
    fun `drop index is manual`() {
        val current = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true)),
            ),
        )
        val desired = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("email"))),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.isEmpty())
        val drops = result.manual.filterIsInstance<MigrationOp.DropIndex>()
        assertEquals(1, drops.size)
    }

    @Test
    fun `drop FK is manual`() {
        val current = schema(
            table(
                "posts",
                columns = listOf(col("id", "serial", primaryKey = true), col("author_id", "integer")),
                foreignKeys = listOf(fk("author_id", "users")),
            ),
        )
        val desired = schema(
            table("posts", columns = listOf(col("id", "serial", primaryKey = true), col("author_id", "integer"))),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.isEmpty())
        val drops = result.manual.filterIsInstance<MigrationOp.DropForeignKey>()
        assertEquals(1, drops.size)
    }

    @Test
    fun `FK nullability change emits drop and re-add`() {
        val current = schema(
            table(
                "posts",
                columns = listOf(col("id", "serial", primaryKey = true), col("author_id", "integer", nullable = true)),
                foreignKeys = listOf(fk("author_id", "users", columnNullable = true)),
            ),
        )
        val desired = schema(
            table(
                "posts",
                columns = listOf(col("id", "serial", primaryKey = true), col("author_id", "integer", nullable = false)),
                foreignKeys = listOf(fk("author_id", "users", columnNullable = false)),
            ),
        )
        val result = differ.diff(desired, current)

        // Should produce a manual DropForeignKey + auto AddForeignKey
        val dropFks = result.manual.filterIsInstance<MigrationOp.DropForeignKey>()
        assertEquals(1, dropFks.size)
        assertEquals("author_id", dropFks[0].column)
        val addFks = result.ops.filterIsInstance<MigrationOp.AddForeignKey>()
        assertEquals(1, addFks.size)
        assertFalse(addFks[0].fk.columnNullable)
    }

    @Test
    fun `index storageKey change is manual`() {
        val current = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true, storageKey = "old_name")),
            ),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true, storageKey = "new_name")),
            ),
        )
        val result = differ.diff(desired, current)

        // Should produce a manual DropIndex + auto AddIndex
        val dropIdxs = result.manual.filterIsInstance<MigrationOp.DropIndex>()
        assertEquals(1, dropIdxs.size)
        assertEquals("old_name", dropIdxs[0].storageKey)
        val addIdxs = result.ops.filterIsInstance<MigrationOp.AddIndex>()
        assertEquals(1, addIdxs.size)
        assertEquals("new_name", addIdxs[0].index.storageKey)
    }

    @Test
    fun `unnamed index gaining explicit storageKey emits rename`() {
        val current = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true, storageKey = null)),
            ),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true, storageKey = "custom_email_idx")),
            ),
        )
        val result = differ.diff(desired, current)

        val dropIdxs = result.manual.filterIsInstance<MigrationOp.DropIndex>()
        assertEquals(1, dropIdxs.size)
        assertNull(dropIdxs[0].storageKey, "Drop should reference the old derived name")
        val addIdxs = result.ops.filterIsInstance<MigrationOp.AddIndex>()
        assertEquals(1, addIdxs.size)
        assertEquals("custom_email_idx", addIdxs[0].index.storageKey)
    }

    @Test
    fun `ordering - CreateTable before AddIndex before AddFK`() {
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("name")),
                indexes = listOf(idx(listOf("name"), unique = true)),
                foreignKeys = listOf(fk("name", "other_table")),
            ),
        )
        val result = differ.diff(desired, NormalizedSchema(emptyMap()))

        val ops = result.ops
        assertTrue(ops.isNotEmpty())

        val createIdx = ops.indexOfFirst { it is MigrationOp.CreateTable }
        val idxIdx = ops.indexOfFirst { it is MigrationOp.AddIndex }
        val fkIdx = ops.indexOfFirst { it is MigrationOp.AddForeignKey }

        assertTrue(createIdx < idxIdx, "CreateTable should come before AddIndex")
        assertTrue(idxIdx < fkIdx, "AddIndex should come before AddForeignKey")
    }

    @Test
    fun `new table indexes and FKs are separate ops`() {
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true)),
                foreignKeys = listOf(fk("email", "emails")),
            ),
        )
        val result = differ.diff(desired, NormalizedSchema(emptyMap()))

        val creates = result.ops.filterIsInstance<MigrationOp.CreateTable>()
        val addIdxs = result.ops.filterIsInstance<MigrationOp.AddIndex>()
        val addFks = result.ops.filterIsInstance<MigrationOp.AddForeignKey>()

        assertEquals(1, creates.size)
        assertEquals(1, addIdxs.size)
        assertEquals(1, addFks.size)
    }

    @Test
    fun `snapshot JSON round-trip is deterministic`() {
        val original = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true)),
                foreignKeys = listOf(fk("email", "emails")),
            ),
            table(
                "posts",
                columns = listOf(
                    col("id", "serial", primaryKey = true),
                    col("title"),
                    col("author_id", "integer"),
                ),
                indexes = listOf(
                    idx(listOf("title")),
                    idx(listOf("author_id", "title"), unique = true, storageKey = "custom_idx"),
                ),
                foreignKeys = listOf(fk("author_id", "users")),
            ),
        )

        // Encode → decode → re-encode → should be byte-identical
        val writer1 = StringWriter()
        original.toJson(writer1)
        val json1 = writer1.toString()

        val decoded = NormalizedSchema.fromJson(StringReader(json1))
        val writer2 = StringWriter()
        decoded.toJson(writer2)
        val json2 = writer2.toString()

        assertEquals(json1, json2, "JSON round-trip should be deterministic")

        // Verify content is preserved
        assertEquals(original.tables.keys, decoded.tables.keys)
        for ((name, table) in original.tables) {
            val decodedTable = decoded.tables[name]!!
            assertEquals(table.columns, decodedTable.columns)
            // Indexes may be reordered by the deterministic sort
            assertEquals(
                table.indexes.sortedBy { it.columns.joinToString(",") + it.unique },
                decodedTable.indexes.sortedBy { it.columns.joinToString(",") + it.unique },
            )
            assertEquals(
                table.foreignKeys.sortedBy { it.column },
                decodedTable.foreignKeys.sortedBy { it.column },
            )
        }
    }

    @Test
    fun `single-column unique is handled as index for diffing`() {
        // current has a unique index on email
        val current = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true)),
            ),
        )
        // desired also has the same unique index
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true)),
            ),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.isEmpty(), "Same index should produce no ops")
        assertTrue(result.manual.isEmpty())
    }

    // ---- describeOp tests ----

    @Test
    fun `describeOp for AlterPrimaryKey`() {
        val addPk = describeOp(MigrationOp.AlterPrimaryKey("users", "email", added = true))
        assertTrue(addPk.contains("add to"), "Should say 'add to' for added=true")
        assertTrue(addPk.contains("users.email"))

        val removePk = describeOp(MigrationOp.AlterPrimaryKey("users", "email", added = false))
        assertTrue(removePk.contains("remove from"), "Should say 'remove from' for added=false")
    }

    @Test
    fun `describeOp for DropIndex with storageKey`() {
        val withKey = describeOp(MigrationOp.DropIndex("users", listOf("email"), unique = true, storageKey = "legacy_idx"))
        assertTrue(withKey.contains("[legacy_idx]"), "Should include storageKey in brackets")
        assertTrue(withKey.contains("email"))

        val withoutKey = describeOp(MigrationOp.DropIndex("users", listOf("email"), unique = true, storageKey = null))
        assertFalse(withoutKey.contains("["), "Should not have brackets when storageKey is null")
    }

    @Test
    fun `describeOp for DropForeignKey with constraintName`() {
        val withName = describeOp(MigrationOp.DropForeignKey("posts", "author_id", constraintName = "fk_posts_author"))
        assertTrue(withName.contains("[fk_posts_author]"), "Should include constraintName in brackets")

        val withoutName = describeOp(MigrationOp.DropForeignKey("posts", "author_id", constraintName = null))
        assertFalse(withoutName.contains("["), "Should not have brackets when constraintName is null")
    }

    // ---- Partial index tests ----

    @Test
    fun `adding a partial index emits AddIndex with where clause`() {
        val current = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
            ),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
                indexes = listOf(idx(listOf("email"), unique = true, where = "active = true")),
            ),
        )
        val result = differ.diff(desired, current)

        val addIdxs = result.ops.filterIsInstance<MigrationOp.AddIndex>()
        assertEquals(1, addIdxs.size)
        assertEquals("active = true", addIdxs[0].index.where)
    }

    @Test
    fun `dropping a partial index emits DropIndex`() {
        val current = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
                indexes = listOf(idx(listOf("email"), unique = true, where = "active = true")),
            ),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
            ),
        )
        val result = differ.diff(desired, current)

        val dropIdxs = result.manual.filterIsInstance<MigrationOp.DropIndex>()
        assertEquals(1, dropIdxs.size)
    }

    @Test
    fun `changing where clause triggers drop and add`() {
        val current = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
                indexes = listOf(idx(listOf("email"), unique = true, where = "active = true")),
            ),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
                indexes = listOf(idx(listOf("email"), unique = true, where = "active = false")),
            ),
        )
        val result = differ.diff(desired, current)

        val dropIdxs = result.manual.filterIsInstance<MigrationOp.DropIndex>()
        assertEquals(1, dropIdxs.size)
        val addIdxs = result.ops.filterIsInstance<MigrationOp.AddIndex>()
        assertEquals(1, addIdxs.size)
        assertEquals("active = false", addIdxs[0].index.where)
    }

    @Test
    fun `same columns with vs without where are different indexes`() {
        val current = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
                indexes = listOf(idx(listOf("email"), unique = true)),
            ),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
                indexes = listOf(
                    idx(listOf("email"), unique = true),
                    idx(listOf("email"), unique = true, where = "active = true"),
                ),
            ),
        )
        val result = differ.diff(desired, current)

        // The non-partial index matches, so only the partial one is new
        val addIdxs = result.ops.filterIsInstance<MigrationOp.AddIndex>()
        assertEquals(1, addIdxs.size)
        assertEquals("active = true", addIdxs[0].index.where)
        // No drops
        assertTrue(result.manual.filterIsInstance<MigrationOp.DropIndex>().isEmpty())
    }

    @Test
    fun `snapshot round-trip preserves partial index where clause`() {
        val original = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
                indexes = listOf(idx(listOf("email"), unique = true, where = "active = true")),
            ),
        )

        val writer = java.io.StringWriter()
        original.toJson(writer)
        val decoded = NormalizedSchema.fromJson(java.io.StringReader(writer.toString()))

        val index = decoded.tables["users"]!!.indexes[0]
        assertEquals("active = true", index.where)
        assertTrue(index.unique)
    }

    // ---- normalizeWhere tests ----

    @Test
    fun `normalizeWhere strips outer parens`() {
        assertEquals("active = true", normalizeWhere("(active = true)"))
    }

    @Test
    fun `normalizeWhere strips nested outer parens`() {
        assertEquals("active = true", normalizeWhere("((active = true))"))
    }

    @Test
    fun `normalizeWhere does not strip compound parens`() {
        assertEquals("(a = 1) OR (b = 2)", normalizeWhere("(a = 1) OR (b = 2)"))
    }

    @Test
    fun `normalizeWhere normalizes whitespace`() {
        assertEquals("active = true", normalizeWhere("active  =  true"))
    }

    @Test
    fun `normalizeWhere returns null for null`() {
        assertNull(normalizeWhere(null))
    }

    @Test
    fun `normalizeWhere strips column casts`() {
        // pg_get_expr deparses boolean column refs as (col)::boolean
        assertEquals("active = true", normalizeWhere("((active)::boolean = true)"))
    }

    @Test
    fun `normalizeWhere strips literal casts`() {
        // pg_get_expr deparses text comparisons with ::text casts
        assertEquals("status = 'active'", normalizeWhere("((status)::text = 'active'::text)"))
    }

    @Test
    fun `normalizeWhere strips bare identifier casts`() {
        assertEquals("x = 5", normalizeWhere("x::integer = 5"))
    }

    @Test
    fun `differ matches indexes with outer-paren difference in where`() {
        // Simulates pg_get_expr wrapping the predicate in parens
        val current = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
                indexes = listOf(idx(listOf("email"), unique = true, where = "(active = true)")),
            ),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
                indexes = listOf(idx(listOf("email"), unique = true, where = "active = true")),
            ),
        )
        val result = differ.diff(desired, current)

        // Should see no changes — same index semantically
        assertTrue(result.ops.filterIsInstance<MigrationOp.AddIndex>().isEmpty())
        assertTrue(result.manual.filterIsInstance<MigrationOp.DropIndex>().isEmpty())
    }

    @Test
    fun `differ matches indexes when introspected predicate has PG casts`() {
        // Simulates pg_get_expr adding casts to a boolean predicate
        val current = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
                indexes = listOf(idx(listOf("email"), unique = true, where = "((active)::boolean = true)")),
            ),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
                indexes = listOf(idx(listOf("email"), unique = true, where = "active = true")),
            ),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.filterIsInstance<MigrationOp.AddIndex>().isEmpty())
        assertTrue(result.manual.filterIsInstance<MigrationOp.DropIndex>().isEmpty())
    }

    @Test
    fun `DropIndex carries where clause`() {
        val current = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
                indexes = listOf(idx(listOf("email"), unique = true, where = "active = true")),
            ),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email"), col("active", "boolean")),
            ),
        )
        val result = differ.diff(desired, current)

        val dropIdx = result.manual.filterIsInstance<MigrationOp.DropIndex>().single()
        assertEquals("active = true", dropIdx.where)
    }

    // ---- JsonCodec tests ----

    @Test
    fun `JsonCodec handles escaped characters in storageKey`() {
        val original = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true)),
                indexes = listOf(idx(listOf("id"), unique = false, storageKey = "idx_with\"quotes")),
                foreignKeys = listOf(
                    NormalizedForeignKey("id", "other", "id", false, constraintName = "fk\\with_backslash"),
                ),
            ),
        )

        val writer = StringWriter()
        original.toJson(writer)
        val json = writer.toString()

        val decoded = NormalizedSchema.fromJson(StringReader(json))
        val decodedTable = decoded.tables["users"]!!
        assertEquals("idx_with\"quotes", decodedTable.indexes[0].storageKey)
        assertEquals("fk\\with_backslash", decodedTable.foreignKeys[0].constraintName)
    }

    @Test
    fun `snapshot round-trip preserves constraintName`() {
        val original = schema(
            table(
                "posts",
                columns = listOf(
                    col("id", "serial", primaryKey = true),
                    col("author_id", "integer", nullable = true),
                ),
                foreignKeys = listOf(
                    NormalizedForeignKey("author_id", "users", "id", columnNullable = true, constraintName = "real_fk_name"),
                ),
            ),
        )

        val writer = StringWriter()
        original.toJson(writer)
        val decoded = NormalizedSchema.fromJson(StringReader(writer.toString()))

        val fk = decoded.tables["posts"]!!.foreignKeys[0]
        assertEquals("real_fk_name", fk.constraintName)
        assertTrue(fk.columnNullable)
    }

    @Test
    fun `snapshot round-trip omits null constraintName`() {
        val original = schema(
            table(
                "posts",
                columns = listOf(col("id", "serial", primaryKey = true), col("ref", "integer")),
                foreignKeys = listOf(
                    NormalizedForeignKey("ref", "other", "id", columnNullable = false, constraintName = null),
                ),
            ),
        )

        val writer = StringWriter()
        original.toJson(writer)
        val json = writer.toString()

        // Null constraintName should not appear in the JSON at all
        assertFalse(json.contains("constraintName"), "Null constraintName should be omitted from JSON")

        val decoded = NormalizedSchema.fromJson(StringReader(json))
        assertNull(decoded.tables["posts"]!!.foreignKeys[0].constraintName)
    }

    // ---- plan() without introspector (planner path) ----

    @Test
    fun `plan without introspector and no snapshot diffs against empty schema`() {
        val typeMapper = object : TypeMapper {
            override fun sqlTypeFor(fieldType: entkt.schema.FieldType, isPrimaryKey: Boolean, idStrategy: entkt.runtime.IdStrategy): String {
                return when {
                    isPrimaryKey && idStrategy == entkt.runtime.IdStrategy.AUTO_INT -> "serial"
                    fieldType == entkt.schema.FieldType.STRING -> "text"
                    fieldType == entkt.schema.FieldType.INT -> "integer"
                    else -> "text"
                }
            }
            override fun canonicalize(rawSqlType: String): String = rawSqlType
        }
        val renderer = object : MigrationSqlRenderer {
            override fun render(op: MigrationOp, mode: RenderMode): List<String> {
                return listOf("-- placeholder")
            }
        }
        val migrator = Migrator(
            differ = SchemaDiffer(),
            renderer = renderer,
            typeMapper = typeMapper,
        )

        val tmpDir = java.nio.file.Files.createTempDirectory("entkt_planner_test")

        val usersSchema = entkt.runtime.EntitySchema(
            table = "users",
            idColumn = "id",
            idStrategy = entkt.runtime.IdStrategy.AUTO_INT,
            columns = listOf(
                entkt.runtime.ColumnMetadata("id", entkt.schema.FieldType.INT, nullable = false, primaryKey = true),
                entkt.runtime.ColumnMetadata("name", entkt.schema.FieldType.STRING, nullable = false),
            ),
            edges = emptyMap(),
        )

        val plan = migrator.plan(listOf(usersSchema), tmpDir, "initial")

        assertNotNull(plan.filePath, "Should generate a migration file")
        assertTrue(plan.filePath!!.fileName.toString().startsWith("V1__"))
        val creates = plan.ops.filterIsInstance<MigrationOp.CreateTable>()
        assertEquals(1, creates.size)
        assertEquals("users", creates[0].table.name)
        assertTrue(tmpDir.resolve("V1.schema.json").toFile().exists(), "Snapshot should be created alongside SQL")

        // V1 snapshot should have null parent
        val v1Content = tmpDir.resolve("V1.schema.json").toFile().readText()
        assertTrue(v1Content.contains("\"parent\": null"), "V1 should have null parent")

        // Generate V2
        val updatedSchema = usersSchema.copy(
            columns = usersSchema.columns + entkt.runtime.ColumnMetadata("bio", entkt.schema.FieldType.STRING, nullable = true),
        )
        migrator.plan(listOf(updatedSchema), tmpDir, "add_bio")

        // V2 snapshot should have parent = sha256(V1 content)
        val v2Content = tmpDir.resolve("V2.schema.json").toFile().readText()
        val expectedParent = sha256(v1Content)
        assertTrue(v2Content.contains(expectedParent), "V2 parent should be sha256 of V1 snapshot")

        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `plan detects broken snapshot chain`() {
        val typeMapper = object : TypeMapper {
            override fun sqlTypeFor(fieldType: entkt.schema.FieldType, isPrimaryKey: Boolean, idStrategy: entkt.runtime.IdStrategy): String {
                return when (fieldType) {
                    entkt.schema.FieldType.INT -> if (isPrimaryKey) "serial" else "integer"
                    entkt.schema.FieldType.STRING -> "text"
                    else -> "text"
                }
            }
            override fun canonicalize(rawSqlType: String): String = rawSqlType
            override fun normalizeIdentifier(name: String): String = name
        }
        val renderer = object : MigrationSqlRenderer {
            override fun render(op: MigrationOp, mode: RenderMode): List<String> {
                return listOf("-- placeholder")
            }
        }
        val migrator = Migrator(
            differ = SchemaDiffer(),
            renderer = renderer,
            typeMapper = typeMapper,
        )

        val tmpDir = java.nio.file.Files.createTempDirectory("entkt_chain_test")

        val usersSchema = entkt.runtime.EntitySchema(
            table = "users",
            idColumn = "id",
            idStrategy = entkt.runtime.IdStrategy.AUTO_INT,
            columns = listOf(
                entkt.runtime.ColumnMetadata("id", entkt.schema.FieldType.INT, nullable = false, primaryKey = true),
                entkt.runtime.ColumnMetadata("name", entkt.schema.FieldType.STRING, nullable = false),
            ),
            edges = emptyMap(),
        )

        // Generate V1
        migrator.plan(listOf(usersSchema), tmpDir, "initial")

        // Generate V2 with a change
        val updated = usersSchema.copy(
            columns = usersSchema.columns + entkt.runtime.ColumnMetadata("bio", entkt.schema.FieldType.STRING, nullable = true),
        )
        migrator.plan(listOf(updated), tmpDir, "add_bio")

        // Now tamper V1 snapshot — V2's parent checksum no longer matches sha256(V1)
        val v1File = tmpDir.resolve("V1.schema.json").toFile()
        v1File.writeText(v1File.readText().replace("\"name\"", "\"full_name\""))

        val further = updated.copy(
            columns = updated.columns + entkt.runtime.ColumnMetadata("age", entkt.schema.FieldType.INT, nullable = true),
        )

        assertFailsWith<BrokenSnapshotChainException> {
            migrator.plan(listOf(further), tmpDir, "add_age")
        }

        tmpDir.toFile().deleteRecursively()
    }
}
