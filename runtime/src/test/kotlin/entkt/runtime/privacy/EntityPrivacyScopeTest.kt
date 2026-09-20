@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import entkt.runtime.rule.EntRuleClient
import entkt.runtime.rule.TestRuleClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityPrivacyScopeTest {
    private class Config : EntityPrivacyConfig<
        BatchPrivacyRule<TestRuleClient, String>,
        BatchPrivacyRule<TestRuleClient, Int>,
        BatchPrivacyRule<TestRuleClient, Boolean>,
        BatchPrivacyRule<TestRuleClient, Long>,
    >()

    private class Scope(config: Config) : EntityPrivacyScope<TestRuleClient, String, Int, Boolean, Long>(config)

    @Test
    fun `registration preserves mixed rule order duplicates and empty inputs in every phase`() {
        val loadScalar = scalarRule<String>()
        val loadBatch = batchRule<String>()
        val createScalar = scalarRule<Int>()
        val createBatch = batchRule<Int>()
        val updateScalar = scalarRule<Boolean>()
        val updateBatch = batchRule<Boolean>()
        val deleteScalar = scalarRule<Long>()
        val deleteBatch = batchRule<Long>()
        val config = Config()
        val scope = Scope(config)

        scope.load(loadScalar, loadBatch, loadScalar)
        scope.load(*arrayOf(loadBatch))
        scope.load()
        scope.load(*emptyArray())
        scope.create(createScalar, createBatch, createScalar)
        scope.create(*arrayOf(createBatch))
        scope.create()
        scope.create(*emptyArray())
        scope.update(updateBatch, updateScalar)
        scope.update(*arrayOf(updateScalar, updateBatch))
        scope.update()
        scope.update(*emptyArray())
        scope.delete(deleteScalar)
        scope.delete(*arrayOf(deleteBatch, deleteScalar))
        scope.delete()
        scope.delete(*emptyArray())

        assertEquals(listOf(loadScalar, loadBatch, loadScalar, loadBatch), config.loadRules)
        assertEquals(listOf(createScalar, createBatch, createScalar, createBatch), config.createRules)
        assertEquals(listOf(updateBatch, updateScalar, updateScalar, updateBatch), config.updateRules)
        assertEquals(listOf(deleteScalar, deleteBatch, deleteScalar), config.deleteRules)
        assertFalse(config.updateDerivesFromCreate)
        assertFalse(config.deleteDerivesFromCreate)
    }

    @Test
    fun `one context rule adapts to every item type and uses each evaluation's current context`() {
        val seen = mutableListOf<PrivacyRuleContext<EntRuleClient>>()
        val shared = ContextPrivacyRule<EntRuleClient> { context ->
            seen += context
            if (context.viewerContext.viewer is Viewer.Anonymous) {
                PrivacyDecision.Deny("authentication required")
            } else {
                PrivacyDecision.Allow
            }
        }
        val config = Config()
        Scope(config).apply {
            load(shared)
            create(shared)
            update(shared)
            delete(shared)
        }
        assertTrue(seen.isEmpty(), "Registration must not evaluate context rules")
        val signedIn = PrivacyRuleContext(ViewerContext(Viewer.User(7L)), TestRuleClient())
        val anonymous = PrivacyRuleContext(ViewerContext(Viewer.Anonymous), TestRuleClient())

        assertEquals(
            listOf(PrivacyDecision.Allow, PrivacyDecision.Allow),
            evaluate(listOf("first", "second"), config.loadRules, signedIn),
        )
        assertEquals(
            listOf(PrivacyDecision.Deny("authentication required")),
            evaluate(listOf(1), config.createRules, anonymous),
        )
        assertEquals(listOf(PrivacyDecision.Allow), evaluate(listOf(true), config.updateRules, signedIn))
        assertEquals(
            listOf(PrivacyDecision.Deny("authentication required")),
            evaluate(listOf(2L), config.deleteRules, anonymous),
        )
        assertEquals(
            listOf<PrivacyRuleContext<EntRuleClient>>(signedIn, signedIn, anonymous, signedIn, anonymous),
            seen,
        )
    }

    @Test
    fun `context scalar and batch rules share registration order and short circuiting`() {
        val calls = mutableListOf<String>()
        val config = Config()
        Scope(config).apply {
            load(batchPrivacyRule { _, batch ->
                batch.decideEach { item ->
                    calls += "batch:$item"
                    PrivacyDecision.Continue
                }
            })
            load(ContextPrivacyRule {
                calls += "context"
                PrivacyDecision.Continue
            })
            load(PrivacyRule { _, item ->
                calls += "scalar:$item"
                PrivacyDecision.Allow
            })
            load(ContextPrivacyRule { error("An allowed item must not reach later rules") })
        }
        assertTrue(calls.isEmpty(), "Registration must not evaluate any rule")

        val context = PrivacyRuleContext(ViewerContext(Viewer.User(7L)), TestRuleClient())
        assertEquals(
            listOf(PrivacyDecision.Allow, PrivacyDecision.Allow),
            evaluate(listOf("first", "second"), config.loadRules, context),
        )
        assertEquals(listOf("batch:first", "batch:second", "context", "context", "scalar:first", "scalar:second"), calls)
    }

    @Test
    fun `update and delete derivation flags are independent and affect only the supplied configuration`() {
        val updateConfig = Config()
        val deleteConfig = Config()

        Scope(updateConfig).updateDerivesFromCreate()
        Scope(deleteConfig).deleteDerivesFromCreate()

        assertTrue(updateConfig.updateDerivesFromCreate)
        assertFalse(updateConfig.deleteDerivesFromCreate)
        assertFalse(deleteConfig.updateDerivesFromCreate)
        assertTrue(deleteConfig.deleteDerivesFromCreate)
        for (config in listOf(updateConfig, deleteConfig)) {
            assertTrue(config.loadRules.isEmpty())
            assertTrue(config.createRules.isEmpty())
            assertTrue(config.updateRules.isEmpty())
            assertTrue(config.deleteRules.isEmpty())
        }
    }

    @Test
    fun `only context registration has a distinct Java name`() {
        val methods = EntityPrivacyScope::class.java.declaredMethods
        for (operation in listOf("load", "create", "update", "delete")) {
            val itemAware = methods.single { it.name == operation }
            assertTrue(itemAware.isVarArgs)
            assertEquals(BatchPrivacyRule::class.java, itemAware.parameterTypes.single().componentType)
            val contextOnly = methods.single { it.name == "${operation}ContextRule" }
            assertFalse(contextOnly.isVarArgs)
            assertEquals(ContextPrivacyRule::class.java, contextOnly.parameterTypes.single())
            assertFalse(methods.any { it.name == "${operation}BatchRule" })
        }
    }

    private fun <Item> scalarRule(): PrivacyRule<TestRuleClient, Item> =
        PrivacyRule { _, _ -> error("Registration must not evaluate scalar rules") }

    private fun <Item> batchRule(): BatchPrivacyRule<TestRuleClient, Item> =
        batchPrivacyRule { _, _ -> error("Registration must not evaluate batch rules") }

    private fun <Item> evaluate(
        items: List<Item>,
        rules: List<BatchPrivacyRule<TestRuleClient, Item>>,
        context: PrivacyRuleContext<TestRuleClient>,
    ): List<PrivacyDecision> = evaluateBatchPrivacyRulesForInternalUse(
        lifecycle = "test privacy",
        items = items,
        rules = rules,
        context = context,
        freshItem = { it },
    )
}
