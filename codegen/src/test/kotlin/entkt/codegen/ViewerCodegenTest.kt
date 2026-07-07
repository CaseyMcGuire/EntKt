package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private enum class Tier { FREE, PRO }

private class ViewerUser : EntSchema("viewer_users") {
    override fun id() = EntId.long()
    val name = string("name")
    val secret = string("secret").sensitive()
    val tier = enum<Tier>("tier")
    val bio = text("bio").nullable()
    val posts = hasMany<ViewerPost>("posts")
}

private class ViewerPost : EntSchema("viewer_posts") {
    override fun id() = EntId.long()
    val title = string("title")
    val author = belongsTo<ViewerUser>("author").inverse(ViewerUser::posts)
}

class ViewerCodegenTest {

    private fun gen(viewer: Boolean): Map<String, String> {
        val user = ViewerUser()
        val post = ViewerPost()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(
            user::class to user, post::class to post,
        )
        user.finalize(registry)
        post.finalize(registry)
        return EntGenerator("com.example.ent", viewer = viewer)
            .generate(listOf(SchemaInput("ViewerUser", user), SchemaInput("ViewerPost", post)))
            .associate { it.name to it.toString().replace("\\s+".toRegex(), " ") }
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
    fun `adapter carries column metadata with sensitivity and filterability`() {
        val adapter = gen(viewer = true).getValue("ViewerUserViewerEntity")
        assertTrue(
            """EntViewerColumn(name = "secret", type = FieldType.STRING, nullable = false, unique = false, sensitive = true, filterable = false, orderable = false)""" in adapter,
            adapter,
        )
        assertTrue(
            """EntViewerColumn(name = "name", type = FieldType.STRING, nullable = false, unique = false, sensitive = false, filterable = true, orderable = true)""" in adapter,
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
        assertTrue("entity.tier.name" in adapter, adapter)
    }

    @Test
    fun `reads go through the generated repo terminals`() {
        val adapter = gen(viewer = true).getValue("ViewerUserViewerEntity")
        assertTrue("client.viewerUsers.query" in adapter, adapter)
        assertTrue(".visibleAll()" in adapter, adapter)
        assertTrue("visibleByIdOrNull(parsed)" in adapter, adapter)
        assertFalse("client.driver" in adapter, "must not touch the raw driver: $adapter")
        assertFalse("Driver.query" in adapter, "must not touch the raw driver: $adapter")
    }

    @Test
    fun `edges emit fk and filter link metadata by kind`() {
        val user = gen(viewer = true).getValue("ViewerUserViewerEntity")
        assertTrue(
            """EntViewerEdge(name = "posts", targetRouteName = "viewerPost", cardinality = "to-many", localFkColumn = null, targetFilterColumn = "author_id")""" in user,
            user,
        )
        val post = gen(viewer = true).getValue("ViewerPostViewerEntity")
        assertTrue(
            """EntViewerEdge(name = "author", targetRouteName = "viewerUser", cardinality = "to-one", localFkColumn = "author_id", targetFilterColumn = null)""" in post,
            post,
        )
    }

    @Test
    fun `registry lists every adapter and bridges withPrivacyContext`() {
        val registry = gen(viewer = true).getValue("GeneratedEntViewerRegistry")
        assertTrue("listOf(ViewerUserViewerEntity, ViewerPostViewerEntity)" in registry, registry)
        assertTrue("client.withPrivacyContext(context, block)" in registry, registry)
    }
}
