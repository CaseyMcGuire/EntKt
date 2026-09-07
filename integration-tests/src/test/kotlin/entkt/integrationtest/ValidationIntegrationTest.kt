package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleCreateValidationRule
import entkt.integrationtest.ent.ArticleDeleteValidationRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.ArticleUpdatePrivacyRule
import entkt.integrationtest.ent.ArticleUpdateValidationRule
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticleCreatePrivacyRule
import entkt.postgres.PostgresDriver
import entkt.runtime.mutation.FieldPatch
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionResult
import entkt.runtime.validation.ValidationDecision
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// ---- Validation rules ----

private val RejectUnpublishedTitle = ArticleCreateValidationRule { _, item ->
    if (item.title.startsWith("DRAFT:") && item.published) {
        ValidationDecision.Invalid("articles with DRAFT: prefix cannot be published", field = "title")
    } else {
        ValidationDecision.Valid
    }
}

private val RequireMinTitleLength = ArticleCreateValidationRule { _, item ->
    if (item.title.length < 3) {
        ValidationDecision.Invalid("title must be at least 3 characters", field = "title")
    } else {
        ValidationDecision.Valid
    }
}

private val RejectUnpublishedCreate = ArticleCreateValidationRule { _, item ->
    if (!item.published) {
        ValidationDecision.Invalid("articles must be published on create", field = "published")
    } else {
        ValidationDecision.Valid
    }
}

private val PreventUnpublish = ArticleUpdateValidationRule { _, item ->
    if (item.before.published && !item.candidate.published) {
        ValidationDecision.Invalid("cannot unpublish a published article")
    } else {
        ValidationDecision.Valid
    }
}

private val CannotDeletePublished = ArticleDeleteValidationRule { _, item ->
    if (item.entity.published) {
        ValidationDecision.Invalid("cannot delete a published article")
    } else {
        ValidationDecision.Valid
    }
}

// ---- Privacy rules for combined tests ----

private val AllowAllLoads = ArticleLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }
private val AllowAllUserLoads = UserLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }
private val RequireAuthForCreate = ArticleCreatePrivacyRule { context, _ ->
    if (context.viewerContext.viewer is Viewer.Anonymous) PrivacyDecision.Deny("authentication required")
    else PrivacyDecision.Allow
}

private fun firstPayloadByte(patch: FieldPatch<ByteArray?>): Byte? =
    (patch as? FieldPatch.Set)?.value?.firstOrNull()

private val MutateCreatePayloadPrivacyCopy = ArticleCreatePrivacyRule { _, item ->
    item.payload?.copyOf()?.set(0, 99)
    PrivacyDecision.Continue
}

private val AllowIfCreatePayloadPrivacySnapshotIsStable = ArticleCreatePrivacyRule { _, item ->
    if (item.payload?.firstOrNull() == 1.toByte()) PrivacyDecision.Allow
    else PrivacyDecision.Deny("CREATE payload snapshot leaked across rules")
}

private val MutateUpdatePayloadPrivacyCopy = ArticleUpdatePrivacyRule { _, item ->
    item.before.payload?.copyOf()?.set(0, 99)
    item.candidate.payload?.copyOf()?.set(0, 99)
    (item.requestedPatch.payload as? FieldPatch.Set<ByteArray?>)?.value?.copyOf()?.set(0, 99)
    (item.effectivePatch.payload as? FieldPatch.Set<ByteArray?>)?.value?.copyOf()?.set(0, 99)
    PrivacyDecision.Continue
}

private val AllowIfUpdatePayloadPrivacySnapshotIsStable = ArticleUpdatePrivacyRule { _, item ->
    val stable = item.before.payload?.firstOrNull() == 1.toByte() &&
        item.candidate.payload?.firstOrNull() == 2.toByte() &&
        firstPayloadByte(item.requestedPatch.payload) == 2.toByte() &&
        firstPayloadByte(item.effectivePatch.payload) == 2.toByte()
    if (stable) PrivacyDecision.Allow
    else PrivacyDecision.Deny("UPDATE payload snapshot leaked across rules")
}

private val MutateCreatePayloadValidationCopy = ArticleCreateValidationRule { _, item ->
    item.payload?.copyOf()?.set(0, 99)
    ValidationDecision.Valid
}

