@file:OptIn(entkt.query.EntktInternal::class)

package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleLoadBatchPrivacyRule
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.schema.ArticleMeta
import entkt.integrationtest.schema.HighlightRect
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.NoopDriver
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.batchPrivacyRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Exercise generated LOAD wiring without database I/O or JSON copying. */
class LoadPrivacyInputTest {
    private val viewerContext = ViewerContext(Viewer.User(7L))
    private val driver = object : DatabaseDriver by NoopDriver {
        override fun <T> copyJsonValue(table: String, column: String, value: T): T =
            error("LOAD privacy must not copy JSON values")
    }

    private fun client(vararg rules: ArticleLoadBatchPrivacyRule): EntClient = EntClient(driver) {
        policies {
            articles(object : EntityPolicy<Article, ArticlePolicyScope> {
                override fun configure(scope: ArticlePolicyScope) = scope.run {
                    privacy { rules.forEach { load(it) } }
                }
            })
        }
    }

    @Test
    fun `scalar and batch LOAD rules share the original entity and mutable fields`() {
        val article = Article(
            id = 1L,
            title = "shared input",
            published = true,
            payload = byteArrayOf(1, 2),
            metadata = ArticleMeta("test", mutableListOf("original")),
            rects = mutableListOf(HighlightRect(1, 0.0, 0.0, 10.0, 20.0)),
            authorId = 7L,
        )
        val seen = mutableListOf<Article>()
        val scalar = ArticleLoadPrivacyRule { _, item ->
            seen += item
            PrivacyDecision.Continue
        }
        val batch: ArticleLoadBatchPrivacyRule = batchPrivacyRule { _, items ->
            items.decideEach { item ->
                seen += item
                PrivacyDecision.Allow
            }
        }

        val evaluation = client(scalar, batch).articles.evaluateLoadPrivacy(
            viewerContext,
            listOf(article, article),
        )

        assertEquals(4, seen.size)
        seen.forEach { item ->
            assertSame(article, item)
            assertSame(article.payload, item.payload)
            assertSame(article.metadata, item.metadata)
            assertSame(article.metadata!!.tags, item.metadata!!.tags)
            assertSame(article.rects, item.rects)
        }
        assertEquals(2, evaluation.allowedSubjects().size)
        evaluation.allowedSubjects().forEach { assertSame(article, it) }
    }

    @Test
    fun `LOAD preserves null mutable fields without invoking JSON copying`() {
        val article = Article(id = 1L, title = "null fields", published = false, authorId = 7L)
        var calls = 0
        val rule = ArticleLoadPrivacyRule { _, item ->
            calls++
            assertSame(article, item)
            assertNull(item.payload)
            assertNull(item.metadata)
            assertNull(item.rects)
            PrivacyDecision.Allow
        }

        val evaluation = client(rule).articles.evaluateLoadPrivacy(viewerContext, listOf(article))

        assertEquals(1, calls)
        assertSame(article, evaluation.allowedSubjects().single())
    }
}
