package entkt.postgres.vector

import entkt.postgres.PostgresDriver
import entkt.query.Column
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.ColumnMetadata
import entkt.runtime.Driver
import entkt.runtime.EntitySchema
import entkt.runtime.IdStrategy
import entkt.runtime.IndexMetadata
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
    fun `raw driver insert with a wrong-dimension vector fails with a field-named entkt error`() {
        val driver = fresh()
        // The column is vector(3); a 4-dim vector must be rejected by the driver
        // (field-named) before it reaches Postgres' vector(n) backstop.
        val ex = assertFailsWith<IllegalArgumentException> {
            driver.insert("vec_items", mapOf("name" to "a", "embedding" to PgVector.of(floatArrayOf(1f, 2f, 3f, 4f))))
        }
        assertTrue("vec_items.embedding" in ex.message!!, ex.message ?: "")
        assertTrue("3" in ex.message!! && "4" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `raw driver update with a wrong-dimension vector fails with a field-named entkt error`() {
        val driver = fresh()
        val row = driver.insert("vec_items", mapOf("name" to "a", "embedding" to PgVector.of(floatArrayOf(1f, 2f, 3f))))
        val ex = assertFailsWith<IllegalArgumentException> {
            driver.update("vec_items", row["id"]!!, mapOf("embedding" to PgVector.of(floatArrayOf(1f, 2f))))
        }
        assertTrue("vec_items.embedding" in ex.message!!, ex.message ?: "")
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

    @Test
    fun `orderBy cosineDistance helper returns rows nearest-first`() {
        val driver = fresh()
        // Three rows at increasing cosine distance from the query [1,0,0]:
        //   a [1,0,0]  -> sim  1 -> distance 0   (nearest)
        //   b [0,1,0]  -> sim  0 -> distance 1
        //   c [-1,0,0] -> sim -1 -> distance 2   (farthest)
        driver.insert("vec_items", mapOf("name" to "a", "embedding" to PgVector.of(floatArrayOf(1f, 0f, 0f))))
        driver.insert("vec_items", mapOf("name" to "b", "embedding" to PgVector.of(floatArrayOf(0f, 1f, 0f))))
        driver.insert("vec_items", mapOf("name" to "c", "embedding" to PgVector.of(floatArrayOf(-1f, 0f, 0f))))

        val query = PgVector.of(floatArrayOf(1f, 0f, 0f))
        val order = Column<Any, PgVector>("embedding").cosineDistance(query).asc()
        val rows = driver.query("vec_items", emptyList(), listOf(order), null, null)

        assertEquals(listOf("a", "b", "c"), rows.map { it["name"] }, "ascending cosine distance orders nearest-first")
    }

    @Test
    fun `orderBy distance composes with a WHERE filter and keeps placeholder order`() {
        val driver = fresh()
        driver.insert("vec_items", mapOf("name" to "keep", "embedding" to PgVector.of(floatArrayOf(0f, 1f, 0f))))
        driver.insert("vec_items", mapOf("name" to "keep", "embedding" to PgVector.of(floatArrayOf(1f, 0f, 0f))))
        driver.insert("vec_items", mapOf("name" to "drop", "embedding" to PgVector.of(floatArrayOf(1f, 0f, 0f))))

        val query = PgVector.of(floatArrayOf(1f, 0f, 0f))
        val order = Column<Any, PgVector>("embedding").l2Distance(query).asc()
        // WHERE name = 'keep' adds a leading param; the vector operand must bind
        // to the trailing placeholder in ORDER BY, not collide with the filter.
        val rows = driver.query(
            "vec_items",
            listOf(Predicate.Leaf<Any>("name", Op.EQ, "keep")),
            listOf(order),
            null,
            null,
        )

        assertEquals(2, rows.size, "filter keeps only matching rows")
        assertEquals(
            PgVector.of(floatArrayOf(1f, 0f, 0f)),
            rows.first()["embedding"],
            "nearest matching row comes first",
        )
    }

    @Test
    fun `orderBy distance with a wrong-dimension query vector fails with a field-named entkt error`() {
        val driver = fresh()
        driver.insert("vec_items", mapOf("name" to "a", "embedding" to PgVector.of(floatArrayOf(1f, 0f, 0f))))

        // The column is vector(3); a 2-dim query vector must be rejected at the
        // query builder (field-named) rather than as an opaque Postgres error.
        val order = Column<Any, PgVector>("embedding").cosineDistance(PgVector.of(floatArrayOf(1f, 0f))).asc()
        val ex = assertFailsWith<IllegalArgumentException> {
            driver.query("vec_items", emptyList(), listOf(order), null, null)
        }
        assertTrue("embedding" in ex.message!!, ex.message ?: "")
        assertTrue("3" in ex.message!! && "2" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `autoDdl creates the extension, vector column, and a real hnsw index`() {
        val schema = EntitySchema(
            table = "vec_indexed",
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
                    columns = listOf("embedding"), name = "idx_vec_indexed_hnsw",
                    using = "hnsw", opclasses = listOf("vector_cosine_ops"),
                ),
            ),
        )
        // Start clean so autoDdl runs the full CREATE EXTENSION + CREATE TABLE + index path.
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("DROP TABLE IF EXISTS \"vec_indexed\"") }
        }
        // A fresh driver (no in-memory cache) so register() runs autoDdl.
        PostgresDriver(dataSource, autoDdl = true).register(schema)

        val indexDef = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_vec_indexed_hnsw'").use { st ->
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
        assertTrue(indexDef != null, "autoDdl must have created the hnsw index")
        assertTrue("hnsw" in indexDef!!, indexDef)
        assertTrue("vector_cosine_ops" in indexDef, indexDef)
    }
}
