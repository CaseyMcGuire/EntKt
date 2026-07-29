package entkt.flyway

import entkt.migrations.ManualMigrationRequiredException
import entkt.migrations.ManualMode
import entkt.migrations.MigrationOp
import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.ForeignKeyRef
import entkt.runtime.driver.IdStrategy
import entkt.schema.FieldType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private enum class FwColor { RED, GREEN }

class FlywayMigrationWorkflowTest {

    // ---- Schema fixtures ----

    private val settingsSchema = EntitySchema(
        table = "fw_settings",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_INT,
        columns = listOf(
            ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
            ColumnMetadata("label", FieldType.STRING, nullable = false, default = "anon"),
            ColumnMetadata("quote_label", FieldType.STRING, nullable = false, default = "O'Brien"),
            ColumnMetadata("enabled", FieldType.BOOL, nullable = false, default = true),
            ColumnMetadata("retries", FieldType.INT, nullable = false, default = 3),
            ColumnMetadata("big_count", FieldType.LONG, nullable = false, default = 100L),
            ColumnMetadata("ratio", FieldType.DOUBLE, nullable = false, default = 1.5),
            ColumnMetadata("color", FieldType.ENUM, nullable = false, default = FwColor.GREEN),
            ColumnMetadata("created_at", FieldType.TIME, nullable = false, default = "now"),
            ColumnMetadata("note", FieldType.STRING, nullable = true, default = "n/a"),
        ),
        edges = emptyMap(),
    )

    private val usersSchema = EntitySchema(
        table = "fw_users",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_INT,
        columns = listOf(
            ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
            ColumnMetadata("email", FieldType.STRING, nullable = false, unique = true),
        ),
        edges = emptyMap(),
    )

    private val postsSchema = EntitySchema(
        table = "fw_posts",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_INT,
        columns = listOf(
            ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
            ColumnMetadata("title", FieldType.STRING, nullable = false),
            ColumnMetadata(
                "author_id", FieldType.INT, nullable = true,
                references = ForeignKeyRef("fw_users", "id"),
            ),
        ),
        edges = emptyMap(),
    )

    private val usersSchemaWithBio = EntitySchema(
        table = "fw_users",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_INT,
        columns = listOf(
            ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
            ColumnMetadata("email", FieldType.STRING, nullable = false, unique = true),
            ColumnMetadata("bio", FieldType.STRING, nullable = true),
        ),
        edges = emptyMap(),
    )

    private val usersSchemaWithNotNullBio = EntitySchema(
        table = "fw_users",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_INT,
        columns = listOf(
            ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
            ColumnMetadata("email", FieldType.STRING, nullable = false, unique = true),
            ColumnMetadata("bio", FieldType.STRING, nullable = false),
        ),
        edges = emptyMap(),
    )

    // ---- Helpers ----

    private fun createWorkflow(): FlywayMigrationWorkflow {
        return FlywayMigrationWorkflow()
    }

    private fun createTempDir(): Path = Files.createTempDirectory("entkt_flyway_test")

    // ---- Tests ----

