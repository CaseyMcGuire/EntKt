package entkt.runtime.validation

import entkt.runtime.rule.EntRuleClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityValidationScopeTest {
    private class Config : EntityValidationConfig<
        BatchValidationRule<EntRuleClient, String>,
        BatchValidationRule<EntRuleClient, String>,
        BatchValidationRule<EntRuleClient, String>,
    >()

    private class Scope(config: Config) : EntityValidationScope<
        BatchValidationRule<EntRuleClient, String>,
        BatchValidationRule<EntRuleClient, String>,
        BatchValidationRule<EntRuleClient, String>,
    >(config)

    @Test
    fun `registration preserves mixed rule order duplicates and empty inputs in every phase`() {
        val scalar = ValidationRule<EntRuleClient, String> { _, _ -> error("registration must not evaluate rules") }
        val batch = batchValidationRule<EntRuleClient, String> { _, _ -> error("registration must not evaluate rules") }
        val config = Config()
        val scope = Scope(config)

        scope.create(scalar, batch, scalar)
        scope.create(*arrayOf(batch))
        scope.create()
        scope.create(*emptyArray())
        scope.update(batch, scalar)
        scope.update(*arrayOf(scalar, batch))
        scope.update()
        scope.update(*emptyArray())
        scope.delete(scalar)
        scope.delete(*arrayOf(batch, scalar))
        scope.delete()
        scope.delete(*emptyArray())

        assertEquals(listOf(scalar, batch, scalar, batch), config.createRules)
        assertEquals(listOf(batch, scalar, scalar, batch), config.updateRules)
        assertEquals(listOf(scalar, batch, scalar), config.deleteRules)
        assertFalse(config.updateDerivesFromCreate)
    }

    @Test
    fun `update derivation changes only the supplied configuration flag`() {
        val config = Config()
        val other = Config()
        val scope = Scope(config)

        scope.updateDerivesFromCreate()
        scope.updateDerivesFromCreate()

        assertTrue(config.updateDerivesFromCreate)
        assertTrue(config.createRules.isEmpty())
        assertTrue(config.updateRules.isEmpty())
        assertTrue(config.deleteRules.isEmpty())
        assertFalse(other.updateDerivesFromCreate)
    }
}
