package entkt.flyway

import entkt.migrations.ManualMigrationRequiredException
import entkt.migrations.ManualMode
import entkt.migrations.MigrationOp
import entkt.runtime.ColumnMetadata
import entkt.runtime.EntitySchema
import entkt.runtime.ForeignKeyRef
import entkt.runtime.IdStrategy
import entkt.schema.FieldType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlywayMigrationWorkflowTest {

    // ---- Schema fixtures ----

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
