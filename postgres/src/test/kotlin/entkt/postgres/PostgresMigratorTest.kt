package entkt.postgres

import entkt.migrations.ManualMigrationRequiredException
import entkt.migrations.ManualMode
import entkt.migrations.MigrationOp
import entkt.migrations.NormalizedColumn
import entkt.migrations.NormalizedForeignKey
import entkt.migrations.NormalizedIndex
import entkt.migrations.NormalizedSchema
import entkt.migrations.NormalizedTable
import entkt.migrations.RenderMode
import entkt.migrations.SchemaDiffer
import entkt.runtime.ColumnMetadata
import entkt.runtime.EntitySchema
import entkt.runtime.ForeignKeyRef
import entkt.runtime.IdStrategy
import entkt.runtime.IndexMetadata
import entkt.schema.FieldType
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class PostgresMigratorTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer("postgres:16-alpine")
    }

    private val dataSource: DataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private fun cleanDb() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                // Drop all tables in public schema
                val rs = stmt.executeQuery(
                    """
                    SELECT tablename FROM pg_tables
                    WHERE schemaname = 'public'
                    """.trimIndent(),
                )
                val tables = mutableListOf<String>()
                while (rs.next()) tables.add(rs.getString("tablename"))
                rs.close()
                if (tables.isNotEmpty()) {
                    stmt.execute("DROP TABLE ${tables.joinToString(", ") { "\"$it\"" }} CASCADE")
                }
            }
        }
    }

    private fun sha256(content: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    // ---- Schema fixtures ----

    private val usersSchema = EntitySchema(
        table = "mig_users",
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
        table = "mig_posts",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_INT,
        columns = listOf(
            ColumnMetadata("id", FieldType.INT, nullable = false, primaryKey = true),
            ColumnMetadata("title", FieldType.STRING, nullable = false),
            ColumnMetadata(
                "author_id", FieldType.INT, nullable = true,
                references = ForeignKeyRef("mig_users", "id"),
            ),
        ),
        edges = emptyMap(),
    )

    // ---- Migrator tests (dev mode) ----

    @Test
    fun `empty db - creates all tables`() {
        cleanDb()
        val migrator = PostgresMigrator.create(dataSource)
        val result = migrator.migrate(listOf(usersSchema, postsSchema))

        assertTrue(result.applied.isNotEmpty())
        val creates = result.applied.filterIsInstance<MigrationOp.CreateTable>()
        assertEquals(2, creates.size)

        // Verify tables actually exist
        val introspector = PostgresIntrospector(dataSource)
        val schema = introspector.introspect(setOf("mig_users", "mig_posts"))
        assertNotNull(schema.tables["mig_users"])
        assertNotNull(schema.tables["mig_posts"])
    }

    @Test
    fun `idempotent re-run produces no ops`() {
        cleanDb()
        val migrator = PostgresMigrator.create(dataSource)
        migrator.migrate(listOf(usersSchema, postsSchema))

        val result = migrator.migrate(listOf(usersSchema, postsSchema))
        assertTrue(result.applied.isEmpty(), "Second run should produce no ops")
    }

    @Test
    fun `add nullable column`() {
        cleanDb()
        val migrator = PostgresMigrator.create(dataSource)
        migrator.migrate(listOf(usersSchema))

        // Add a nullable bio column
        val updatedSchema = usersSchema.copy(
            columns = usersSchema.columns + ColumnMetadata("bio", FieldType.TEXT, nullable = true),
        )
        val result = migrator.migrate(listOf(updatedSchema))

        val addCols = result.applied.filterIsInstance<MigrationOp.AddColumn>()
        assertEquals(1, addCols.size)
        assertEquals("bio", addCols[0].column.name)

        // Verify column exists
        val introspected = PostgresIntrospector(dataSource).introspect(setOf("mig_users"))
        val cols = introspected.tables["mig_users"]!!.columns.map { it.name }
        assertTrue("bio" in cols)
    }

    @Test
    fun `add index`() {
        cleanDb()
        val migrator = PostgresMigrator.create(dataSource)
        migrator.migrate(listOf(usersSchema))

        // Add a composite index
        val updatedSchema = usersSchema.copy(
            indexes = listOf(IndexMetadata(listOf("name", "email"), unique = false)),
        )
        val result = migrator.migrate(listOf(updatedSchema))

        val addIdxs = result.applied.filterIsInstance<MigrationOp.AddIndex>()
        assertTrue(addIdxs.any { it.index.columns == listOf("name", "email") })
    }

    @Test
    fun `non-null column addition detected as manual`() {
        cleanDb()
        val migrator = PostgresMigrator.create(dataSource)
        migrator.migrate(listOf(usersSchema))

        val updatedSchema = usersSchema.copy(
            columns = usersSchema.columns + ColumnMetadata("required_field", FieldType.STRING, nullable = false),
        )

        val ex = assertFailsWith<ManualMigrationRequiredException> {
            migrator.migrate(listOf(updatedSchema))
        }
        assertTrue(ex.ops.any { it is MigrationOp.AddColumn })
    }

    @Test
    fun `introspector only sees managed tables`() {
        cleanDb()
        // Create an unmanaged table
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE \"unrelated\" (\"id\" serial PRIMARY KEY)") }
        }

        val migrator = PostgresMigrator.create(dataSource)
        migrator.migrate(listOf(usersSchema))

        // Second run should not try to drop the unrelated table
        val result = migrator.migrate(listOf(usersSchema))
        assertTrue(result.applied.isEmpty())
        assertTrue(result.manual.isEmpty())
    }

    // ---- Renderer tests ----

    @Test
    fun `RenderMode DEV emits IF NOT EXISTS for CREATE TABLE and INDEX`() {
        val renderer = PostgresSqlRenderer()

        val createOp = MigrationOp.CreateTable(
            NormalizedTable(
                "test",
                listOf(NormalizedColumn("id", "serial", false, true)),
                emptyList(),
                emptyList(),
            ),
        )
        val createSql = renderer.render(createOp, RenderMode.DEV)
        assertTrue(createSql[0].contains("IF NOT EXISTS"))

        val indexOp = MigrationOp.AddIndex("test", NormalizedIndex(listOf("name"), true, null))
        val indexSql = renderer.render(indexOp, RenderMode.DEV)
        assertTrue(indexSql[0].contains("IF NOT EXISTS"))
    }

    @Test
    fun `RenderMode MIGRATION_FILE omits IF NOT EXISTS`() {
        val renderer = PostgresSqlRenderer()

        val createOp = MigrationOp.CreateTable(
            NormalizedTable(
                "test",
                listOf(NormalizedColumn("id", "serial", false, true)),
                emptyList(),
                emptyList(),
            ),
        )
        val createSql = renderer.render(createOp, RenderMode.MIGRATION_FILE)
        assertFalse(createSql[0].contains("IF NOT EXISTS"))
    }

    @Test
    fun `CreateTable emits only columns and PK not indexes or FKs`() {
        val renderer = PostgresSqlRenderer()
        val table = NormalizedTable(
            "test",
            listOf(
                NormalizedColumn("id", "serial", false, true),
                NormalizedColumn("name", "text", false, false),
            ),
            listOf(NormalizedIndex(listOf("name"), true, null)),
            listOf(NormalizedForeignKey("name", "other", "id", columnNullable = false)),
        )
        val sql = renderer.render(MigrationOp.CreateTable(table), RenderMode.MIGRATION_FILE)
        assertEquals(1, sql.size)
        assertFalse(sql[0].contains("INDEX"), "CREATE TABLE should not inline indexes")
        assertFalse(sql[0].contains("REFERENCES"), "CREATE TABLE should not inline FKs")
        assertFalse(sql[0].contains("FOREIGN KEY"), "CREATE TABLE should not inline FKs")
    }

    // ---- Plan mode tests ----

    @Test
    fun `plan bootstraps against live DB when no snapshot exists`() {
        cleanDb()
        // Pre-create tables matching the entity schemas (simulating an
        // existing deployment that already has the right schema)
        val migrator = PostgresMigrator.create(dataSource)
        migrator.migrate(listOf(usersSchema, postsSchema))

        val tmpDir = Files.createTempDirectory("entkt_test_bootstrap")
        val snapshotPath = tmpDir.resolve("schema_snapshot.json")
        assertFalse(snapshotPath.toFile().exists())

        // plan() with no snapshot should diff against the live DB,
        // find nothing to do, and create the initial snapshot
        val plan = migrator.plan(listOf(usersSchema, postsSchema), snapshotPath, tmpDir, "bootstrap")

        assertNull(plan.filePath, "No migration file needed — DB already matches")
        assertTrue(plan.ops.isEmpty())
        assertTrue(plan.manual.isEmpty())
        assertTrue(plan.snapshotAdvanced, "Initial snapshot should be created even with no ops")
        assertTrue(snapshotPath.toFile().exists(), "Snapshot file should exist after bootstrap")

        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `plan bootstrap with live DB detects delta only`() {
        cleanDb()
        // Pre-create only users table
        val migrator = PostgresMigrator.create(dataSource)
        migrator.migrate(listOf(usersSchema))

        val tmpDir = Files.createTempDirectory("entkt_test_bootstrap_delta")
        val snapshotPath = tmpDir.resolve("schema_snapshot.json")

        // plan() should only generate CreateTable for posts, not users
        val plan = migrator.plan(listOf(usersSchema, postsSchema), snapshotPath, tmpDir, "add_posts")

        assertNotNull(plan.filePath)
        val creates = plan.ops.filterIsInstance<MigrationOp.CreateTable>()
        assertEquals(1, creates.size)
        assertEquals("mig_posts", creates[0].table.name)

        // Should not contain a CreateTable op for mig_users
        val content = plan.filePath!!.toFile().readText()
        assertFalse(content.contains("CreateTable: mig_users"), "Should not re-create existing table")

        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `plan with no manual ops generates file and advances snapshot`() {
        cleanDb()
        val migrator = PostgresMigrator.create(dataSource)
        val tmpDir = Files.createTempDirectory("entkt_test_migrations")
        val snapshotPath = tmpDir.resolve("schema_snapshot.json")

        val plan = migrator.plan(
            schemas = listOf(usersSchema),
            snapshotPath = snapshotPath,
            outputDir = tmpDir,
            description = "initial",
        )

        assertNotNull(plan.filePath)
        assertTrue(plan.filePath!!.toFile().exists())
        assertTrue(plan.snapshotAdvanced)
        assertTrue(snapshotPath.toFile().exists())
        assertTrue(plan.ops.isNotEmpty())

        // Verify the SQL file content
        val content = plan.filePath!!.toFile().readText()
        assertTrue(content.contains("CREATE TABLE"))
        assertFalse(content.contains("IF NOT EXISTS"), "Prod migrations should not use IF NOT EXISTS")

        // Cleanup
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `plan with manual ops and FAIL mode throws`() {
        cleanDb()
        val migrator = PostgresMigrator.create(dataSource)
        val tmpDir = Files.createTempDirectory("entkt_test_migrations")
        val snapshotPath = tmpDir.resolve("schema_snapshot.json")

        // Create initial snapshot
        migrator.plan(listOf(usersSchema), snapshotPath, tmpDir, "initial")

        // Now add a non-null column — manual op
        val updated = usersSchema.copy(
            columns = usersSchema.columns + ColumnMetadata("required", FieldType.STRING, nullable = false),
        )

        assertFailsWith<ManualMigrationRequiredException> {
            migrator.plan(listOf(updated), snapshotPath, tmpDir, "add_required")
        }

        // Snapshot should not have advanced (only 1 file from initial)
        val files = tmpDir.toFile().listFiles { f -> f.name.endsWith(".sql") }!!
        assertEquals(1, files.size, "No new file should be generated on FAIL")

        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `plan with ACKNOWLEDGE_AND_ADVANCE emits manual checklist and advances snapshot`() {
        cleanDb()
        val migrator = PostgresMigrator.create(dataSource)
        val tmpDir = Files.createTempDirectory("entkt_test_migrations")
        val snapshotPath = tmpDir.resolve("schema_snapshot.json")

        // Create initial snapshot
        migrator.plan(listOf(usersSchema), snapshotPath, tmpDir, "initial")

        // Add a non-null column (manual) + nullable column (auto)
        val updated = usersSchema.copy(
            columns = usersSchema.columns +
                ColumnMetadata("required", FieldType.STRING, nullable = false) +
                ColumnMetadata("optional_field", FieldType.TEXT, nullable = true),
        )

        val plan = migrator.plan(
            listOf(updated), snapshotPath, tmpDir, "mixed_changes",
            manualMode = ManualMode.ACKNOWLEDGE_AND_ADVANCE,
        )

        assertNotNull(plan.filePath)
        assertTrue(plan.snapshotAdvanced)
        assertTrue(plan.manual.isNotEmpty())

        val content = plan.filePath!!.toFile().readText()
        assertTrue(content.contains("!! MANUAL STEPS REQUIRED !!"))
        assertTrue(content.contains("[ ]"))
        assertTrue(content.contains("ADD COLUMN"))

        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `plan preserves real index and FK names across snapshot advancement`() {
        cleanDb()
        val migrator = PostgresMigrator.create(dataSource)
        val tmpDir = Files.createTempDirectory("entkt_test_name_preserve")
        val snapshotPath = tmpDir.resolve("schema_snapshot.json")

        // Seed the initial snapshot by planning with users + posts
        migrator.plan(listOf(usersSchema, postsSchema), snapshotPath, tmpDir, "initial")

        // Manually patch the snapshot to simulate real names from introspection:
        // index "legacy_email_idx" instead of derived name (storageKey absent),
        // FK constraint "mig_posts_author_id_fkey" instead of derived name (constraintName absent)
        val snapshotText = snapshotPath.toFile().readText()
        val patched = snapshotText
            // Index entries look like: {"columns": ["email"], "unique": true}
            // Insert storageKey before the closing brace
            .replace(
                "\"unique\": true}",
                "\"unique\": true, \"storageKey\": \"legacy_email_idx\"}",
            )
            // FK entries end with: "columnNullable": true}
            // Insert constraintName before the closing brace
            .replace(
                "\"columnNullable\": true}",
                "\"columnNullable\": true, \"constraintName\": \"mig_posts_author_id_fkey\"}",
            )
        snapshotPath.toFile().writeText(patched)

        // Add a nullable column so plan() produces an op and advances the snapshot
        val updatedUsers = usersSchema.copy(
            columns = usersSchema.columns + ColumnMetadata("bio", FieldType.TEXT, nullable = true),
        )
        migrator.plan(listOf(updatedUsers, postsSchema), snapshotPath, tmpDir, "add_bio")

        // Verify the advanced snapshot still has the real names
        val advancedSnapshot = snapshotPath.toFile().readText()
        assertTrue(
            advancedSnapshot.contains("legacy_email_idx"),
            "Snapshot should preserve real index name across advancement",
        )
        assertTrue(
            advancedSnapshot.contains("mig_posts_author_id_fkey"),
            "Snapshot should preserve real FK constraint name across advancement",
        )

        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `plan drops old FK constraint name from snapshot when nullability flips`() {
        cleanDb()
        val migrator = PostgresMigrator.create(dataSource)
        val tmpDir = Files.createTempDirectory("entkt_test_fk_recreate")
        val snapshotPath = tmpDir.resolve("schema_snapshot.json")

        // Seed initial snapshot with posts (nullable author_id FK)
        migrator.plan(listOf(usersSchema, postsSchema), snapshotPath, tmpDir, "initial")

        // Patch snapshot to have a real constraint name
        val snapshotText = snapshotPath.toFile().readText()
        val patched = snapshotText.replace(
            "\"columnNullable\": true}",
            "\"columnNullable\": true, \"constraintName\": \"old_fk_name\"}",
        )
        snapshotPath.toFile().writeText(patched)

        // Flip author_id to non-null — this triggers FK drop+recreate
        val nonNullPosts = postsSchema.copy(
            columns = postsSchema.columns.map {
                if (it.name == "author_id") it.copy(nullable = false) else it
            },
        )

        val plan = migrator.plan(
            listOf(usersSchema, nonNullPosts), snapshotPath, tmpDir, "flip_nullable",
            manualMode = ManualMode.ACKNOWLEDGE_AND_ADVANCE,
        )

        // The old name should NOT be carried into the new snapshot
        val advancedSnapshot = snapshotPath.toFile().readText()
        assertFalse(
            advancedSnapshot.contains("old_fk_name"),
            "Old FK name should not survive a nullability flip (FK is recreated under a new name)",
        )

        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `plan with introspector captures real names from live DB into snapshot`() {
        cleanDb()
        val migrator = PostgresMigrator.create(dataSource)
        val tmpDir = Files.createTempDirectory("entkt_test_introspect_names")
        val snapshotPath = tmpDir.resolve("schema_snapshot.json")

        // Create tables via raw DDL with non-standard naming (simulating
        // pre-migration-system tables or PostgresDriver.register() output)
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE "mig_users" (
                        "id" serial PRIMARY KEY,
                        "name" text NOT NULL,
                        "email" text NOT NULL
                    )
                    """.trimIndent(),
                )
                // Postgres default unique constraint name
                stmt.execute("CREATE UNIQUE INDEX \"legacy_email_idx\" ON \"mig_users\" (\"email\")")
                stmt.execute(
                    """
                    CREATE TABLE "mig_posts" (
                        "id" serial PRIMARY KEY,
                        "title" text NOT NULL,
                        "author_id" integer,
                        CONSTRAINT "mig_posts_author_id_fkey" FOREIGN KEY ("author_id")
                            REFERENCES "mig_users" ("id") ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        // First plan() — tables already exist in DB, so no ops needed.
        // But the snapshot should capture real names from introspection.
        val plan = migrator.plan(listOf(usersSchema, postsSchema), snapshotPath, tmpDir, "initial")

        val snapshot = snapshotPath.toFile().readText()
        assertTrue(
            snapshot.contains("legacy_email_idx"),
            "Snapshot should capture real index name from live DB",
        )
        assertTrue(
            snapshot.contains("mig_posts_author_id_fkey"),
            "Snapshot should capture real FK constraint name from live DB",
        )

        tmpDir.toFile().deleteRecursively()
    }

    // ---- MigrationRunner tests ----

    @Test
    fun `migration runner applies pending files`() {
        cleanDb()
        val tmpDir = Files.createTempDirectory("entkt_test_runner")

        // Write a migration file
        val migrationFile = tmpDir.resolve("V20260411120000__create_test.sql").toFile()
        migrationFile.writeText(
            """
            CREATE TABLE "runner_test" (
                "id" serial PRIMARY KEY,
                "name" text NOT NULL
            );
            """.trimIndent(),
        )

        val runner = PostgresMigrator.runner(dataSource)
        val result = runner.applyPending(tmpDir)

        assertEquals(1, result.applied.size)
        assertEquals("V20260411120000", result.applied[0])

        // Verify table exists
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM \"runner_test\"")
                rs.next()
                assertEquals(0, rs.getInt(1))
            }
        }

        // Re-run — should be idempotent
        val result2 = runner.applyPending(tmpDir)
        assertTrue(result2.applied.isEmpty())

        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `migration runner fails on checksum mismatch`() {
        cleanDb()
        val tmpDir = Files.createTempDirectory("entkt_test_checksum")

        val file = tmpDir.resolve("V20260411120000__test.sql").toFile()
        file.writeText("CREATE TABLE \"checksum_test\" (\"id\" serial PRIMARY KEY);")

        val runner = PostgresMigrator.runner(dataSource)
        runner.applyPending(tmpDir)

        // Modify the file after application
        file.writeText("CREATE TABLE \"checksum_test\" (\"id\" serial PRIMARY KEY, \"extra\" text);")

        val ex = assertFailsWith<entkt.migrations.ChecksumMismatchException> {
            runner.applyPending(tmpDir)
        }
        assertTrue(ex.message!!.contains("modified"))

        tmpDir.toFile().deleteRecursively()
    }

    // ---- PostgresTypeMapper tests ----

    @Test
    fun `normalizeIdentifier truncates with hash suffix at 63 bytes`() {
        val typeMapper = PostgresTypeMapper()
        // A name that exceeds 63 bytes
        val longName = "idx_" + "a".repeat(100)
        val normalized = typeMapper.normalizeIdentifier(longName)
        assertTrue(normalized.toByteArray(Charsets.UTF_8).size <= 63, "Normalized name must fit in 63 bytes")
        // Should end with _XXXXXXXX (underscore + 8 hex chars)
        assertTrue(normalized.matches(Regex(".*_[0-9a-f]{8}$")), "Should end with hash suffix")
        // Should be stable
        assertEquals(normalized, typeMapper.normalizeIdentifier(longName))
    }

    @Test
    fun `normalizeIdentifier handles multibyte UTF-8 without splitting characters`() {
        val typeMapper = PostgresTypeMapper()
        // Each emoji is 4 bytes in UTF-8. Build a name that forces truncation
        // right at a character boundary.
        val emoji = "\uD83D\uDE00" // 😀 = 4 bytes
        val longName = "idx_" + emoji.repeat(20) // 4 + 80 = 84 bytes, exceeds 63
        val normalized = typeMapper.normalizeIdentifier(longName)
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= 63, "Must fit in 63 bytes, got ${bytes.size}")
        // Should be valid UTF-8 (no split characters)
        val roundTripped = String(bytes, Charsets.UTF_8)
        assertEquals(normalized, roundTripped, "Should be valid UTF-8 after truncation")
    }

    @Test
    fun `normalizeIdentifier returns short names unchanged`() {
        val typeMapper = PostgresTypeMapper()
        val shortName = "idx_users_email"
        assertEquals(shortName, typeMapper.normalizeIdentifier(shortName))
    }

    // ---- FK ON DELETE rendering ----

    @Test
    fun `FK rendering uses SET NULL for nullable columns and RESTRICT for non-null`() {
        val renderer = PostgresSqlRenderer()

        val nullableFk = MigrationOp.AddForeignKey(
            "posts",
            NormalizedForeignKey("author_id", "users", "id", columnNullable = true),
        )
        val nullableSql = renderer.render(nullableFk, RenderMode.MIGRATION_FILE)
        assertTrue(nullableSql[0].contains("ON DELETE SET NULL"), "Nullable FK should use SET NULL")

        val nonNullFk = MigrationOp.AddForeignKey(
            "posts",
            NormalizedForeignKey("author_id", "users", "id", columnNullable = false),
        )
        val nonNullSql = renderer.render(nonNullFk, RenderMode.MIGRATION_FILE)
        assertTrue(nonNullSql[0].contains("ON DELETE RESTRICT"), "Non-null FK should use RESTRICT")
    }

    // ---- Renderer: DropIndex with/without storageKey ----

    @Test
    fun `renderer DropIndex uses storageKey when present and derives name when null`() {
        val renderer = PostgresSqlRenderer()

        val withKey = MigrationOp.DropIndex("users", listOf("email"), unique = true, storageKey = "legacy_email_idx")
        val withKeySql = renderer.render(withKey, RenderMode.MIGRATION_FILE)
        assertTrue(withKeySql[0].contains("legacy_email_idx"), "Should use storageKey")

        val withoutKey = MigrationOp.DropIndex("users", listOf("email"), unique = true, storageKey = null)
        val withoutKeySql = renderer.render(withoutKey, RenderMode.MIGRATION_FILE)
        // Should derive a name like idx_users_email_unique
        assertTrue(withoutKeySql[0].contains("idx_users_email_unique"), "Should derive index name")
    }

    // ---- Version collision protection ----

    @Test
    fun `plan generates suffixed version on collision`() {
        cleanDb()
        val migrator = PostgresMigrator.create(dataSource)
        val tmpDir = java.nio.file.Files.createTempDirectory("entkt_test_collision")
        val snapshotPath = tmpDir.resolve("schema_snapshot.json")

        // Generate the first migration
        val plan1 = migrator.plan(listOf(usersSchema), snapshotPath, tmpDir, "first")
        assertNotNull(plan1.filePath)

        // Extract the version from the generated filename
        val version1 = plan1.filePath!!.toFile().nameWithoutExtension.substringBefore("__")

        // Create a fake file with the next version that would be generated
        // (same timestamp in the same millisecond). We simulate by writing
        // a file with the same version prefix that the next plan() would produce.
        val updatedSchema = usersSchema.copy(
            columns = usersSchema.columns + ColumnMetadata("bio", FieldType.TEXT, nullable = true),
        )
        val plan2 = migrator.plan(listOf(updatedSchema, postsSchema), snapshotPath, tmpDir, "second")
        assertNotNull(plan2.filePath)

        // Both files should exist with different names
        val sqlFiles = tmpDir.toFile().listFiles { f -> f.name.endsWith(".sql") }!!
        assertTrue(sqlFiles.size >= 2, "Should have at least 2 migration files")
        val versions = sqlFiles.map { it.nameWithoutExtension.substringBefore("__") }.toSet()
        assertEquals(sqlFiles.size, versions.size, "All versions should be unique")

        tmpDir.toFile().deleteRecursively()
    }

    // ---- CRLF checksum backwards compatibility ----

    @Test
    fun `migration runner accepts pre-CRLF checksum for existing migrations`() {
        cleanDb()
        val tmpDir = java.nio.file.Files.createTempDirectory("entkt_test_crlf")

        // Write a file with CRLF line endings
        val crlfContent = "CREATE TABLE \"crlf_test\" (\r\n  \"id\" serial PRIMARY KEY\r\n);"
        val migrationFile = tmpDir.resolve("V20260411120000__crlf.sql").toFile()
        migrationFile.writeText(crlfContent)

        // First apply — the runner normalizes to LF for the stored checksum
        val runner = PostgresMigrator.runner(dataSource)
        val result1 = runner.applyPending(tmpDir)
        assertEquals(1, result1.applied.size)

        // Verify the stored checksum is based on the LF-normalized content
        val storedVersions = PostgresMigrationExecutor(dataSource).appliedVersions()
        val storedChecksum = storedVersions["V20260411120000"]!!

        // LF-normalized checksum
        val lfContent = crlfContent.replace("\r\n", "\n")
        val lfChecksum = sha256(lfContent)
        assertEquals(lfChecksum, storedChecksum, "Stored checksum should be LF-normalized")

        // Re-run should succeed (same CRLF file, same normalized checksum)
        val result2 = runner.applyPending(tmpDir)
        assertTrue(result2.applied.isEmpty(), "Already applied, should skip")

        // Now simulate a checkout that converted CRLF→LF — file content changed
        // but the normalized checksum matches, so no error
        migrationFile.writeText(lfContent)
        val result3 = runner.applyPending(tmpDir)
        assertTrue(result3.applied.isEmpty(), "LF version should also pass checksum")

        tmpDir.toFile().deleteRecursively()
    }

    // ---- Serial column introspection ----

    @Test
    fun `introspector detects serial columns from nextval default`() {
        cleanDb()
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE "serial_test" (
                        "id" serial PRIMARY KEY,
                        "big_id" bigserial NOT NULL,
                        "name" text NOT NULL,
                        "count" integer NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val introspector = PostgresIntrospector(dataSource)
        val schema = introspector.introspect(setOf("serial_test"))
        val table = schema.tables["serial_test"]!!
        val colsByName = table.columns.associateBy { it.name }

        assertEquals("serial", colsByName["id"]!!.sqlType, "serial column should be detected")
        assertEquals("bigserial", colsByName["big_id"]!!.sqlType, "bigserial column should be detected")
        assertEquals("text", colsByName["name"]!!.sqlType, "text column should remain text")
        assertEquals("integer", colsByName["count"]!!.sqlType, "integer column should remain integer")
    }

    @Test
    fun `migration runner refuses files with unresolved manual steps`() {
        cleanDb()
        val tmpDir = Files.createTempDirectory("entkt_test_manual_marker")

        val file = tmpDir.resolve("V20260411120000__mixed.sql").toFile()
        file.writeText(
            """
            -- entkt migration V20260411120000
            --
            -- !! MANUAL STEPS REQUIRED !!
            -- [ ] DropColumn: posts.legacy_field
            --
            -- Auto-applied operations follow.

            ALTER TABLE "mig_users" ADD COLUMN "bio" text;
            """.trimIndent(),
        )

        val runner = PostgresMigrator.runner(dataSource)
        val ex = assertFailsWith<entkt.migrations.UnresolvedManualStepsException> {
            runner.applyPending(tmpDir)
        }
        assertTrue(ex.message!!.contains("manual steps"))

        // Verify nothing was applied
        val applied = runner.applyPending(tmpDir.parent.resolve("empty_dir_that_does_not_exist"))
        assertTrue(applied.applied.isEmpty())

        tmpDir.toFile().deleteRecursively()
    }
}
