@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.hook

import kotlin.test.Test
import kotlin.test.assertEquals

class EntityHooksTest {
    @Test
    fun `resolution detaches and freezes every hook registry`() {
        val calls = mutableListOf<String>()
        val source = EntityHooks<Int, String, Double, Long>()
        source.beforeSave { calls += "initial:$it" }

        val resolved = source.resolveForInternalUse()
        source.beforeSave { calls += "late:$it" }

        resolved.beforeSave.run(listOf(1))
        assertEquals(listOf("initial:1"), calls)
    }
}
