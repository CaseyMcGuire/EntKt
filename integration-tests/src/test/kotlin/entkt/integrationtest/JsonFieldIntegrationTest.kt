package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticleCreatePrivacyRule
import entkt.integrationtest.ent.ArticleCreateValidationRule
import entkt.integrationtest.ent.ArticleLoadPrivacyRule
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.ArticleUpdatePrivacyRule
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.schema.ArticleMeta
import entkt.integrationtest.schema.HighlightRect
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.mutation.FieldPatch
import entkt.runtime.validation.ValidationDecision
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

    @Test
    fun `privacy and validation rules receive detached typed JSON snapshots`() {
        val driver = resetAndDriver()
        val system = sysClient(driver)
        val author = system.users.create {
            name = "A"
            email = "json-snapshots@example.com"
        }.saveAndLoad().getOrThrow()

        val firstTags = mutableListOf("first")
        val secondTags = mutableListOf("second")
        val seenByPrivacy = mutableListOf<List<String>>()
        val seenByValidation = mutableListOf<List<String>>()
        val seenByLoad = mutableListOf<List<String>>()

        val policy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    create(
                        ArticleCreatePrivacyRule { context ->
                            @Suppress("UNCHECKED_CAST")
                            (context.candidate.metadata!!.tags as MutableList<String>) += "privacy mutation"
                            PrivacyDecision.Continue
                        },
                        ArticleCreatePrivacyRule { context ->
                            seenByPrivacy += context.candidate.metadata!!.tags.toList()
                            PrivacyDecision.Allow
                        },
                    )
                    load(
                        ArticleLoadPrivacyRule { context ->
                            @Suppress("UNCHECKED_CAST")
                            (context.entity.metadata!!.tags as MutableList<String>) += "load mutation"
                            PrivacyDecision.Continue
                        },
                        ArticleLoadPrivacyRule { context ->
                            seenByLoad += context.entity.metadata!!.tags.toList()
                            PrivacyDecision.Allow
                        },
                    )
                }
                validation {
                    create(
                        ArticleCreateValidationRule { context ->
                            @Suppress("UNCHECKED_CAST")
                            (context.candidate.metadata!!.tags as MutableList<String>) += "validation mutation"
                            ValidationDecision.Valid
                        },
                        ArticleCreateValidationRule { context ->
                            seenByValidation += context.candidate.metadata!!.tags.toList()
                            ValidationDecision.Valid
                        },
                    )
                }
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(author.id)) }
            policies { articles(policy) }
        }

        val created = client.articles.createMany(
            {
                title = "first"
                authorId = author.id
                metadata = ArticleMeta("test", firstTags)
            },
            {
                title = "second"
                authorId = author.id
                metadata = ArticleMeta("test", secondTags)
            },
        ).getOrThrow()

        val expected = listOf(listOf("first"), listOf("second"))
        assertEquals(expected, seenByPrivacy, "each privacy rule gets a fresh JSON graph")
        assertEquals(expected, seenByValidation, "each validation rule gets a fresh JSON graph")
        assertEquals(expected, seenByLoad, "each LOAD rule gets a fresh JSON graph")
        assertEquals(listOf("first"), firstTags, "rule mutation cannot reach caller-owned input")
        assertEquals(listOf("second"), secondTags, "rule mutation cannot reach caller-owned input")
        assertEquals(expected, created.map { it.metadata!!.tags }, "rule mutation cannot reach returned rows")

        val stored = system.articles.query().all().getOrThrow().sortedBy { it.title }
        assertEquals(expected, stored.map { it.metadata!!.tags }, "rule mutation cannot reach persisted rows")
    }

    @Test
    fun `create preparation detaches caller-owned JSON before lifecycle callbacks`() {
        val driver = resetAndDriver()
        val system = sysClient(driver)
        val author = system.users.create {
            name = "A"
            email = "json-create-alias@example.com"
        }.saveAndLoad().getOrThrow()
        val callerTags = mutableListOf("original")
        var seenCandidate: List<String>? = null

        val policy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    create(
                        ArticleCreatePrivacyRule {
                            callerTags += "captured alias mutation"
                            PrivacyDecision.Continue
                        },
                        ArticleCreatePrivacyRule { context ->
                            seenCandidate = context.candidate.metadata!!.tags.toList()
                            PrivacyDecision.Allow
                        },
                    )
                    load(ArticleLoadPrivacyRule { PrivacyDecision.Allow })
                }
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(author.id)) }
            policies { articles(policy) }
        }

        val created = client.articles.create {
            title = "detached create"
            authorId = author.id
            metadata = ArticleMeta("test", callerTags)
        }.saveAndLoad().getOrThrow()

        assertEquals(listOf("original", "captured alias mutation"), callerTags)
        assertEquals(listOf("original"), seenCandidate)
        assertEquals(listOf("original"), created.metadata!!.tags)
        val stored = system.articles.findById(created.id).getOrThrow()!!
        assertEquals(listOf("original"), stored.metadata!!.tags)
    }

    @Test
    fun `update hook and rule snapshots cannot mutate the pending JSON write`() {
        val driver = resetAndDriver()
        val system = sysClient(driver)
        val author = system.users.create {
            name = "A"
            email = "json-update-snapshots@example.com"
        }.saveAndLoad().getOrThrow()
        val original = system.articles.create {
            title = "update snapshots"
            authorId = author.id
            metadata = ArticleMeta("before", listOf("before"))
        }.saveAndLoad().getOrThrow()

        val replacementTags = mutableListOf("replacement")
        val hookBeforeSeen = mutableListOf<List<String>>()
        val hookPatchSeen = mutableListOf<List<String>>()
        var ruleCandidateSeen: List<String>? = null

        val policy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy {
                    update(
                        ArticleUpdatePrivacyRule {
                            replacementTags += "captured alias mutation"
                            PrivacyDecision.Continue
                        },
                        ArticleUpdatePrivacyRule { context ->
                            ruleCandidateSeen = context.candidate.metadata!!.tags.toList()
                            PrivacyDecision.Allow
                        },
                    )
                    load(ArticleLoadPrivacyRule { PrivacyDecision.Allow })
                }
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(author.id)) }
            policies { articles(policy) }
            hooks {
                articles {
                    beforeUpdate { context ->
                        @Suppress("UNCHECKED_CAST")
                        (context.before.metadata!!.tags as MutableList<String>) += "before mutation"
                        @Suppress("UNCHECKED_CAST")
                        (((context.patch.metadata as FieldPatch.Set<ArticleMeta?>).value!!)
                            .tags as MutableList<String>) += "patch mutation"
                    }
                    beforeUpdate { context ->
                        hookBeforeSeen += context.before.metadata!!.tags.toList()
                        val patch = context.patch.metadata as FieldPatch.Set<ArticleMeta?>
                        hookPatchSeen += patch.value!!.tags.toList()
                    }
                }
            }
        }

        val updated = client.articles.update(original.id) {
            metadata = ArticleMeta("after", replacementTags)
        }.saveAndLoad().getOrThrow()

        assertEquals(listOf(listOf("before")), hookBeforeSeen)
        assertEquals(listOf(listOf("replacement")), hookPatchSeen)
        assertEquals(listOf("replacement", "captured alias mutation"), replacementTags)
        assertEquals(listOf("replacement"), ruleCandidateSeen)
        assertEquals(listOf("replacement"), updated.metadata!!.tags)
        val stored = system.articles.findById(original.id).getOrThrow()!!
        assertEquals(listOf("replacement"), stored.metadata!!.tags)
    }
}
