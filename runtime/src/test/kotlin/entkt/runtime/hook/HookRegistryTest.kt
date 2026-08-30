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

        runBatchHooksForInternalUse(
            elements = listOf(2, 1),
            hooks = registry.snapshotForInternalUse(),
        )

        assertEquals(
            listOf("scalar:2", "scalar:1", "batch:2, 1"),
            calls,
        )
    }

    @Test
    fun `snapshots and copied registries are detached from later registrations`() {
        val calls = mutableListOf<String>()
        val source = HookRegistry<Int>()
        source { calls += "initial:$it" }

        val snapshot = source.snapshotForInternalUse()
        val copy = HookRegistry<Int>().also { it.copyFromForInternalUse(source) }
        source { calls += "late:$it" }

        runBatchHooksForInternalUse(listOf(1), snapshot)
        runBatchHooksForInternalUse(listOf(2), copy.snapshotForInternalUse())

        assertEquals(listOf("initial:1", "initial:2"), calls)
    }
}
