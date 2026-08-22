@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.query.execution

import entkt.runtime.entity.EntEntity
import entkt.runtime.result.EntBatchRuleContractException
import entkt.runtime.result.EntityKey
import entkt.runtime.result.PrivacyDenial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class LoadPrivacyEvaluatorTest {
    private data class Item(override val id: Long) : EntEntity.LongId

    @Test
    fun `correlation retains each entity with its positional denial`() {
        val first = Item(1)
        val second = Item(2)
        val denial = PrivacyDenial("Item", EntityKey("id", second.id), "hidden")

        val evaluations = correlateLoadPrivacyEvaluationsForInternalUse(
            lifecycle = "Item LOAD privacy",
            entities = listOf(first, second),
            denials = listOf(null, denial),
        )

        val allowed = assertIs<LoadPrivacyEvaluation.Allowed<Item>>(evaluations[0])
        assertSame(first, allowed.entity)
        assertSame(first, allowed.allowedEntityOrNull())
        assertNull(allowed.denialOrNull())

        val denied = assertIs<LoadPrivacyEvaluation.Denied<Item>>(evaluations[1])
        assertSame(second, denied.entity)
        assertSame(denial, denied.denial)
        assertNull(denied.allowedEntityOrNull())
        assertSame(denial, denied.denialOrNull())
    }

    @Test
    fun `correlation rejects a denial count that does not match the entity count`() {
        val exception = assertFailsWith<EntBatchRuleContractException> {
            correlateLoadPrivacyEvaluationsForInternalUse(
                lifecycle = "Item LOAD privacy",
                entities = listOf(Item(1), Item(2)),
                denials = listOf(null),
            )
        }

        assertEquals("Item LOAD privacy", exception.lifecycle)
        assertEquals(2, exception.expectedSize)
        assertEquals(1, exception.actualSize)
    }
}
