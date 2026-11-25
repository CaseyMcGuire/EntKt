@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.rule.TestRuleClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private typealias TestPrivacyConfig = EntityPrivacyConfig<
    BatchPrivacyRule<TestViewer, TestRuleClient, String>,
    BatchPrivacyRule<TestViewer, TestRuleClient, String>,
    BatchPrivacyRule<TestViewer, TestRuleClient, String>,
    BatchPrivacyRule<TestViewer, TestRuleClient, String>,
>

class EntityPrivacyConfigTest {
    @Test
    fun `configurations start empty with independent rule lists and derivation disabled`() {
        val config = TestPrivacyConfig()
        val other = TestPrivacyConfig()

        assertTrue(config.loadRules.isEmpty())
        assertTrue(config.createRules.isEmpty())
        assertTrue(config.updateRules.isEmpty())
        assertTrue(config.deleteRules.isEmpty())
        assertFalse(config.updateDerivesFromCreate)
        assertFalse(config.deleteDerivesFromCreate)

        config.loadRules += PrivacyRule<TestViewer, TestRuleClient, String> { _, _ -> PrivacyDecision.Allow }

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
    fun `a deep copy keeps rule order duplicates and derivation and is detached from the original`() {
        val first = PrivacyRule<TestViewer, TestRuleClient, String> { _, _ -> error("copying must not evaluate rules") }
        val second = batchPrivacyRule<TestViewer, TestRuleClient, String> { _, _ -> error("copying must not evaluate rules") }
        val config = TestPrivacyConfig().apply {
            loadRules.addAll(listOf(first, second, first))
            createRules.addAll(listOf(second, first))
            updateRules.addAll(listOf(first, second))
            deleteRules.addAll(listOf(second, first, second))
            updateDerivesFromCreate = true
            deleteDerivesFromCreate = true
        }

        val copy = config.deepCopy()
        config.loadRules.clear()
        config.createRules.clear()
        config.updateRules.clear()
        config.deleteRules.clear()
        config.updateDerivesFromCreate = false
        config.deleteDerivesFromCreate = false

        assertEquals(listOf(first, second, first), copy.loadRules)
        assertEquals(listOf(second, first), copy.createRules)
        assertEquals(listOf(first, second), copy.updateRules)
        assertEquals(listOf(second, first, second), copy.deleteRules)
        assertTrue(copy.updateDerivesFromCreate)
        assertTrue(copy.deleteDerivesFromCreate)

        val subsequent = config.deepCopy()
        assertTrue(subsequent.loadRules.isEmpty())
        assertTrue(subsequent.createRules.isEmpty())
        assertTrue(subsequent.updateRules.isEmpty())
        assertTrue(subsequent.deleteRules.isEmpty())
        assertFalse(subsequent.updateDerivesFromCreate)
        assertFalse(subsequent.deleteDerivesFromCreate)
    }
}
