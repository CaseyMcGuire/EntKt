package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private enum class Tier { FREE, PRO }

private class ViewerUser : EntSchema("viewer_users", clientName = "viewerUsers") {
    override fun id() = EntId.long()
    val name by string("name")
    val secret by string("secret").sensitive()
    val tier by enum<Tier>("tier")
    val bio by text("bio").nullable()
    val posts by hasMany<ViewerPost>("posts")
}

/**
 * Deliberately long entity name. The generated repository property and
 * adapter names derive from the class, so exercising the long-name
 * formatting path needs a real class rather than a caller-supplied
 * override.
 */
private class ConversationAsset : EntSchema("conversation_assets", clientName = "conversationAssets") {
    override fun id() = EntId.long()
    val name by string("name")
}

private class ViewerPost : EntSchema("viewer_posts", clientName = "viewerPosts") {
    override fun id() = EntId.long()
    val title by string("title")
    val author by belongsTo<ViewerUser>("author").inverse(ViewerUser::posts)
}

class ViewerCodegenTest {

    private fun gen(
        viewer: Boolean,
        extra: EntSchema? = null,
        normalizeWhitespace: Boolean = true,
    ): Map<String, String> {
        val user = ViewerUser()
        val post = ViewerPost()
        val all = listOfNotNull<EntSchema>(user, post, extra)
        val registry = all.associateBy { it::class }
        all.forEach { it.finalize(registry) }
        return EntGenerator("com.example.ent", viewer = viewer)
            .generate(all.map { SchemaInput(it) })
            .associate {
                val source = it.toString()
                it.name to if (normalizeWhitespace) source.replace("\\s+".toRegex(), " ") else source
            }
    }

    @Test
    fun `no viewer files are emitted unless the flag is on`() {
        val files = gen(viewer = false)
        assertFalse("ViewerUserViewerEntity" in files.keys, files.keys.toString())
        assertFalse("GeneratedEntViewerRegistry" in files.keys, files.keys.toString())
        val enabled = gen(viewer = true)
        assertTrue("ViewerUserViewerEntity" in enabled.keys, enabled.keys.toString())
        assertTrue("GeneratedEntViewerRegistry" in enabled.keys, enabled.keys.toString())
    }

    @Test
    fun `viewer files omit framework comments`() {
        val files = gen(viewer = true, normalizeWhitespace = false)
        val commentStart = Regex("(?m)^\\s*(?://|/\\*)")
        val commentedFiles = files
            .filterKeys { it.endsWith("ViewerEntity") || it == "GeneratedEntViewerRegistry" }
            .filterValues(commentStart::containsMatchIn)
            .keys

        assertTrue(
            commentedFiles.isEmpty(),
            "Generated viewer files contain framework comments: $commentedFiles",
        )
    }

    @Test
    fun `adapter carries column metadata with sensitivity and filterability`() {
        val adapter = gen(viewer = true).getValue("ViewerUserViewerEntity")
        assertTrue(
            """EntViewerColumn(name = "secret", type = FieldType.STRING, nullable = false, unique = false, sensitive = true, filterable = false, orderable = false, entType = "String")""" in adapter,
            adapter,
        )
        assertTrue(
            """EntViewerColumn(name = "name", type = FieldType.STRING, nullable = false, unique = false, sensitive = false, filterable = true, orderable = true, entType = "String")""" in adapter,
            adapter,
        )
    }

    @Test
    fun `sensitive fields are redacted in toRow and never materialized`() {
        val adapter = gen(viewer = true).getValue("ViewerUserViewerEntity")
        assertTrue("""EntViewerValue.redacted("secret")""" in adapter, adapter)
        assertFalse("entity.secret" in adapter, "sensitive property must never be read: $adapter")
    }

    @Test
    fun `enum columns validate against constant names`() {
        val adapter = gen(viewer = true).getValue("ViewerUserViewerEntity")
        assertTrue(""""tier" to setOf("FREE", "PRO")""" in adapter, adapter)
        assertTrue("""entType = "Tier"""" in adapter, "enum ent type is the enum class: " + adapter)
        assertTrue("entity.tier.name" in adapter, adapter)
    }

    @Test
    fun `reads go through the generated repo terminals`() {
        val adapter = gen(viewer = true).getValue("ViewerUserViewerEntity")
        assertTrue("client.viewerUsers.query" in adapter, adapter)
        // list: the strict all() terminal over an overfetch-by-one page.
        assertTrue(".all(viewerContext)" in adapter, adapter)
        assertTrue("val fetchLimit = request.pageSize + 1" in adapter, adapter)
        assertTrue("is ReadResult.Success -> result.value" in adapter, adapter)
        // A Root LOAD denial renders as an empty privacy-filtered page
        // (hasNext unknown); any other read failure propagates.
        assertTrue(
            "if (e is EntPrivacyDeniedException && e.origin is LoadDenialOrigin.Root) " +
                "{ return EntViewerListResult(emptyList(), hasNext = null, privacyFiltered = true) } throw e" in adapter,
            adapter,
        )
        assertTrue("hasNext = rows.size > request.pageSize" in adapter, adapter)
        // get: findById + visibleOrNull — an individually denied row
        // reads as absent (null), not as an error.
        assertTrue(
            "client.viewerUsers.findById(viewerContext, parsed).visibleOrNull().getOrThrow() ?: return null" in adapter,
            adapter,
        )
        // Removed legacy surface: no visible* scanning terminals, no
        // overfetch cap machinery.
        assertFalse("visibleAllOrError" in adapter, adapter)
        assertFalse("visibleByIdOrNull" in adapter, adapter)
        assertFalse("visibleOverfetchLimit" in adapter, adapter)
        assertFalse("OverfetchCapExceeded" in adapter, adapter)
        assertFalse("client.driver" in adapter, "must not touch the raw driver: $adapter")
        assertFalse("DatabaseDriver.query" in adapter, "must not touch the raw driver: $adapter")
    }

    @Test
    fun `long repository names keep return null on one logical line`() {
        val adapter = gen(
            viewer = true,
            extra = ConversationAsset(),
            normalizeWhitespace = false,
        ).getValue("ConversationAssetViewerEntity")

        assertTrue("?: return null" in adapter, adapter)
        assertFalse(Regex("return\\s*\\n\\s*null").containsMatchIn(adapter), adapter)
    }

    @Test
    fun `edges emit fk and filter link metadata by kind`() {
        val user = gen(viewer = true).getValue("ViewerUserViewerEntity")
        assertTrue(
            """EntViewerEdge(name = "posts", targetRouteName = "viewerPosts", cardinality = "to-many", localFkColumn = null, targetFilterColumn = "author_id")""" in user,
            user,
        )
        val post = gen(viewer = true).getValue("ViewerPostViewerEntity")
        assertTrue(
            """EntViewerEdge(name = "author", targetRouteName = "viewerUsers", cardinality = "to-one", localFkColumn = "author_id", targetFilterColumn = null)""" in post,
            post,
        )
    }

    @Test
    fun `generated entity list contains every adapter without a registry wrapper`() {
        val registry = gen(viewer = true).getValue("GeneratedEntViewerRegistry")
        assertTrue("val GeneratedEntViewerRegistry: List<EntViewerEntity<EntClient>>" in registry, registry)
        assertTrue("listOf(ViewerUserViewerEntity, ViewerPostViewerEntity)" in registry, registry)
        assertFalse("object GeneratedEntViewerRegistry" in registry, registry)
        assertFalse("withViewerContext" in registry, registry)
        assertFalse("ViewerContext" in registry, registry)
    }
}
