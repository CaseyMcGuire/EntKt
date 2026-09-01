@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.hook

import kotlin.test.Test
import kotlin.test.assertEquals

class EntityHooksTest {
    @Test
    fun `resolution detaches and freezes every hook registry`() {
        val calls = mutableListOf<String>()
        val source = EntityHooks<Int, String, Double, Long>()
        source.beforeSave { value ->
            calls += "initial:$value"
            value + 1
        }

        val resolved = source.resolveForInternalUse("User")
        source.beforeSave { value ->
            calls += "late:$value"
            value
        }

        val result = resolved.beforeSave.run(1)
        assertEquals(listOf("initial:1"), calls)
        assertEquals(2, result)
    }
}
