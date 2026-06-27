package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.postgres.PostgresDriver
import entkt.runtime.EntConstraintViolationException
import entkt.runtime.EntError
import entkt.runtime.EntOperation
import entkt.runtime.EntResult
import entkt.runtime.EntityPolicy
import entkt.runtime.PrivacyContext
import entkt.runtime.PrivacyDecision
import entkt.runtime.Viewer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * end-to-end coverage that PostgresDriver's
 * `classifyException` (SQLSTATE 23xxx → ConstraintViolation)
 * actually triggers in real generated *OrError paths against real
 * Postgres. The `PostgresDriverClassifyTest` unit test in :postgres
 * synthesizes PSQLException values; this suite confirms the same
 * mapping fires when Postgres itself raises the violation.
 *
 * Pins the SQLSTATE-to-error mapping for unique/FK/check violations:
 * each test asserts the EntError.ConstraintViolation
 * carries `code` == the SQLSTATE and (where Postgres surfaces it)
 * `constraint` populated from ServerErrorMessage.
 */
@Testcontainers
class SqlstateConstraintMappingPostgresIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
    }

    private val dataSource: DataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private fun seedSchemas() {
        val driver = PostgresDriver(dataSource, autoDdl = true)
        EntClient.SCHEMAS.forEach(driver::register)
    }

    private object AllowAllArticles : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    private fun freshClient(): EntClient {
        val driver = PostgresDriver(dataSource)
        seedSchemas()

        val tables = EntClient.SCHEMAS.joinToString(", ") { "\"${it.table}\"" }
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE $tables RESTART IDENTITY CASCADE")
            }
        }

        return EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
        }
    }

    // ---- SQLSTATE 23505: unique violation ----

    @Test
    fun `unique constraint violation surfaces as Err(ConstraintViolation) with code 23505`() {
        val client = freshClient()

        // User.email is unique on the schema. Insert two rows with
        // the same email — second one trips the unique index.
        client.users.create { name = "A"; email = "dup@example.com" }.saveOrThrow()

        val result = client.users.create {
            name = "B"
            email = "dup@example.com"
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.ConstraintViolation)
        assertEquals("User", error.entity)
        assertEquals(EntOperation.CREATE, error.operation)
        assertEquals("23505", error.code)
        // PSQLException carries the unique-index name on
        // ServerErrorMessage.constraint — verify the classifier
        // surfaces it on the EntError.
        assertNotNull(error.constraint)
        assertTrue(error.constraint!!.contains("email"), "constraint should mention email: ${error.constraint}")
    }

    @Test
    fun `unique constraint violation on saveOrThrow surfaces as EntConstraintViolationException`() {
        val client = freshClient()
        client.users.create { name = "A"; email = "dup2@example.com" }.saveOrThrow()

        val ex = assertFailsWith<EntConstraintViolationException> {
            client.users.create { name = "B"; email = "dup2@example.com" }.saveOrThrow()
        }
        assertEquals("User", ex.constraintViolation.entity)
        assertEquals("23505", ex.constraintViolation.code)
        // The underlying PSQLException isn't preserved on the structured
        // ConstraintViolation by design — only on DriverFailure. The
        // structured constraint/field/code metadata is the intended
        // public surface here.
    }

    // ---- SQLSTATE 23503: foreign key violation ----

    @Test
    fun `foreign key violation surfaces as Err(ConstraintViolation) with code 23503`() {
        val client = freshClient()

        // Article.authorId references User.id. Insert an article
        // with a non-existent author id — trips the FK constraint
        // declared by the belongsTo edge.
        val result = client.articles.create {
            title = "Orphan"
            published = true
            authorId = 999_999L
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.ConstraintViolation)
        assertEquals("Article", error.entity)
        assertEquals(EntOperation.CREATE, error.operation)
        assertEquals("23503", error.code)
        assertNotNull(error.constraint)
    }

    @Test
    fun `foreign key violation on update surfaces as Err(ConstraintViolation) with code 23503`() {
        val client = freshClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
        val article = client.articles.create {
            title = "Hello"
            published = true
            authorId = author.id
        }.saveOrThrow()

        // Repoint authorId to a non-existent user — trips the FK
        // on the UPDATE path.
        val result = client.articles.update(article.id) {
            authorId = 999_999L
        }.saveOrError()

        assertTrue(result is EntResult.Err)
        val error = result.error
        assertTrue(error is EntError.ConstraintViolation)
        assertEquals("Article", error.entity)
        assertEquals(EntOperation.UPDATE, error.operation)
        assertEquals("23503", error.code)
    }

    // ---- byIdOrError driver-side classification ----

    @Test
    fun `byIdOrError returns Err(NotFound) — no constraint violation possible on read`() {
        // Sanity check: read operations don't typically trip 23xxx
        // (SELECT against an absent id returns no rows, not a
        // constraint failure). Verifies the read path still works on
        // real Postgres without false-positive classifications.
        val client = freshClient()

        val result = client.users.byIdOrError(999_999L)
        assertTrue(result is EntResult.Err)
        assertTrue(result.error is EntError.NotFound)
    }
}
