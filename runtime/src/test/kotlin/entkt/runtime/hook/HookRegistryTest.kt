@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.hook

import kotlin.test.Test
import kotlin.test.assertEquals

class HookRegistryTest {
    @Test
    fun `scalar and batch registrations retain encounter order`() {
        val calls = mutableListOf<String>()
        val registry = HookRegistry<Int>()

        registry { value -> calls += "scalar:$value" }
        registry(
            batchHook { values ->
                calls += "batch:${values.joinToString()}"
            },
        )

        HookRunner(registry.snapshotForInternalUse()).run(listOf(2, 1))

        assertEquals(
            listOf("scalar:2", "scalar:1", "batch:2, 1"),
            calls,
        )
    }

    @Test
    fun `snapshots are detached from later registrations`() {
        val calls = mutableListOf<String>()
        val source = HookRegistry<Int>()
        source { calls += "initial:$it" }

        val snapshot = source.snapshotForInternalUse()
        source { calls += "late:$it" }

        HookRunner(snapshot).run(listOf(1))

        assertEquals(listOf("initial:1"), calls)
    }
}
