@file:OptIn(entkt.query.EntktInternal::class)

package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DirectToManyQuery
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.RelatedRows
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.allowAll
import entkt.runtime.query.EdgeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** End-to-end parity gate for generated terminals delegated to ReadQueryEvaluator. */
class ReadQueryEvaluatorIntegrationTest : PostgresTestBase() {
    private object OpenUsers : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(allowAll) }
        }
    }

    private object OpenArticles : EntityPolicy<Article, ArticlePolicyScope> {
        override fun configure(scope: ArticlePolicyScope) = scope.run {
            privacy { load(allowAll) }
        }
    }

    private class RecordingDriver(
        private val delegate: DatabaseDriver,
    ) : DatabaseDriver by delegate {
        val rootQueries = mutableListOf<RootQuery>()
        val directToManyQueries = mutableListOf<DirectToManyQuery>()

        data class RootQuery(
            val table: String,
            val limit: Int?,
            val offset: Int?,
        )

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            rootQueries += RootQuery(table, limit, offset)
            return delegate.query(table, predicates, orderBy, limit, offset)
        }

        override fun queryDirectToMany(query: DirectToManyQuery): RelatedRows {
            directToManyQueries += query
            return delegate.queryDirectToMany(query)
        }

        fun clearReads() {
            rootQueries.clear()
            directToManyQueries.clear()
        }
    }

    @Test
    fun `generated all and first terminals delegate while retaining graph completion`() {
        val driver = RecordingDriver(resetAndDriver())
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(7L)) }
            policies {
                users(OpenUsers)
                articles(OpenArticles)
            }
        }
        val author = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("seed"))) { seed ->
            val created = seed.users.create {
                name = "Ada"
                email = "ada@example.com"
            }.saveAndLoad().getOrThrow()
            seed.articles.create {
                title = "First"
                authorId = created.id
            }.save().getOrThrow()
            seed.articles.create {
                title = "Second"
                authorId = created.id
            }.save().getOrThrow()
            created
        }

        driver.clearReads()
        val all = client.users.query {
            where(User.id eq author.id)
        }.all().getOrThrow()

        assertEquals(listOf(author.id), all.map { it.id })
        assertEquals(listOf(RecordingDriver.RootQuery("users", null, null)), driver.rootQueries)
        assertEquals(emptyList(), driver.directToManyQueries)

        driver.clearReads()
        val first = client.users.query {
            where(User.id eq author.id)
            loadArticles { orderBy(Article.id.asc()) }
        }.firstOrNull().getOrThrow()

        assertNotNull(first)
        val articles = assertIs<EdgeState.Loaded<List<Article>>>(first.edges.articles).value
        assertEquals(listOf("First", "Second"), articles.map { it.title })
        assertEquals(listOf(RecordingDriver.RootQuery("users", 1, null)), driver.rootQueries)
        assertEquals(1, driver.directToManyQueries.size)
        assertEquals(listOf(author.id), driver.directToManyQueries.single().sourceKeys)
    }
}
