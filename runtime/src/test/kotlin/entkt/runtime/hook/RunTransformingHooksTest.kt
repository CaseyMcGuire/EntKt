@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.hook

import entkt.runtime.result.EntBatchMutationHookContractException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class RunTransformingHooksTest {
    @Test
    fun `scalar hooks pass immutable replacement state to the next hook`() {
        data class State(val value: String)

        val initial = State("initial")
        val result = runTransformingHooks(
            lifecycle = "User.beforeUpdate",
            states = MutationBatch.from(listOf(initial)),
            hooks = listOf(
                TransformingHook<State> { state -> state.copy(value = "${state.value}:first") },
                TransformingHook<State> { state -> state.copy(value = "${state.value}:second") },
            ),
        ).single()

        assertEquals(State("initial"), initial)
        assertEquals(State("initial:first:second"), result)
    }

    @Test
    fun `scalar hooks retain batch order and correspondence`() {
        val result = runTransformingHooks(
            lifecycle = "User.beforeCreate",
            states = MutationBatch.from(listOf(3, 1, 3)),
            hooks = listOf(
                TransformingHook<Int> { it * 10 },
                TransformingHook<Int> { it + 1 },
            ),
        )

        assertEquals(listOf(31, 11, 31), result)
    }

    @Test
    fun `batch hooks can derive replacement states without returning a parallel list`() {
        val observed = mutableListOf<List<Int>>()
        val result = runTransformingHooks(
            lifecycle = "User.beforeCreate",
            states = MutationBatch.from(listOf(4, 4, 4)),
            hooks = listOf(
                batchTransformingHook<Int> { states ->
                    observed.add(states.toList())
                    states.mapStatesIndexed { index, state -> state + index }
                },
                batchTransformingHook<Int> { states ->
                    observed.add(states.toList())
                    states.mapStates { it * 2 }
                },
            ),
        )

        assertEquals(listOf(listOf(4, 4, 4), listOf(4, 5, 6)), observed)
        assertEquals(listOf(8, 10, 12), result)
    }

    @Test
    fun `batch hook may preserve every state unchanged`() {
        val initial = Any()
        val result = runTransformingHooks(
            lifecycle = "User.beforeUpdate",
            states = MutationBatch.from(listOf(initial)),
            hooks = listOf(batchTransformingHook<Any> { it }),
        ).single()

        assertSame(initial, result)
    }

    @Test
    fun `rejects a batch created for another invocation before calling later hooks`() {
        val foreign = MutationBatch.from(listOf("foreign"))
        var laterCalls = 0

        val exception = assertFailsWith<EntBatchMutationHookContractException> {
            runTransformingHooks(
                lifecycle = "User.beforeUpdate",
                states = MutationBatch.from(listOf("current")),
                hooks = listOf(
                    batchTransformingHook<String> { foreign },
                    TransformingHook<String> { laterCalls++; it },
                ),
            )
        }

        assertEquals("User.beforeUpdate", exception.lifecycle)
        assertEquals(1, exception.expectedSize)
        assertEquals(1, exception.actualSize)
        assertEquals(true, exception.foreignBatchResult)
        assertEquals(0, laterCalls)
    }

    @Test
    fun `empty batches do not invoke hooks`() {
        var calls = 0
        val initial = MutationBatch.from(emptyList<Int>())
        val result = runTransformingHooks(
            lifecycle = "User.beforeCreate",
            states = initial,
            hooks = listOf(TransformingHook<Int> { calls++; it }),
        )

        assertEquals(0, calls)
        assertSame(initial, result)
    }

    @Test
    fun `no hooks preserves the supplied batch without copying`() {
        val initial = MutationBatch.from(listOf(1, 2))

        val result = runTransformingHooks("User.beforeUpdate", initial, emptyList())

        assertSame(initial, result)
    }

    @Test
    fun `blank lifecycle is rejected even for an empty batch`() {
        assertFailsWith<IllegalArgumentException> {
            runTransformingHooks(" ", MutationBatch.from(emptyList<Int>()), emptyList())
        }
    }

    @Test
    fun `null returned by a Java hook is reported as a contract failure`() {
        @Suppress("UNCHECKED_CAST")
        val hook = Proxy.newProxyInstance(
            BatchTransformingHook::class.java.classLoader,
            arrayOf(BatchTransformingHook::class.java),
        ) { _, _, _ -> null } as BatchTransformingHook<Int>

        val exception = assertFailsWith<EntBatchMutationHookContractException> {
            runTransformingHooks(
                lifecycle = "User.beforeUpdate",
                states = MutationBatch.from(listOf(1, 2)),
                hooks = listOf(hook),
            )
        }

        assertEquals("User.beforeUpdate", exception.lifecycle)
        assertEquals(2, exception.expectedSize)
        assertNull(exception.actualSize)
    }

    @Test
    fun `hook exceptions propagate without running subsequent hooks`() {
        val failure = IllegalStateException("hook failed")
        var laterCalls = 0
        val hooks = listOf(
            batchTransformingHook<Int> { throw failure },
            TransformingHook<Int> { laterCalls++; it },
        )

        val thrown = assertFailsWith<IllegalStateException> {
            runTransformingHooks("User.beforeUpdate", MutationBatch.from(listOf(1)), hooks)
        }

        assertSame(failure, thrown)
        assertEquals(0, laterCalls)
    }
}
