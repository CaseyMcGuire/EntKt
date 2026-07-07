package entkt.viewer.html

import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Component test: the tri-state next link and prev behavior. */
class PagerTest {

    private val urls = ViewerUrls("/_ent")

    private fun render(page: Int, hasNext: Boolean?): String =
        createHTML().div {
            pager(ListContext("user", emptyList(), null, page, 50), hasNext, urls)
        }

    @Test
    fun `exact hasNext controls the next link`() {
        assertTrue("Next &gt;" in render(1, hasNext = true))
        assertFalse("Next &gt;" in render(1, hasNext = false))
    }

    @Test
    fun `unknown hasNext (privacy-windowed) offers next unconditionally`() {
        assertTrue("Next &gt;" in render(1, hasNext = null))
    }

    @Test
    fun `prev appears only past page one`() {
        assertFalse("Prev" in render(1, hasNext = true))
        assertTrue("Prev" in render(2, hasNext = true))
        assertTrue("page=2" in render(1, hasNext = true), "next link targets the following page")
    }
}
