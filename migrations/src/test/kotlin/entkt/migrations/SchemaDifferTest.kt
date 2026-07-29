package entkt.migrations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        default: String? = null,
    ) = NormalizedColumn(name, sqlType, nullable, primaryKey, default)

    private fun idx(
        columns: List<String>,
        unique: Boolean = false,
        name: String? = null,
        where: String? = null,
    ) = NormalizedIndex(columns, unique, name, where)

    private fun fk(
        column: String,
        targetTable: String,
        targetColumn: String = "id",
        columnNullable: Boolean = false,
    ) = NormalizedForeignKey(
        listOf(column), targetTable, listOf(targetColumn),
        onDelete = resolveDslOnDelete(null, columnNullable),
    )

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
    fun `add non-null column with default is auto op`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true))),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(
                    col("id", "serial", primaryKey = true),
                    col("status", "text", nullable = false, default = "'active'"),
                ),
            ),
        )
        val result = differ.diff(desired, current)

        val addCols = result.ops.filterIsInstance<MigrationOp.AddColumn>()
        assertEquals(1, addCols.size)
        assertEquals("status", addCols[0].column.name)
        assertEquals("'active'", addCols[0].column.default)
        assertTrue(result.manual.isEmpty())
    }

    @Test
    fun `setting a default on existing column is auto op`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("status"))),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("status", default = "'active'")),
            ),
        )
        val result = differ.diff(desired, current)

        val setDefaults = result.ops.filterIsInstance<MigrationOp.SetColumnDefault>()
        assertEquals(1, setDefaults.size)
        assertEquals("status", setDefaults[0].columnName)
        assertEquals("'active'", setDefaults[0].default)
        assertTrue(result.manual.isEmpty())
    }

    @Test
    fun `changing a default on existing column is auto op`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("status", default = "'old'"))),
        )
        val desired = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("status", default = "'new'"))),
        )
        val result = differ.diff(desired, current)

        val setDefaults = result.ops.filterIsInstance<MigrationOp.SetColumnDefault>()
        assertEquals(1, setDefaults.size)
        assertEquals("'new'", setDefaults[0].default)
    }

    @Test
    fun `removing a default from existing column is a drop default op`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("status", default = "'active'"))),
        )
        val desired = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("status"))),
        )
        val result = differ.diff(desired, current)

        val dropDefaults = result.ops.filterIsInstance<MigrationOp.DropColumnDefault>()
        assertEquals(1, dropDefaults.size)
        assertEquals("status", dropDefaults[0].columnName)
    }

    @Test
    fun `default differing only by type cast does not diff`() {
        // Desired comes from formatSqlDefault ('active'); current comes from
        // the database ('active'::text). They must reconcile.
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("status", default = "'active'::text"))),
        )
        val desired = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("status", default = "'active'"))),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.isEmpty(), "expected no default change, got: ${result.ops}")
        assertTrue(result.manual.isEmpty())
    }

    @Test
    fun `changing a string default containing a double colon is detected`() {
        val current = schema(
            table("t", columns = listOf(col("id", "serial", primaryKey = true), col("tok", default = "'a::b'"))),
        )
        val desired = schema(
            table("t", columns = listOf(col("id", "serial", primaryKey = true), col("tok", default = "'a::c'"))),
        )
        val result = differ.diff(desired, current)

        val setDefaults = result.ops.filterIsInstance<MigrationOp.SetColumnDefault>()
        assertEquals(1, setDefaults.size)
        assertEquals("'a::c'", setDefaults[0].default)
    }

    @Test
    fun `numeric default differing by cast and quoting does not diff`() {
        // bigint default: desired "5" vs database-reported "'5'::bigint".
        val current = schema(
            table("t", columns = listOf(col("id", "bigserial", primaryKey = true), col("n", "bigint", default = "'5'::bigint"))),
        )
        val desired = schema(
            table("t", columns = listOf(col("id", "bigserial", primaryKey = true), col("n", "bigint", default = "5"))),
        )
        val result = differ.diff(desired, current)

        assertTrue(result.ops.isEmpty(), "expected no default change, got: ${result.ops}")
    }

    @Test
    fun `adding a unique column with a default defers the unique index to manual`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true))),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(
                    col("id", "serial", primaryKey = true),
                    col("code", "text", nullable = false, default = "'x'"),
                ),
                indexes = listOf(idx(listOf("code"), unique = true, name = "idx_users_code_unique")),
            ),
        )
        val result = differ.diff(desired, current)

        // The column add itself is safe (auto)...
        assertEquals(1, result.ops.filterIsInstance<MigrationOp.AddColumn>().size)
        // ...but a unique index over the constant-backfilled column would
        // fail on a populated table, so it is deferred to manual.
        assertTrue(result.ops.filterIsInstance<MigrationOp.AddIndex>().isEmpty())
        val manualIdx = result.manual.filterIsInstance<MigrationOp.AddIndex>()
        assertEquals(1, manualIdx.size)
        assertEquals(listOf("code"), manualIdx[0].index.columns)
    }

    @Test
    fun `unique index mixing an existing column with a defaulted new column is manual`() {
        val current = schema(
            table("t", columns = listOf(col("id", "serial", primaryKey = true), col("a"))),
        )
        val desired = schema(
            table(
                "t",
                columns = listOf(
                    col("id", "serial", primaryKey = true),
                    col("a"),
                    col("b", "text", nullable = false, default = "'x'"),
                ),
                indexes = listOf(idx(listOf("a", "b"), unique = true, name = "idx_t_a_b")),
            ),
        )
        val result = differ.diff(desired, current)

        // Backfilling b to a constant collapses (a, b) uniqueness onto `a`,
        // which has no prior unique constraint and may already hold dups —
        // so the index can fail on real data. Defer to manual.
        assertTrue(result.ops.filterIsInstance<MigrationOp.AddIndex>().isEmpty())
        assertEquals(1, result.manual.filterIsInstance<MigrationOp.AddIndex>().size)
    }

    @Test
    fun `unique index with a defaulted new column plus a nullable null-backfilled column stays auto`() {
        val current = schema(
            table("t", columns = listOf(col("id", "serial", primaryKey = true))),
        )
        val desired = schema(
            table(
                "t",
                columns = listOf(
                    col("id", "serial", primaryKey = true),
                    col("a", "text", nullable = false, default = "'x'"),
                    col("b", "text", nullable = true), // NULL for every existing row
                ),
                indexes = listOf(idx(listOf("a", "b"), unique = true, name = "idx_t_a_b")),
            ),
        )
        val result = differ.diff(desired, current)

        // Every existing row gets b = NULL, and NULLS DISTINCT makes all
        // (x, NULL) tuples distinct, so the index is provably safe to build.
        assertEquals(1, result.ops.filterIsInstance<MigrationOp.AddIndex>().size)
        assertTrue(result.manual.filterIsInstance<MigrationOp.AddIndex>().isEmpty())
    }

    @Test
    fun `adding a unique index over only pre-existing columns stays auto`() {
        // Unchanged behavior: data-dependent (not guaranteed) failure stays
        // the caller's responsibility, matching `add index is auto op`.
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true), col("email"))),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true, name = "idx_users_email")),
            ),
        )
        val result = differ.diff(desired, current)

        assertEquals(1, result.ops.filterIsInstance<MigrationOp.AddIndex>().size)
        assertTrue(result.manual.filterIsInstance<MigrationOp.AddIndex>().isEmpty())
    }

    @Test
    fun `adding a nullable unique column without a default keeps the index auto`() {
        val current = schema(
            table("users", columns = listOf(col("id", "serial", primaryKey = true))),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(
                    col("id", "serial", primaryKey = true),
                    col("code", "text", nullable = true), // no default → backfills NULL, distinct under unique
                ),
                indexes = listOf(idx(listOf("code"), unique = true, name = "idx_users_code_unique")),
            ),
        )
        val result = differ.diff(desired, current)

        assertEquals(1, result.ops.filterIsInstance<MigrationOp.AddIndex>().size)
        assertTrue(result.manual.filterIsInstance<MigrationOp.AddIndex>().isEmpty())
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
        assertEquals(listOf("author_id"), addFks[0].fk.columns)
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
        assertEquals(listOf("author_id"), dropFks[0].columns)
        val addFks = result.ops.filterIsInstance<MigrationOp.AddForeignKey>()
        assertEquals(1, addFks.size)
        assertEquals(FkAction.RESTRICT, addFks[0].fk.onDelete)
    }

    @Test
    fun `index name change is manual`() {
        val current = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true, name = "old_name")),
            ),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true, name = "new_name")),
            ),
        )
        val result = differ.diff(desired, current)

        // Should produce a manual DropIndex + auto AddIndex
        val dropIdxs = result.manual.filterIsInstance<MigrationOp.DropIndex>()
        assertEquals(1, dropIdxs.size)
        assertEquals("old_name", dropIdxs[0].name)
        val addIdxs = result.ops.filterIsInstance<MigrationOp.AddIndex>()
        assertEquals(1, addIdxs.size)
        assertEquals("new_name", addIdxs[0].index.name)
    }

    @Test
    fun `unnamed index gaining explicit name emits rename`() {
        val current = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true, name = null)),
            ),
        )
        val desired = schema(
            table(
                "users",
                columns = listOf(col("id", "serial", primaryKey = true), col("email")),
                indexes = listOf(idx(listOf("email"), unique = true, name = "custom_email_idx")),
            ),
        )
        val result = differ.diff(desired, current)

        val dropIdxs = result.manual.filterIsInstance<MigrationOp.DropIndex>()
        assertEquals(1, dropIdxs.size)
        assertNull(dropIdxs[0].name, "Drop should reference the old derived name")
        val addIdxs = result.ops.filterIsInstance<MigrationOp.AddIndex>()
        assertEquals(1, addIdxs.size)
        assertEquals("custom_email_idx", addIdxs[0].index.name)
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
    fun `describeOp for DropIndex with name`() {
        val withKey = describeOp(MigrationOp.DropIndex("users", listOf("email"), unique = true, name = "legacy_idx"))
        assertTrue(withKey.contains("[legacy_idx]"), "Should include name in brackets")
        assertTrue(withKey.contains("email"))

        val withoutKey = describeOp(MigrationOp.DropIndex("users", listOf("email"), unique = true, name = null))
        assertFalse(withoutKey.contains("["), "Should not have brackets when name is null")
    }

    @Test
    fun `describeOp for DropForeignKey with constraintName`() {
        val withName = describeOp(MigrationOp.DropForeignKey("posts", listOf("author_id"), constraintName = "fk_posts_author"))
        assertTrue(withName.contains("[fk_posts_author]"), "Should include constraintName in brackets")

        val withoutName = describeOp(MigrationOp.DropForeignKey("posts", listOf("author_id"), constraintName = null))
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
}
