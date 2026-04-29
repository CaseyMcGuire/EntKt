package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleCreateValidationRule
import entkt.integrationtest.ent.ArticleDeleteValidationRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.ArticleUpdateValidationRule
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticleCreatePrivacyRule
import entkt.postgres.PostgresDriver
import entkt.runtime.EntityPolicy
import entkt.runtime.PrivacyContext
import entkt.runtime.PrivacyDecision
import entkt.runtime.PrivacyDeniedException
import entkt.runtime.ValidationDecision
import entkt.runtime.ValidationException
import entkt.runtime.Viewer
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---- Validation rules ----

private val RejectUnpublishedTitle = ArticleCreateValidationRule { ctx ->
    if (ctx.candidate.title.startsWith("DRAFT:") && ctx.candidate.published) {
        ValidationDecision.Invalid("articles with DRAFT: prefix cannot be published", field = "title")
    } else {
        ValidationDecision.Valid
    }
}

private val RequireMinTitleLength = ArticleCreateValidationRule { ctx ->
    if (ctx.candidate.title.length < 3) {
        ValidationDecision.Invalid("title must be at least 3 characters", field = "title")
    } else {
        ValidationDecision.Valid
    }
}

private val RejectUnpublishedCreate = ArticleCreateValidationRule { ctx ->
    if (!ctx.candidate.published) {
        ValidationDecision.Invalid("articles must be published on create", field = "published")
    } else {
        ValidationDecision.Valid
    }
}

private val PreventUnpublish = ArticleUpdateValidationRule { ctx ->
    if (ctx.before.published && !ctx.candidate.published) {
        ValidationDecision.Invalid("cannot unpublish a published article")
    } else {
        ValidationDecision.Valid
    }
}

private val CannotDeletePublished = ArticleDeleteValidationRule { ctx ->
    if (ctx.entity.published) {
        ValidationDecision.Invalid("cannot delete a published article")
    } else {
        ValidationDecision.Valid
    }
}

// ---- Privacy rules for combined tests ----

private val AllowAllLoads = ArticleLoadPrivacyRule { PrivacyDecision.Allow }
private val AllowAllUserLoads = UserLoadPrivacyRule { PrivacyDecision.Allow }
private val RequireAuthForCreate = ArticleCreatePrivacyRule { ctx ->
    if (ctx.privacy.viewer is Viewer.Anonymous) PrivacyDecision.Deny("authentication required")
    else PrivacyDecision.Continue
}

// ---- Policies ----

/** Policy with create validation only. */
object ValidatedArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy {
            load(AllowAllLoads)
        }
        validation {
            create(RejectUnpublishedTitle, RequireMinTitleLength)
        }
    }
}

/** Policy with two independently-failing create rules for multi-violation tests. */
object MultiRuleArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy {
            load(AllowAllLoads)
        }
        validation {
            create(RequireMinTitleLength, RejectUnpublishedCreate)
        }
    }
}

/** Policy with create, update, and delete validation. */
object FullyValidatedArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy {
            load(AllowAllLoads)
        }
        validation {
            create(RejectUnpublishedTitle, RequireMinTitleLength)
            update(PreventUnpublish)
            delete(CannotDeletePublished)
        }
    }
}

/** Policy with create validation + updateDerivesFromCreate. */
object DerivedValidationPolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy {
            load(AllowAllLoads)
        }
        validation {
            create(RejectUnpublishedTitle, RequireMinTitleLength)
            updateDerivesFromCreate()
        }
    }
}

/** Policy with both privacy and validation to test ordering. */
object PrivacyBeforeValidationPolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy {
            load(AllowAllLoads)
            create(RequireAuthForCreate)
        }
        validation {
            create(RequireMinTitleLength)
        }
    }
}

object OpenUserPolicy : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy {
            load(AllowAllUserLoads)
        }
    }
}

// ---- Tests ----

@Testcontainers
class ValidationIntegrationTest {

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

