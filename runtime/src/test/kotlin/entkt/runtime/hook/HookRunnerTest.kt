@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.hook

import kotlin.test.Test
import kotlin.test.assertEquals

class HookRunnerTest {
    @Test
    fun `runner snapshots hooks and preserves registration order`() {
        val events = mutableListOf<String>()
        val hooks = mutableListOf<BatchActionHook<Int>>(
            ActionHook { events += "first:$it" },
            batchActionHook { events += "batch:${it.joinToString()}" },
        )
        val runner = HookRunner(hooks)
        hooks += ActionHook { events += "late:$it" }

        runner.run(listOf(2, 1))

        assertEquals(
            listOf("first:2", "first:1", "batch:2, 1"),
            events,
        )
    }
}
