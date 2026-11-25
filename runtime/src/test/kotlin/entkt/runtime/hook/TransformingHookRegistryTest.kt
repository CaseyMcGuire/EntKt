@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.hook

import kotlin.test.Test
import kotlin.test.assertEquals

class TransformingHookRegistryTest {
    @Test
    fun `scalar and batch transformations retain registration order`() {
        val registry = TransformingHookRegistry<Int>()
        registry { it + 1 }
        registry(batchTransformingHook { states -> states.mapStates { it * 2 } })

        val result = runTransformingHooks(
            lifecycle = "User.beforeCreate",
            states = MutationBatch.from(listOf(2, 1)),
            hooks = registry.hooks,
        )

        assertEquals(listOf(6, 4), result)
    }
}