    @Test
    fun `validate and generate reject truncatable identifiers before any database work`() {
        // The preflight must fire from the public entry points — a
        // programmatic caller bypasses SchemaInspector.validate, and a
        // name PostgreSQL would silently truncate wedges the differ
        // permanently once applied.
        val longTable = EntitySchema(
            table = "this_table_name_is_deliberately_far_too_long_to_survive_the_postgres_identifier_limit",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true)),
            edges = emptyMap(),
        )
        val dir = createTempDir()
        try {
            val e = assertFailsWith<IllegalArgumentException> {
                createWorkflow().validate(listOf(longTable), dir)
            }
            assertTrue("truncates" in (e.message ?: ""), "actionable message; was: ${e.message}")
            assertFailsWith<IllegalArgumentException> {
                createWorkflow().generate(listOf(longTable), dir)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `generate creates migration file for new schemas`() {
        val dir = createTempDir()
        try {
            val result = createWorkflow().generate(
                schemas = listOf(usersSchema),
                migrationsDir = dir,
                description = "initial",
            )

            val written = result as GenerateResult.Written
            assertTrue(written.filePath.toFile().exists())
            assertTrue(written.filePath.fileName.toString().startsWith("V1__"))
            assertTrue(written.ops.isNotEmpty())

            val content = written.filePath.toFile().readText()
            assertTrue(content.contains("CREATE TABLE"))
            assertTrue(content.contains("fw_users"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `generate returns NoChanges when schema matches existing migrations`() {
        val dir = createTempDir()
        try {
            // Generate V1 matching usersSchema
            val first = createWorkflow().generate(
                schemas = listOf(usersSchema),
                migrationsDir = dir,
                description = "initial",
            )
            assertTrue(first is GenerateResult.Written)

            // Generate again with same schema — should find no drift
            val second = createWorkflow().generate(
                schemas = listOf(usersSchema),
                migrationsDir = dir,
                description = "no_changes",
            )
            assertTrue(second is GenerateResult.NoChanges)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `generate detects additive column change`() {
        val dir = createTempDir()
        try {
            // V1: users without bio
            createWorkflow().generate(
                schemas = listOf(usersSchema),
                migrationsDir = dir,
                description = "initial",
            )

            // V2: users with nullable bio column
            val second = createWorkflow().generate(
                schemas = listOf(usersSchemaWithBio),
                migrationsDir = dir,
                description = "add_bio",
            )
            val written = second as GenerateResult.Written
            assertTrue(written.filePath.fileName.toString().startsWith("V2__"))

            val addColumnOps = written.ops.filterIsInstance<MigrationOp.AddColumn>()
            assertEquals(1, addColumnOps.size)
            assertEquals("bio", addColumnOps[0].column.name)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `generate throws for manual ops when mode is FAIL`() {
        val dir = createTempDir()
        try {
            // V1: users without bio
            createWorkflow().generate(
                schemas = listOf(usersSchema),
                migrationsDir = dir,
                description = "initial",
            )

            // Adding a NOT NULL column requires manual migration
            assertFailsWith<ManualMigrationRequiredException> {
                createWorkflow().generate(
                    schemas = listOf(usersSchemaWithNotNullBio),
                    migrationsDir = dir,
                    description = "add_notnull_bio",
                    manualMode = ManualMode.FAIL,
                )
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `generate writes manual checklist with ACKNOWLEDGE_AND_ADVANCE`() {
        val dir = createTempDir()
        try {
            // V1: users without bio
            createWorkflow().generate(
                schemas = listOf(usersSchema),
                migrationsDir = dir,
                description = "initial",
            )

            // Add NOT NULL column with ACKNOWLEDGE_AND_ADVANCE
            val result = createWorkflow().generate(
                schemas = listOf(usersSchemaWithNotNullBio),
                migrationsDir = dir,
                description = "add_notnull_bio",
                manualMode = ManualMode.ACKNOWLEDGE_AND_ADVANCE,
            )

            val written = result as GenerateResult.Written
            assertTrue(written.manual.isNotEmpty())

            val content = written.filePath.toFile().readText()
            assertTrue(content.contains("MANUAL STEPS REQUIRED"))
            assertTrue(content.contains("RAISE EXCEPTION"), "Should contain a failing statement to block Flyway application")
            // The checklist item is kept...
            assertTrue(content.contains("-- [ ] AddColumn: fw_users.bio"), content)
            // ...and the operation is emitted COMMENTED OUT beneath it.
            assertTrue(
                content.contains("""--   ALTER TABLE "fw_users" ADD COLUMN "bio" text NOT NULL;"""),
                content,
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `column defaults round-trip through real Postgres without drift`() {
        val dir = createTempDir()
        try {
            // Generating with verify=true applies the DDL to the shadow DB,
            // re-introspects, and re-diffs — so any mismatch between the
            // rendered DEFAULT and the database-reported default surfaces here.
            val result = createWorkflow().generate(
                schemas = listOf(settingsSchema),
                migrationsDir = dir,
                description = "settings_with_defaults",
                verify = true,
            ) as GenerateResult.Written

            val content = result.filePath.toFile().readText()
            assertTrue(content.contains("DEFAULT 'anon'"), content)
            assertTrue(content.contains("DEFAULT 'O''Brien'"), content)
            assertTrue(content.contains("DEFAULT true"), content)
            assertTrue(content.contains("DEFAULT 3"), content)
            assertTrue(content.contains("DEFAULT 100"), content)
            assertTrue(content.contains("DEFAULT 1.5"), content)
            assertTrue(content.contains("DEFAULT 'GREEN'"), content)
            assertTrue(content.contains("DEFAULT now()"), content)

            // Re-running against the same schema must find no drift, proving
            // the desired/introspected default forms reconcile.
            val second = createWorkflow().generate(
                schemas = listOf(settingsSchema),
                migrationsDir = dir,
                description = "no_changes",
            )
            assertTrue(second is GenerateResult.NoChanges, "unexpected drift: $second")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `adding a default to an existing column round-trips as auto op`() {
        val dir = createTempDir()
        try {
            val noDefault = EntitySchema(
                table = "fw_flags",
                idColumn = "id",
                idStrategy = IdStrategy.AUTO_INT,
                columns = listOf(
                    ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
                    ColumnMetadata("state", FieldType.STRING, nullable = false),
                ),
                edges = emptyMap(),
            )
            val withDefault = EntitySchema(
                table = "fw_flags",
                idColumn = "id",
                idStrategy = IdStrategy.AUTO_INT,
                columns = listOf(
                    ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
                    ColumnMetadata("state", FieldType.STRING, nullable = false, default = "pending"),
                ),
                edges = emptyMap(),
            )

            createWorkflow().generate(schemas = listOf(noDefault), migrationsDir = dir, description = "initial")

            val result = createWorkflow().generate(
                schemas = listOf(withDefault),
                migrationsDir = dir,
                description = "add_default",
                verify = true,
            ) as GenerateResult.Written

            val setDefaults = result.ops.filterIsInstance<MigrationOp.SetColumnDefault>()
            assertEquals(1, setDefaults.size)
            assertEquals("state", setDefaults[0].columnName)
            assertTrue(result.filePath.toFile().readText().contains("SET DEFAULT 'pending'"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `adding a unique column with a default surfaces the index as a manual step`() {
        val dir = createTempDir()
        try {
            val base = EntitySchema(
                table = "fw_codes",
                idColumn = "id",
                idStrategy = IdStrategy.AUTO_INT,
                columns = listOf(
                    ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
                    ColumnMetadata("name", FieldType.STRING, nullable = false),
                ),
                edges = emptyMap(),
            )
            val withUniqueDefaulted = EntitySchema(
                table = "fw_codes",
                idColumn = "id",
                idStrategy = IdStrategy.AUTO_INT,
                columns = listOf(
                    ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
                    ColumnMetadata("name", FieldType.STRING, nullable = false),
                    ColumnMetadata("code", FieldType.STRING, nullable = false, unique = true, default = "x"),
                ),
                edges = emptyMap(),
            )

            createWorkflow().generate(schemas = listOf(base), migrationsDir = dir, description = "initial")

            // The constant-backfilled unique column would make CREATE UNIQUE
            // INDEX fail on a populated table, so it must be a manual step —
            // the empty shadow DB can't see the conflict.
            val ex = assertFailsWith<ManualMigrationRequiredException> {
                createWorkflow().generate(
                    schemas = listOf(withUniqueDefaulted),
                    migrationsDir = dir,
                    description = "add_unique_code",
                    manualMode = ManualMode.FAIL,
                )
            }
            assertTrue(ex.ops.any { it is MigrationOp.AddIndex }, "expected the unique index to be manual: ${ex.ops}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `verify succeeds for additive change`() {
        val dir = createTempDir()
        try {
            // V1: users
            createWorkflow().generate(
                schemas = listOf(usersSchema),
                migrationsDir = dir,
                description = "initial",
            )

            // Generate and verify V2: add nullable bio
            val result = createWorkflow().generate(
                schemas = listOf(usersSchemaWithBio),
                migrationsDir = dir,
                description = "add_bio",
                verify = true,
            ) as GenerateResult.Written

            assertTrue(result.filePath.toFile().exists(), "Verified file should still exist")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate reports drift when schema has changed`() {
        val dir = createTempDir()
        try {
            // V1: users without bio
            createWorkflow().generate(
                schemas = listOf(usersSchema),
                migrationsDir = dir,
                description = "initial",
            )

            // Validate with bio added — should detect drift
            val drift = createWorkflow().validate(
                schemas = listOf(usersSchemaWithBio),
                migrationsDir = dir,
            )
            assertTrue(drift.hasDrift)
            assertTrue(drift.ops.isNotEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate detects dropped table as drift`() {
        val dir = createTempDir()
        try {
            // V1: create both users and posts
            createWorkflow().generate(
                schemas = listOf(usersSchema, postsSchema),
                migrationsDir = dir,
                description = "initial",
            )

            // Validate with only users — posts table was removed from schema
            // but still exists in the shadow DB after migrations
            val drift = createWorkflow().validate(
                schemas = listOf(usersSchema),
                migrationsDir = dir,
            )
            assertTrue(drift.hasDrift, "Should detect the removed posts table as drift")
            val dropOps = drift.manual.filterIsInstance<MigrationOp.DropTable>()
            assertEquals(1, dropOps.size)
            assertEquals("fw_posts", dropOps[0].tableName)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate reports no drift when schema matches`() {
        val dir = createTempDir()
        try {
            // V1: create users
            createWorkflow().generate(
                schemas = listOf(usersSchema),
                migrationsDir = dir,
                description = "initial",
            )

            // Validate with same schema — no drift
            val drift = createWorkflow().validate(
                schemas = listOf(usersSchema),
                migrationsDir = dir,
            )
            assertFalse(drift.hasDrift)
            assertTrue(drift.ops.isEmpty())
            assertTrue(drift.manual.isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `version numbering increments correctly`() {
        val dir = createTempDir()
        try {
            // Generate V1
            val first = createWorkflow().generate(
                schemas = listOf(usersSchema),
                migrationsDir = dir,
                description = "create_users",
            )
            assertTrue((first as GenerateResult.Written).filePath.fileName.toString().startsWith("V1__"))

            // Generate V2
            val second = createWorkflow().generate(
                schemas = listOf(usersSchema, postsSchema),
                migrationsDir = dir,
                description = "add_posts",
            )
            assertTrue((second as GenerateResult.Written).filePath.fileName.toString().startsWith("V2__"))

            // Generate V3
            val third = createWorkflow().generate(
                schemas = listOf(usersSchemaWithBio, postsSchema),
                migrationsDir = dir,
                description = "add_bio",
            )
            assertTrue((third as GenerateResult.Written).filePath.fileName.toString().startsWith("V3__"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nextVersion returns V1 for empty directory`() {
        val dir = createTempDir()
        try {
            assertEquals("V1", FlywayMigrationWorkflow.nextVersion(dir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nextVersion increments past existing files`() {
        val dir = createTempDir()
        try {
            dir.resolve("V1__init.sql").toFile().writeText("-- v1")
            dir.resolve("V2__add_users.sql").toFile().writeText("-- v2")
            assertEquals("V3", FlywayMigrationWorkflow.nextVersion(dir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nextVersion ignores non-SQL files`() {
        val dir = createTempDir()
        try {
            dir.resolve("V1__init.sql").toFile().writeText("-- v1")
            dir.resolve("V2.schema.json").toFile().writeText("{}")
            dir.resolve("V5_notes.txt").toFile().writeText("notes")
            assertEquals("V2", FlywayMigrationWorkflow.nextVersion(dir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nextVersion scans nested directories`() {
        val dir = createTempDir()
        try {
            dir.resolve("V1__init.sql").toFile().writeText("-- v1")
            val subdir = dir.resolve("sub").toFile().apply { mkdirs() }
            subdir.resolve("V3__nested.sql").writeText("-- v3")
            assertEquals("V4", FlywayMigrationWorkflow.nextVersion(dir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects repeatable migrations`() {
        val dir = createTempDir()
        try {
            dir.resolve("V1__init.sql").toFile().writeText("CREATE TABLE fw_users (id serial PRIMARY KEY);")
            dir.resolve("R__refresh_views.sql").toFile().writeText("-- repeatable")

            val ex = assertFailsWith<IllegalArgumentException> {
                createWorkflow().validate(
                    schemas = listOf(usersSchema),
                    migrationsDir = dir,
                )
            }
            assertTrue(ex.message!!.contains("Repeatable"))
            assertTrue(ex.message!!.contains("R__refresh_views.sql"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects SQL callbacks`() {
        val dir = createTempDir()
        try {
            dir.resolve("V1__init.sql").toFile().writeText("CREATE TABLE fw_users (id serial PRIMARY KEY);")
            dir.resolve("beforeMigrate.sql").toFile().writeText("-- callback")

            val ex = assertFailsWith<IllegalArgumentException> {
                createWorkflow().validate(
                    schemas = listOf(usersSchema),
                    migrationsDir = dir,
                )
            }
            assertTrue(ex.message!!.contains("callback"))
            assertTrue(ex.message!!.contains("beforeMigrate.sql"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects suffixed SQL callbacks`() {
        val dir = createTempDir()
        try {
            dir.resolve("V1__init.sql").toFile().writeText("CREATE TABLE fw_users (id serial PRIMARY KEY);")
            dir.resolve("beforeMigrate__reset_sequences.sql").toFile().writeText("-- callback")

            val ex = assertFailsWith<IllegalArgumentException> {
                createWorkflow().validate(
                    schemas = listOf(usersSchema),
                    migrationsDir = dir,
                )
            }
            assertTrue(ex.message!!.contains("callback"))
            assertTrue(ex.message!!.contains("beforeMigrate__reset_sequences.sql"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects nested repeatable migrations`() {
        val dir = createTempDir()
        try {
            dir.resolve("V1__init.sql").toFile().writeText("CREATE TABLE fw_users (id serial PRIMARY KEY);")
            val subdir = dir.resolve("sub").toFile().apply { mkdirs() }
            subdir.resolve("R__refresh_views.sql").writeText("-- repeatable")

            val ex = assertFailsWith<IllegalArgumentException> {
                createWorkflow().validate(
                    schemas = listOf(usersSchema),
                    migrationsDir = dir,
                )
            }
            assertTrue(ex.message!!.contains("Repeatable"))
            assertTrue(ex.message!!.contains("R__refresh_views.sql"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects baseline callbacks`() {
        val dir = createTempDir()
        try {
            dir.resolve("V1__init.sql").toFile().writeText("CREATE TABLE fw_users (id serial PRIMARY KEY);")
            dir.resolve("beforeBaseline.sql").toFile().writeText("-- callback")

            val ex = assertFailsWith<IllegalArgumentException> {
                createWorkflow().validate(
                    schemas = listOf(usersSchema),
                    migrationsDir = dir,
                )
            }
            assertTrue(ex.message!!.contains("callback"))
            assertTrue(ex.message!!.contains("beforeBaseline.sql"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `generate with multiple tables handles FK dependencies`() {
        val dir = createTempDir()
        try {
            // Generate both users and posts (posts references users)
            val result = createWorkflow().generate(
                schemas = listOf(usersSchema, postsSchema),
                migrationsDir = dir,
                description = "initial",
            )

            val written = result as GenerateResult.Written
            val content = written.filePath.toFile().readText()
            // users table should appear before posts table in the SQL
            val usersIdx = content.indexOf("fw_users")
            val postsIdx = content.indexOf("fw_posts")
            assertTrue(usersIdx < postsIdx, "Users table should be created before posts (FK dependency)")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `excludeTables hides tables from drift detection`() {
        val dir = createTempDir()
        try {
            // V1: create users and posts
            createWorkflow().generate(
                schemas = listOf(usersSchema, postsSchema),
                migrationsDir = dir,
                description = "initial",
            )

            // Validate with only users schema, but exclude fw_posts from management.
            // The workflow should NOT report fw_posts as drift because it's excluded.
            val workflow = FlywayMigrationWorkflow(excludeTables = setOf("fw_posts"))
            val drift = workflow.validate(
                schemas = listOf(usersSchema),
                migrationsDir = dir,
            )
            assertFalse(drift.hasDrift, "Excluded table should not appear as drift")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

}
