@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.hook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EntityHooksTest {
    @Test
    fun `resolution detaches and freezes every hook registry`() {
        val calls = mutableListOf<String>()
        val source = EntityHooks<Int, String, Double, Long>()
        source.beforeSave { calls += "initial:$it" }

        val resolved = source.resolveForInternalUse()
        source.beforeSave { calls += "late:$it" }

        runBatchHooksForInternalUse(listOf(1), resolved.beforeSave)
        assertEquals(listOf("initial:1"), calls)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (resolved.beforeSave as MutableList<BatchHook<Int>>).clear()
        }
    }
}
