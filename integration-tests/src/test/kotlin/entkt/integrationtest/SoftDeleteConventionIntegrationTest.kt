package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Memo
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.ExcludeDeleted
import entkt.runtime.PrivacyContext
import entkt.runtime.Viewer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
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
 *  - the `entkt.runtime.ExcludeDeleted` interceptor (filters
 *    rows with non-null `deleted_at` out of default read paths)
 *
 * Neither artifact requires soft-delete-specific codegen.
 * Generated `delete*` APIs keep their existing physical-delete
 * meaning; applications soft-delete by writing the timestamp
 * through the ordinary `update { deletedAt = … }` API.
 *
 * Backed by [Memo], a minimal schema that includes `DeletedAt`.
 */
class SoftDeleteConventionIntegrationTest : PostgresTestBase() {

    /** Client with `ExcludeDeleted` installed — hides soft-deleted rows by default. */
    private fun filteredClient(driver: PostgresDriver): EntClient =
        EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
            interceptors {
                memos(ExcludeDeleted(), name = "soft-delete")
            }
        }

    /** Client without the interceptor — sees every row, deleted or not. */
    private fun unfilteredClient(driver: PostgresDriver): EntClient =
        EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.System) }
        }

    // ---- Read-filter behavior ----

    @Test
    fun `filtered client excludes soft-deleted rows from allOrThrow`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val alive = filtered.memos.create { body = "alive" }.save()
        val gone = filtered.memos.create { body = "gone" }.save()

        // Soft-delete `gone` via the ordinary update API.
        filtered.memos.update(gone.id) { deletedAt = Instant.now() }.save()

        // Filtered client sees only the live row.
        val visible = filtered.memos.query().allOrThrow()
        assertEquals(listOf("alive"), visible.map { it.body })
        assertEquals(listOf(alive.id), visible.map { it.id })

        // Unfiltered client sees both rows; the soft-deleted one
        // carries the stamped timestamp.
        val all = unfiltered.memos.query().allOrThrow().sortedBy { it.id }
        assertEquals(listOf("alive", "gone"), all.map { it.body })
        assertNull(all[0].deletedAt, "live row's deletedAt must remain null")
        assertNotNull(all[1].deletedAt, "soft-deleted row's deletedAt must be stamped")
    }

    @Test
    fun `filtered client byIdOrNull returns null for a soft-deleted row`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val memo = filtered.memos.create { body = "x" }.save()
        filtered.memos.update(memo.id) { deletedAt = Instant.now() }.save()

        // Filtered client: invisible via byIdOrNull.
        assertNull(filtered.memos.byIdOrNull(memo.id))

        // Unfiltered client: still reachable.
        val reread = unfiltered.memos.byIdOrNull(memo.id)
        assertNotNull(reread)
        assertEquals(memo.id, reread.id)
        assertNotNull(reread.deletedAt)
    }

    @Test
    fun `filtered client rawCount and rawExists ignore soft-deleted rows`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        filtered.memos.create { body = "live-a" }.save()
        val gone = filtered.memos.create { body = "live-b" }.save()
        filtered.memos.update(gone.id) { deletedAt = Instant.now() }.save()

        // Filtered: 1 visible row.
        assertEquals(1L, filtered.memos.query().rawCount())
        assertTrue(filtered.memos.query().rawExists())

        // Unfiltered: 2 rows in storage.
        assertEquals(2L, unfiltered.memos.query().rawCount())
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

        val memo = filtered.memos.create { body = "phoenix" }.save()
        filtered.memos.update(memo.id) { deletedAt = Instant.now() }.save()

        // Sanity: row is invisible via filtered query path.
        assertNull(filtered.memos.byIdOrNull(memo.id))

        // Restore via the unfiltered client (the recommended pattern).
        val restored = unfiltered.memos.update(memo.id) { deletedAt = null }.save()
        assertNotNull(restored)
        assertNull(restored.deletedAt)

        // And the row reappears through the filtered client.
        val reread = filtered.memos.byIdOrNull(memo.id)
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

        val memo = filtered.memos.create { body = "fragile" }.save()
        filtered.memos.update(memo.id) { deletedAt = Instant.now() }.save()

        val restored = filtered.memos.update(memo.id) { deletedAt = null }.save()
        assertNotNull(restored, "filtered restore happens to work today via the unfiltered owner-row load")
        assertNull(restored.deletedAt)

        // Visible through both clients again.
        assertNotNull(filtered.memos.byIdOrNull(memo.id))
        assertNotNull(unfiltered.memos.byIdOrNull(memo.id))
    }

    // ---- Generated delete* keeps its physical-delete meaning ----

    @Test
    fun `deleteOrThrow physically removes the row — not a soft delete`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val memo = filtered.memos.create { body = "for-real" }.save()

        filtered.memos.deleteOrThrow(memo)

        // Both clients see zero rows — physical removal, not a
        // soft delete masquerading.
        assertEquals(0L, filtered.memos.query().rawCount())
        assertEquals(0L, unfiltered.memos.query().rawCount())
        assertNull(unfiltered.memos.byIdOrNull(memo.id))
    }

    @Test
    fun `deleteByIdOrError physically removes the row`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val memo = filtered.memos.create { body = "rm" }.save()

        val result = filtered.memos.deleteByIdOrError(memo.id)
        assertTrue(result is entkt.runtime.EntResult.Ok)
        assertTrue(result.value)

        assertEquals(0L, unfiltered.memos.query().rawCount())
    }

    @Test
    fun `deleteMany on the filtered client only purges visible candidates`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val live = filtered.memos.create { body = "live" }.save()
        val gone = filtered.memos.create { body = "gone" }.save()
        filtered.memos.update(gone.id) { deletedAt = Instant.now() }.save()

        // deleteMany's candidate fetch routes through read
        // interceptors today (DELETE_CANDIDATES) — so the filtered
        // client's ExcludeDeleted narrows the candidate set to
        // visible rows only. The already-soft-deleted row is left
        // alone.
        val purged = filtered.memos.deleteMany()
        assertEquals(1, purged, "only the live row should be physically removed")

        // The soft-deleted row survives, still soft-deleted.
        val remaining = unfiltered.memos.query().allOrThrow()
        assertEquals(1, remaining.size)
        assertEquals("gone", remaining.single().body)
        assertNotNull(remaining.single().deletedAt)

        // The live row is gone from storage.
        assertNull(unfiltered.memos.byIdOrNull(live.id))
    }

    // ---- Convention-level invariants ----

    @Test
    fun `soft-delete persists the timestamp — UPDATE pipeline, not DELETE pipeline`() {
        val driver = resetAndDriver()
        val filtered = filteredClient(driver)
        val unfiltered = unfilteredClient(driver)

        val stamp = Instant.parse("2024-01-15T12:00:00Z")
        val memo = filtered.memos.create { body = "tick" }.save()
        val stamped = filtered.memos.update(memo.id) {
            deletedAt = stamp
        }.save()

        // The update returned the post-write entity, including the
        // new deletedAt — proving this was an UPDATE that returned
        // a row, not a DELETE that returned a boolean.
        assertNotNull(stamped)
        assertEquals(stamp, stamped.deletedAt)

        // The row's body is unchanged — only deletedAt was patched
        // (and the bound timestamp round-trips through the driver
        // unchanged, not e.g. replaced with NOW() at the SQL layer).
        val reread = unfiltered.memos.byIdOrNull(memo.id)
        assertNotNull(reread)
        assertEquals("tick", reread.body)
        assertEquals(stamp, reread.deletedAt)
    }
}
