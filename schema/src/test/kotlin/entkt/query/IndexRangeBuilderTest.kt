package entkt.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class RangeEntity

class IndexRangeBuilderTest {

    private val column = ComparableColumn<RangeEntity, Int>("n")

    @Test
    fun `lower and upper bound produce two predicates, lower first`() {
        val preds = IndexRangeBuilder(column).apply { gte(10); lt(20) }.build()
        assertEquals(2, preds.size)
        assertEquals(Predicate.Leaf("n", Op.GTE, 10), preds[0])
        assertEquals(Predicate.Leaf("n", Op.LT, 20), preds[1])
    }

    @Test
    fun `a single bound is allowed`() {
        val preds = IndexRangeBuilder(column).apply { gt(5) }.build()
        assertEquals(listOf(Predicate.Leaf<RangeEntity>("n", Op.GT, 5)), preds)
    }

    @Test
    fun `an empty range block fails with a clear error`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            IndexRangeBuilder(column).build()
        }
        assertTrue(ex.message?.contains("at least one bound") == true, ex.message ?: "")
    }

    @Test
    fun `duplicate lower bounds fail with a clear error`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            IndexRangeBuilder(column).apply { gt(1); gte(2) }.build()
        }
        assertTrue(ex.message?.contains("lower bound") == true, ex.message ?: "")
    }

    @Test
    fun `duplicate upper bounds fail with a clear error`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            IndexRangeBuilder(column).apply { lt(1); lte(2) }.build()
        }
        assertTrue(ex.message?.contains("upper bound") == true, ex.message ?: "")
    }
}
