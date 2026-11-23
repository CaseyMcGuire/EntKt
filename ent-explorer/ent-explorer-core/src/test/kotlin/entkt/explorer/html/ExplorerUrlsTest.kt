package entkt.explorer.html

import entkt.explorer.EntExplorerFilter
import entkt.explorer.EntExplorerFilterOp
import entkt.explorer.EntExplorerOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/** The URL grammar all components share. */
class ExplorerUrlsTest {

    private val urls = ExplorerUrls("/_ent")

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
                listOf(EntExplorerFilter("name", EntExplorerFilterOp.EQ, "casey")),
                EntExplorerOrder("age", descending = true),
                page = 2,
                size = 10,
            ),
        )
    }

    @Test
    fun `value-less ops produce two-part filter tokens`() {
        assertEquals("age:isnull", urls.filterToken(EntExplorerFilter("age", EntExplorerFilterOp.IS_NULL, null)))
    }
}
