package entkt.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ViewerTest {

    @Test
    fun `PrivacyBypass requires a non-blank reason`() {
        assertFailsWith<IllegalArgumentException> { Viewer.PrivacyBypass("") }
        assertFailsWith<IllegalArgumentException> { Viewer.PrivacyBypass("   ") }
    }

    @Test
    fun `PrivacyBypass keeps its reason`() {
        assertEquals("migration backfill", Viewer.PrivacyBypass("migration backfill").reason)
    }
}
