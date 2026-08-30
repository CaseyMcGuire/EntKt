@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.privacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResolvedEntityPrivacyConfigTest {
    @Test
    fun `rule lists are detached and immutable`() {
        val loadRules = mutableListOf("initial")
        val resolved = ResolvedEntityPrivacyConfig(
            loadRules = loadRules,
            createRules = emptyList<String>(),
            updateRules = emptyList<String>(),
            deleteRules = emptyList<String>(),
            updateDerivesFromCreate = true,
            deleteDerivesFromCreate = false,
        )

        loadRules += "late"

        assertEquals(listOf("initial"), resolved.loadRules)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (resolved.loadRules as MutableList<String>).clear()
        }
    }
}
