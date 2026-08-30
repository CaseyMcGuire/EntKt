@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResolvedEntityValidationConfigTest {
    @Test
    fun `rule lists are detached and immutable`() {
        val createRules = mutableListOf("initial")
        val resolved = ResolvedEntityValidationConfig(
            createRules = createRules,
            updateRules = emptyList<String>(),
            deleteRules = emptyList<String>(),
            updateDerivesFromCreate = true,
        )

        createRules += "late"

        assertEquals(listOf("initial"), resolved.createRules)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (resolved.createRules as MutableList<String>).clear()
        }
    }
}