private val ValidateCreatePayloadSnapshotIsStable = ArticleCreateValidationRule { _, item ->
    if (item.payload?.firstOrNull() == 1.toByte()) ValidationDecision.Valid
    else ValidationDecision.Invalid("CREATE payload snapshot leaked across rules")
}

private val MutateUpdatePayloadValidationCopy = ArticleUpdateValidationRule { _, item ->
    item.before.payload?.copyOf()?.set(0, 99)
    item.candidate.payload?.copyOf()?.set(0, 99)
    (item.requestedPatch.payload as? FieldPatch.Set<ByteArray?>)?.value?.copyOf()?.set(0, 99)
    (item.effectivePatch.payload as? FieldPatch.Set<ByteArray?>)?.value?.copyOf()?.set(0, 99)
    ValidationDecision.Valid
}

private val ValidateUpdatePayloadSnapshotIsStable = ArticleUpdateValidationRule { _, item ->
    val stable = item.before.payload?.firstOrNull() == 1.toByte() &&
        item.candidate.payload?.firstOrNull() == 2.toByte() &&
        firstPayloadByte(item.requestedPatch.payload) == 2.toByte() &&
        firstPayloadByte(item.effectivePatch.payload) == 2.toByte()
    if (stable) ValidationDecision.Valid
    else ValidationDecision.Invalid("UPDATE payload snapshot leaked across rules")
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

private object ByteArraySnapshotArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy {
            load(AllowAllLoads)
            create(MutateCreatePayloadPrivacyCopy, AllowIfCreatePayloadPrivacySnapshotIsStable)
            update(MutateUpdatePayloadPrivacyCopy, AllowIfUpdatePayloadPrivacySnapshotIsStable)
        }
        validation {
            create(MutateCreatePayloadValidationCopy, ValidateCreatePayloadSnapshotIsStable)
            update(MutateUpdatePayloadValidationCopy, ValidateUpdatePayloadSnapshotIsStable)
        }
    }
}

// ---- Tests ----

/**
 * End-to-end validation enforcement through the canonical result
 * algebra: a validation failure never throws from a mutation terminal —
 * it is `MutationResult.Failed(EntValidationException(entityType,
 * operation, violations))` with `writeState = NotPersisted` (validation
 * runs before persistence), where each violation is a
 * `ValidationViolation(message, field, code)`. Privacy runs before
 * validation, `Viewer.PrivacyBypass` bypasses privacy but never
 * validation, and scoped / transaction clients preserve validation
 * config.
 */
@Testcontainers
class ValidationIntegrationTest {
    private var viewerContext = testViewerContext

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

