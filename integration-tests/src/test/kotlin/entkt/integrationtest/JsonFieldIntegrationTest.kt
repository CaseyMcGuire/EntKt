package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.schema.ArticleMeta
import entkt.integrationtest.schema.HighlightRect
import entkt.integrationtest.support.PostgresTestBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for typed JSON fields through the GENERATED client
 * (codegen unit tests only check generated text; this proves the generated
 * `ArticleMeta.serializer()` references compile and the full create/read/
 * update + isNull path runs against Postgres). Terminals follow the
 * canonical result algebra — `saveAndLoad()` / `findById()` / `all()` —
 * projected with `getOrThrow()`. System viewer bypasses fail-closed
 * privacy.
 */
class JsonFieldIntegrationTest : PostgresTestBase() {

    private fun client() = sysClient(resetAndDriver())

    @Test
    fun `create, read, and update a typed JSON field`() {
        val client = client()
        val author = client.users.create { name = "A"; email = "a@example.com" }.saveAndLoad().getOrThrow()

        val meta = ArticleMeta(source = "rss", tags = listOf("kotlin", "orm"))
        val created = client.articles.create {
            title = "Hello"
            authorId = author.id
            metadata = meta
        }.saveAndLoad().getOrThrow()
        assertEquals(meta, created.metadata, "create round-trips the typed JSON value")

        val read = client.articles.findById(created.id).getOrThrow()!!
        assertEquals(meta, read.metadata, "read decodes the typed JSON value")

        val newMeta = ArticleMeta(source = null, tags = listOf("updated"))
        val updated = client.articles.update(created.id) { metadata = newMeta }.saveAndLoad().getOrThrow()
        assertEquals(newMeta, updated.metadata, "update round-trips the new value")
    }

    @Test
    fun `generic JSON field round-trips with the element type intact`() {
        val client = client()
        val author = client.users.create { name = "A"; email = "a3@example.com" }.saveAndLoad().getOrThrow()

        val regions = listOf(
            HighlightRect(page = 1, x = 0.1, y = 0.2, w = 0.3, h = 0.4),
            HighlightRect(page = 2, x = 0.5, y = 0.6, w = 0.7, h = 0.8),
        )
        val created = client.articles.create {
            title = "g"
            authorId = author.id
            rects = regions
        }.saveAndLoad().getOrThrow()
        assertEquals(regions, created.rects, "create round-trips the typed list")

        // assertEquals against HighlightRect data classes proves the driver
        // decoded real elements via ListSerializer — a raw List<Map> (the
        // pre-KType failure mode) would not compare equal.
        val read = client.articles.findById(created.id).getOrThrow()!!
        assertEquals(regions, read.rects, "read decodes List<HighlightRect>, not List<Map>")

        val shorter = regions.take(1)
        val updated = client.articles.update(created.id) { rects = shorter }.saveAndLoad().getOrThrow()
        assertEquals(shorter, updated.rects, "update round-trips the new list")

        val cleared = client.articles.update(created.id) { rects = null }.saveAndLoad().getOrThrow()
        assertNull(cleared.rects, "a nullable generic JSON field round-trips null")
    }

    @Test
    fun `nullable JSON round-trips null and supports isNull filtering`() {
        val client = client()
        val author = client.users.create { name = "A"; email = "a2@example.com" }.saveAndLoad().getOrThrow()

        val withoutMeta = client.articles.create { title = "n"; authorId = author.id }.saveAndLoad().getOrThrow()
        assertNull(withoutMeta.metadata, "an omitted JSON field is null")

        val withMeta = client.articles.create {
            title = "m"; authorId = author.id; metadata = ArticleMeta(null, listOf("x"))
        }.saveAndLoad().getOrThrow()

        val nullMetaIds = client.articles
            .query { where(Article.metadata.isNull()) }
            .all()
            .getOrThrow()
            .map { it.id }
            .toSet()
        assertTrue(withoutMeta.id in nullMetaIds, "isNull matches the null-metadata row")
        assertTrue(withMeta.id !in nullMetaIds, "isNull excludes the row with metadata")
    }
}
