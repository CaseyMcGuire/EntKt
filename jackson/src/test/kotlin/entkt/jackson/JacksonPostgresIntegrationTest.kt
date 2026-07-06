package entkt.jackson

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import entkt.postgres.PostgresDriver
import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.JsonColumnMetadata
import entkt.runtime.driver.JsonMapperIds
import entkt.schema.FieldType
import kotlinx.serialization.builtins.serializer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end: the Postgres driver with a [JacksonJsonCodec] round-trips
 * typed JSON columns (plain and generic) built from Jackson-mapper metadata
 * — no kotlinx.serialization involvement — plus both directions of the
 * register()-time generate/configure mismatch check.
 */
@Testcontainers
class JacksonPostgresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
    }

    private val dataSource: DataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl); user = postgres.username; password = postgres.password
        }
    }

    private val jacksonSchema = EntitySchema(
        table = "jackson_docs",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
            ColumnMetadata(
                "rect", FieldType.JSON, nullable = true,
                json = JsonColumnMetadata(
                    klass = Rect::class,
                    kType = typeOf<Rect>(),
                    typeName = "entkt.jackson.Rect",
                    mapper = JsonMapperIds.JACKSON,
                ),
            ),
            ColumnMetadata(
                "rects", FieldType.JSON, nullable = true,
                json = JsonColumnMetadata(
                    klass = List::class,
                    kType = typeOf<List<Rect>>(),
                    typeName = "kotlin.collections.List<entkt.jackson.Rect>",
                    mapper = JsonMapperIds.JACKSON,
                ),
            ),
        ),
        edges = emptyMap(),
    )

    private fun fresh(): PostgresDriver {
        val driver = PostgresDriver(dataSource, autoDdl = true, jsonCodec = JacksonJsonCodec(jacksonObjectMapper()))
        driver.register(jacksonSchema)
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE TABLE \"jackson_docs\" RESTART IDENTITY") }
        }
        return driver
    }

    @Test
    fun `round-trips plain and generic JSON columns through Jackson`() {
        val driver = fresh()
        val rect = Rect(1, 0.25, 0.75)
        val rects = listOf(Rect(1, 0.1, 0.2), Rect(2, 0.3, 0.4))

        val row = driver.insert("jackson_docs", mapOf("name" to "a", "rect" to rect, "rects" to rects))
        assertEquals(rect, row["rect"], "insert RETURNING * decodes the value through Jackson")
        assertEquals(rects, row["rects"], "generic elements decode typed, not as maps")

        val back = driver.byId("jackson_docs", row["id"]!!)!!
        assertEquals(rect, back["rect"])
        assertEquals(rects, back["rects"])
    }

    @Test
    fun `null JSON values round-trip without reaching the codec`() {
        val driver = fresh()
        val row = driver.insert("jackson_docs", mapOf("name" to "a", "rect" to null, "rects" to null))
        assertNull(row["rect"])
        assertNull(driver.byId("jackson_docs", row["id"]!!)!!["rects"])
    }

    @Test
    fun `kotlinx-generated metadata is rejected by a Jackson-configured driver at register`() {
        val kotlinxSchema = EntitySchema(
            table = "kotlinx_docs",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata(
                    "meta", FieldType.JSON, nullable = true,
                    json = JsonColumnMetadata(
                        klass = String::class,
                        kType = typeOf<String>(),
                        typeName = "kotlin.String",
                        mapper = JsonMapperIds.KOTLINX,
                        kotlinxSerializer = String.serializer(),
                    ),
                ),
            ),
            edges = emptyMap(),
        )
        val driver = PostgresDriver(dataSource, jsonCodec = JacksonJsonCodec(jacksonObjectMapper()))
        val ex = assertFailsWith<IllegalStateException> { driver.register(kotlinxSchema) }
        assertTrue("kotlinx_docs.meta" in ex.message!!, ex.message ?: "")
        assertTrue("'kotlinx'" in ex.message!!, "should name the generated mapper: ${ex.message}")
        assertTrue("'jackson'" in ex.message!!, "should name the configured codec: ${ex.message}")
    }

    @Test
    fun `jackson-generated metadata is rejected by the default kotlinx driver at register`() {
        val driver = PostgresDriver(dataSource) // default KotlinxJsonCodec
        val ex = assertFailsWith<IllegalStateException> { driver.register(jacksonSchema) }
        assertTrue("jackson_docs" in ex.message!!, ex.message ?: "")
        assertTrue("'jackson'" in ex.message!!, ex.message ?: "")
        assertTrue("'kotlinx'" in ex.message!!, ex.message ?: "")
    }
}
