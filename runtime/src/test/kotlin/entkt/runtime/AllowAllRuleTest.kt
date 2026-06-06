package entkt.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class AllowAllRuleTest {

    private data class FakeLoadCtx(val id: Int)
    private data class FakeCreateCtx(val name: String)

    @Test
    fun `allowAll returns Allow for any context value`() {
        assertEquals(PrivacyDecision.Allow, allowAll.run(FakeLoadCtx(1)))
        assertEquals(PrivacyDecision.Allow, allowAll.run("anything"))
        assertEquals(PrivacyDecision.Allow, allowAll.run(null))
    }

    @Test
    fun `allowAll is assignable to any typed rule slot via contravariance`() {
        // The single value substitutes for every PrivacyRule<SpecificContext> —
        // this is what lets `load(allowAll)` / `create(allowAll)` compile on any
        // generated entity. Assigning to the typed aliases here is the test.
        val asLoad: PrivacyRule<FakeLoadCtx> = allowAll
        val asCreate: PrivacyRule<FakeCreateCtx> = allowAll
        assertEquals(PrivacyDecision.Allow, asLoad.run(FakeLoadCtx(2)))
        assertEquals(PrivacyDecision.Allow, asCreate.run(FakeCreateCtx("x")))
    }
}
