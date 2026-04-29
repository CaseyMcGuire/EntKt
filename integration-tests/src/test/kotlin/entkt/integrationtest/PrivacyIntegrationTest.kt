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
    if (ctx.privacy.viewer is Viewer.Anonymous) PrivacyDecision.Deny("authentication required")
    else PrivacyDecision.Continue
}

private val OwnerCanDelete = ArticleDeletePrivacyRule { ctx ->
    val viewer = ctx.privacy.viewer as? Viewer.User
        ?: return@ArticleDeletePrivacyRule PrivacyDecision.Deny("authentication required")
    if (viewer.id == ctx.entity.authorId) PrivacyDecision.Allow
    else PrivacyDecision.Deny("only the author can delete")
}

/** All users are publicly visible. */
object UserPolicy : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy {
            load(AllowAllUsers)
        }
    }
}

private val AllowAllUsers = UserLoadPrivacyRule { PrivacyDecision.Allow }

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
        val system = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
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

    // ---- LOAD: query.all() ----

    @Test
    fun `all throws when any result entity is denied`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        // Anonymous can see published but not drafts — should throw
        assertFailsWith<PrivacyDeniedException> {
            client.articles.query().all()
        }
    }

    @Test
    fun `all succeeds when all results are allowed`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        // Query only published — all allowed
        val articles = client.articles.query {
            where(Article.published eq true)
        }.all()
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
            }.all()
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

    // ---- LOAD: repo.byId() ----

    @Test
    fun `byId throws on denied entity`() {
        val client = freshClient(Viewer.Anonymous)
        val (alice, _) = seedData(client)

        // Find Alice's draft via system
        val draft = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq false)
            }.firstOrNull()
        }
        assertNotNull(draft)

        assertFailsWith<PrivacyDeniedException> {
            client.articles.byId(draft.id)
        }
    }

    @Test
    fun `byId returns allowed entity`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val published = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.query { where(Article.published eq true) }.firstOrNull()
        }
        assertNotNull(published)

        val result = client.articles.byId(published.id)
        assertNotNull(result)
        assertEquals(published.id, result.id)
    }

    // ---- Viewer.System bypass ----

    @Test
    fun `System viewer sees all entities`() {
        val client = freshClient(Viewer.System)
        seedData(client)

        val all = client.articles.query().all()
        assertEquals(4, all.size)
    }

    @Test
    fun `System viewer can create without auth`() {
        val client = freshClient(Viewer.System)
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
        val client = freshClient(Viewer.System)
        val (alice, _) = seedData(client)

        val article = client.articles.query {
            where(Article.authorId eq alice.id)
        }.all().first()

        val deleted = client.articles.delete(article)
        assertTrue(deleted)
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
    fun `exists throws when the matched row is denied`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        assertFailsWith<PrivacyDeniedException> {
            client.articles.query {
                where(Article.published eq false)
            }.exists()
        }
    }

    @Test
    fun `exists returns true when the matched row is allowed`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val result = client.articles.query {
            where(Article.published eq true)
        }.exists()
        assertTrue(result)
    }

    @Test
    fun `exists returns false when no rows match`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val result = client.articles.query {
            where(Article.title eq "nonexistent")
        }.exists()
        assertFalse(result)
    }

    // ---- CREATE privacy ----

    @Test
    fun `create denied for anonymous viewer`() {
        val client = freshClient(Viewer.Anonymous)
        val user = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
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
        val user = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
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

            val failure = assertFailsWith<PrivacyDeniedException> {
                scoped.articles.delete(article)
            }
            article to failure
        }
        assertEquals(PrivacyOperation.DELETE, ex.operation)
        assertEquals("only the author can delete", ex.reason)

        // Verify the article still exists
        val still = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.byId(aliceArticle.id)
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

            val deleted = scoped.articles.delete(article)
            assertTrue(deleted)
            article.id
        }

        val gone = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.byId(articleId)
        }
        assertNull(gone)
    }

    // ---- withPrivacyContext ----

    @Test
    fun `withPrivacyContext scopes viewer correctly`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        // Anonymous: drafts throw
        assertFailsWith<PrivacyDeniedException> {
            client.articles.query().all()
        }

        // Elevate to System within a block
        val all = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.query().all()
        }
        assertEquals(4, all.size)

        // Back to anonymous: still throws
        assertFailsWith<PrivacyDeniedException> {
            client.articles.query().all()
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
        }.all()
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
            }.all()
            assertEquals(1, articles.size)
            assertNotNull(articles[0].edges.author)

            // Now query ALL published articles with eager author. Bob's article is
            // published (allowed), but eager-loading Bob as the author should throw
            // because RestrictiveUserPolicy only allows viewing yourself.
            assertFailsWith<PrivacyDeniedException> {
                scoped.articles.query {
                    where(Article.published eq true)
                    withAuthor()
                }.all()
            }
        }
    }

    // ---- Transactions ----

    @Test
    fun `privacy enforced within transactions`() {
        val client = freshClient(Viewer.Anonymous)

        val user = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
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
                scoped.articles.update(article) { title = "Hacked" }.save()
            }
            assertEquals(PrivacyOperation.UPDATE, ex.operation)
            assertEquals("only the author can update", ex.reason)
        }

        // Verify title unchanged
        val unchanged = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
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

            val updated = scoped.articles.update(article) { title = "Updated Title" }.save()!!
            assertEquals("Updated Title", updated.title)
        }
    }

    // ---- Derived policies ----

    @Test
    fun `updateDerivesFromCreate uses create rules for update`() {
        val client = freshClient(Viewer.User(0L), articlePolicy = ArticlePolicyWithDerived)
        seedData(client)

        // Get an article via System
        val article = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.query { where(Article.published eq true) }.firstOrNull()
        }
        assertNotNull(article)

        // Anonymous update should fail — RequireAuth create rule denies anonymous
        assertFailsWith<PrivacyDeniedException> {
            client.withPrivacyContext(PrivacyContext(Viewer.Anonymous)) { anon ->
                anon.articles.update(article) { title = "Anon Update" }.save()
            }
        }
    }

    @Test
    fun `deleteDerivesFromCreate uses create rules for delete`() {
        val client = freshClient(Viewer.Anonymous, articlePolicy = ArticlePolicyWithDerived)
        seedData(client)

        // Anonymous delete should fail — RequireAuth create rule denies anonymous
        val article = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.query { where(Article.published eq true) }.firstOrNull()
        }
        assertNotNull(article)

        assertFailsWith<PrivacyDeniedException> {
            client.articles.delete(article)
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

            val deleted = scoped.articles.delete(article)
            assertTrue(deleted)
        }
    }

    // ---- deleteById: bypass LOAD, enforce DELETE ----

    @Test
    fun `deleteById bypasses LOAD privacy but enforces DELETE`() {
        val client = freshClient(Viewer.User(0L))
        val (alice, bob) = seedData(client)

        // Get Alice's draft ID via System
        val draftId = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq false)
            }.firstOrNull()!!.id
        }

        // Bob can't load the draft (anonymous/non-owner), but deleteById
        // bypasses LOAD. However, DELETE privacy should still deny Bob.
        client.withPrivacyContext(PrivacyContext(Viewer.User(bob.id))) { scoped ->
            val ex = assertFailsWith<PrivacyDeniedException> {
                scoped.articles.deleteById(draftId)
            }
            assertEquals(PrivacyOperation.DELETE, ex.operation)
        }

        // Verify the draft still exists
        val still = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.byId(draftId)
        }
        assertNotNull(still)
    }

    @Test
    fun `deleteById succeeds for owner even on LOAD-denied entity`() {
        val client = freshClient(Viewer.Anonymous)
        val (alice, _) = seedData(client)

        // Get Alice's draft ID via System — anonymous can't load it
        val draftId = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq false)
            }.firstOrNull()!!.id
        }

        // Alice can deleteById her own draft — LOAD bypassed, DELETE allowed
        client.withPrivacyContext(PrivacyContext(Viewer.User(alice.id))) { scoped ->
            val deleted = scoped.articles.deleteById(draftId)
            assertTrue(deleted)
        }

        val gone = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.byId(draftId)
        }
        assertNull(gone)
    }

    @Test
    fun `deleteById returns false for nonexistent ID`() {
        val client = freshClient(Viewer.System)
        seedData(client)

        val deleted = client.articles.deleteById(99999)
        assertFalse(deleted)
    }

    // ---- Bulk convenience methods ----

    @Test
    fun `createMany enforces per-row CREATE privacy`() {
        val client = freshClient(Viewer.Anonymous)
        val user = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.users.create { name = "U"; email = "u@test.com" }.save()
        }

        // Anonymous can't create — should fail on the first item
        assertFailsWith<PrivacyDeniedException> {
            client.articles.createMany(
                { title = "A"; published = true; authorId = user.id },
                { title = "B"; published = true; authorId = user.id },
            )
        }
    }

    @Test
    fun `createMany succeeds for authenticated viewer`() {
        val client = freshClient(Viewer.User(1L))
        val user = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.users.create { name = "U"; email = "u@test.com" }.save()
        }

        val articles = client.articles.createMany(
            { title = "A"; published = true; authorId = user.id },
            { title = "B"; published = false; authorId = user.id },
        )
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
        val remaining = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.query { where(Article.published eq true) }.all()
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

        val remaining = client.withPrivacyContext(PrivacyContext(Viewer.System)) { sys ->
            sys.articles.query { where(Article.authorId eq alice.id) }.all()
        }
        assertTrue(remaining.isEmpty())
    }

    // ---- No policy = no enforcement ----

    @Test
    fun `no policy means no privacy enforcement`() {
        val driver = PostgresDriver(dataSource)
        seedSchemas()
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE \"articles\", \"users\" RESTART IDENTITY CASCADE")
            }
        }

        // Client with no policies configured
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.Anonymous) }
        }

        val user = client.users.create { name = "U"; email = "u@test.com" }.save()
        client.articles.create { title = "Draft"; published = false; authorId = user.id }.save()

        // Without load policy, all() returns everything — no enforcement
        val all = client.articles.query().all()
        assertEquals(1, all.size)
    }
}
