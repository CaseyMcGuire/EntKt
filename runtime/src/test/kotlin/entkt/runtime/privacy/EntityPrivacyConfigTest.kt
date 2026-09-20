@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.rule.TestRuleClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityPrivacyConfigTest {
    private class Config : EntityPrivacyConfig<
        BatchPrivacyRule<TestRuleClient, String>,
        BatchPrivacyRule<TestRuleClient, String>,
        BatchPrivacyRule<TestRuleClient, String>,
        BatchPrivacyRule<TestRuleClient, String>,
    >()

    @Test
    fun `configurations start empty with independent rule lists and derivation disabled`() {
        val config = Config()
        val other = Config()

        assertTrue(config.loadRules.isEmpty())
        assertTrue(config.createRules.isEmpty())
        assertTrue(config.updateRules.isEmpty())
        assertTrue(config.deleteRules.isEmpty())
        assertFalse(config.updateDerivesFromCreate)
        assertFalse(config.deleteDerivesFromCreate)

        config.loadRules += PrivacyRule<TestRuleClient, String> { _, _ -> PrivacyDecision.Allow }

        assertTrue(config.createRules.isEmpty())
        assertTrue(config.updateRules.isEmpty())
        assertTrue(config.deleteRules.isEmpty())
        assertTrue(other.loadRules.isEmpty())
        assertTrue(other.createRules.isEmpty())
        assertTrue(other.updateRules.isEmpty())
        assertTrue(other.deleteRules.isEmpty())
        assertFalse(other.updateDerivesFromCreate)
        assertFalse(other.deleteDerivesFromCreate)
    }

    @Test
    fun `resolution preserves rule order duplicates and derivation without retaining mutable lists`() {
        val first = PrivacyRule<TestRuleClient, String> { _, _ -> error("resolution must not evaluate rules") }
        val second = batchPrivacyRule<TestRuleClient, String> { _, _ -> error("resolution must not evaluate rules") }
        val config = Config().apply {
            loadRules.addAll(listOf(first, second, first))
            createRules.addAll(listOf(second, first))
            updateRules.addAll(listOf(first, second))
            deleteRules.addAll(listOf(second, first, second))
            updateDerivesFromCreate = true
            deleteDerivesFromCreate = true
        }

        val resolved = config.resolveForInternalUse()
        config.loadRules.clear()
        config.createRules.clear()
        config.updateRules.clear()
        config.deleteRules.clear()
        config.updateDerivesFromCreate = false
        config.deleteDerivesFromCreate = false

        assertEquals(listOf(first, second, first), resolved.loadRules)
        assertEquals(listOf(second, first), resolved.createRules)
        assertEquals(listOf(first, second), resolved.updateRules)
        assertEquals(listOf(second, first, second), resolved.deleteRules)
        assertTrue(resolved.updateDerivesFromCreate)
        assertTrue(resolved.deleteDerivesFromCreate)
        for (rules in listOf(resolved.loadRules, resolved.createRules, resolved.updateRules, resolved.deleteRules)) {
            assertFailsWith<UnsupportedOperationException> {
                (rules as MutableList<*>).clear()
            }
        }

        val subsequent = config.resolveForInternalUse()
        assertTrue(subsequent.loadRules.isEmpty())
        assertTrue(subsequent.createRules.isEmpty())
        assertTrue(subsequent.updateRules.isEmpty())
        assertTrue(subsequent.deleteRules.isEmpty())
        assertFalse(subsequent.updateDerivesFromCreate)
        assertFalse(subsequent.deleteDerivesFromCreate)
    }
}
