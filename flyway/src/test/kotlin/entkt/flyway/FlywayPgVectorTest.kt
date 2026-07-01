package entkt.flyway

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.IndexMetadata
import entkt.schema.ColumnStorage
import entkt.schema.FieldType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Flyway shadow-workflow coverage for pgvector schemas. The default shadow
 * image is pgvector-capable, so a `vector(n)` schema's `CREATE EXTENSION` and
 * hnsw index apply and round-trip; an explicitly plain Postgres image fails
 * the preflight with an actionable message.
 */
class FlywayPgVectorTest {

    private val vectorSchema = EntitySchema(
        table = "fw_vec",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata(
                "embedding", FieldType.PGVECTOR, nullable = true,
                storage = ColumnStorage.Native("postgres", "vector", "vector(3)", "postgres.vector", "vector", 3),
            ),
        ),
        edges = emptyMap(),
        indexes = listOf(
            IndexMetadata(
                columns = listOf("embedding"), name = "idx_fw_vec_hnsw",
                using = "hnsw", opclasses = listOf("vector_cosine_ops"),
            ),
        ),
    )

    private fun tempDir(): Path = Files.createTempDirectory("entkt_flyway_pgvec")

    @Test
    fun `default shadow image generates and verifies a vector schema migration`() {
        val dir = tempDir()
        try {
            val result = FlywayMigrationWorkflow().generate(
                schemas = listOf(vectorSchema),
                migrationsDir = dir,
                description = "vectors",
                verify = true,
            )
            val written = result as GenerateResult.Written
            // verify=true re-applies the migration and re-diffs — passing proves
            // the vector(n) column + hnsw index round-trip with no spurious drift.
            assertEquals(VerificationOutcome.Verified, written.verification)

            val sql = written.filePath.toFile().readText()
            assertTrue("CREATE EXTENSION IF NOT EXISTS \"vector\"" in sql, sql)
            assertTrue("vector(3)" in sql, sql)
            assertTrue("USING hnsw" in sql && "vector_cosine_ops" in sql, sql)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a plain Postgres shadow image fails the preflight with an actionable message`() {
        val dir = tempDir()
        try {
            val workflow = FlywayMigrationWorkflow(ShadowDockerConfig(image = "postgres:16-alpine"))
            val ex = assertFailsWith<IllegalStateException> {
                workflow.generate(schemas = listOf(vectorSchema), migrationsDir = dir, description = "vectors")
            }
            assertTrue("vector" in ex.message!!, ex.message ?: "")
            assertTrue("postgres:16-alpine" in ex.message!!, ex.message ?: "")
            // No migration file should have been written.
            assertTrue(dir.toFile().listFiles()?.none { it.name.endsWith(".sql") } ?: true)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
