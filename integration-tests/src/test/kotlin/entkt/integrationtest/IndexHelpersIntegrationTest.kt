package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.allowAll
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.ReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for indexed query helpers. Proves the generated
 * `client.<repo>.indexes` staged builders delegate to the normal query
 * builder so privacy, read interceptors, predicate seeding, and ordering
 * all behave like a hand-written query. A unique index stage exposes a
 * single `find(): ReadResult<Entity?>` terminal (delegating to
 * `firstOrNull()`), where `Success(null)` is authoritative absence and
 * LOAD denial is `Failed(EntPrivacyDeniedException)` — plus `query()`
 * for the full query surface.
 *
 * Backing indexes: `Article.byAuthorTitle (author_id, title)` and the
 * synthesized unique index from `User.email.unique()`.
 */
class IndexHelpersIntegrationTest : PostgresTestBase() {

    private object AllowAllArticles : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(allowAll) }
        }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(allowAll) }
        }
    }

    private val denyAllArticles = object : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(ArticleLoadPrivacyRule { PrivacyDecision.Deny("hidden") }) }
        }
    }

    private val denyAllUsers = object : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { PrivacyDecision.Deny("hidden") }) }
        }
    }

    private fun freshClient(
        viewer: Viewer = Viewer.PrivacyBypass("test"),
        articlePolicy: EntityPolicy<Article, ArticlePolicyScope> = AllowAllArticles,
        userPolicy: EntityPolicy<User, UserPolicyScope> = OpenUser,
    ): EntClient {
        val driver = resetAndDriver()
        return EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
            policies {
                articles(articlePolicy)
                users(userPolicy)
            }
        }
    }

    private data class Seed(val authorA: Long, val authorB: Long)

    /** Author A: Alpha (pub), Mango (unpub), Zebra (pub). Author B: Other (pub). */
    private fun seed(client: EntClient): Seed =
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { sys ->
            val a = sys.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().getOrThrow()
            val b = sys.users.create { name = "B"; email = "b@example.com" }.saveAndLoad().getOrThrow()
            sys.articles.create { title = "Alpha"; published = true; authorId = a.id }.save().getOrThrow()
            sys.articles.create { title = "Mango"; published = false; authorId = a.id }.save().getOrThrow()
            sys.articles.create { title = "Zebra"; published = true; authorId = a.id }.save().getOrThrow()
            sys.articles.create { title = "Other"; published = true; authorId = b.id }.save().getOrThrow()
            Seed(a.id, b.id)
        }

    @Test
    fun `query() seeds the indexed prefix predicate`() {
        val client = freshClient()
        val s = seed(client)

        val rows = client.articles.indexes.authorId(s.authorA).query().all().getOrThrow()
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.authorId == s.authorA })
    }

    @Test
    fun `extra where() in query block is ANDed with the indexed prefix`() {
        val client = freshClient()
        val s = seed(client)

        val rows = client.articles.indexes
            .authorId(s.authorA)
            .query { where(Article.published eq true) }
            .all()
            .getOrThrow()
        assertEquals(setOf("Alpha", "Zebra"), rows.map { it.title }.toSet())
    }

    @Test
    fun `orderBy and limit work after an equality-prefix helper`() {
        val client = freshClient()
        val s = seed(client)

        val rows = client.articles.indexes
            .authorId(s.authorA)
            .query { orderBy(Article.title.asc()); limit(2) }
            .all()
            .getOrThrow()
        assertEquals(listOf("Alpha", "Mango"), rows.map { it.title })
    }

    @Test
    fun `range block lowers to comparable bounds and stays scoped to the prefix`() {
        val client = freshClient()
        val s = seed(client)

        val rows = client.articles.indexes
            .authorId(s.authorA)
            .title { gte("M") }
            .query()
            .all()
            .getOrThrow()
        // Author A's titles >= "M": Mango, Zebra (Alpha < "M"); Author B's
        // "Other" excluded by the seeded author_id prefix.
        assertEquals(setOf("Mango", "Zebra"), rows.map { it.title }.toSet())
    }

    @Test
    fun `orderBy works after a range-block helper`() {
        val client = freshClient()
        val s = seed(client)

        val rows = client.articles.indexes
            .authorId(s.authorA)
            .title { gte("A") }
            .query { orderBy(Article.title.desc()) }
            .all()
            .getOrThrow()
        // All of Author A's titles are >= "A"; ordered descending by title.
        assertEquals(listOf("Zebra", "Mango", "Alpha"), rows.map { it.title })
    }

    @Test
    fun `unique find returns the row or null via getOrThrow`() {
        val client = freshClient()
        seed(client)

        val found = client.users.indexes.email("a@example.com").find().getOrThrow()
        assertNotNull(found)
        assertEquals("A", found.name)

        assertNull(client.users.indexes.email("missing@example.com").find().getOrThrow())
    }

    @Test
    fun `unique find reports absence as authoritative Success(null)`() {
        val client = freshClient()
        seed(client)

        val hit = client.users.indexes.email("b@example.com").find()
        val ok = assertIs<ReadResult.Success<User?>>(hit)
        assertEquals("B", assertNotNull(ok.value).name)

        // No not-found failure exists on this surface: a missing row is
        // an authoritative Success(null), not a Failed.
        val miss = client.users.indexes.email("nobody@example.com").find()
        val absent = assertIs<ReadResult.Success<User?>>(miss)
        assertNull(absent.value)
    }

    @Test
    fun `LOAD privacy still runs through an index query terminal`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyAllArticles)
        val s = seed(client)

        val result = client.articles.indexes.authorId(s.authorA).query().all()
        val failed = assertIs<ReadResult.Failed>(result)
        val denied = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.Root>(denied.origin)
    }

    @Test
    fun `LOAD privacy denial surfaces as Failed through the unique find terminal`() {
        val client = freshClient(viewer = Viewer.User(1L), userPolicy = denyAllUsers)
        seed(client)

        val result = client.users.indexes.email("a@example.com").find()
        val failed = assertIs<ReadResult.Failed>(result)
        val denied = assertIs<EntPrivacyDeniedException>(failed.exception)
        assertIs<LoadDenialOrigin.Root>(denied.origin)
        assertEquals(1, denied.denials.size)
    }

    @Test
    fun `read interceptors still fire through an index query terminal`() {
        val driver = resetAndDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies {
                articles(AllowAllArticles)
                users(OpenUser)
            }
            interceptors {
                articles(
                    QueryInterceptor { scope, _ -> scope.addPredicate(Article.published eq true) },
                    name = "published-only",
                )
            }
        }
        val s = seed(client)

        // The interceptor's published = true predicate composes with the
        // seeded author_id prefix: Author A's published rows are Alpha, Zebra.
        val rows = client.articles.indexes.authorId(s.authorA).query().all().getOrThrow()
        assertEquals(setOf("Alpha", "Zebra"), rows.map { it.title }.toSet())
    }
}
