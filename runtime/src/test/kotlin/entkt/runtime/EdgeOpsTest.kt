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
    fun `delta mode same-id paired add and remove cancel in computed delta`() {
        // The RFC's canonical cancellation example: current=[a],
        // tags.remove(a); tags.add(a) → added=[], removed=[].
        // Intent fields preserve both calls (literal call log).
        val ec = computeEdgeChanges(
            PendingEdgeOps(
                requestedAdds = setOf("a"),
                requestedRemoves = setOf("a"),
            ),
            setOf("a"),
        )
        assertEquals(setOf("a"), ec.requestedAdds)
        assertEquals(setOf("a"), ec.requestedRemoves)
        assertEquals(emptySet(), ec.added)
        assertEquals(emptySet(), ec.removed)
    }

    @Test
    fun `delta mode same-id paired cancel when id was not previously linked is also a no-op`() {
        // Set-based cancellation: paired add+remove of an id NOT
        // previously linked yields no DB op regardless of call order.
        // (Diverges from a strict ordered op-log walk, which would
        // distinguish remove-then-add as net-add. RFC test list
        // explicitly allows "potentially empty when operations cancel"
        // for either ordering.)
        val ec = computeEdgeChanges(
            PendingEdgeOps(
                requestedAdds = setOf("z"),
                requestedRemoves = setOf("z"),
            ),
            emptySet(),
        )
        assertEquals(setOf("z"), ec.requestedAdds)
        assertEquals(setOf("z"), ec.requestedRemoves)
        assertEquals(emptySet(), ec.added)
        assertEquals(emptySet(), ec.removed)
    }

    @Test
    fun `delta mode mixed — adds, removes, and a paired cancel each follow its own rule`() {
        // current=[c], add(a); add(c); remove(b); add(b); remove(b)
        //   → requestedAdds={a,b,c}, requestedRemoves={b}, paired={b}
        //   → added = ({a,c} - {c}) = {a}     // c was already linked → skip
        //     removed = ({} intersect {c}) = {}
        // Wait — that's not right. requestedRemoves={b}, paired={b} so
        // unpaired_removes = {} → removed={}. Let me redo.
        val ec = computeEdgeChanges(
            PendingEdgeOps(
                requestedAdds = setOf("a", "b", "c"),
                requestedRemoves = setOf("b"),
            ),
            setOf("c"),
        )
        // unpaired_adds = {a,b,c} - {b} = {a,c}; added = {a,c} - {c} = {a}
        // unpaired_removes = {b} - {b} = {}; removed = {} intersect {c} = {}
        assertEquals(setOf("a"), ec.added)
        assertEquals(emptySet(), ec.removed)
    }
}
