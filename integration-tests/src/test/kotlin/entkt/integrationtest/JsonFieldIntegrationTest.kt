package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.schema.ArticleMeta
import entkt.integrationtest.support.PostgresTestBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for typed JSON fields through the GENERATED client
 * (codegen unit tests only check generated text; this proves the generated
 * `ArticleMeta.serializer()` references compile and the full create/read/
 * update + isNull path runs against Postgres). System viewer bypasses
 * fail-closed privacy.
 */
class JsonFieldIntegrationTest : PostgresTestBase() {

    private fun client() = sysClient(resetAndDriver())

    @Test
    fun `create, read, and update a typed JSON field`() {
        val client = client()
        val author = client.users.create { name = "A"; email = "a@example.com" }.save()

        val meta = ArticleMeta(source = "rss", tags = listOf("kotlin", "orm"))
        val created = client.articles.create {
            title = "Hello"
            authorId = author.id
            metadata = meta
        }.save()
        assertEquals(meta, created.metadata, "create round-trips the typed JSON value")

        val read = client.articles.byIdOrNull(created.id)!!
        assertEquals(meta, read.metadata, "read decodes the typed JSON value")

        val newMeta = ArticleMeta(source = null, tags = listOf("updated"))
        val updated = client.articles.update(created.id) { metadata = newMeta }.saveOrThrow()
        assertEquals(newMeta, updated.metadata, "update round-trips the new value")
    }

    @Test
    fun `nullable JSON round-trips null and supports isNull filtering`() {
        val client = client()
        val author = client.users.create { name = "A"; email = "a2@example.com" }.save()

        val withoutMeta = client.articles.create { title = "n"; authorId = author.id }.save()
        assertNull(withoutMeta.metadata, "an omitted JSON field is null")

        val withMeta = client.articles.create {
            title = "m"; authorId = author.id; metadata = ArticleMeta(null, listOf("x"))
        }.save()

        val nullMetaIds = client.articles
            .query { where(Article.metadata.isNull()) }
            .allOrThrow()
            .map { it.id }
            .toSet()
        assertTrue(withoutMeta.id in nullMetaIds, "isNull matches the null-metadata row")
        assertTrue(withMeta.id !in nullMetaIds, "isNull excludes the row with metadata")
    }
}
