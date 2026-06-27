package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticleCreatePrivacyRule
import entkt.integrationtest.ent.ArticleDeletePrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.ArticleUpdatePrivacyRule
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.postgres.PostgresDriver
import entkt.runtime.EntError
import entkt.runtime.EntOperation
import entkt.runtime.allowAll
import entkt.runtime.EntPrivacyDeniedException
import entkt.runtime.EntResult
import entkt.runtime.EntityPolicy
import entkt.runtime.PrivacyContext
import entkt.runtime.PrivacyDecision
import entkt.runtime.PrivacyDeniedException
import entkt.runtime.PrivacyOperation
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---- Policies ----

/** Published articles are visible to everyone; owners see their own drafts. */
object ArticlePolicy : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy {
            load(AllowPublished, AllowAuthorLoad)
            create(RequireAuth)
            delete(OwnerCanDelete)
        }
    }
}

private val AllowPublished = ArticleLoadPrivacyRule { ctx ->
    if (ctx.entity.published) PrivacyDecision.Allow else PrivacyDecision.Continue
}

private val AllowAuthorLoad = ArticleLoadPrivacyRule { ctx ->
    val viewer = ctx.privacy.viewer as? Viewer.User ?: return@ArticleLoadPrivacyRule PrivacyDecision.Continue
    if (viewer.id == ctx.entity.authorId) PrivacyDecision.Allow else PrivacyDecision.Continue
}

private val RequireAuth = ArticleCreatePrivacyRule { ctx ->
    // Fail-closed: an authenticated viewer must be explicitly allowed, not left
    // to fall through (which would now deny).
    if (ctx.privacy.viewer is Viewer.Anonymous) PrivacyDecision.Deny("authentication required")
    else PrivacyDecision.Allow
}

private val OwnerCanDelete = ArticleDeletePrivacyRule { ctx ->
    val viewer = ctx.privacy.viewer as? Viewer.User
        ?: return@ArticleDeletePrivacyRule PrivacyDecision.Deny("authentication required")
    if (viewer.id == ctx.entity.authorId) PrivacyDecision.Allow
    else PrivacyDecision.Deny("only the author can delete")
}

/** All users are publicly visible (stock `allowAll` rule). */
object UserPolicy : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy {
            load(allowAll)
        }
    }
}

/** Users can only see themselves. */
object RestrictiveUserPolicy : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy {
            load(AllowSelfOnly)
        }
    }
}

/** Only the author can update their article. */
private val OwnerCanUpdate = ArticleUpdatePrivacyRule { ctx ->
    val viewer = ctx.privacy.viewer as? Viewer.User
        ?: return@ArticleUpdatePrivacyRule PrivacyDecision.Deny("authentication required")
    if (viewer.id == ctx.before.authorId) PrivacyDecision.Allow
    else PrivacyDecision.Deny("only the author can update")
}

/** Policy with explicit update rule. */
object ArticlePolicyWithUpdate : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy {
            load(AllowPublished, AllowAuthorLoad)
            create(RequireAuth)
            update(OwnerCanUpdate)
            delete(OwnerCanDelete)
        }
    }
}

/** Policy that derives update and delete from create. */
object ArticlePolicyWithDerived : EntityPolicy<Article, ArticlePolicyScope> {
    override fun configure(scope: ArticlePolicyScope) = scope.run {
        privacy {
            load(AllowPublished, AllowAuthorLoad)
            create(RequireAuth)
            updateDerivesFromCreate()
            deleteDerivesFromCreate()
        }
    }
}

private val AllowSelfOnly = UserLoadPrivacyRule { ctx ->
    val viewer = ctx.privacy.viewer as? Viewer.User ?: return@UserLoadPrivacyRule PrivacyDecision.Continue
    if (viewer.id == ctx.entity.id) PrivacyDecision.Allow else PrivacyDecision.Continue
}

// ---- Tests ----

@Testcontainers
class PrivacyIntegrationTest {

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

