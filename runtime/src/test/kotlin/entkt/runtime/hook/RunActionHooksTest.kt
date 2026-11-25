@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.hook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RunActionHooksTest {
    @Test
    fun `hooks run in registration order and scalar hooks retain element order`() {
        val events = mutableListOf<String>()
        val hooks = listOf(
            ActionHook<Int> { events += "first:$it" },
            batchActionHook<Int> { events += "batch:${it.joinToString()}" },
            ActionHook<Int> { events += "last:$it" },
        )

        runActionHooks(listOf(2, 1), hooks)

        assertEquals(
            listOf("first:2", "first:1", "batch:2, 1", "last:2", "last:1"),
            events,
        )
    }

    @Test
    fun `every hook receives the supplied list`() {
        val input = listOf(2, 1)
        val received = mutableListOf<List<Int>>()
        val hooks = listOf(
            batchActionHook<Int> { batch -> received += batch },
            batchActionHook<Int> { batch -> received += batch },
        )

        runActionHooks(input, hooks)

        assertEquals(2, received.size)
        assertSame(input, received[0])
        assertSame(input, received[1])
    }

    @Test
    fun `empty batches do not invoke hooks`() {
        var calls = 0

        runActionHooks(emptyList(), listOf(batchActionHook<Int> { calls++ }))

        assertEquals(0, calls)
    }

    @Test
    fun `hook exceptions propagate without running subsequent hooks`() {
        val failure = IllegalStateException("hook failed")
        var laterCalls = 0
        val hooks = listOf(
            batchActionHook<Int> { throw failure },
            batchActionHook<Int> { laterCalls++ },
        )

        val thrown = assertFailsWith<IllegalStateException> {
            runActionHooks(listOf(1), hooks)
        }

        assertSame(failure, thrown)
        assertEquals(0, laterCalls)
    }
}