    private fun freshClient(
        viewer: Viewer = Viewer.PrivacyBypass("test"),
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope> = ValidatedArticlePolicy,
        userPolicy: EntityPolicy<User, UserPolicyScope> = OpenUserPolicy,
    ): EntClient {
        viewerContext = ViewerContext(viewer)
        val driver = PostgresDriver(dataSource)
        seedSchemas()

        val tables = EntClient.SCHEMAS.joinToString(", ") { "\"${it.table}\"" }
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE $tables RESTART IDENTITY CASCADE")
            }
        }

        return EntClient(driver) {

            policies {
                articles(articlePolicy)
                users(userPolicy)
            }
        }
    }

    private fun seedAuthor(client: EntClient): User {
        return run {
            val sys = client
            val viewerContext = testBypassContext("test")
            sys.users.create { name = "Alice"; email = "alice@test.com" }.saveAndLoad(viewerContext).getOrThrow()
        }
    }

    @Test
    fun `rules can mutate their own byte array copies without changing shared inputs or persistence`() {
        val client = freshClient(
            viewer = Viewer.User(7L),
            articlePolicy = ByteArraySnapshotArticlePolicy,
        )
        val author = seedAuthor(client)

        val createPayload = byteArrayOf(1, 10)
        val created = client.articles.create {
            title = "Byte snapshots"
            published = true
            payload = createPayload
            authorId = author.id
        }.saveAndLoad(viewerContext).getOrThrow()

        assertContentEquals(byteArrayOf(1, 10), createPayload)
        assertContentEquals(byteArrayOf(1, 10), created.payload)

        val updatePayload = byteArrayOf(2, 20)
        val updated = client.articles.update(created.id) {
            payload = updatePayload
        }.saveAndLoad(viewerContext).getOrThrow()

        assertContentEquals(byteArrayOf(2, 20), updatePayload)
        assertContentEquals(byteArrayOf(2, 20), updated.payload)
        assertContentEquals(
            byteArrayOf(2, 20),
            client.articles.findById(viewerContext, created.id).getOrThrow()?.payload,
        )
    }

    // ---- CREATE validation ----

    @Test
    fun `create fails with EntValidationException when rule fails`() {
        val client = freshClient()
        val author = seedAuthor(client)

        val failed = assertIs<MutationResult.Failed>(
            client.articles.create {
                title = "DRAFT: My Post"
                published = true
                authorId = author.id
            }.save(viewerContext),
        )
        val ex = assertIs<EntValidationException>(failed.exception)
        assertEquals("Article", ex.entityType)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals(1, ex.violations.size)
        assertEquals("title", ex.violations[0].field)
        assertTrue(ex.violations[0].message.contains("DRAFT:"))
    }

    @Test
    fun `create collects all violations from multiple rules`() {
        val client = freshClient(articlePolicy = MultiRuleArticlePolicy)
        val author = seedAuthor(client)

        val failed = assertIs<MutationResult.Failed>(
            client.articles.create {
                title = "AB" // too short (fails RequireMinTitleLength)
                published = false // not published (fails RejectUnpublishedCreate)
                authorId = author.id
            }.save(viewerContext),
        )
        val ex = assertIs<EntValidationException>(failed.exception)
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
        }.saveAndLoad(viewerContext).getOrThrow()
        assertEquals("Valid Title", article.title)
    }

    @Test
    fun `create validation does not persist the row on failure`() {
        val client = freshClient()
        val author = seedAuthor(client)

        val failed = assertIs<MutationResult.Failed>(
            client.articles.create {
                title = "AB"
                published = false
                authorId = author.id
            }.save(viewerContext),
        )
        val ex = assertIs<EntValidationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)

        val count = client.articles.query().all(viewerContext).getOrThrow().size.toLong()
        assertEquals(0L, count)
    }

    // ---- UPDATE validation ----

    @Test
    fun `update fails with EntValidationException when rule fails`() {
        val client = freshClient(articlePolicy = FullyValidatedArticlePolicy)
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Published"
            published = true
            authorId = author.id
        }.saveAndLoad(viewerContext).getOrThrow()

        val failed = assertIs<MutationResult.Failed>(
            client.articles.update(article.id) { published = false }.save(viewerContext),
        )
        val ex = assertIs<EntValidationException>(failed.exception)
        assertEquals(EntOperation.UPDATE, ex.operation)
        assertTrue(ex.violations.any { it.message.contains("cannot unpublish") })
    }

    @Test
    fun `update succeeds when validation rules pass`() {
        val client = freshClient(articlePolicy = FullyValidatedArticlePolicy)
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Draft"
            published = false
            authorId = author.id
        }.saveAndLoad(viewerContext).getOrThrow()

        val updated = client.articles.update(article.id) { published = true }.saveAndLoad(viewerContext).getOrThrow()
        assertTrue(updated.published)
    }

    // ---- DELETE validation ----

    @Test
    fun `delete fails with EntValidationException when rule fails`() {
        val client = freshClient(articlePolicy = FullyValidatedArticlePolicy)
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Published"
            published = true
            authorId = author.id
        }.saveAndLoad(viewerContext).getOrThrow()

        val failed = assertIs<MutationResult.Failed>(client.articles.delete(viewerContext, article))
        val ex = assertIs<EntValidationException>(failed.exception)
        assertEquals(EntOperation.DELETE, ex.operation)
        assertTrue(ex.violations.any { it.message.contains("cannot delete a published") })
    }

    @Test
    fun `delete succeeds when validation rules pass`() {
        val client = freshClient(articlePolicy = FullyValidatedArticlePolicy)
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Draft"
            published = false
            authorId = author.id
        }.saveAndLoad(viewerContext).getOrThrow()

        client.articles.delete(viewerContext, article).getOrThrow()
    }

    // ---- Privacy runs before validation ----

    @Test
    fun `privacy denial fires before validation on create`() {
        val client = freshClient(
            viewer = Viewer.Anonymous,
            articlePolicy = PrivacyBeforeValidationPolicy,
        )
        val author = run {
            val sys = client
            val viewerContext = testBypassContext("test")
            sys.users.create { name = "U"; email = "u@test.com" }.saveAndLoad(viewerContext).getOrThrow()
        }

        // Title "AB" would fail validation (too short), but privacy denies first.
        // If validation ran first, the Failed would carry EntValidationException instead.
        val failed = assertIs<MutationResult.Failed>(
            client.articles.create {
                title = "AB"
                published = false
                authorId = author.id
            }.save(viewerContext),
        )
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals("authentication required", ex.reason)
    }

    // ---- Viewer.PrivacyBypass does NOT bypass validation ----

    @Test
    fun `PrivacyBypass viewer does not bypass create validation`() {
        val client = freshClient(viewer = Viewer.PrivacyBypass("test"))
        val author = seedAuthor(client)

        val failed = assertIs<MutationResult.Failed>(
            client.articles.create {
                title = "AB" // too short
                published = false
                authorId = author.id
            }.save(viewerContext),
        )
        assertIs<EntValidationException>(failed.exception)
    }

    @Test
    fun `PrivacyBypass viewer does not bypass update validation`() {
        val client = freshClient(
            viewer = Viewer.PrivacyBypass("test"),
            articlePolicy = FullyValidatedArticlePolicy,
        )
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Published"
            published = true
            authorId = author.id
        }.saveAndLoad(viewerContext).getOrThrow()

        val failed = assertIs<MutationResult.Failed>(
            client.articles.update(article.id) { published = false }.save(viewerContext),
        )
        assertIs<EntValidationException>(failed.exception)
    }

    @Test
    fun `PrivacyBypass viewer does not bypass delete validation`() {
        val client = freshClient(
            viewer = Viewer.PrivacyBypass("test"),
            articlePolicy = FullyValidatedArticlePolicy,
        )
        val author = seedAuthor(client)

        val article = client.articles.create {
            title = "Published"
            published = true
            authorId = author.id
        }.saveAndLoad(viewerContext).getOrThrow()

        val failed = assertIs<MutationResult.Failed>(client.articles.delete(viewerContext, article))
        assertIs<EntValidationException>(failed.exception)
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
        }.saveAndLoad(viewerContext).getOrThrow()

        // Update title to something that fails create validation (DRAFT: prefix + published)
        val failed = assertIs<MutationResult.Failed>(
            client.articles.update(article.id) {
                title = "DRAFT: Now Published"
                published = true
            }.save(viewerContext),
        )
        val ex = assertIs<EntValidationException>(failed.exception)
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
        }.saveAndLoad(viewerContext).getOrThrow()

        val failed = assertIs<MutationResult.Failed>(
            client.articles.update(article.id) { title = "AB" }.save(viewerContext),
        )
        val ex = assertIs<EntValidationException>(failed.exception)
        assertTrue(ex.violations.any { it.message.contains("at least 3") })
    }

    // ---- Scoped and transaction clients preserve validation config ----

    @Test
    fun `explicit viewer context preserves validation config`() {
        val client = freshClient(viewer = Viewer.PrivacyBypass("test"))
        val author = seedAuthor(client)

        // Validation is still enforced inside a scoped client
        run {
            val scoped = client
            val viewerContext = testBypassContext("test")
            val failed = assertIs<MutationResult.Failed>(
                scoped.articles.create {
                    title = "AB"
                    published = false
                    authorId = author.id
                }.save(viewerContext),
            )
            assertIs<EntValidationException>(failed.exception)
        }
    }

    @Test
    fun `withTransaction preserves validation config`() {
        val client = freshClient(viewer = Viewer.PrivacyBypass("test"))
        val author = seedAuthor(client)

        // The validation failure recorded through the transaction client
        // becomes the boundary's stored failure with rollback confirmed.
        val result = client.withTransaction { tx ->
            tx.articles.create {
                title = "AB"
                published = false
                authorId = author.id
            }.save(viewerContext).orRollback()
        }
        val failed = assertIs<TransactionResult.Failed>(result)
        assertIs<EntValidationException>(failed.exception)
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

        val client = EntClient(driver)

        val user = client.users.create { name = "U"; email = "u@test.com" }.saveAndLoad(viewerContext).getOrThrow()
        // Title "AB" would fail validation if rules were registered, but none are
        val article = client.articles.create {
            title = "AB"
            published = false
            authorId = user.id
        }.saveAndLoad(viewerContext).getOrThrow()
        assertEquals("AB", article.title)
    }
}
