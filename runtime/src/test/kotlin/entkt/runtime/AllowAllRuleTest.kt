package entkt.runtime

import entkt.runtime.privacy.BatchPrivacyRule
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyRule
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.allowAll
import entkt.runtime.rule.RuleBatch
import kotlin.test.Test
import kotlin.test.assertEquals

class AllowAllRuleTest {

    private data class FakeLoadCtx(val id: Int)
    private data class FakeCreateCtx(val name: String)
    private val context = PrivacyRuleContext(
        viewerContext = ViewerContext(Viewer.Anonymous),
        client = "client",
    )

    @Test
    fun `allowAll returns Allow for any item value`() {
        assertEquals(PrivacyDecision.Allow, allowAll.run(context, FakeLoadCtx(1)))
        assertEquals(PrivacyDecision.Allow, allowAll.run(context, "anything"))
        assertEquals(PrivacyDecision.Allow, allowAll.run(context, null))
    }

    @Test
    fun `allowAll is assignable to any typed rule slot via contravariance`() {
        // The single value substitutes for every PrivacyRule<Client, SpecificItem> —
        // this is what lets `load(allowAll)` / `create(allowAll)` compile on any
        // generated entity. Assigning to the typed aliases here is the test.
        val asLoad: PrivacyRule<String, FakeLoadCtx> = allowAll
        val asCreate: PrivacyRule<String, FakeCreateCtx> = allowAll
        val asBatch: BatchPrivacyRule<String, FakeLoadCtx> = allowAll
        assertEquals(PrivacyDecision.Allow, asLoad.run(context, FakeLoadCtx(2)))
        assertEquals(PrivacyDecision.Allow, asCreate.run(context, FakeCreateCtx("x")))
        val batch = RuleBatch.from(listOf(FakeLoadCtx(3), FakeLoadCtx(4)))
        assertEquals(
            listOf<PrivacyDecision>(PrivacyDecision.Allow, PrivacyDecision.Allow),
            asBatch.runBatch(context, batch),
        )
    }
}
