package entkt.explorer.html

import entkt.explorer.EntExplorerEdge
import entkt.explorer.EntExplorerRow
import entkt.explorer.EntExplorerValue
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertTrue

/** Component test: every edge-link rule in isolation. */
class EdgeLinkCellTest {

    private val urls = ExplorerUrls("/_ent")
    private val row = EntExplorerRow(
        id = "7",
        values = listOf(
            EntExplorerValue.of("owner_id", "42"),
            EntExplorerValue.of("gone_id", null),
            EntExplorerValue.redacted("secret_id"),
        ),
    )

    private fun render(edge: EntExplorerEdge, visible: Set<String> = setOf("user", "post")): String =
        createHTML().div { edgeLinkCell(edge, row, visible, urls) }

    @Test
    fun `local fk links to the target detail`() {
        val html = render(EntExplorerEdge("owner", "user", "to-one", localFkColumn = "owner_id"))
        assertTrue("/_ent/entities/user/42" in html)
    }

    @Test
    fun `null and redacted fks render as text, never links`() {
        assertTrue("<a" !in render(EntExplorerEdge("owner", "user", "to-one", localFkColumn = "gone_id")))
        assertTrue("<a" !in render(EntExplorerEdge("owner", "user", "to-one", localFkColumn = "secret_id")))
    }

    @Test
    fun `target filter column links to the filtered target list`() {
        val html = render(EntExplorerEdge("posts", "post", "to-many", targetFilterColumn = "author_id"))
        assertTrue("f=author_id%3Aeq%3A7" in html)
    }

    @Test
    fun `m2m and unviewable targets render disabled`() {
        assertTrue("no generated traversal link" in render(EntExplorerEdge("tags", "tag", "many-to-many"), setOf("tag")))
        assertTrue("not viewable" in render(EntExplorerEdge("posts", "post", "to-many", targetFilterColumn = "x"), setOf("user")))
    }
}
