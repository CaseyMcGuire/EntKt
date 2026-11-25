@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.hook

import kotlin.test.Test
import kotlin.test.assertEquals

class ActionHookRegistryTest {
    @Test
    fun `scalar and batch registrations retain encounter order`() {
        val calls = mutableListOf<String>()
        val registry = ActionHookRegistry<Int>()

        registry { value -> calls += "scalar:$value" }
        registry(
            batchActionHook { values ->
                calls += "batch:${values.joinToString()}"
            },
        )

        runActionHooks(listOf(2, 1), registry.hooks)

        assertEquals(
            listOf("scalar:2", "scalar:1", "batch:2, 1"),
            calls,
        )
    }
}
