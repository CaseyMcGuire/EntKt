@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.hook

import kotlin.test.Test
import kotlin.test.assertEquals

class EntityHooksTest {
    @Test
    fun `a copy keeps the hooks registered before it was made in their original order`() {
        val calls = mutableListOf<String>()
        val source = EntityHooks<Int, String, Double, Long>()
        source.beforeSave { value ->
            calls += "first:$value"
            value + 1
        }
        source.beforeSave { value ->
            calls += "second:$value"
            value * 10
        }

        val copy = source.deepCopy()
        source.beforeSave { value ->
            calls += "late:$value"
            value
        }

        val result = runTransformingHooks(
            "User.beforeSave",
            MutationBatch.from(listOf(1)),
            copy.beforeSave.hooks,
        ).single()
        assertEquals(listOf("first:1", "second:2"), calls)
        assertEquals(20, result)
    }

    @Test
    fun `a copy and its source do not see each other's later registrations in any registry`() {
        val source = EntityHooks<Int, Int, Int, Int>()
        registerOneHookInEveryRegistry(source)

        val copy = source.deepCopy()
        registerOneHookInEveryRegistry(source)
        registerOneHookInEveryRegistry(source)
        registerOneHookInEveryRegistry(copy)

        assertEquals(List(7) { 3 }, hookCounts(source))
        assertEquals(List(7) { 2 }, hookCounts(copy))
    }

    private fun registerOneHookInEveryRegistry(hooks: EntityHooks<Int, Int, Int, Int>) {
        hooks.beforeSave { state -> state + 1 }
        hooks.beforeCreate { state -> state + 1 }
        hooks.afterCreate { }
        hooks.beforeUpdate { state -> state + 1 }
        hooks.afterUpdate { }
        hooks.beforeDelete { }
        hooks.afterDelete { }
    }

    private fun hookCounts(hooks: EntityHooks<Int, Int, Int, Int>): List<Int> = listOf(
        hooks.beforeSave.hooks.size,
        hooks.beforeCreate.hooks.size,
        hooks.afterCreate.hooks.size,
        hooks.beforeUpdate.hooks.size,
        hooks.afterUpdate.hooks.size,
        hooks.beforeDelete.hooks.size,
        hooks.afterDelete.hooks.size,
    )
}
