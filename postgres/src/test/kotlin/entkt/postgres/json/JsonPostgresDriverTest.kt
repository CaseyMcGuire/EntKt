package entkt.postgres.json

import entkt.postgres.PostgresDriver
import entkt.runtime.ColumnMetadata
import entkt.runtime.Driver
import entkt.runtime.EntitySchema
import entkt.runtime.IdStrategy
import entkt.runtime.JsonColumnMetadata
import entkt.runtime.NoopDriver
import entkt.runtime.UnsupportedDriverCapabilityException
import entkt.schema.FieldType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
data class Meta(val nickname: String?, val tags: List<String>)

/**
 * Driver-level bind/decode/DDL/capability coverage for typed JSON columns
 * against a real Postgres. Uses a hand-built
 * [EntitySchema] carrying [JsonColumnMetadata]; `jsonb` needs no extension, so a
 * plain Postgres image suffices.
 */
@Testcontainers
class JsonPostgresDriverTest {

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

    private val jsonSchema = EntitySchema(
        table = "json_items",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
            ColumnMetadata(
                "metadata", FieldType.JSON, nullable = true,
                json = JsonColumnMetadata(Meta::class, Meta.serializer()),
            ),
        ),
        edges = emptyMap(),
    )

    private fun fresh(json: Json = Json.Default): PostgresDriver {
        val driver = PostgresDriver(dataSource, autoDdl = true, json = json)
        driver.register(jsonSchema)
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE TABLE \"json_items\" RESTART IDENTITY") }
        }
        return driver
    }

    @Test
    fun `round-trips a non-null JSON value via bind and decode`() {
        val driver = fresh()
        val meta = Meta(nickname = "Mochi", tags = listOf("senior", "cat"))
        val row = driver.insert("json_items", mapOf("name" to "a", "metadata" to meta))
        assertEquals(meta, row["metadata"], "insert RETURNING * decodes the JSON")
        val back = driver.byId("json_items", row["id"]!!)!!
        assertEquals(meta, back["metadata"], "byId decodes the JSON")
    }

    @Test
    fun `round-trips a null JSON value`() {
        val driver = fresh()
        val row = driver.insert("json_items", mapOf("name" to "a", "metadata" to null))
        assertNull(row["metadata"])
        assertNull(driver.byId("json_items", row["id"]!!)!!["metadata"])
    }

    @Test
    fun `autoDdl creates a jsonb column`() {
        fresh()
        val sqlType = dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT data_type FROM information_schema.columns " +
                    "WHERE table_name = 'json_items' AND column_name = 'metadata'",
            ).use { st -> st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }
        }
        assertEquals("jsonb", sqlType)
    }

    @Test
    fun `a non-Postgres driver rejects a typed JSON schema at register`() {
        val noJson = object : Driver by NoopDriver {
            override fun supportsTypedJson(): Boolean = false
            override fun register(schema: EntitySchema) = checkTypedJsonSupported(schema)
        }
        val ex = assertFailsWith<UnsupportedDriverCapabilityException> { noJson.register(jsonSchema) }
        assertTrue("json_items.metadata" in ex.message!!, ex.message ?: "")
        assertTrue("typed JSON" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `registering a JSON column without serializer metadata fails clearly`() {
        val noMeta = EntitySchema(
            table = "json_no_meta",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                // FieldType.JSON but json = null — must be rejected at register, not
                // deferred to first write/read.
                ColumnMetadata("metadata", FieldType.JSON, nullable = true),
            ),
            edges = emptyMap(),
        )
        val ex = assertFailsWith<IllegalStateException> {
            PostgresDriver(dataSource).register(noMeta)
        }
        assertTrue("json_no_meta.metadata" in ex.message!!, ex.message ?: "")
        assertTrue("JsonColumnMetadata" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `a wrong-type JSON write fails with a field-named entkt error`() {
        val driver = fresh()
        val ex = assertFailsWith<IllegalStateException> {
            driver.insert("json_items", mapOf("name" to "a", "metadata" to "not a Meta"))
        }
        assertTrue("json_items.metadata" in ex.message!!, ex.message ?: "")
        assertTrue("Meta" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `decode failure includes table and column context`() {
        val driver = fresh()
        // Write a jsonb document that doesn't satisfy Meta (missing required tags).
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("INSERT INTO \"json_items\" (name, metadata) VALUES ('x', '{\"nickname\": null}'::jsonb)")
            }
        }
        val ex = assertFailsWith<IllegalStateException> {
            driver.query("json_items", emptyList(), emptyList(), null, null)
        }
        assertTrue("json_items.metadata" in ex.message!!, ex.message ?: "")
        assertTrue("Meta" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `a json schema round-trips through introspection with no drift`() {
        fresh() // autoDdl creates the jsonb table
        val typeMapper = entkt.postgres.PostgresTypeMapper()
        val desired = entkt.migrations.NormalizedSchema.fromEntitySchemas(listOf(jsonSchema), typeMapper)
        val current = entkt.postgres.PostgresIntrospector(dataSource).introspect(setOf("json_items"))

        // Sanity: the metadata column introspects as jsonb.
        assertEquals(
            "jsonb",
            current.tables.getValue("json_items").columns.first { it.name == "metadata" }.sqlType,
        )
        val diff = entkt.migrations.SchemaDiffer().diff(desired, current)
        assertTrue(diff.ops.isEmpty(), "expected no auto ops, got: ${diff.ops}")
        assertTrue(diff.manual.isEmpty(), "expected no manual ops, got: ${diff.manual}")
    }

    @Test
    fun `configured Json (ignoreUnknownKeys) decodes documents with extra keys`() {
        // Default Json rejects the extra key; a configured driver accepts it.
        dataSource.connection.use { conn ->
            PostgresDriver(dataSource, autoDdl = true).register(jsonSchema)
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE \"json_items\" RESTART IDENTITY")
                it.execute(
                    "INSERT INTO \"json_items\" (name, metadata) VALUES " +
                        "('x', '{\"nickname\": \"Mochi\", \"tags\": [\"cat\"], \"extra\": 1}'::jsonb)",
                )
            }
        }
        val lenient = PostgresDriver(dataSource, json = Json { ignoreUnknownKeys = true })
        lenient.register(jsonSchema)
        val rows = lenient.query("json_items", emptyList(), emptyList(), null, null)
        assertEquals(Meta("Mochi", listOf("cat")), rows.single()["metadata"])
    }
}
