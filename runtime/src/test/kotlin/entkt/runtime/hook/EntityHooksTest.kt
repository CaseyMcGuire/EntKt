@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.hook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EntityHooksTest {
    @Test
    fun `resolved hooks retain their original registration order`() {
        val calls = mutableListOf<String>()
        val source = EntityHooks<Int, String, Double, Long>()
        source.beforeSave { value ->
            calls += "initial:$value"
            value + 1
        }

        val resolved = source.resolveForInternalUse()
        source.beforeSave { value ->
            calls += "late:$value"
            value
        }

        val result = runTransformingHooks(
            "User.beforeSave",
            MutationBatch.from(listOf(1)),
            resolved.beforeSave,
        ).single()
        assertEquals(listOf("initial:1"), calls)
        assertEquals(2, result)
    }

    @Test
    fun `resolution detaches and freezes every hook registry`() {
        val source = EntityHooks<Int, Int, Int, Int>()
        val transforming = listOf(source.beforeSave, source.beforeCreate, source.beforeUpdate)
        val actions = listOf(source.afterCreate, source.afterUpdate, source.beforeDelete, source.afterDelete)
        transforming.forEach { it { state -> state + 1 } }
        actions.forEach { it { } }

        val resolved = source.resolveForInternalUse()
        transforming.forEach { it { state -> state * 100 } }
        actions.forEach { it { } }

        val snapshots = listOf(
            resolved.beforeSave,
            resolved.beforeCreate,
            resolved.afterCreate,
            resolved.beforeUpdate,
            resolved.afterUpdate,
            resolved.beforeDelete,
            resolved.afterDelete,
        )
        for (snapshot in snapshots) {
            assertEquals(1, snapshot.size)
            assertFailsWith<UnsupportedOperationException> {
                (snapshot as MutableList<*>).clear()
            }
        }
    }
}
