package entkt.viewer.html

import entkt.viewer.EntViewerFilter
import entkt.viewer.EntViewerFilterOp
import entkt.viewer.EntViewerOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/** The URL grammar all components share. */
class ViewerUrlsTest {

    private val urls = ViewerUrls("/_ent")

    @Test
    fun `detail ids are path-encoded, not form-encoded`() {
        assertEquals("/_ent/entities/doc/hello%20world%2Fx", urls.entityDetail("doc", "hello world/x"))
    }

    @Test
    fun `list urls include only non-default state`() {
        assertEquals("/_ent/entities/user", urls.entityList("user"))
        assertEquals(
            "/_ent/entities/user?f=name%3Aeq%3Acasey&order=age&dir=desc&page=2&size=10",
            urls.entityList(
                "user",
                listOf(EntViewerFilter("name", EntViewerFilterOp.EQ, "casey")),
                EntViewerOrder("age", descending = true),
                page = 2,
                size = 10,
            ),
        )
    }

    @Test
    fun `value-less ops produce two-part filter tokens`() {
        assertEquals("age:isnull", urls.filterToken(EntViewerFilter("age", EntViewerFilterOp.IS_NULL, null)))
    }
}
