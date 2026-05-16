package entkt.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `PendingEdgeOps data class itself doesn't enforce disjoint-by-construction — the mutator does`() {
        // The disjoint-requestedAdds-vs-requestedRemoves invariant
        // (RFC #5) is enforced at the *mutator* call site so the data
        // class never receives a violating snapshot in normal use.
        // The data class itself is a plain immutable value with no
        // validation in its constructor, so a directly-constructed
        // instance can still hold overlapping sets — useful for
        // testing downstream code without going through the mutator.
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
    fun `EdgeChanges data class doesn't enforce disjoint-requestedAdds-vs-requestedRemoves — the mutator does`() {
        // Same shape note as PendingEdgeOps: in normal use the
        // mutator's call-site rejection keeps requestedAdds and
        // requestedRemoves disjoint, so this combination doesn't
        // arise. EdgeChanges is a plain data class that doesn't
        // validate its constructor; downstream code that consumes
        // EdgeChanges from a trusted source can rely on the
        // disjoint invariant.
        val ec = EdgeChanges(
            requestedAdds = setOf(7L),
            requestedRemoves = setOf(7L),
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

    // ---------- computeEdgeChanges ----------

    @Test
    fun `replacement mode — added is requestedSet minus current, removed is current minus requestedSet`() {
        // The RFC's canonical replacement example: current=[a,c],
        // tags.set([a,b]) → added=[b], removed=[c].
        val ec = computeEdgeChanges(
            PendingEdgeOps(requestedSet = setOf("a", "b")),
            setOf("a", "c"),
        )
        assertEquals(setOf("a", "b"), ec.requestedSet)
        assertEquals(setOf("b"), ec.added)
        assertEquals(setOf("c"), ec.removed)
        // Intent fields stay empty in replacement mode — mixed-mode is
        // rejected at the mutator call site, so requestedAdds /
        // requestedRemoves cannot coexist with requestedSet.
        assertEquals(emptySet(), ec.requestedAdds)
        assertEquals(emptySet(), ec.requestedRemoves)
    }

    @Test
    fun `replacement mode with empty requestedSet removes everything currently linked`() {
        val ec = computeEdgeChanges(
            PendingEdgeOps(requestedSet = emptySet()),
            setOf("a", "b"),
        )
        assertEquals(emptySet(), ec.requestedSet)
        assertEquals(emptySet(), ec.added)
        assertEquals(setOf("a", "b"), ec.removed)
    }

    @Test
    fun `delta mode add only — added is requestedAdds minus current`() {
        val ec = computeEdgeChanges(
            PendingEdgeOps(requestedAdds = setOf("a", "b")),
            setOf("a"),
        )
        // "a" already linked → not in added; "b" new → in added.
        assertEquals(setOf("a", "b"), ec.requestedAdds)
        assertEquals(setOf("b"), ec.added)
        assertEquals(emptySet(), ec.removed)
    }

    @Test
    fun `delta mode remove only — removed is requestedRemoves intersect current`() {
        val ec = computeEdgeChanges(
            PendingEdgeOps(requestedRemoves = setOf("a", "x")),
            setOf("a"),
        )
        // "a" currently linked → in removed; "x" not linked → no-op delete.
        // Intent surface still carries "x" (literal call log).
        assertEquals(setOf("a", "x"), ec.requestedRemoves)
        assertEquals(emptySet(), ec.added)
        assertEquals(setOf("a"), ec.removed)
    }

    @Test
    fun `computeEdgeChanges rejects requestedSet alongside non-empty intent sets`() {
        // Defense-in-depth for direct callers: the data class doesn't
        // enforce mutual exclusivity in its constructor, but the
        // utility does. Generated-code call sites can't trip this —
        // the mutator's per-call check throws first.
        val mixedReplacement = PendingEdgeOps(
            requestedSet = setOf("a"),
            requestedAdds = setOf("b"),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            computeEdgeChanges(mixedReplacement, emptySet())
        }
        assertTrue(ex.message!!.contains("mutually exclusive"))
    }

    @Test
    fun `computeEdgeChanges rejects overlapping requestedAdds and requestedRemoves`() {
        val overlap = PendingEdgeOps(
            requestedAdds = setOf("a", "b"),
            requestedRemoves = setOf("b", "c"),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            computeEdgeChanges(overlap, emptySet())
        }
        assertTrue(ex.message!!.contains("disjoint"))
        assertTrue(ex.message!!.contains("b"))
    }

    @Test
    fun `delta mode with disjoint adds and removes computes straight set algebra`() {
        // Per RFC #5: requestedAdds and requestedRemoves are disjoint
        // by construction (mutator rejects same-id mixed-direction at
        // the call site). computeEdgeChanges therefore doesn't need
        // cancellation logic — just set algebra.
        val ec = computeEdgeChanges(
            PendingEdgeOps(
                requestedAdds = setOf("a", "b", "c"),
                requestedRemoves = setOf("d"),
            ),
            setOf("c", "d"),
        )
        // added = requestedAdds - current = {a,b,c} - {c,d} = {a,b}
        // removed = requestedRemoves ∩ current = {d} ∩ {c,d} = {d}
        assertEquals(setOf("a", "b"), ec.added)
        assertEquals(setOf("d"), ec.removed)
        // Intent fields pass through verbatim.
        assertEquals(setOf("a", "b", "c"), ec.requestedAdds)
        assertEquals(setOf("d"), ec.requestedRemoves)
    }
}
