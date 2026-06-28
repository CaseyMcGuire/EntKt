package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.EntError
import entkt.runtime.EntOperation
import entkt.runtime.EntResult
import entkt.runtime.EntityPolicy
import entkt.runtime.PrivacyContext
import entkt.runtime.PrivacyDecision
import entkt.runtime.PrivacyDeniedException
import entkt.runtime.QueryInterceptor
import entkt.runtime.Viewer
import entkt.runtime.allowAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for indexed query helpers. Proves the generated
 * `client.<repo>.indexes` staged builders delegate to the normal query
 * builder so privacy, read interceptors, predicate seeding, ordering, and
 * the unique terminals all behave like a hand-written query.
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
            val a = sys.users.create { name = "A"; email = "a@example.com" }.saveOrThrow()
            val b = sys.users.create { name = "B"; email = "b@example.com" }.saveOrThrow()
            sys.articles.create { title = "Alpha"; published = true; authorId = a.id }.saveOrThrow()
            sys.articles.create { title = "Mango"; published = false; authorId = a.id }.saveOrThrow()
            sys.articles.create { title = "Zebra"; published = true; authorId = a.id }.saveOrThrow()
            sys.articles.create { title = "Other"; published = true; authorId = b.id }.saveOrThrow()
            Seed(a.id, b.id)
        }

    @Test
    fun `query() seeds the indexed prefix predicate`() {
        val client = freshClient()
        val s = seed(client)

        val rows = client.articles.indexes.authorId(s.authorA).query().allOrThrow()
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
            .allOrThrow()
        assertEquals(setOf("Alpha", "Zebra"), rows.map { it.title }.toSet())
    }

    @Test
    fun `orderBy and limit work after an equality-prefix helper`() {
        val client = freshClient()
        val s = seed(client)

        val rows = client.articles.indexes
            .authorId(s.authorA)
            .query { orderBy(Article.title.asc()); limit(2) }
            .allOrThrow()
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
            .allOrThrow()
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
            .allOrThrow()
        // All of Author A's titles are >= "A"; ordered descending by title.
        assertEquals(listOf("Zebra", "Mango", "Alpha"), rows.map { it.title })
    }

    @Test
    fun `unique single-column orNull returns the row or null`() {
        val client = freshClient()
        seed(client)

        val found = client.users.indexes.email("a@example.com").orNull()
        assertNotNull(found)
        assertEquals("A", found.name)

        assertNull(client.users.indexes.email("missing@example.com").orNull())
    }

    @Test
    fun `unique orError returns Ok or NotFound with QUERY semantics`() {
        val client = freshClient()
        seed(client)

        val ok = client.users.indexes.email("b@example.com").orError()
        assertTrue(ok is EntResult.Ok)
        assertEquals("B", ok.value.name)

        val miss = client.users.indexes.email("nobody@example.com").orError()
        assertTrue(miss is EntResult.Err)
        val error = miss.error
        assertTrue(error is EntError.NotFound)
        assertEquals(EntOperation.QUERY, error.operation)
        assertNull(error.id)
    }

    @Test
    fun `LOAD privacy still runs through an index query terminal`() {
        val client = freshClient(viewer = Viewer.User(1L), articlePolicy = denyAllArticles)
        val s = seed(client)

        val result = client.articles.indexes.authorId(s.authorA).query().allOrError()
        assertTrue(result is EntResult.Err)
        assertTrue(result.error is EntError.PrivacyDenied)
        assertEquals(EntOperation.LOAD, result.error.operation)
    }

    @Test
    fun `LOAD privacy denial throws through a unique orNull terminal`() {
        val client = freshClient(viewer = Viewer.User(1L), userPolicy = denyAllUsers)
        seed(client)

        assertFailsWith<PrivacyDeniedException> {
            client.users.indexes.email("a@example.com").orNull()
        }
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
        val rows = client.articles.indexes.authorId(s.authorA).query().allOrThrow()
        assertEquals(setOf("Alpha", "Zebra"), rows.map { it.title }.toSet())
    }
}
