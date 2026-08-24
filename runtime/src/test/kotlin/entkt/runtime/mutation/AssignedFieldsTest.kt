@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation

import entkt.query.Column
import entkt.query.JsonColumn
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssignedFieldsTest {
    private class Article

    @Test
    fun `tracks every generated column-reference kind by storage identity`() {
        val title = Column<Article, String>("title")
        val metadata = JsonColumn<Article, Map<String, String>>("metadata")
        val fields = AssignedFields<Article>()

        assertFalse(title in fields)
        assertFalse(metadata in fields)

        fields.mark(title)
        fields.mark(metadata)

        assertTrue(title in fields)
        assertTrue(metadata in fields)

        fields.unmark(title)
        assertFalse(title in fields)
        assertTrue(metadata in fields)
    }
}
