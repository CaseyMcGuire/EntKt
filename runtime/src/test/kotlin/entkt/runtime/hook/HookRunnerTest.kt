@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.hook

import kotlin.test.Test
import kotlin.test.assertEquals

class HookRunnerTest {
    @Test
    fun `runner snapshots hooks and preserves registration order`() {
        val events = mutableListOf<String>()
        val hooks = mutableListOf<BatchHook<Int>>(
            Hook { events += "first:$it" },
            batchHook { events += "batch:${it.joinToString()}" },
        )
        val runner = HookRunner(hooks)
        hooks += Hook { events += "late:$it" }

        runner.run(listOf(2, 1))

        assertEquals(
            listOf("first:2", "first:1", "batch:2, 1"),
            events,
        )
    }

    @Test
    fun `fresh values are rebuilt immediately before each hook`() {
        val events = mutableListOf<String>()
        var current = "initial"
        var builds = 0
        val runner = HookRunner(
            listOf(
                Hook<String> {
                    events += "first:$it"
                    current = "changed"
                },
                Hook<String> { events += "second:$it" },
            ),
        )

        runner.runFresh {
            builds++
            listOf(current)
        }

        assertEquals(2, builds)
        assertEquals(listOf("first:initial", "second:changed"), events)
    }

    @Test
    fun `fresh values are not built when no hooks are registered`() {
        var builds = 0
        val runner = HookRunner<String>(emptyList())

        runner.runFresh {
            builds++
            listOf("unused")
        }

        assertEquals(0, builds)
    }
}
