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

        val result = registry
            .runnerForInternalUse("User.beforeCreate")
            .runBatch(listOf(2, 1))

        assertEquals(listOf(6, 4), result)
    }

    @Test
    fun `resolved runner is detached from later registrations`() {
        val registry = TransformingHookRegistry<Int>()
        registry { it + 1 }
        val runner = registry.runnerForInternalUse("User.beforeCreate")
        registry { it * 100 }

        assertEquals(2, runner.run(1))
    }
}