    /** Create a fresh driver with migrated tables, truncate between tests. */
    private fun freshClient(
        viewer: Viewer,
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope> = ArticlePolicy,
        userPolicy: EntityPolicy<User, UserPolicyScope> = UserPolicy,
    ): EntClient {
        val driver = PostgresDriver(dataSource)
        seedSchemas()

        // Truncate all managed tables between tests so each test starts with a clean DB.
        // Derived from EntClient.SCHEMAS so new schemas are picked up automatically.
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

    private fun seedData(client: EntClient): Pair<User, User> {
        // Use System viewer to bypass create privacy
        val system = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val alice = sys.users.create { name = "Alice"; email = "alice@test.com" }.save()
            val bob = sys.users.create { name = "Bob"; email = "bob@test.com" }.save()

            sys.articles.create { title = "Public by Alice"; published = true; authorId = alice.id }.save()
            sys.articles.create { title = "Draft by Alice"; published = false; authorId = alice.id }.save()
            sys.articles.create { title = "Public by Bob"; published = true; authorId = bob.id }.save()
            sys.articles.create { title = "Draft by Bob"; published = false; authorId = bob.id }.save()

            alice to bob
        }
        return system
    }

    // ---- LOAD: query.allOrThrow() ----

    @Test
    fun `all throws when any result entity is denied`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        // Anonymous can see published but not drafts — should throw.
        // allOrThrow wraps allOrError().getOrThrow() so the throw is
        // the structured EntPrivacyDeniedException, not the raw
        // PrivacyDeniedException.
        assertFailsWith<EntPrivacyDeniedException> {
            client.articles.query().allOrThrow()
        }
    }

    @Test
    fun `all succeeds when all results are allowed`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        // Query only published — all allowed
        val articles = client.articles.query {
            where(Article.published eq true)
        }.allOrThrow()
        assertEquals(2, articles.size)
        assertTrue(articles.all { it.published })
    }

    @Test
    fun `all with owner viewer sees own drafts`() {
        val client = freshClient(Viewer.User(0L))
        val (alice, _) = seedData(client)

        // Alice querying only her own articles — should see both published and draft
        val articles = client.withPrivacyContext(PrivacyContext(Viewer.User(alice.id))) { scoped ->
            scoped.articles.query {
                where(Article.authorId eq alice.id)
            }.allOrThrow()
        }
        assertEquals(2, articles.size)
    }

    // ---- LOAD: query.firstOrNull() ----

    @Test
    fun `firstOrNull throws when the entity is denied`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        assertFailsWith<PrivacyDeniedException> {
            client.articles.query {
                where(Article.published eq false)
            }.firstOrNull()
        }
    }

    @Test
    fun `firstOrNull returns null when no row matches`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val result = client.articles.query {
            where(Article.title eq "nonexistent")
        }.firstOrNull()
        assertNull(result)
    }

    @Test
    fun `firstOrNull returns entity when allowed`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val result = client.articles.query {
            where(Article.published eq true)
        }.firstOrNull()
        assertNotNull(result)
        assertTrue(result.published)
    }

    // ---- LOAD: repo.byIdOrNull() ----

    @Test
    fun `byIdOrNull throws on denied entity`() {
        val client = freshClient(Viewer.Anonymous)
        val (alice, _) = seedData(client)

        // Find Alice's draft via system
        val draft = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq false)
            }.firstOrNull()
        }
        assertNotNull(draft)

        assertFailsWith<PrivacyDeniedException> {
            client.articles.byIdOrNull(draft.id)
        }
    }

    @Test
    fun `byIdOrNull returns allowed entity`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val published = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query { where(Article.published eq true) }.firstOrNull()
        }
        assertNotNull(published)

        val result = client.articles.byIdOrNull(published.id)
        assertNotNull(result)
        assertEquals(published.id, result.id)
    }

    // ---- Viewer.PrivacyBypass bypass ----

    @Test
    fun `System viewer sees all entities`() {
        val client = freshClient(Viewer.PrivacyBypass("test"))
        seedData(client)

        val all = client.articles.query().allOrThrow()
        assertEquals(4, all.size)
    }

    @Test
    fun `System viewer can create without auth`() {
        val client = freshClient(Viewer.PrivacyBypass("test"))
        val user = client.users.create { name = "Sys"; email = "sys@test.com" }.save()
        val article = client.articles.create {
            title = "System Article"
            published = false
            authorId = user.id
        }.save()
        assertEquals("System Article", article.title)
    }

    @Test
    fun `System viewer can delete any entity`() {
        val client = freshClient(Viewer.PrivacyBypass("test"))
        val (alice, _) = seedData(client)

        val article = client.articles.query {
            where(Article.authorId eq alice.id)
        }.allOrThrow().first()

        client.articles.deleteOrThrow(article)
    }

    // ---- visibleCount() ----

    @Test
    fun `visibleCount returns count of allowed entities`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val count = client.articles.query().visibleCount()
        // 2 published allowed, 2 drafts denied
        assertEquals(2L, count)
    }

    @Test
    fun `visibleCount for owner includes own drafts`() {
        val client = freshClient(Viewer.User(0L))
        val (alice, _) = seedData(client)

        val count = client.withPrivacyContext(PrivacyContext(Viewer.User(alice.id))) { scoped ->
            scoped.articles.query().visibleCount()
        }
        // Alice sees: her 2 articles + Bob's published = 3, Bob's draft denied
        assertEquals(3L, count)
    }

    @Test
    fun `visibleCount returns zero when all denied`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val count = client.articles.query {
            where(Article.published eq false)
        }.visibleCount()
        assertEquals(0L, count)
    }

    // ---- rawCount() ----

    @Test
    fun `rawCount reports all rows regardless of privacy`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val count = client.articles.query().rawCount()
        assertEquals(4L, count)
    }

    @Test
    fun `rawCount with predicate counts matching rows`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val count = client.articles.query {
            where(Article.published eq true)
        }.rawCount()
        assertEquals(2L, count)
    }

    // ---- exists() ----

    @Test
    fun `visibleExists returns false when the only matching row is denied`() {
        // The legacy `exists()` threw PrivacyDeniedException for this
        // case (fetched the first row and checked LOAD on it). The
        // new visibleExists silently returns false because the cap-
        // exhausted-with-no-visible outcome matches "no visible row
        // exists" — same optimistic-read shape as firstVisibleOrNull.
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val result = client.articles.query {
            where(Article.published eq false)
        }.visibleExists()
        assertFalse(result)
    }

    @Test
    fun `visibleExists returns true when at least one matching row is allowed`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val result = client.articles.query {
            where(Article.published eq true)
        }.visibleExists()
        assertTrue(result)
    }

    @Test
    fun `visibleExists returns false when no rows match`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val result = client.articles.query {
            where(Article.title eq "nonexistent")
        }.visibleExists()
        assertFalse(result)
    }

    @Test
    fun `rawExists ignores LOAD privacy and returns true if any storage row matches`() {
        // The denied-draft case that visibleExists returns false for:
        // rawExists doesn't care about privacy, so it sees the
        // existing-in-storage row and returns true.
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val result = client.articles.query {
            where(Article.published eq false)
        }.rawExists()
        assertTrue(result)
    }

    // ---- CREATE privacy ----

    @Test
    fun `create denied for anonymous viewer`() {
        val client = freshClient(Viewer.Anonymous)
        val user = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "U"; email = "u@test.com" }.save()
        }

        val ex = assertFailsWith<PrivacyDeniedException> {
            client.articles.create {
                title = "Anon Post"
                published = true
                authorId = user.id
            }.save()
        }
        assertEquals(PrivacyOperation.CREATE, ex.operation)
        assertEquals("authentication required", ex.reason)
    }

    @Test
    fun `create allowed for authenticated viewer`() {
        val client = freshClient(Viewer.User(1L))
        val user = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "U"; email = "u@test.com" }.save()
        }

        val article = client.articles.create {
            title = "Auth Post"
            published = true
            authorId = user.id
        }.save()
        assertEquals("Auth Post", article.title)
    }

    // ---- DELETE privacy ----

    @Test
    fun `delete denied for non-owner`() {
        val client = freshClient(Viewer.User(0L))
        val (alice, bob) = seedData(client)

        val (aliceArticle, ex) = client.withPrivacyContext(PrivacyContext(Viewer.User(bob.id))) { scoped ->
            // Bob tries to delete Alice's published article
            val article = scoped.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq true)
            }.firstOrNull()
            assertNotNull(article)

            // deleteOrThrow → deleteOrError().getOrThrow() converts
            // the raw PrivacyDeniedException into the structured
            // EntPrivacyDeniedException at the throw boundary.
            val failure = assertFailsWith<EntPrivacyDeniedException> {
                scoped.articles.deleteOrThrow(article)
            }
            article to failure
        }
        assertEquals(EntOperation.DELETE, ex.privacyDenied.operation)
        assertEquals("only the author can delete", ex.privacyDenied.reason)

        // Verify the article still exists
        val still = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.byIdOrNull(aliceArticle.id)
        }
        assertNotNull(still)
    }

    @Test
    fun `delete allowed for owner`() {
        val client = freshClient(Viewer.User(0L))
        val (alice, _) = seedData(client)

        val articleId = client.withPrivacyContext(PrivacyContext(Viewer.User(alice.id))) { scoped ->
            val article = scoped.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq true)
            }.firstOrNull()
            assertNotNull(article)

            scoped.articles.deleteOrThrow(article)
            article.id
        }

        val gone = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.byIdOrNull(articleId)
        }
        assertNull(gone)
    }

    // ---- withPrivacyContext ----

    @Test
    fun `withPrivacyContext scopes viewer correctly`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        // Anonymous: drafts throw — allOrThrow wraps via getOrThrow
        // so the throw is the structured EntPrivacyDeniedException.
        assertFailsWith<EntPrivacyDeniedException> {
            client.articles.query().allOrThrow()
        }

        // Elevate to System within a block
        val all = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query().allOrThrow()
        }
        assertEquals(4, all.size)

        // Back to anonymous: still throws
        assertFailsWith<EntPrivacyDeniedException> {
            client.articles.query().allOrThrow()
        }
    }

    // ---- Eager loading + privacy ----

    @Test
    fun `eager loaded edge respects privacy when target is allowed`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        // Eagerly load author on published articles — authors are publicly visible
        val articles = client.articles.query {
            where(Article.published eq true)
            withAuthor()
        }.allOrThrow()
        assertEquals(2, articles.size)
        for (article in articles) {
            assertNotNull(article.edges.author)
        }
    }

    @Test
    fun `eager loaded edge throws when target entity is denied`() {
        val client = freshClient(Viewer.User(0L), userPolicy = RestrictiveUserPolicy)
        val (alice, _) = seedData(client)

        client.withPrivacyContext(PrivacyContext(Viewer.User(alice.id))) { scoped ->
            // Alice queries her own published article with eager author.
            // The article itself is allowed (she's the owner), and the eager author
            // is also Alice — so AllowSelfOnly allows it.
            val articles = scoped.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq true)
                withAuthor()
            }.allOrThrow()
            assertEquals(1, articles.size)
            assertNotNull(articles[0].edges.author)

            // Now query ALL published articles with eager author. Bob's article is
            // published (allowed), but eager-loading Bob as the author should throw
            // because RestrictiveUserPolicy only allows viewing yourself.
            assertFailsWith<EntPrivacyDeniedException> {
                scoped.articles.query {
                    where(Article.published eq true)
                    withAuthor()
                }.allOrThrow()
            }
        }
    }

    // ---- Transactions ----

    @Test
    fun `privacy enforced within transactions`() {
        val client = freshClient(Viewer.Anonymous)

        val user = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "U"; email = "u@test.com" }.save()
        }

        // Anonymous create should fail inside a transaction too
        assertFailsWith<PrivacyDeniedException> {
            client.withTransaction { tx ->
                tx.articles.create {
                    title = "TX Post"
                    published = true
                    authorId = user.id
                }.save()
            }
        }
    }

    // ---- UPDATE privacy ----

    @Test
    fun `update denied for non-owner`() {
        val client = freshClient(Viewer.User(0L), articlePolicy = ArticlePolicyWithUpdate)
        val (alice, bob) = seedData(client)

        client.withPrivacyContext(PrivacyContext(Viewer.User(bob.id))) { scoped ->
            // Bob can see Alice's published article
            val article = scoped.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq true)
            }.firstOrNull()
            assertNotNull(article)

            val ex = assertFailsWith<PrivacyDeniedException> {
                scoped.articles.update(article.id) { title = "Hacked" }.save()
            }
            assertEquals(PrivacyOperation.UPDATE, ex.operation)
            assertEquals("only the author can update", ex.reason)
        }

        // Verify title unchanged
        val unchanged = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq true)
            }.firstOrNull()
        }
        assertNotNull(unchanged)
        assertEquals("Public by Alice", unchanged.title)
    }

    @Test
    fun `update allowed for owner`() {
        val client = freshClient(Viewer.User(0L), articlePolicy = ArticlePolicyWithUpdate)
        val (alice, _) = seedData(client)

        client.withPrivacyContext(PrivacyContext(Viewer.User(alice.id))) { scoped ->
            val article = scoped.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq true)
            }.firstOrNull()!!

            val updated = scoped.articles.update(article.id) { title = "Updated Title" }.save()!!
            assertEquals("Updated Title", updated.title)
        }
    }

    // ---- Derived policies ----

    @Test
    fun `updateDerivesFromCreate uses create rules for update`() {
        val client = freshClient(Viewer.User(0L), articlePolicy = ArticlePolicyWithDerived)
        seedData(client)

        // Get an article via System
        val article = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query { where(Article.published eq true) }.firstOrNull()
        }
        assertNotNull(article)

        // Anonymous update should fail — RequireAuth create rule denies anonymous
        assertFailsWith<PrivacyDeniedException> {
            client.withPrivacyContext(PrivacyContext(Viewer.Anonymous)) { anon ->
                anon.articles.update(article.id) { title = "Anon Update" }.save()
            }
        }
    }

    @Test
    fun `deleteDerivesFromCreate uses create rules for delete`() {
        val client = freshClient(Viewer.Anonymous, articlePolicy = ArticlePolicyWithDerived)
        seedData(client)

        // Anonymous delete should fail — RequireAuth create rule denies anonymous
        val article = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query { where(Article.published eq true) }.firstOrNull()
        }
        assertNotNull(article)

        assertFailsWith<EntPrivacyDeniedException> {
            client.articles.deleteOrThrow(article)
        }
    }

    @Test
    fun `derived delete allows authenticated viewer`() {
        val client = freshClient(Viewer.User(0L), articlePolicy = ArticlePolicyWithDerived)
        val (alice, _) = seedData(client)

        // Authenticated user can delete (create rule only blocks anonymous)
        client.withPrivacyContext(PrivacyContext(Viewer.User(alice.id))) { scoped ->
            val article = scoped.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq true)
            }.firstOrNull()
            assertNotNull(article)

            scoped.articles.deleteOrThrow(article)
        }
    }

    // ---- deleteByIdOrError: bypass LOAD, enforce DELETE ----

    @Test
    fun `deleteById bypasses LOAD privacy but enforces DELETE`() {
        val client = freshClient(Viewer.User(0L))
        val (alice, bob) = seedData(client)

        // Get Alice's draft ID via System
        val draftId = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq false)
            }.firstOrNull()!!.id
        }

        // Bob can't load the draft (anonymous/non-owner), but
        // deleteByIdOrError bypasses LOAD. However, DELETE privacy
        // should still deny Bob.
        client.withPrivacyContext(PrivacyContext(Viewer.User(bob.id))) { scoped ->
            val result = scoped.articles.deleteByIdOrError(draftId)
            assertTrue(result is EntResult.Err)
            val error = result.error
            assertTrue(error is EntError.PrivacyDenied)
            assertEquals(EntOperation.DELETE, error.operation)
        }

        // Verify the draft still exists
        val still = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.byIdOrNull(draftId)
        }
        assertNotNull(still)
    }

    @Test
    fun `deleteById succeeds for owner even on LOAD-denied entity`() {
        val client = freshClient(Viewer.Anonymous)
        val (alice, _) = seedData(client)

        // Get Alice's draft ID via System — anonymous can't load it
        val draftId = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq false)
            }.firstOrNull()!!.id
        }

        // Alice can deleteByIdOrError her own draft — LOAD bypassed, DELETE allowed
        client.withPrivacyContext(PrivacyContext(Viewer.User(alice.id))) { scoped ->
            val result = scoped.articles.deleteByIdOrError(draftId)
            assertTrue(result is EntResult.Ok)
            assertTrue(result.value)
        }

        val gone = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.byIdOrNull(draftId)
        }
        assertNull(gone)
    }

    @Test
    fun `deleteByIdOrError returns Ok(false) for nonexistent ID`() {
        val client = freshClient(Viewer.PrivacyBypass("test"))
        seedData(client)

        val result = client.articles.deleteByIdOrError(99999)
        assertTrue(result is EntResult.Ok)
        assertFalse(result.value)
    }

    // ---- Bulk convenience methods ----

    @Test
    fun `createManyOrError surfaces per-row CREATE privacy denial as Err`() {
        val client = freshClient(Viewer.Anonymous)
        val user = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "U"; email = "u@test.com" }.save()
        }

        // Anonymous can't create — first item fails, helper returns
        // Err with the matching EntError.PrivacyDenied. createManyOrError
        // requires a tx, so wrap in withTransaction.
        val result = client.withTransaction { tx ->
            tx.articles.createManyOrError(
                { title = "A"; published = true; authorId = user.id },
                { title = "B"; published = true; authorId = user.id },
            )
        }
        assertTrue(result is EntResult.Err)
        assertTrue(result.error is EntError.PrivacyDenied)
    }

    @Test
    fun `createManyOrError succeeds for authenticated viewer (inside tx)`() {
        val client = freshClient(Viewer.User(1L))
        val user = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "U"; email = "u@test.com" }.save()
        }

        val result = client.withTransaction { tx ->
            tx.articles.createManyOrError(
                { title = "A"; published = true; authorId = user.id },
                { title = "B"; published = false; authorId = user.id },
            )
        }
        assertTrue(result is EntResult.Ok)
        val articles = result.value
        assertEquals(2, articles.size)
        assertEquals("A", articles[0].title)
        assertEquals("B", articles[1].title)
    }

    @Test
    fun `deleteMany enforces per-row DELETE privacy`() {
        val client = freshClient(Viewer.User(0L))
        val (alice, bob) = seedData(client)

        // Bob tries to deleteMany all articles — should fail on Alice's article
        client.withPrivacyContext(PrivacyContext(Viewer.User(bob.id))) { scoped ->
            assertFailsWith<PrivacyDeniedException> {
                scoped.articles.deleteMany(Article.published eq true)
            }
        }

        // Verify Alice's published article still exists (Bob's may or may not
        // depending on iteration order, but at least one survived)
        val remaining = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query { where(Article.published eq true) }.allOrThrow()
        }
        assertTrue(remaining.isNotEmpty())
    }

    @Test
    fun `deleteMany succeeds when viewer owns all matched entities`() {
        val client = freshClient(Viewer.User(0L))
        val (alice, _) = seedData(client)

        client.withPrivacyContext(PrivacyContext(Viewer.User(alice.id))) { scoped ->
            val count = scoped.articles.deleteMany(Article.authorId eq alice.id)
            assertEquals(2, count)
        }

        val remaining = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query { where(Article.authorId eq alice.id) }.allOrThrow()
        }
        assertTrue(remaining.isEmpty())
    }

    // ---- No policy = deny everything (fail-closed) ----

    @Test
    fun `no policy means every operation is denied (fail-closed)`() {
        val driver = PostgresDriver(dataSource)
        seedSchemas()
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE \"articles\", \"users\" RESTART IDENTITY CASCADE")
            }
        }

        // Client with NO policies configured, authenticated viewer.
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(1L)) }
        }

        // Fail-closed: with no create rule, even an authenticated create denies.
        assertFailsWith<PrivacyDeniedException> {
            client.users.create { name = "U"; email = "u@test.com" }.save()
        }

        // Seed a row via System (the bypass), then confirm LOAD denies too.
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val u = sys.users.create { name = "U"; email = "u2@test.com" }.save()
            sys.articles.create { title = "Draft"; published = false; authorId = u.id }.save()
        }
        assertFailsWith<EntPrivacyDeniedException> {
            client.articles.query().allOrThrow()
        }
    }
}
