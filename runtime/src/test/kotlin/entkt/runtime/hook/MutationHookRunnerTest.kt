@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.hook

import entkt.runtime.result.EntBatchMutationHookContractException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class MutationHookRunnerTest {
    @Test
    fun `scalar hooks pass immutable replacement state to the next hook`() {
        data class State(val value: String)

        val initial = State("initial")
        val runner = MutationHookRunner<State>(
            lifecycle = "User.beforeUpdate",
            hooks = listOf(
                MutationHook<State> { state -> state.copy(value = "${state.value}:first") },
                MutationHook<State> { state -> state.copy(value = "${state.value}:second") },
            ),
        )

        val result = runner.run(initial)

        assertEquals(State("initial"), initial)
        assertEquals(State("initial:first:second"), result)
    }

    @Test
    fun `scalar hooks retain batch order and correspondence`() {
        val runner = MutationHookRunner<Int>(
            lifecycle = "User.beforeCreate",
            hooks = listOf(
                MutationHook<Int> { it * 10 },
                MutationHook<Int> { it + 1 },
            ),
        )

        val result = runner.runBatch(listOf(3, 1, 3))

        assertEquals(listOf(31, 11, 31), result)
    }

    @Test
    fun `batch hooks can derive replacement states without returning a parallel list`() {
        val observed = mutableListOf<List<Int>>()
        val runner = MutationHookRunner<Int>(
            lifecycle = "User.beforeCreate",
            hooks = listOf(
                batchMutationHook<Int> { states ->
                    observed.add(states.toList())
                    states.mapStatesIndexed { index, state -> state + index }
                },
                batchMutationHook<Int> { states ->
                    observed.add(states.toList())
                    states.mapStates { it * 2 }
                },
            ),
        )

        val result = runner.runBatch(listOf(4, 4, 4))

        assertEquals(listOf(listOf(4, 4, 4), listOf(4, 5, 6)), observed)
        assertEquals(listOf(8, 10, 12), result)
    }

    @Test
    fun `batch hook may preserve every state unchanged`() {
        val initial = Any()
        val runner = MutationHookRunner<Any>(
            lifecycle = "User.beforeUpdate",
            hooks = listOf(batchMutationHook<Any> { it }),
        )

        val result = runner.run(initial)

        assertSame(initial, result)
    }

    @Test
    fun `runner rejects a batch created for another invocation`() {
        val foreign = MutationBatch.from(listOf("foreign"))
        val runner = MutationHookRunner<String>(
            lifecycle = "User.beforeUpdate",
            hooks = listOf(batchMutationHook<String> { foreign }),
        )

        val exception = assertFailsWith<EntBatchMutationHookContractException> {
            runner.runBatch(listOf("current"))
        }

        assertEquals("User.beforeUpdate", exception.lifecycle)
        assertEquals(1, exception.expectedSize)
        assertEquals(1, exception.actualSize)
        assertEquals(true, exception.foreignBatchResult)
    }

    @Test
    fun `empty batches do not invoke hooks`() {
        var calls = 0
        val runner = MutationHookRunner<Int>(
            lifecycle = "User.beforeCreate",
            hooks = listOf(MutationHook<Int> { calls++; it }),
        )

        val result = runner.runBatch(emptyList())

        assertEquals(0, calls)
        assertEquals(emptyList(), result)
    }
}
