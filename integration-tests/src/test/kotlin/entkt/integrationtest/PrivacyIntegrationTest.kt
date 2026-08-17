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
import entkt.runtime.query.requireLoaded
import entkt.runtime.privacy.allowAll
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.ReadResult
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

private val AllowPublished = ArticleLoadPrivacyRule { _, item ->
    if (item.entity.published) PrivacyDecision.Allow else PrivacyDecision.Continue
}

private val AllowAuthorLoad = ArticleLoadPrivacyRule { context, item ->
    val viewer = context.privacy.viewer as? Viewer.User ?: return@ArticleLoadPrivacyRule PrivacyDecision.Continue
    if (viewer.id == item.entity.authorId) PrivacyDecision.Allow else PrivacyDecision.Continue
}

private val RequireAuth = ArticleCreatePrivacyRule { context, _ ->
    // Fail-closed: an authenticated viewer must be explicitly allowed, not left
    // to fall through (which would now deny).
    if (context.privacy.viewer is Viewer.Anonymous) PrivacyDecision.Deny("authentication required")
    else PrivacyDecision.Allow
}

private val OwnerCanDelete = ArticleDeletePrivacyRule { context, item ->
    val viewer = context.privacy.viewer as? Viewer.User
        ?: return@ArticleDeletePrivacyRule PrivacyDecision.Deny("authentication required")
    if (viewer.id == item.entity.authorId) PrivacyDecision.Allow
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
private val OwnerCanUpdate = ArticleUpdatePrivacyRule { context, item ->
    val viewer = context.privacy.viewer as? Viewer.User
        ?: return@ArticleUpdatePrivacyRule PrivacyDecision.Deny("authentication required")
    if (viewer.id == item.before.authorId) PrivacyDecision.Allow
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

private val AllowSelfOnly = UserLoadPrivacyRule { context, item ->
    val viewer = context.privacy.viewer as? Viewer.User ?: return@UserLoadPrivacyRule PrivacyDecision.Continue
    if (viewer.id == item.entity.id) PrivacyDecision.Allow else PrivacyDecision.Continue
}

// ---- Tests ----

/**
 * End-to-end privacy enforcement through the canonical result algebra.
 * A read LOAD denial never throws: it is
 * `ReadResult.Failed(EntPrivacyDeniedException(origin, denials))` —
 * `LoadDenialOrigin.Root` for the terminal's own selection (one keyed
 * denial per denied row in encountered query order), `EagerEdge` for a
 * denied eager-load target. A mutation privacy denial is
 * `MutationResult.Failed(EntMutationPrivacyDeniedException(...))`
 * carrying the denied operation and `writeState = NotPersisted` for
 * pre-write rejections. Raw terminals (`rawCount` / `rawExists`) skip
 * LOAD privacy and return their answer as `ReadResult`.
 */
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
        driver.registerAll(EntClient.SCHEMAS)
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
        // Use the privacy bypass to seed past create privacy
        val system = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val alice = sys.users.create { name = "Alice"; email = "alice@test.com" }.saveAndLoad().getOrThrow()
            val bob = sys.users.create { name = "Bob"; email = "bob@test.com" }.saveAndLoad().getOrThrow()

            sys.articles.create { title = "Public by Alice"; published = true; authorId = alice.id }.save().getOrThrow()
            sys.articles.create { title = "Draft by Alice"; published = false; authorId = alice.id }.save().getOrThrow()
            sys.articles.create { title = "Public by Bob"; published = true; authorId = bob.id }.save().getOrThrow()
            sys.articles.create { title = "Draft by Bob"; published = false; authorId = bob.id }.save().getOrThrow()

            alice to bob
        }
        return system
    }

    // ---- LOAD: query.all() ----

    @Test
    fun `all fails when any result entity is denied`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        // Anonymous can see published but not drafts. Strict all()
        // evaluates the full selected window and reports every denied
        // root row — one keyed denial per draft, no hydrated data.
        val failed = assertIs<ReadResult.Failed>(client.articles.query().all())
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertEquals(LoadDenialOrigin.Root, ex.origin)
        assertEquals(2, ex.denials.size)
        assertTrue(ex.denials.all { it.entityType == "Article" })
    }

    @Test
    fun `all succeeds when all results are allowed`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        // Query only published — all allowed
        val articles = client.articles.query {
            where(Article.published eq true)
        }.all().getOrThrow()
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
            }.all().getOrThrow()
        }
        assertEquals(2, articles.size)
    }

    // ---- LOAD: query.firstOrNull() ----

    @Test
    fun `firstOrNull fails when the selected first row is denied`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        // The denied selected first row is reported with exactly one
        // keyed denial; no second row is scanned.
        val failed = assertIs<ReadResult.Failed>(
            client.articles.query {
                where(Article.published eq false)
            }.firstOrNull(),
        )
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertEquals(LoadDenialOrigin.Root, ex.origin)
        assertEquals(1, ex.denials.size)
    }

    @Test
    fun `firstOrNull returns null when no row matches`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val result = client.articles.query {
            where(Article.title eq "nonexistent")
        }.firstOrNull().getOrThrow()
        assertNull(result)
    }

    @Test
    fun `firstOrNull returns entity when allowed`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val result = client.articles.query {
            where(Article.published eq true)
        }.firstOrNull().getOrThrow()
        assertNotNull(result)
        assertTrue(result.published)
    }

    // ---- LOAD: repo.findById() ----

    @Test
    fun `findById fails on denied entity`() {
        val client = freshClient(Viewer.Anonymous)
        val (alice, _) = seedData(client)

        // Find Alice's draft via the bypass
        val draft = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq false)
            }.firstOrNull().getOrThrow()
        }
        assertNotNull(draft)

        val failed = assertIs<ReadResult.Failed>(client.articles.findById(draft.id))
        val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertEquals(LoadDenialOrigin.Root, ex.origin)
        assertEquals(draft.id, ex.denials.single().entityKey.value)
    }

    @Test
    fun `findById returns allowed entity`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val published = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query { where(Article.published eq true) }.firstOrNull().getOrThrow()
        }
        assertNotNull(published)

        val result = client.articles.findById(published.id).getOrThrow()
        assertNotNull(result)
        assertEquals(published.id, result.id)
    }

    // ---- Viewer.PrivacyBypass bypass ----

    @Test
    fun `System viewer sees all entities`() {
        val client = freshClient(Viewer.PrivacyBypass("test"))
        seedData(client)

        val all = client.articles.query().all().getOrThrow()
        assertEquals(4, all.size)
    }

    @Test
    fun `System viewer can create without auth`() {
        val client = freshClient(Viewer.PrivacyBypass("test"))
        val user = client.users.create { name = "Sys"; email = "sys@test.com" }.saveAndLoad().getOrThrow()
        val article = client.articles.create {
            title = "System Article"
            published = false
            authorId = user.id
        }.saveAndLoad().getOrThrow()
        assertEquals("System Article", article.title)
    }

    @Test
    fun `System viewer can delete any entity`() {
        val client = freshClient(Viewer.PrivacyBypass("test"))
        val (alice, _) = seedData(client)

        val article = client.articles.query {
            where(Article.authorId eq alice.id)
        }.all().getOrThrow().first()

        client.articles.delete(article).getOrThrow()
    }

    // ---- rawCount() ----

    @Test
    fun `rawCount reports all rows regardless of privacy`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val count = client.articles.query().rawCount().getOrThrow()
        assertEquals(4L, count)
    }

    @Test
    fun `rawCount with predicate counts matching rows`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val count = client.articles.query {
            where(Article.published eq true)
        }.rawCount().getOrThrow()
        assertEquals(2L, count)
    }

    // ---- rawExists() ----

    @Test
    fun `rawExists ignores LOAD privacy and returns true if any storage row matches`() {
        // The drafts are LOAD-denied to Anonymous, but rawExists doesn't
        // evaluate privacy: it sees the existing-in-storage row and
        // returns Success(true).
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        val result = client.articles.query {
            where(Article.published eq false)
        }.rawExists().getOrThrow()
        assertTrue(result)
    }

    // ---- CREATE privacy ----

    @Test
    fun `create denied for anonymous viewer`() {
        val client = freshClient(Viewer.Anonymous)
        val user = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "U"; email = "u@test.com" }.saveAndLoad().getOrThrow()
        }

        val failed = assertIs<MutationResult.Failed>(
            client.articles.create {
                title = "Anon Post"
                published = true
                authorId = user.id
            }.save(),
        )
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals("authentication required", ex.reason)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
    }

    @Test
    fun `create allowed for authenticated viewer`() {
        val client = freshClient(Viewer.User(1L))
        val user = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "U"; email = "u@test.com" }.saveAndLoad().getOrThrow()
        }

        val article = client.articles.create {
            title = "Auth Post"
            published = true
            authorId = user.id
        }.saveAndLoad().getOrThrow()
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
            }.firstOrNull().getOrThrow()
            assertNotNull(article)

            val failed = assertIs<MutationResult.Failed>(scoped.articles.delete(article))
            article to assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        }
        assertEquals(EntOperation.DELETE, ex.operation)
        assertEquals("only the author can delete", ex.reason)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)

        // Verify the article still exists
        val still = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.findById(aliceArticle.id).getOrThrow()
        }
        assertNotNull(still)
    }

    @Test
    fun `delete authorizes against the current row, not the caller's entity`() {
        val client = freshClient(Viewer.User(0L))
        val (alice, bob) = seedData(client)

        val aliceArticle = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq true)
            }.firstOrNull().getOrThrow()
        }
        assertNotNull(aliceArticle)

        val ex = client.withPrivacyContext(PrivacyContext(Viewer.User(bob.id))) { scoped ->
            // Entities are public data classes, so Bob can hand delete
            // a copy claiming HE is the author of Alice's article. The
            // delete pipeline must treat the entity as an id handle and
            // authorize against the reloaded row — where the author is
            // still Alice — not against these fabricated fields.
            val forged = aliceArticle.copy(authorId = bob.id)
            val failed = assertIs<MutationResult.Failed>(scoped.articles.delete(forged))
            assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        }
        assertEquals(EntOperation.DELETE, ex.operation)
        assertEquals("only the author can delete", ex.reason)

        // The row survived.
        val still = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.findById(aliceArticle.id).getOrThrow()
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
            }.firstOrNull().getOrThrow()
            assertNotNull(article)

            scoped.articles.delete(article).getOrThrow()
            article.id
        }

        val gone = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.findById(articleId).getOrThrow()
        }
        assertNull(gone)
    }

    // ---- withPrivacyContext ----

    @Test
    fun `withPrivacyContext scopes viewer correctly`() {
        val client = freshClient(Viewer.Anonymous)
        seedData(client)

        // Anonymous: drafts are denied — strict all() fails.
        val before = assertIs<ReadResult.Failed>(client.articles.query().all())
        assertIs<EntPrivacyDeniedException>(before.exception)

        // Elevate to the bypass within a block
        val all = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query().all().getOrThrow()
        }
        assertEquals(4, all.size)

        // Back to anonymous: still denied
        val after = assertIs<ReadResult.Failed>(client.articles.query().all())
        assertIs<EntPrivacyDeniedException>(after.exception)
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
        }.all().getOrThrow()
        assertEquals(2, articles.size)
        for (article in articles) {
            assertNotNull(article.edges.author.requireLoaded())
        }
    }

    @Test
    fun `eager loaded edge fails when target entity is denied`() {
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
            }.all().getOrThrow()
            assertEquals(1, articles.size)
            assertNotNull(articles[0].edges.author.requireLoaded())

            // Now query ALL published articles with eager author. Bob's article is
            // published (allowed), but eager-loading Bob as the author is denied
            // because RestrictiveUserPolicy only allows viewing yourself — the
            // denial carries the EagerEdge origin naming the offending edge path.
            val failed = assertIs<ReadResult.Failed>(
                scoped.articles.query {
                    where(Article.published eq true)
                    withAuthor()
                }.all(),
            )
            val ex = assertIs<EntPrivacyDeniedException>(failed.exception)
            val origin = assertIs<LoadDenialOrigin.EagerEdge>(ex.origin)
            assertEquals("author", origin.path.single().edgeName)
            assertEquals("User", ex.denials.single().entityType)
        }
    }

    // ---- Transactions ----

    @Test
    fun `privacy enforced within transactions`() {
        val client = freshClient(Viewer.Anonymous)

        val user = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "U"; email = "u@test.com" }.saveAndLoad().getOrThrow()
        }

        // Anonymous create fails inside a transaction too: the denial is
        // recorded on the scope and the boundary reports it as the
        // transaction's failure with rollback confirmed.
        val result = client.withTransaction { tx ->
            tx.articles.create {
                title = "TX Post"
                published = true
                authorId = user.id
            }.save().orRollback()
        }
        val failed = assertIs<TransactionResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals(TransactionFailureState.NotCommitted, failed.transactionState)
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
            }.firstOrNull().getOrThrow()
            assertNotNull(article)

            val failed = assertIs<MutationResult.Failed>(
                scoped.articles.update(article.id) { title = "Hacked" }.save(),
            )
            val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
            assertEquals(EntOperation.UPDATE, ex.operation)
            assertEquals("only the author can update", ex.reason)
            assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        }

        // Verify title unchanged
        val unchanged = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq true)
            }.firstOrNull().getOrThrow()
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
            }.firstOrNull().getOrThrow()!!

            val updated = scoped.articles.update(article.id) { title = "Updated Title" }.saveAndLoad().getOrThrow()
            assertEquals("Updated Title", updated.title)
        }
    }

    // ---- Derived policies ----

    @Test
    fun `updateDerivesFromCreate uses create rules for update`() {
        val client = freshClient(Viewer.User(0L), articlePolicy = ArticlePolicyWithDerived)
        seedData(client)

        // Get an article via the bypass
        val article = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query { where(Article.published eq true) }.firstOrNull().getOrThrow()
        }
        assertNotNull(article)

        // Anonymous update fails — RequireAuth create rule denies anonymous.
        // The denial still reports the UPDATE operation (the denied decision).
        client.withPrivacyContext(PrivacyContext(Viewer.Anonymous)) { anon ->
            val failed = assertIs<MutationResult.Failed>(
                anon.articles.update(article.id) { title = "Anon Update" }.save(),
            )
            val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
            assertEquals(EntOperation.UPDATE, ex.operation)
            assertEquals("authentication required", ex.reason)
        }
    }

    @Test
    fun `deleteDerivesFromCreate uses create rules for delete`() {
        val client = freshClient(Viewer.Anonymous, articlePolicy = ArticlePolicyWithDerived)
        seedData(client)

        // Anonymous delete fails — RequireAuth create rule denies anonymous
        val article = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query { where(Article.published eq true) }.firstOrNull().getOrThrow()
        }
        assertNotNull(article)

        val failed = assertIs<MutationResult.Failed>(client.articles.delete(article))
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.DELETE, ex.operation)
        assertEquals("authentication required", ex.reason)
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
            }.firstOrNull().getOrThrow()
            assertNotNull(article)

            scoped.articles.delete(article).getOrThrow()
        }
    }

    // ---- deleteById: bypass LOAD, enforce DELETE ----

    @Test
    fun `deleteById bypasses LOAD privacy but enforces DELETE`() {
        val client = freshClient(Viewer.User(0L))
        val (alice, bob) = seedData(client)

        // Get Alice's draft ID via the bypass
        val draftId = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq false)
            }.firstOrNull().getOrThrow()!!.id
        }

        // Bob can't load the draft (non-owner), but deleteById's reload
        // bypasses LOAD — the delete-side rule is the authoritative
        // check, and DELETE privacy still denies Bob.
        client.withPrivacyContext(PrivacyContext(Viewer.User(bob.id))) { scoped ->
            val failed = assertIs<MutationResult.Failed>(scoped.articles.deleteById(draftId))
            val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
            assertEquals(EntOperation.DELETE, ex.operation)
        }

        // Verify the draft still exists
        val still = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.findById(draftId).getOrThrow()
        }
        assertNotNull(still)
    }

    @Test
    fun `deleteById succeeds for owner even on LOAD-denied entity`() {
        val client = freshClient(Viewer.Anonymous)
        val (alice, _) = seedData(client)

        // Get Alice's draft ID via the bypass — anonymous can't load it
        val draftId = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query {
                where(Article.authorId eq alice.id)
                where(Article.published eq false)
            }.firstOrNull().getOrThrow()!!.id
        }

        // Alice can deleteById her own draft — LOAD bypassed, DELETE allowed;
        // Success(true) means this call deleted the row.
        client.withPrivacyContext(PrivacyContext(Viewer.User(alice.id))) { scoped ->
            assertTrue(scoped.articles.deleteById(draftId).getOrThrow())
        }

        val gone = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.findById(draftId).getOrThrow()
        }
        assertNull(gone)
    }

    @Test
    fun `deleteById returns Success false for nonexistent ID`() {
        val client = freshClient(Viewer.PrivacyBypass("test"))
        seedData(client)

        assertEquals(MutationResult.Success(false), client.articles.deleteById(99999))
    }

    // ---- Bulk convenience methods ----

    @Test
    fun `createMany surfaces per-row CREATE privacy denial as Failed`() {
        val client = freshClient(Viewer.Anonymous)
        val user = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "U"; email = "u@test.com" }.saveAndLoad().getOrThrow()
        }

        // Anonymous can't create — the first item fails pre-write. With no
        // caller transaction, createMany owns its own transaction and rolls
        // the whole batch back: no committed subset remains.
        val result = client.articles.createMany(
            { title = "A"; published = true; authorId = user.id },
            { title = "B"; published = true; authorId = user.id },
        )
        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        assertEquals(0L, client.articles.query().rawCount().getOrThrow())
    }

    @Test
    fun `createMany succeeds for authenticated viewer`() {
        val client = freshClient(Viewer.User(1L))
        val user = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.create { name = "U"; email = "u@test.com" }.saveAndLoad().getOrThrow()
        }

        // No caller transaction needed: createMany is atomic via its own
        // EntKt-owned transaction when the caller has none.
        val articles = client.articles.createMany(
            { title = "A"; published = true; authorId = user.id },
            { title = "B"; published = false; authorId = user.id },
        ).getOrThrow()
        assertEquals(2, articles.size)
        assertEquals("A", articles[0].title)
        assertEquals("B", articles[1].title)
    }

    @Test
    fun `deleteMany enforces per-row DELETE privacy atomically`() {
        val client = freshClient(Viewer.User(0L))
        val (alice, bob) = seedData(client)

        // Bob tries to deleteMany all published articles — Alice's article
        // denies, and the whole batch shares one transaction, so nothing
        // is deleted (no committed subset after the confirmed rollback).
        client.withPrivacyContext(PrivacyContext(Viewer.User(bob.id))) { scoped ->
            val failed = assertIs<MutationResult.Failed>(
                scoped.articles.deleteMany(Article.published eq true),
            )
            val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
            assertEquals(EntOperation.DELETE, ex.operation)
            assertEquals("only the author can delete", ex.reason)
        }

        // Both published articles survived the rollback.
        val remaining = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query { where(Article.published eq true) }.all().getOrThrow()
        }
        assertEquals(2, remaining.size)
    }

    @Test
    fun `deleteMany succeeds when viewer owns all matched entities`() {
        val client = freshClient(Viewer.User(0L))
        val (alice, _) = seedData(client)

        client.withPrivacyContext(PrivacyContext(Viewer.User(alice.id))) { scoped ->
            val count = scoped.articles.deleteMany(Article.authorId eq alice.id).getOrThrow()
            assertEquals(2, count)
        }

        val remaining = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.articles.query { where(Article.authorId eq alice.id) }.all().getOrThrow()
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
        val createFailed = assertIs<MutationResult.Failed>(
            client.users.create { name = "U"; email = "u@test.com" }.save(),
        )
        val createEx = assertIs<EntMutationPrivacyDeniedException>(createFailed.exception)
        assertEquals(EntOperation.CREATE, createEx.operation)

        // Seed a row via the bypass, then confirm LOAD denies too.
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            val u = sys.users.create { name = "U"; email = "u2@test.com" }.saveAndLoad().getOrThrow()
            sys.articles.create { title = "Draft"; published = false; authorId = u.id }.save().getOrThrow()
        }
        val readFailed = assertIs<ReadResult.Failed>(client.articles.query().all())
        assertIs<EntPrivacyDeniedException>(readFailed.exception)
    }
}
