package entkt.postgres.vector

import entkt.postgres.PostgresDriver
import entkt.runtime.ColumnMetadata
import entkt.runtime.Driver
import entkt.runtime.EntitySchema
import entkt.runtime.IdStrategy
import entkt.runtime.NoopDriver
import entkt.runtime.UnsupportedDriverCapabilityException
import entkt.schema.ColumnStorage
import entkt.schema.FieldType
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Driver-level bind/decode/DDL coverage for pgvector against a real
 * pgvector-enabled Postgres (RFC "Native Database Column Types", Phase 4).
 * Uses a hand-built [EntitySchema] carrying [ColumnStorage.Native]; the
 * generated-entity end-to-end path is exercised in the integration-tests module.
 */
@Testcontainers
class PgVectorPostgresDriverTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"),
            )
    }

    private val dataSource: DataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private val vecSchema = EntitySchema(
        table = "vec_items",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
            ColumnMetadata(
                "embedding", FieldType.PGVECTOR, nullable = true,
                storage = ColumnStorage.Native("postgres", "vector", "vector(3)", "postgres.vector", "vector", 3),
            ),
        ),
        edges = emptyMap(),
    )

    private fun fresh(): PostgresDriver {
        // Phase 5 emits CREATE EXTENSION automatically; here we install it
        // manually before autoDdl creates the vector(3) column.
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("CREATE EXTENSION IF NOT EXISTS vector") }
        }
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.register(vecSchema)
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE TABLE \"vec_items\" RESTART IDENTITY") }
        }
        return driver
    }

    @Test
    fun `round-trips a non-null vector via bind and decode`() {
        val driver = fresh()
        val v = PgVector.of(floatArrayOf(1f, 2f, 3f))
        val row = driver.insert("vec_items", mapOf("name" to "a", "embedding" to v))
        assertEquals(v, row["embedding"], "insert RETURNING * decodes the vector")
        val back = driver.byId("vec_items", row["id"]!!)!!
        assertEquals(v, back["embedding"], "byId decodes the vector")
    }

    @Test
    fun `round-trips a null vector`() {
        val driver = fresh()
        val row = driver.insert("vec_items", mapOf("name" to "a", "embedding" to null))
        assertNull(row["embedding"])
        assertNull(driver.byId("vec_items", row["id"]!!)!!["embedding"])
    }

    @Test
    fun `wrong-dimension vector is rejected by the vector(n) column`() {
        val driver = fresh()
        assertFailsWith<Exception> {
            driver.insert("vec_items", mapOf("name" to "a", "embedding" to PgVector.of(floatArrayOf(1f, 2f, 3f, 4f))))
        }
    }

    @Test
    fun `a non-Postgres driver rejects a vector schema at register`() {
        val noVector = object : Driver by NoopDriver {
            override fun supportsNativeStorage(codec: String): Boolean = false
            override fun register(schema: EntitySchema) = checkNativeStorageSupported(schema)
        }
        val ex = assertFailsWith<UnsupportedDriverCapabilityException> { noVector.register(vecSchema) }
        assertTrue("postgres.vector" in ex.message!!, ex.message ?: "")
        assertTrue("vec_items.embedding" in ex.message!!, ex.message ?: "")
    }
}