    private fun freshClient(
        viewer: Viewer = Viewer.System,
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope> = ValidatedArticlePolicy,
        userPolicy: EntityPolicy<User, UserPolicyScope> = OpenUserPolicy,
    ): EntClient {
        val driver = PostgresDriver(dataSource)
        seedSchemas()

        val tables = EntClient.SCHEMAS.joinToString(", ") { "\"${it.table}\"" }
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE $tables RESTART IDENTITY CASCADE")
            }
        }

        return EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
            policies {
                articles(articlePolicy)
                users(userPolicy)
            }
        }
    }

    private fun seedAuthor(client: EntClient): User {
        return client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.users.create { name = "Alice"; email = "alice@test.com" }.save()
        }
    }

    // ---- CREATE validation ----

    @Test
    fun `create throws ValidationException when rule fails`() {
        val client = freshClient()
        val author = seedAuthor(client)

        val ex = assertFailsWith<ValidationException> {
            client.articles.create {
                title = "DRAFT: My Post"
                published = true
                authorId = author.id
            }.save()
        }
        assertEquals("Article", ex.entity)
        assertEquals(1, ex.violations.size)
        assertEquals("title", ex.violations[0].field)
        assertTrue(ex.violations[0].message.contains("DRAFT:"))
    }

    @Test
    fun `create collects all violations from multiple rules`() {
        val client = freshClient(articlePolicy = MultiRuleArticlePolicy)
        val author = seedAuthor(client)

        val ex = assertFailsWith<ValidationException> {
            client.articles.create {
                title = "AB" // too short (fails RequireMinTitleLength)
                published = false // not published (fails RejectUnpublishedCreate)
                authorId = author.id
            }.save()
        }
        // Both rules fire independently — a fail-fast implementation would only report one.
        assertEquals(2, ex.violations.size)
        assertTrue(ex.violations.any { it.message.contains("at least 3") })
        assertTrue(ex.violations.any { it.message.contains("must be published") })
    }

    @Test
    fun `create succeeds when all validation rules pass`() {
        val client = freshClient()
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Valid Title"
            published = true
            authorId = author.id
        }.save()
        assertEquals("Valid Title", article.title)
    }

    @Test
    fun `create validation does not persist the row on failure`() {
        val client = freshClient()
        val author = seedAuthor(client)

        assertFailsWith<ValidationException> {
            client.articles.create {
                title = "AB"
                published = false
                authorId = author.id
            }.save()
        }

        val count = client.articles.query().rawCount()
        assertEquals(0L, count)
    }

    // ---- UPDATE validation ----

    @Test
    fun `update throws ValidationException when rule fails`() {
        val client = freshClient(articlePolicy = FullyValidatedArticlePolicy)
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Published"
            published = true
            authorId = author.id
        }.save()

        val ex = assertFailsWith<ValidationException> {
            client.articles.update(article) { published = false }.save()
        }
        assertTrue(ex.message!!.contains("cannot unpublish"))
    }

    @Test
    fun `update succeeds when validation rules pass`() {
        val client = freshClient(articlePolicy = FullyValidatedArticlePolicy)
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Draft"
            published = false
            authorId = author.id
        }.save()

        val updated = client.articles.update(article) { published = true }.save()!!
        assertTrue(updated.published)
    }

    // ---- DELETE validation ----

    @Test
    fun `delete throws ValidationException when rule fails`() {
        val client = freshClient(articlePolicy = FullyValidatedArticlePolicy)
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Published"
            published = true
            authorId = author.id
        }.save()

        val ex = assertFailsWith<ValidationException> {
            client.articles.delete(article)
        }
        assertTrue(ex.message!!.contains("cannot delete a published"))
    }

    @Test
    fun `delete succeeds when validation rules pass`() {
        val client = freshClient(articlePolicy = FullyValidatedArticlePolicy)
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Draft"
            published = false
            authorId = author.id
        }.save()

        val deleted = client.articles.delete(article)
        assertTrue(deleted)
    }

    // ---- Privacy runs before validation ----

    @Test
    fun `privacy denial fires before validation on create`() {
        val client = freshClient(
            viewer = Viewer.Anonymous,
            articlePolicy = PrivacyBeforeValidationPolicy,
        )
        val author = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.users.create { name = "U"; email = "u@test.com" }.save()
        }

        // Title "AB" would fail validation (too short), but privacy should deny first.
        // If validation ran first, we'd get ValidationException instead.
        val ex = assertFailsWith<PrivacyDeniedException> {
            client.articles.create {
                title = "AB"
                published = false
                authorId = author.id
            }.save()
        }
        assertEquals("authentication required", ex.reason)
    }

    // ---- Viewer.System does NOT bypass validation ----

    @Test
    fun `System viewer does not bypass create validation`() {
        val client = freshClient(viewer = Viewer.System)
        val author = seedAuthor(client)

        assertFailsWith<ValidationException> {
            client.articles.create {
                title = "AB" // too short
                published = false
                authorId = author.id
            }.save()
        }
    }

    @Test
    fun `System viewer does not bypass update validation`() {
        val client = freshClient(
            viewer = Viewer.System,
            articlePolicy = FullyValidatedArticlePolicy,
        )
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Published"
            published = true
            authorId = author.id
        }.save()

        assertFailsWith<ValidationException> {
            client.articles.update(article) { published = false }.save()
        }
    }

    @Test
    fun `System viewer does not bypass delete validation`() {
        val client = freshClient(
            viewer = Viewer.System,
            articlePolicy = FullyValidatedArticlePolicy,
        )
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Published"
            published = true
            authorId = author.id
        }.save()

        assertFailsWith<ValidationException> {
            client.articles.delete(article)
        }
    }

    // ---- Derived create rules run on update ----

    @Test
    fun `updateDerivesFromCreate runs create rules on update`() {
        val client = freshClient(articlePolicy = DerivedValidationPolicy)
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Good Title"
            published = false
            authorId = author.id
        }.save()

        // Update title to something that fails create validation (DRAFT: prefix + published)
        val ex = assertFailsWith<ValidationException> {
            client.articles.update(article) {
                title = "DRAFT: Now Published"
                published = true
            }.save()
        }
        assertTrue(ex.violations.any { it.message.contains("DRAFT:") })
    }

    @Test
    fun `updateDerivesFromCreate runs create min-length rule on update`() {
        val client = freshClient(articlePolicy = DerivedValidationPolicy)
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Good Title"
            published = false
            authorId = author.id
        }.save()

        val ex = assertFailsWith<ValidationException> {
            client.articles.update(article) { title = "AB" }.save()
        }
        assertTrue(ex.violations.any { it.message.contains("at least 3") })
    }

    // ---- Scoped and transaction clients preserve validation config ----

    @Test
    fun `withPrivacyContext preserves validation config`() {
        val client = freshClient(viewer = Viewer.System)
        val author = seedAuthor(client)

        // Validation should still be enforced inside a scoped client
        client.withPrivacyContext(PrivacyContext(Viewer.System)) { scoped ->
            assertFailsWith<ValidationException> {
                scoped.articles.create {
                    title = "AB"
                    published = false
                    authorId = author.id
                }.save()
            }
        }
    }

    @Test
    fun `withTransaction preserves validation config`() {
        val client = freshClient(viewer = Viewer.System)
        val author = seedAuthor(client)

        assertFailsWith<ValidationException> {
            client.withTransaction { tx ->
                tx.articles.create {
                    title = "AB"
                    published = false
                    authorId = author.id
                }.save()
            }
        }
    }

    // ---- No validation policy = no enforcement ----

    @Test
    fun `no validation policy means no validation enforcement`() {
        val driver = PostgresDriver(dataSource)
        seedSchemas()
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE \"articles\", \"users\" RESTART IDENTITY CASCADE")
            }
        }

        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
        }

        val user = client.users.create { name = "U"; email = "u@test.com" }.save()
        // Title "AB" would fail validation if rules were registered, but none are
        val article = client.articles.create {
            title = "AB"
            published = false
            authorId = user.id
        }.save()
        assertEquals("AB", article.title)
    }
}
