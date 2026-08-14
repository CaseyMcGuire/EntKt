package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.query.ExcludeDeleted
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.MutationResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the soft-delete *convention* per
 * `docs/possible-features/schema/soft-delete.md`. The convention
 * has two artifacts:
 *
 *  - the `entkt.schema.DeletedAt` mixin (declares a nullable
 *    `deleted_at` timestamp on the host schema)
 *  - the `entkt.runtime.query.ExcludeDeleted` interceptor (filters
 *    rows with non-null `deleted_at` out of default read paths)
 *
 * Neither artifact requires soft-delete-specific codegen.
 * Generated `delete`/`deleteById`/`deleteMany` APIs keep their
 * existing physical-delete meaning; applications soft-delete by
 * writing the timestamp through the ordinary `update { deletedAt = … }`
 * API. Reads surface through `ReadResult` (a filtered-out row is
 * `Success(null)` / absent from `Success(list)`), mutations through
 * `MutationResult`.
 *
 * Backed by [Memo], a minimal schema that includes `DeletedAt`.
 */
class SoftDeleteConventionIntegrationTest : PostgresTestBase() {

    /** Client with `ExcludeDeleted` installed — hides soft-deleted rows by default. */
    private fun filteredClient(driver: PostgresDriver): EntClient =
        EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                memos(ExcludeDeleted(), name = "soft-delete")
            }
        }

    /** Client without the interceptor — sees every row, deleted or not. */
    private fun unfilteredClient(driver: PostgresDriver): EntClient =
        EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }

    // ---- Read-filter behavior ----

    @Test
    fun `filtered client excludes soft-deleted rows from all`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val alive = filtered.memos.create { body = "alive" }.saveAndLoad().getOrThrow()
        val gone = filtered.memos.create { body = "gone" }.saveAndLoad().getOrThrow()

        // Soft-delete `gone` via the ordinary update API.
        filtered.memos.update(gone.id) { deletedAt = Instant.now() }.save().getOrThrow()

        // Filtered client sees only the live row.
        val visible = filtered.memos.query().all().getOrThrow()
        assertEquals(listOf("alive"), visible.map { it.body })
        assertEquals(listOf(alive.id), visible.map { it.id })

        // Unfiltered client sees both rows; the soft-deleted one
        // carries the stamped timestamp.
        val all = unfiltered.memos.query().all().getOrThrow().sortedBy { it.id }
        assertEquals(listOf("alive", "gone"), all.map { it.body })
        assertNull(all[0].deletedAt, "live row's deletedAt must remain null")
        assertNotNull(all[1].deletedAt, "soft-deleted row's deletedAt must be stamped")
    }

    @Test
    fun `filtered client findById returns Success(null) for a soft-deleted row`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val memo = filtered.memos.create { body = "x" }.saveAndLoad().getOrThrow()
        filtered.memos.update(memo.id) { deletedAt = Instant.now() }.save().getOrThrow()

        // Filtered client: invisible via findById — authoritative absence.
        assertNull(filtered.memos.findById(memo.id).getOrThrow())

        // Unfiltered client: still reachable.
        val reread = unfiltered.memos.findById(memo.id).getOrThrow()
        assertNotNull(reread)
        assertEquals(memo.id, reread.id)
        assertNotNull(reread.deletedAt)
    }

    @Test
    fun `filtered client rawCount and rawExists ignore soft-deleted rows`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        filtered.memos.create { body = "live-a" }.save().getOrThrow()
        val gone = filtered.memos.create { body = "live-b" }.saveAndLoad().getOrThrow()
        filtered.memos.update(gone.id) { deletedAt = Instant.now() }.save().getOrThrow()

        // Filtered: 1 visible row.
        assertEquals(1L, filtered.memos.query().rawCount().getOrThrow())
        assertTrue(filtered.memos.query().rawExists().getOrThrow())

        // Unfiltered: 2 rows in storage.
        assertEquals(2L, unfiltered.memos.query().rawCount().getOrThrow())
    }

    // ---- Restore needs the unfiltered client ----

    @Test
    fun `restore through the unfiltered client is the recommended pattern`() {
        // The contract recommends restore through the unfiltered client.
        // Verify it works end-to-end: soft-delete → unfiltered restore →
        // row reappears through the filtered client.
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val memo = filtered.memos.create { body = "phoenix" }.saveAndLoad().getOrThrow()
        filtered.memos.update(memo.id) { deletedAt = Instant.now() }.save().getOrThrow()

        // Sanity: row is invisible via filtered query path.
        assertNull(filtered.memos.findById(memo.id).getOrThrow())

        // Restore via the unfiltered client (the recommended pattern).
        val restored = unfiltered.memos.update(memo.id) { deletedAt = null }.saveAndLoad().getOrThrow()
        assertNull(restored.deletedAt)

        // And the row reappears through the filtered client.
        val reread = filtered.memos.findById(memo.id).getOrThrow()
        assertNotNull(reread)
        assertEquals("phoenix", reread.body)
    }

    @Test
    fun `restore through the filtered client also works today — but pattern is brittle`() {
        // Owner-row load inside update(id).save() routes through
        // driver.byId(...) directly today, NOT through the
        // read-interceptor chain. So the filtered client can in
        // fact restore a soft-deleted row. This test pins the
        // current behavior; the contract text recommends the unfiltered
        // client anyway because if the framework later routes the
        // owner-row load through interceptors (a reasonable
        // consistency fix), the filtered-restore path would
        // silently break.
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val memo = filtered.memos.create { body = "fragile" }.saveAndLoad().getOrThrow()
        filtered.memos.update(memo.id) { deletedAt = Instant.now() }.save().getOrThrow()

        // Filtered restore happens to succeed today via the unfiltered
        // owner-row load; a Failed(EntTargetAbsentException) here would
        // mean the owner-row load started honoring interceptors.
        val restored = filtered.memos.update(memo.id) { deletedAt = null }.saveAndLoad().getOrThrow()
        assertNull(restored.deletedAt)

        // Visible through both clients again.
        assertNotNull(filtered.memos.findById(memo.id).getOrThrow())
        assertNotNull(unfiltered.memos.findById(memo.id).getOrThrow())
    }

    // ---- Generated delete* keeps its physical-delete meaning ----

    @Test
    fun `delete physically removes the row — not a soft delete`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val memo = filtered.memos.create { body = "for-real" }.saveAndLoad().getOrThrow()

        filtered.memos.delete(memo).getOrThrow()

        // Both clients see zero rows — physical removal, not a
        // soft delete masquerading.
        assertEquals(0L, filtered.memos.query().rawCount().getOrThrow())
        assertEquals(0L, unfiltered.memos.query().rawCount().getOrThrow())
        assertNull(unfiltered.memos.findById(memo.id).getOrThrow())
    }

    @Test
    fun `deleteById physically removes the row`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val memo = filtered.memos.create { body = "rm" }.saveAndLoad().getOrThrow()

        val result = filtered.memos.deleteById(memo.id)
        val ok = assertIs<MutationResult.Success<Boolean>>(result)
        assertTrue(ok.value, "deleteById must report true when this call deleted the row")

        assertEquals(0L, unfiltered.memos.query().rawCount().getOrThrow())
    }

    @Test
    fun `deleteMany on the filtered client only purges visible candidates`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val live = filtered.memos.create { body = "live" }.saveAndLoad().getOrThrow()
        val gone = filtered.memos.create { body = "gone" }.saveAndLoad().getOrThrow()
        filtered.memos.update(gone.id) { deletedAt = Instant.now() }.save().getOrThrow()

        // deleteMany's candidate fetch routes through read
        // interceptors today (DELETE_CANDIDATES) — so the filtered
        // client's ExcludeDeleted narrows the candidate set to
        // visible rows only. The already-soft-deleted row is left
        // alone.
        val purged = filtered.memos.deleteMany().getOrThrow()
        assertEquals(1, purged, "only the live row should be physically removed")

        // The soft-deleted row survives, still soft-deleted.
        val remaining = unfiltered.memos.query().all().getOrThrow()
        assertEquals(1, remaining.size)
        assertEquals("gone", remaining.single().body)
        assertNotNull(remaining.single().deletedAt)

        // The live row is gone from storage.
        assertNull(unfiltered.memos.findById(live.id).getOrThrow())
    }

    // ---- Convention-level invariants ----

    @Test
    fun `soft-delete persists the timestamp — UPDATE pipeline, not DELETE pipeline`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val stamp = Instant.parse("2024-01-15T12:00:00Z")
        val memo = filtered.memos.create { body = "tick" }.saveAndLoad().getOrThrow()
        val stamped = filtered.memos.update(memo.id) {
            deletedAt = stamp
        }.saveAndLoad().getOrThrow()

        // The update returned the post-write entity, including the
        // new deletedAt — proving this was an UPDATE that returned
        // a row, not a DELETE that returned an acknowledgement.
        assertEquals(stamp, stamped.deletedAt)

        // The row's body is unchanged — only deletedAt was patched
        // (and the bound timestamp round-trips through the driver
        // unchanged, not e.g. replaced with NOW() at the SQL layer).
        val reread = unfiltered.memos.findById(memo.id).getOrThrow()
        assertNotNull(reread)
        assertEquals("tick", reread.body)
        assertEquals(stamp, reread.deletedAt)
    }
}
