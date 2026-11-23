package entkt.explorer.html

import entkt.explorer.EntExplorerValue
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Component test: render the cell in isolation. */
class ValueCellTest {

    private fun render(cell: EntExplorerValue?, truncateAt: Int? = null): String =
        createHTML().div { valueCell(cell, truncateAt) }

    @Test
    fun `redacted, null, absent, and plain values render distinctly`() {
        assertTrue("""<span class="redacted">***</span>""" in render(EntExplorerValue.redacted("c")))
        assertTrue("""<span class="null">null</span>""" in render(EntExplorerValue.of("c", null)))
        assertTrue("""<span class="muted">-</span>""" in render(null))
        assertTrue(">hello<" in render(EntExplorerValue.of("c", "hello")))
    }

    @Test
    fun `long values truncate with a length marker, exact values do not`() {
        val long = "x".repeat(150)
        val rendered = render(EntExplorerValue.of("c", long), truncateAt = 120)
        assertTrue("x".repeat(120) in rendered)
        assertTrue("150 chars" in rendered)
        assertEquals(false, "x".repeat(121) in rendered)
        assertTrue("x".repeat(120) in render(EntExplorerValue.of("c", "x".repeat(120)), truncateAt = 120))
    }
}
