package entkt.postgres.vector

import entkt.codegen.SchemaInput
import entkt.codegen.buildEntitySchemas
import entkt.migrations.NormalizedSchema
import entkt.migrations.SchemaDiffer
import entkt.postgres.PostgresDriver
import entkt.postgres.PostgresIntrospector
import entkt.postgres.PostgresTypeMapper
import entkt.schema.EntId
import entkt.schema.EntSchema
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RtArticle : EntSchema("rt_articles", clientName = "rtArticles") {
    override fun id() = EntId.long()
    val embedding by postgresVector("embedding", 3).nullable()
    val hnsw = postgresVectorIndex("idx_rt_articles_hnsw", embedding).hnsw(VectorMetric.Cosine)
}

private class RtIvf : EntSchema("rt_ivf", clientName = "rtIvfs") {
    override fun id() = EntId.long()
    val embedding by postgresVector("embedding", 3).nullable()
    val ivf = postgresVectorIndex("idx_rt_ivf", embedding).ivfflat(VectorMetric.L2, lists = 100)
}

/**
 * Proves a pgvector schema round-trips through live introspection: after
 * autoDdl applies the vector column + hnsw/ivfflat indexes, re-introspecting
 * and diffing against the desired schema must yield zero ops — no spurious
 * AlterColumnType (vector(3) vs USER-DEFINED) and no DropIndex/AddIndex churn.
 * This is the regression guard for the deferred-read-back drift (Fix C).
 */
@Testcontainers
class PgVectorIntrospectRoundTripTest {

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
            setURL(postgres.jdbcUrl); user = postgres.username; password = postgres.password
        }
    }

    private val typeMapper = PostgresTypeMapper()

    private fun desiredSchema(vararg schemas: EntSchema): NormalizedSchema =
        NormalizedSchema.fromEntitySchemas(
            buildEntitySchemas(schemas.map { SchemaInput(it) }),
            typeMapper,
        )

    @Test
    fun `a pgvector schema round-trips through introspection with no drift`() {
        val tables = setOf("rt_articles", "rt_ivf")
        // Clean slate, then apply the schema via autoDdl (extension + tables +
        // vector columns + hnsw/ivfflat indexes).
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS \"rt_articles\"")
                st.execute("DROP TABLE IF EXISTS \"rt_ivf\"")
            }
        }
        val runtimeSchemas = buildEntitySchemas(
            listOf(SchemaInput(RtArticle()), SchemaInput(RtIvf())),
        )
        val driver = PostgresDriver(dataSource, autoDdl = true)
        runtimeSchemas.forEach { driver.register(it) }

        val current = PostgresIntrospector(dataSource).introspect(tables)

        // Sanity: introspection reconstructed the native column + index metadata.
        val art = current.tables.getValue("rt_articles")
        assertEquals("vector(3)", art.columns.first { it.name == "embedding" }.sqlType)
        val hnsw = art.indexes.single { it.columns == listOf("embedding") }
        assertEquals("hnsw", hnsw.using)
        assertEquals(listOf("vector_cosine_ops"), hnsw.opclasses)
        val ivf = current.tables.getValue("rt_ivf").indexes.single { it.columns == listOf("embedding") }
        assertEquals("ivfflat", ivf.using)
        assertEquals(listOf("vector_l2_ops"), ivf.opclasses)
        assertEquals(mapOf("lists" to "100"), ivf.with)

        // The actual round-trip assertion: desired vs introspected → no drift.
        val desired = desiredSchema(RtArticle(), RtIvf())
        val diff = SchemaDiffer().diff(desired, current)
        assertTrue(diff.ops.isEmpty(), "expected no auto ops, got: ${diff.ops}")
        assertTrue(diff.manual.isEmpty(), "expected no manual ops, got: ${diff.manual}")
    }
}
