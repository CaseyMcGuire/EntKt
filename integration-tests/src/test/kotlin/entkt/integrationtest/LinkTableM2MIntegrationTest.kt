package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Post
import entkt.integrationtest.ent.PostUpdate
import entkt.integrationtest.ent.PostUpdateHookContext
import entkt.integrationtest.ent.Tag
import entkt.integrationtest.support.LockSupportInMemoryDriver
import entkt.runtime.Driver
import entkt.runtime.InMemoryDriver
import entkt.runtime.TransactionRequiredException
import entkt.runtime.UnsupportedDriverCapabilityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for RFC #5 link-table M2M helpers. The bare
 * [InMemoryDriver] reports both lock capabilities as `false`, so any
 * helper-eligible link-table M2M update against it triggers the
 * `UnsupportedDriverCapabilityException` preflight (RFC #5 Phase 4).
 * Happy-path tests wrap it in [LockSupportInMemoryDriver], which
 * advertises both capabilities and forwards lock calls to `byId`
 * (no real lock semantics — those belong on Postgres).
 *
 * Junction-table reads (`SELECT * FROM post_tags WHERE post_id = ?`)
 * use the bare driver since they're plain queries with no locking.
 */
class LinkTableM2MIntegrationTest {

    private fun lockingClient(): Pair<EntClient, InMemoryDriver> {
        val real = InMemoryDriver()
        val locking = LockSupportInMemoryDriver(real)
        return EntClient(locking) to real
    }

    private fun seedPostAndTags(client: EntClient): Triple<Post, Tag, Tag> {
        val post = client.posts.create { title = "Hello" }.save()
        val tagA = client.tags.create { name = "a" }.save()
        val tagB = client.tags.create { name = "b" }.save()
        return Triple(post, tagA, tagB)
    }

    /**
     * Read the junction rows for a given post id via a raw driver
     * query, returning the linked tag ids as a sorted list. Lets tests
     * assert against junction state without depending on M2M eager
     * loading (which is forward query traversal — a separate code path).
     */
    private fun linkedTagIds(driver: InMemoryDriver, postId: Long): List<Long> {
        return driver.query(
            "post_tags",
            listOf(entkt.query.Predicate.Leaf("post_id", entkt.query.Op.EQ, postId)),
            emptyList(),
            null,
            null,
        ).map { it["tag_id"] as Long }.sorted()
    }

    // ---------- Transaction + capability preflight ----------

    @Test
    fun `M2M update outside a transaction throws TransactionRequiredException`() {
        // Bare InMemoryDriver doesn't claim row-lock support, but the
        // preflight checks `inTransaction` first, so the missing-tx
        // error surfaces before the capability error.
        val client = EntClient(InMemoryDriver())
        val (post, tagA, _) = seedPostAndTags(client)

        val ex = assertFailsWith<TransactionRequiredException> {
            client.posts.update(post.id) {
                tags.add(tagA.id)
            }.save()
        }
        assertTrue(ex.message!!.contains("link-table M2M update requires a transaction"))
    }

    @Test
    fun `M2M update with a driver that supports neither capability throws UnsupportedDriverCapabilityException`() {
        // Bare InMemoryDriver inside a transaction satisfies inTransaction
        // but reports both capability flags as false → second preflight
        // throws.
        val client = EntClient(InMemoryDriver())
        val (post, tagA, _) = seedPostAndTags(client)

        val ex = client.withTransaction { tx ->
            assertFailsWith<UnsupportedDriverCapabilityException> {
                tx.posts.update(post.id) {
                    tags.add(tagA.id)
                }.save()
            }
        }
        assertTrue(ex.message!!.contains("supportsReadRowForUpdate"))
        assertTrue(ex.message!!.contains("supportsOwnerEdgeSerialization"))
    }

    @Test
    fun `non-M2M update on a bare driver outside a transaction still succeeds`() {
        // Regression guard: the M2M preflight only fires when
        // _hasPendingLinkTableM2MOps() is true. A pure scalar update
        // on a Post (or any other entity) keeps the existing
        // ReadCurrent path with no transaction required.
        val client = EntClient(InMemoryDriver())
        val (post, _, _) = seedPostAndTags(client)

        val updated = client.posts.update(post.id) {
            title = "Renamed"
        }.save()

        assertNotNull(updated)
        assertEquals("Renamed", updated.title)
    }

    // ---------- Happy-path add / remove / set ----------

    @Test
    fun `add inserts one junction row`() {
        val (client, real) = lockingClient()
        val (post, tagA, _) = seedPostAndTags(client)

        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(tagA.id)
            }.save()
        }

        assertEquals(listOf(tagA.id), linkedTagIds(real, post.id))
    }

    @Test
    fun `add then remove of the same id throws IllegalStateException at the second call site`() {
        // RFC #5 §Generated Builder Shape: same-id mixed-direction
        // (add(x); remove(x) or the reverse) is rejected at the
        // builder call site so requestedAdds and requestedRemoves stay
        // disjoint by construction.
        val (client, real) = lockingClient()
        val (post, tagA, _) = seedPostAndTags(client)

        val ex = client.withTransaction { tx ->
            assertFailsWith<IllegalStateException> {
                tx.posts.update(post.id) {
                    tags.add(tagA.id)
                    tags.remove(tagA.id) // throws here
                }.save()
            }
        }
        assertTrue(ex.message!!.contains("edge 'tags'"))
        assertTrue(ex.message!!.contains("cannot remove(id) after add(id)"))
        // No junction rows were written.
        assertEquals(emptyList(), linkedTagIds(real, post.id))
    }

    @Test
    fun `remove then add of the same id throws IllegalStateException at the second call site`() {
        // The reverse ordering is symmetric.
        val (client, real) = lockingClient()
        val (post, tagA, _) = seedPostAndTags(client)

        val ex = client.withTransaction { tx ->
            assertFailsWith<IllegalStateException> {
                tx.posts.update(post.id) {
                    tags.remove(tagA.id)
                    tags.add(tagA.id) // throws here
                }.save()
            }
        }
        assertTrue(ex.message!!.contains("edge 'tags'"))
        assertTrue(ex.message!!.contains("cannot add(id) after remove(id)"))
        assertEquals(emptyList(), linkedTagIds(real, post.id))
    }

    @Test
    fun `add of a nonexistent target id is rejected at the junction insert — no dangling row`() {
        // PostgresDriver gets this for free from the FK constraint
        // declared by RFC #3's junction-shape rule 4. InMemoryDriver
        // previously skipped FK existence validation on insert, so
        // dangling junction rows could be silently created — masking
        // the bug in InMemoryDriver-backed tests. The new in-memory
        // FK validation now rejects the dangling insert with a
        // structured exception.
        val (client, real) = lockingClient()
        val (post, _, _) = seedPostAndTags(client)
        val nonexistentTagId = 999_999L

        val ex = client.withTransaction { tx ->
            assertFailsWith<IllegalStateException> {
                tx.posts.update(post.id) {
                    tags.add(nonexistentTagId)
                }.save()
            }
        }
        // Message names the table, column, value, and the referenced
        // (table, column) pair so the failure points at the bad input.
        val msg = ex.message!!
        assertTrue(
            msg.contains("post_tags.tag_id"),
            "FK violation message should name the violating table.column; got: $msg",
        )
        assertTrue(
            msg.contains("$nonexistentTagId"),
            "FK violation message should name the bad value; got: $msg",
        )
        assertTrue(
            msg.contains("tags.id"),
            "FK violation message should name the referenced table.column; got: $msg",
        )
        // No dangling junction row was persisted.
        assertEquals(emptyList(), linkedTagIds(real, post.id))
    }

    @Test
    fun `remove of an unlinked id is a database no-op`() {
        val (client, real) = lockingClient()
        val (post, tagA, _) = seedPostAndTags(client)

        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.remove(tagA.id)
            }.save()
        }

        assertEquals(emptyList(), linkedTagIds(real, post.id))
    }

    @Test
    fun `set replaces the link set — adds new ids and removes missing ones`() {
        val (client, real) = lockingClient()
        val (post, tagA, tagB) = seedPostAndTags(client)
        val tagC = client.tags.create { name = "c" }.save()

        // Seed: post linked to [a, b].
        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(tagA.id)
                tags.add(tagB.id)
            }.save()
        }
        assertEquals(listOf(tagA.id, tagB.id).sorted(), linkedTagIds(real, post.id))

        // Replace with [a, c] → adds c, removes b, keeps a.
        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.set(listOf(tagA.id, tagC.id))
            }.save()
        }
        assertEquals(listOf(tagA.id, tagC.id).sorted(), linkedTagIds(real, post.id))
    }

    @Test
    fun `set takes a defensive copy — mutating the input list after the call does not change the saved set`() {
        // Without the defensive copy, the mutator would alias the
        // caller's MutableList. Mutating the list between tags.set(...)
        // and save() would then change the persisted relationship,
        // because _buildPendingEdgeOps's `toSet()` only fires at save
        // time and would see the post-mutation contents.
        val (client, real) = lockingClient()
        val (post, tagA, tagB) = seedPostAndTags(client)
        val tagC = client.tags.create { name = "c" }.save()

        val ids = mutableListOf(tagA.id)
        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.set(ids)
                // Mutate the caller's list AFTER the set() call. With the
                // defensive copy, _requestedSet holds an independent
                // snapshot containing only tagA; without it, the save
                // would persist [tagA, tagB, tagC].
                ids.add(tagB.id)
                ids.add(tagC.id)
            }.save()
        }

        assertEquals(listOf(tagA.id), linkedTagIds(real, post.id))
    }

    @Test
    fun `set with duplicate ids dedupes before writing`() {
        val (client, real) = lockingClient()
        val (post, tagA, _) = seedPostAndTags(client)

        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.set(listOf(tagA.id, tagA.id, tagA.id))
            }.save()
        }

        assertEquals(listOf(tagA.id), linkedTagIds(real, post.id))
    }

    // ---------- Mixed-mode rejection ----------

    @Test
    fun `mixing set and add on the same edge throws IllegalStateException at the call site`() {
        val (client, _) = lockingClient()
        val (post, tagA, tagB) = seedPostAndTags(client)

        val ex = client.withTransaction { tx ->
            assertFailsWith<IllegalStateException> {
                tx.posts.update(post.id) {
                    tags.set(listOf(tagA.id))
                    tags.add(tagB.id) // fails fast at this call site
                }.save()
            }
        }
        assertTrue(ex.message!!.contains("edge 'tags'"))
        assertTrue(ex.message!!.contains("cannot mix replacement"))
    }

    @Test
    fun `mixing remove and set on the same edge throws IllegalStateException at the set call site`() {
        val (client, _) = lockingClient()
        val (post, tagA, _) = seedPostAndTags(client)

        client.withTransaction { tx ->
            assertFailsWith<IllegalStateException> {
                tx.posts.update(post.id) {
                    tags.remove(tagA.id)
                    tags.set(listOf(tagA.id))
                }.save()
            }
        }
    }

    // ---------- pendingEdges visible to before hooks ----------

    private fun lockingClientWithHook(
        beforeUpdate: (PostUpdateHookContext) -> Unit,
    ): Pair<EntClient, InMemoryDriver> {
        val real = InMemoryDriver()
        val locking = LockSupportInMemoryDriver(real)
        val client = EntClient(locking) {
            hooks {
                posts {
                    beforeUpdate(beforeUpdate)
                }
            }
        }
        return client to real
    }

    @Test
    fun `beforeSave hook on update cannot cast to PostUpdateMutationView to reach unsetX or pendingEdges`() {
        // Residual cast-attack hole: even with `mutation as PostUpdate`
        // blocked, a hook could previously cast to
        // PostUpdateMutationView and reach unsetTitle() (silently drop
        // a caller's patch entry) or pendingEdges (info leak). The
        // _beforeSaveView adapter implements ONLY PostMutation, so
        // this cast now fails at runtime.
        val real = InMemoryDriver()
        val locking = LockSupportInMemoryDriver(real)
        val client = EntClient(locking) {
            hooks {
                posts {
                    beforeSave { mutation ->
                        val klass = mutation::class.java.name
                        if (klass.contains("PostUpdate") || klass.contains("anonymous")) {
                            @Suppress("UNUSED_VARIABLE")
                            val view = mutation as entkt.integrationtest.ent.PostUpdateMutationView
                        }
                    }
                }
            }
        }
        val (post, _, _) = seedPostAndTags(client)

        val ex = client.withTransaction { tx ->
            assertFailsWith<ClassCastException> {
                tx.posts.update(post.id) {
                    title = "Renamed"
                }.save()
            }
        }
        assertTrue(
            ex.message!!.contains("PostUpdateMutationView") || ex.message!!.contains("cannot be cast"),
            "Expected ClassCastException about PostUpdateMutationView; got: ${ex.message}",
        )
    }

    @Test
    fun `beforeSave hook on update cannot cast to PostUpdate to reach the tags mutator`() {
        // Cast-attack mitigation: the beforeSave hook receives the
        // restricted PostUpdateMutationView adapter, not the concrete
        // PostUpdate. A hook attempting to cast to PostUpdate to reach
        // the public tags mutator throws ClassCastException at the
        // cast site, surfacing the misuse before any snapshot/live
        // divergence can occur.
        //
        // The hook only attempts the cast on the update path because
        // beforeSave is shared between create and update; on create
        // the runtime object is PostCreate, where the cast would also
        // fail but for a different (and uninteresting) reason.
        val real = InMemoryDriver()
        val locking = LockSupportInMemoryDriver(real)
        val client = EntClient(locking) {
            hooks {
                posts {
                    beforeSave { mutation ->
                        val klass = mutation::class.java.name
                        if (klass.contains("PostUpdate")) {
                            @Suppress("UNUSED_VARIABLE")
                            val pu = mutation as entkt.integrationtest.ent.PostUpdate
                        }
                    }
                }
            }
        }
        val (post, tagA, _) = seedPostAndTags(client)

        val ex = client.withTransaction { tx ->
            assertFailsWith<ClassCastException> {
                tx.posts.update(post.id) {
                    tags.add(tagA.id)
                }.save()
            }
        }
        // The cast site, not the mutator call, is the source.
        assertTrue(
            ex.message!!.contains("PostUpdate") || ex.message!!.contains("cannot be cast"),
            "Expected ClassCastException about PostUpdate; got: ${ex.message}",
        )
        // The cast attack also can't smuggle in writes: the runtime
        // object is the restricted adapter, so the only way to mutate
        // the M2M op log is through the public DSL block — already
        // covered by other tests.
    }

    @Test
    fun `before hooks see the captured pendingEdges snapshot`() {
        val observed = mutableListOf<Set<Long>>()
        val (client, _) = lockingClientWithHook { ctx ->
            observed.add(ctx.pendingEdges.tags.requestedAdds)
        }
        val (post, tagA, tagB) = seedPostAndTags(client)

        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(tagA.id)
                tags.add(tagB.id)
            }.save()
        }

        assertEquals(1, observed.size)
        assertEquals(setOf(tagA.id, tagB.id), observed.first())
    }

    @Test
    fun `escaped ctx_mutation cannot read pendingEdges after save returns`() {
        // Without the try/finally clearing _capturedPendingEdges, a
        // hook stashing ctx.mutation could read pendingEdges after
        // save() returned — contradicting the getter's own contract
        // ("accessed outside a save() — the captured snapshot is only
        // populated during save() between the owner-row read and the
        // afterUpdate hook block"). The Phase 8 follow-up wraps the
        // save body in try/finally and clears the captured field on
        // every exit, so post-save reads now throw consistently.
        var stashed: entkt.integrationtest.ent.PostUpdateMutationView? = null
        val (client, _) = lockingClientWithHook { ctx ->
            stashed = ctx.mutation
        }
        val (post, tagA, _) = seedPostAndTags(client)

        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(tagA.id)
            }.save()
        }

        val view = assertNotNull(stashed)
        val ex = assertFailsWith<IllegalStateException> {
            view.pendingEdges
        }
        assertTrue(
            ex.message!!.contains("outside a save"),
            "Post-save read should error with the documented contract message; got: ${ex.message}",
        )
    }

    @Test
    fun `escaped ctx_mutation also throws after a save that returns null`() {
        // The finally must clear on the missing-row return-null path
        // too, not just on normal completion. Use a fresh Update
        // builder (different id) so the missing-row branch runs.
        var stashed: entkt.integrationtest.ent.PostUpdateMutationView? = null
        val (client, _) = lockingClientWithHook { ctx ->
            stashed = ctx.mutation
        }
        val (post, tagA, _) = seedPostAndTags(client)

        // Issue an update against a missing id so save() returns null
        // from the owner-row read short-circuit. NOTE: that
        // short-circuit happens BEFORE the try-block (the
        // _capturedPendingEdges assignment is the first statement
        // inside try), so the field was never populated for that
        // call — the existing `?: error(...)` getter handles it.
        // For coverage of the finally-clearing path specifically,
        // run a normal save first to populate the field, stash the
        // mutation, then run a second save that returns null. The
        // finally on the first save clears the field; the second
        // save's missing-row return doesn't reach the try-block
        // assignment so nothing further is needed.
        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(tagA.id)
            }.save()
        }
        client.withTransaction { tx ->
            tx.posts.update(id = 9_999_999L) {
                tags.add(tagA.id)
            }.save()
        }

        val view = assertNotNull(stashed)
        val ex = assertFailsWith<IllegalStateException> {
            view.pendingEdges
        }
        assertTrue(ex.message!!.contains("outside a save"))
    }

    @Test
    fun `ctx_pendingEdges and ctx_mutation_pendingEdges are the same object instance`() {
        // Phase 8 (P3): both surfaces route through the captured
        // _capturedPendingEdges field, so they're object-identity
        // equal. Pre-Phase-8 the adapter rebuilt on every read; the
        // values were structurally equal but `===` was false.
        var observed: Pair<entkt.runtime.PendingEdgeOps<Long>, entkt.runtime.PendingEdgeOps<Long>>? = null
        val (client, _) = lockingClientWithHook { ctx ->
            observed = ctx.pendingEdges.tags to ctx.mutation.pendingEdges.tags
        }
        val (post, tagA, _) = seedPostAndTags(client)

        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(tagA.id)
            }.save()
        }

        val (fromCtx, fromMutationView) = assertNotNull(observed)
        // Object identity, not just structural equality.
        kotlin.test.assertSame(
            fromCtx,
            fromMutationView,
            "ctx.pendingEdges.tags and ctx.mutation.pendingEdges.tags must be the same instance",
        )
    }

    @Test
    fun `pendingEdges requestedAdds and requestedRemoves stay disjoint — paired calls cannot reach pending state`() {
        // The mutator's call-site rejection means hooks never see a
        // PendingEdgeOps with the same id in both intent sets. This
        // test pins the disjoint-by-construction invariant by sending
        // distinct add and remove ids; for the same-id case see
        // `add then remove of the same id throws` above.
        val observed = mutableListOf<Pair<Set<Long>, Set<Long>>>()
        val (client, _) = lockingClientWithHook { ctx ->
            observed.add(ctx.pendingEdges.tags.requestedAdds to ctx.pendingEdges.tags.requestedRemoves)
        }
        val (post, tagA, tagB) = seedPostAndTags(client)

        client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(tagA.id)
                tags.remove(tagB.id)
            }.save()
        }

        val (adds, removes) = observed.single()
        assertEquals(setOf(tagA.id), adds)
        assertEquals(setOf(tagB.id), removes)
        // No overlap.
        assertTrue((adds intersect removes).isEmpty())
    }

    // ---------- Edge-only updates ----------

    @Test
    fun `M2M-only update no longer throws NoChanges and persists junction writes`() {
        // Phase 6 fix: an update with no scalar changes but pending
        // M2M ops must proceed to the junction writes. Pre-Phase 6,
        // the hook-cleared empty branch fired and swallowed the M2M
        // operations.
        val (client, real) = lockingClient()
        val (post, tagA, _) = seedPostAndTags(client)

        val updated = client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(tagA.id)
            }.save()
        }

        assertNotNull(updated)
        assertEquals(post.title, updated.title) // no scalar change
        assertEquals(listOf(tagA.id), linkedTagIds(real, post.id))
    }

    @Test
    fun `edge-only update returns the loaded before row unchanged because no owner UPDATE fires`() {
        // Phase 6 fix: when `values.isEmpty()` (no scalar/FK
        // changes and no update defaults that apply), the owner
        // driver.update(...) is skipped and the loaded `before` row is
        // returned as the after-state.
        val (client, _) = lockingClient()
        val (post, tagA, _) = seedPostAndTags(client)

        val updated = client.withTransaction { tx ->
            tx.posts.update(post.id) {
                tags.add(tagA.id)
            }.save()
        }

        assertNotNull(updated)
        // The returned entity matches the pre-save Post object — same id, same title.
        assertEquals(post.id, updated.id)
        assertEquals(post.title, updated.title)
    }

    @Test
    fun `update with both scalar change and M2M ops emits both the owner UPDATE and the junction write`() {
        val (client, real) = lockingClient()
        val (post, tagA, _) = seedPostAndTags(client)

        val updated = client.withTransaction { tx ->
            tx.posts.update(post.id) {
                title = "Renamed"
                tags.add(tagA.id)
            }.save()
        }

        assertNotNull(updated)
        assertEquals("Renamed", updated.title)
        assertEquals(listOf(tagA.id), linkedTagIds(real, post.id))
    }

    @Test
    fun `update with neither scalar change nor M2M ops still throws NoChanges`() {
        // Regression guard: the M2M gate on the empty-branch condition
        // only opens when M2M ops are pending. An update(id) {} on an
        // M2M-capable schema is still a NoChanges from the syntactic
        // pre-load check.
        val (client, _) = lockingClient()
        val (post, _, _) = seedPostAndTags(client)

        client.withTransaction { tx ->
            assertFailsWith<entkt.runtime.EntNoChangesException> {
                tx.posts.update(post.id) { }.save()
            }
        }
    }

    @Test
    fun `update on a missing owner id returns null without junction reads or writes`() {
        // The owner-row read short-circuits with `?: return null` before
        // any junction work fires.
        val (client, real) = lockingClient()
        val (_, tagA, _) = seedPostAndTags(client)

        val result = client.withTransaction { tx ->
            tx.posts.update(id = 9_999_999L) {
                tags.add(tagA.id)
            }.save()
        }

        // Returned null and no junction rows were inserted.
        assertEquals(null, result)
        assertEquals(emptyList(), linkedTagIds(real, 9_999_999L))
    }
}
