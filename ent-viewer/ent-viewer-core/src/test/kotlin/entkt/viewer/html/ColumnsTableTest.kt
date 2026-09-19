package entkt.viewer.html

import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.schema.FieldType
import entkt.viewer.EntViewerColumn
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColumnsTableTest {

    private val column = EntViewerColumn(
        name = "name",
        type = FieldType.STRING,
        nullable = false,
        unique = false,
        sensitive = false,
        filterable = true,
        orderable = true,
    )

    private fun render(vararg metadata: ColumnMetadata): String {
        val schema = EntitySchema(
            table = "users",
            idColumn = "id",
            idStrategy = IdStrategy.EXPLICIT,
            columns = metadata.toList(),
            edges = emptyMap(),
        )
        return createHTML(prettyPrint = false).div { columnsTable(listOf(column), schema) }
    }

    @Test
    fun `renders the matching schema comment beneath the column name`() {
        val html = render(
            ColumnMetadata("other", FieldType.STRING, nullable = false, comment = "Unrelated comment"),
            ColumnMetadata("name", FieldType.STRING, nullable = false, comment = "Public display name."),
        )

        assertTrue("""<td>name<div class="muted">Public display name.</div></td>""" in html)
        assertFalse("Unrelated comment" in html)
    }

    @Test
    fun `omits absent and blank comments`() {
        for (comment in listOf(null, "", " \n\t")) {
            val html = render(ColumnMetadata("name", FieldType.STRING, nullable = false, comment = comment))

            assertTrue("<td>name</td>" in html)
            assertFalse("""class="muted"""" in html)
        }
    }

    @Test
    fun `renders columns without schema metadata`() {
        val html = render()

        assertTrue("<td>name</td>" in html)
        assertTrue("<td>-</td>" in html)
    }

    @Test
    fun `escapes HTML in comments`() {
        val comment = "<script>alert(1)</script> & <b>required</b>"
        val html = render(ColumnMetadata("name", FieldType.STRING, nullable = false, comment = comment))

        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &lt;b&gt;required&lt;/b&gt;" in html)
        assertFalse("<script>" in html)
        assertFalse("<b>" in html)
    }
}
