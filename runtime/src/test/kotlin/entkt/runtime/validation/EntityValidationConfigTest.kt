@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import entkt.runtime.rule.EntRuleClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EntityValidationConfigTest {
    private class Config : EntityValidationConfig<
        BatchValidationRule<EntRuleClient, String>,
        BatchValidationRule<EntRuleClient, String>,
        BatchValidationRule<EntRuleClient, String>,
    >()

    @Test
    fun `configurations start empty with independent rule lists and derivation disabled`() {
        val config = Config()
        val other = Config()

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
    fun `resolution preserves rule order duplicates and derivation without retaining mutable lists`() {
        val first = ValidationRule<EntRuleClient, String> { _, _ -> error("resolution must not evaluate rules") }
        val second = batchValidationRule<EntRuleClient, String> { _, _ -> error("resolution must not evaluate rules") }
        val config = Config().apply {
            createRules.addAll(listOf(first, second, first))
            updateRules.addAll(listOf(second, first))
            deleteRules.addAll(listOf(first, second))
            updateDerivesFromCreate = true
        }

        val resolved = config.resolveForInternalUse()
        config.createRules.clear()
        config.updateRules.clear()
        config.deleteRules.clear()
        config.updateDerivesFromCreate = false

        assertEquals(listOf(first, second, first), resolved.createRules)
        assertEquals(listOf(second, first), resolved.updateRules)
        assertEquals(listOf(first, second), resolved.deleteRules)
        assertTrue(resolved.updateDerivesFromCreate)
        for (rules in listOf(resolved.createRules, resolved.updateRules, resolved.deleteRules)) {
            assertFailsWith<UnsupportedOperationException> {
                (rules as MutableList<*>).clear()
            }
        }

        val subsequent = config.resolveForInternalUse()
        assertTrue(subsequent.createRules.isEmpty())
        assertTrue(subsequent.updateRules.isEmpty())
        assertTrue(subsequent.deleteRules.isEmpty())
        assertFalse(subsequent.updateDerivesFromCreate)
    }
}
