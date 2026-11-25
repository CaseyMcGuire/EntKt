@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.runtime.rule.EntRuleClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private typealias TestValidationConfig = EntityValidationConfig<
    BatchValidationRule<EntRuleClient, String>,
    BatchValidationRule<EntRuleClient, String>,
    BatchValidationRule<EntRuleClient, String>,
>

class EntityValidationConfigTest {
    @Test
    fun `configurations start empty with independent rule lists and derivation disabled`() {
        val config = TestValidationConfig()
        val other = TestValidationConfig()

        assertTrue(config.createRules.isEmpty())
        assertTrue(config.updateRules.isEmpty())
        assertTrue(config.deleteRules.isEmpty())
        assertFalse(config.updateDerivesFromCreate)

        config.createRules += ValidationRule<EntRuleClient, String> { _, _ -> ValidationDecision.Valid }

        assertTrue(config.updateRules.isEmpty())
        assertTrue(config.deleteRules.isEmpty())
        assertTrue(other.createRules.isEmpty())
        assertTrue(other.updateRules.isEmpty())
        assertTrue(other.deleteRules.isEmpty())
        assertFalse(other.updateDerivesFromCreate)
    }

    @Test
    fun `a deep copy keeps rule order duplicates and derivation and is detached from the original`() {
        val first = ValidationRule<EntRuleClient, String> { _, _ -> error("copying must not evaluate rules") }
        val second = batchValidationRule<EntRuleClient, String> { _, _ -> error("copying must not evaluate rules") }
        val config = TestValidationConfig().apply {
            createRules.addAll(listOf(first, second, first))
            updateRules.addAll(listOf(second, first))
            deleteRules.addAll(listOf(first, second))
            updateDerivesFromCreate = true
        }

        val copy = config.deepCopy()
        config.createRules.clear()
        config.updateRules.clear()
        config.deleteRules.clear()
        config.updateDerivesFromCreate = false

        assertEquals(listOf(first, second, first), copy.createRules)
        assertEquals(listOf(second, first), copy.updateRules)
        assertEquals(listOf(first, second), copy.deleteRules)
        assertTrue(copy.updateDerivesFromCreate)

        val subsequent = config.deepCopy()
        assertTrue(subsequent.createRules.isEmpty())
        assertTrue(subsequent.updateRules.isEmpty())
        assertTrue(subsequent.deleteRules.isEmpty())
        assertFalse(subsequent.updateDerivesFromCreate)
    }
}
