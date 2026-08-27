package entkt.runtime

import entkt.query.EntktInternal
import entkt.runtime.mutation.PreparedCreate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(EntktInternal::class)
class PreparedCreateTest {
    @Test
    fun `carrier keeps one normalized row and its matching candidate together`() {
        val candidate = Any()
        val values = mapOf<String, Any?>("name" to "Ada", "age" to 37)

        val prepared = PreparedCreate(
            values = values,
            candidate = candidate,
        )

        assertEquals(values, prepared.values)
        assertSame(candidate, prepared.candidate)
    }
}
