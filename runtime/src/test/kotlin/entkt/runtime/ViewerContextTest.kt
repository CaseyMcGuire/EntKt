package entkt.runtime

import entkt.runtime.privacy.ViewerContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ViewerContextTest {

    @Test
    fun `PrivacyBypass requires a non-blank reason`() {
        assertFailsWith<IllegalArgumentException> { ViewerContext.privacyBypass_DANGEROUS("") }
        assertFailsWith<IllegalArgumentException> { ViewerContext.privacyBypass_DANGEROUS("   ") }
    }

    @Test
    fun `PrivacyBypass keeps its reason`() {
        assertEquals("migration backfill", ViewerContext.privacyBypass_DANGEROUS("migration backfill").reason)
    }
}
