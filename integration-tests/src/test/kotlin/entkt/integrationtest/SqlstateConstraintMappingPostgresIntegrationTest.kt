package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.postgres.PostgresDriver
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntConstraintViolationException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.ReadResult
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end coverage that PostgresDriver's `classifyException`
 * (SQLSTATE 23xxx → EntConstraintViolationException) actually fires in
 * real generated mutation terminals against real Postgres. The
 * `PostgresDriverClassifyTest` unit test in :postgres synthesizes
 * PSQLException values; this suite confirms the same mapping when
 * Postgres itself raises the violation.
 *
 * Pins the structured surface: `driverCode` == the SQLSTATE,
 * `constraint` populated from ServerErrorMessage where Postgres
 * surfaces it, the original PSQLException preserved as `cause`, and
 * `writeState` hardcoding NotPersisted for the recognized statement-
 * level rejection.
 *
 * A 40001/40P01 serialization-conflict test (EntConflictException) is
 * deliberately absent: reproducing a genuine serialization failure
 * requires racing concurrent SERIALIZABLE transactions and is not
 * cheaply deterministic here; the mapping is unit-pinned in
 * `PostgresDriverClassifyTest`.
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
        driver.registerAll(EntClient.SCHEMAS)
    }

    private object AllowAllArticles : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
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
    fun `unique violation is Failed(EntConstraintViolationException) with driverCode 23505 and PSQLException cause`() {
        val client = freshClient()

        // User.email is unique on the schema. Insert two rows with
        // the same email — the second trips the unique index.
        client.users.create { name = "A"; email = "dup@example.com" }.save().getOrThrow()

        val result = client.users.create {
            name = "B"
            email = "dup@example.com"
        }.save()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals("User", ex.entityType)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("23505", ex.driverCode)
        // PSQLException carries the unique-index name on
        // ServerErrorMessage.constraint.
        assertNotNull(ex.constraint)
        assertTrue(ex.constraint!!.contains("email"), "constraint should mention email: ${ex.constraint}")
        // The original driver exception is preserved as the cause.
        val cause = assertIs<PSQLException>(ex.cause)
        assertEquals("23505", cause.sqlState)
        // `field` mirrors ServerErrorMessage.column — Postgres does not
        // populate it for unique violations, so no value is pinned here.
    }

    @Test
    fun `getOrThrow throws the exact stored constraint exception`() {
        val client = freshClient()
        client.users.create { name = "A"; email = "dup2@example.com" }.save().getOrThrow()

        val result = client.users.create { name = "B"; email = "dup2@example.com" }.saveAndLoad()
        val failed = assertIs<MutationResult.Failed>(result)
        try {
            result.getOrThrow()
            throw AssertionError("expected getOrThrow to throw")
        } catch (e: EntConstraintViolationException) {
            assertSame(failed.exception, e)
            assertEquals("23505", e.driverCode)
            assertIs<PSQLException>(e.cause)
        }
    }

    // ---- SQLSTATE 23503: foreign key violation ----

    @Test
    fun `foreign key violation is Failed(EntConstraintViolationException) with driverCode 23503`() {
        val client = freshClient()

        // Article.authorId references User.id. Insert an article with a
        // non-existent author id — trips the FK constraint declared by
        // the belongsTo edge.
        val result = client.articles.create {
            title = "Orphan"
            published = true
            authorId = 999_999L
        }.save()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals("Article", ex.entityType)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals("23503", ex.driverCode)
        assertNotNull(ex.constraint)
        assertIs<PSQLException>(ex.cause)
    }

    @Test
    fun `foreign key violation on update carries operation UPDATE and driverCode 23503`() {
        val client = freshClient()
        val author = client.users.create { name = "A"; email = "a@example.com" }
            .saveAndLoad().getOrThrow()
        val article = client.articles.create {
            title = "Hello"
            published = true
            authorId = author.id
        }.saveAndLoad().getOrThrow()

        // Repoint authorId to a non-existent user — trips the FK on the
        // UPDATE path.
        val result = client.articles.update(article.id) {
            authorId = 999_999L
        }.save()

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals("Article", ex.entityType)
        assertEquals(EntOperation.UPDATE, ex.operation)
        assertEquals("23503", ex.driverCode)
        assertIs<PSQLException>(ex.cause)
    }

    // ---- Read-path sanity ----

    @Test
    fun `findById of an absent row is Success(null) — no false constraint classification on reads`() {
        // Read operations don't trip 23xxx (SELECT against an absent id
        // returns no rows). Verifies the read path works on real
        // Postgres without false-positive classifications.
        val client = freshClient()

        val result = client.users.findById(999_999L)
        val success = assertIs<ReadResult.Success<User?>>(result)
        assertNull(success.value)
    }
}
