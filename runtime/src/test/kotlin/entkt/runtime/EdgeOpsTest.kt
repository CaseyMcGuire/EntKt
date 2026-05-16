package entkt.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EdgeOpsTest {

    // ---------- PendingEdgeOps ----------

    @Test
    fun `empty PendingEdgeOps reports no replacement and no changes`() {
        val ops = PendingEdgeOps<Long>()
        assertNull(ops.requestedSet)
        assertEquals(emptySet(), ops.requestedAdds)
        assertEquals(emptySet(), ops.requestedRemoves)
        assertFalse(ops.hasReplacement)
        assertFalse(ops.hasChanges)
    }

    @Test
    fun `replacement-mode PendingEdgeOps reports replacement and changes`() {
        val ops = PendingEdgeOps(requestedSet = setOf(1L, 2L))
        assertTrue(ops.hasReplacement)
        assertTrue(ops.hasChanges)
    }

    @Test
    fun `replacement-mode set with no ids still flips hasReplacement`() {
        val ops = PendingEdgeOps<Long>(requestedSet = emptySet())
        assertTrue(ops.hasReplacement)
        assertTrue(ops.hasChanges)
    }

    @Test
    fun `delta-mode add only reports changes but not replacement`() {
        val ops = PendingEdgeOps(requestedAdds = setOf(1L))
        assertFalse(ops.hasReplacement)
        assertTrue(ops.hasChanges)
    }

    @Test
    fun `delta-mode remove only reports changes but not replacement`() {
        val ops = PendingEdgeOps(requestedRemoves = setOf(1L))
        assertFalse(ops.hasReplacement)
        assertTrue(ops.hasChanges)
    }

    @Test
    fun `PendingEdgeOps preserves same-id in both add and remove sets`() {
        // The intent fields are the literal call log (deduped). A
        // paired add+remove on the same id does NOT cancel out at the
        // intent layer — it cancels only in the computed EdgeChanges
        // delta. RFC #5 contract.
        val ops = PendingEdgeOps(
            requestedAdds = setOf(7L),
            requestedRemoves = setOf(7L),
        )
        assertTrue(7L in ops.requestedAdds)
        assertTrue(7L in ops.requestedRemoves)
        assertTrue(ops.hasChanges)
    }

    // ---------- EdgeChanges ----------

    @Test
    fun `empty EdgeChanges reports no changes and no database effect`() {
        val ec = EdgeChanges<Long>()
        assertFalse(ec.hasReplacement)
        assertFalse(ec.hasChanges)
        assertFalse(ec.hasDatabaseEffect)
    }

    @Test
    fun `EdgeChanges with set replacement reports replacement and database effect`() {
        val ec = EdgeChanges(
            requestedSet = setOf(1L, 2L),
            added = setOf(2L),
            removed = setOf(99L),
        )
        assertTrue(ec.hasReplacement)
        assertTrue(ec.hasChanges)
        assertTrue(ec.hasDatabaseEffect)
    }

    @Test
    fun `EdgeChanges with cancelled add+remove reports intent but no database effect`() {
        // The RFC's canonical "operations cancel" case: caller did
        // remove(aId); add(aId). Intent surface shows aId in both
        // sets, database effect is empty.
        val ec = EdgeChanges(
            requestedAdds = setOf(7L),
            requestedRemoves = setOf(7L),
            // added / removed left empty — the computed delta
        )
        assertTrue(ec.hasChanges)
        assertFalse(ec.hasDatabaseEffect)
    }

    @Test
    fun `EdgeChanges with intent-only remove of unknown id reports intent but no database effect`() {
        // The "remove(unknownId)" case from Target Loading And Existence:
        // intent surface has the id, computed delta does not.
        val ec = EdgeChanges(requestedRemoves = setOf(99L))
        assertTrue(ec.hasChanges)
        assertFalse(ec.hasDatabaseEffect)
    }

    @Test
    fun `EdgeChanges with computed-only added reports database effect`() {
        // After a `set(...)` where the requestedSet ends up adding
        // ids that weren't requested as adds (delta mode isn't used).
        val ec = EdgeChanges(
            requestedSet = setOf(1L),
            added = setOf(1L),
        )
        assertTrue(ec.hasReplacement)
        assertTrue(ec.hasDatabaseEffect)
    }

    @Test
    fun `EdgeChanges is parameterized so different id scalar types compile`() {
        // Pin the generic parameter compiles for the common id scalar
        // types entkt supports.
        val longEc: EdgeChanges<Long> = EdgeChanges(added = setOf(1L))
        val uuidEc: EdgeChanges<java.util.UUID> =
            EdgeChanges(added = setOf(java.util.UUID.randomUUID()))
        val intEc: EdgeChanges<Int> = EdgeChanges(added = setOf(1))
        val stringEc: EdgeChanges<String> = EdgeChanges(added = setOf("x"))
        assertTrue(longEc.hasDatabaseEffect)
        assertTrue(uuidEc.hasDatabaseEffect)
        assertTrue(intEc.hasDatabaseEffect)
        assertTrue(stringEc.hasDatabaseEffect)
    }
}
