package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.TransactionRequiredException
import entkt.runtime.TransactionRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * End-to-end coverage for [TransactionRequirement] enforcement. Pins that the generated-save preflight throws
 * [TransactionRequiredException] *before* hooks, privacy, validation,
 * or driver writes when the configured requirement isn't satisfied —
 * and conversely that the same operation succeeds inside
 * `withTransaction { }`.
 *
 * `Article` gives us a single-write create/update/delete shape.
 * `RequiredForMultiWrite` only fires for multi-write saves
 * such as link-table M2M updates, so the
 * single-write shapes here treat it the same as `Optional`.
 */
class TransactionRequirementIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    @Test
    fun `Optional permits writes outside a transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver) {
            // default — explicit for clarity
            transactionRequirement = TransactionRequirement.Optional
        }
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()
        assertNotNull(user)
    }

    @Test
    fun `RequiredForAllWrites rejects create outside a transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        val ex = assertFailsWith<TransactionRequiredException> {
            client.users.create {
                name = "Alice"
                email = "alice@example.com"
            }.save()
        }
        assertEquals(true, ex.message!!.contains("RequiredForAllWrites"))
        assertEquals(true, ex.message!!.contains("create"))
    }

    @Test
    fun `RequiredForAllWrites accepts create inside a transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        client.withTransaction { tx ->
            val user = tx.users.create {
                name = "Alice"
                email = "alice@example.com"
            }.save()
            assertNotNull(user)
        }
    }

    @Test
    fun `RequiredForAllWrites rejects update outside a transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.Optional
        }
        // Seed inside Optional so the assertion below targets the
        // requirement check, not the seed.
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        // Re-create the client with the strict requirement and try to update.
        val strict = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        val ex = assertFailsWith<TransactionRequiredException> {
            strict.users.update(user.id) {
                name = "Renamed"
            }.save()
        }
        assertEquals(true, ex.message!!.contains("RequiredForAllWrites"))
        assertEquals(true, ex.message!!.contains("update"))
    }

    @Test
    fun `RequiredForAllWrites rejects delete outside a transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.Optional
        }
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        val strict = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        // deleteByIdOrError catches the TransactionRequiredException
        // through its `catch (Exception)` arm — `classifyDriverError`
        // recognizes it as a configuration error and re-throws,
        // so the exception still escapes intact.
        val ex = assertFailsWith<TransactionRequiredException> {
            strict.users.deleteByIdOrError(user.id)
        }
        assertEquals(true, ex.message!!.contains("RequiredForAllWrites"))
        assertEquals(true, ex.message!!.contains("delete"))
    }

    @Test
    fun `RequiredForAllWrites rejects deleteByIdOrError for a missing id (preflight runs before the byId read)`() {
        // Without the preflight, a missing-id deleteById would silently
        // return false outside a transaction because the byId read
        // returns null before the per-entity delete fires its own
        // transaction-requirement check. The preflight must fire first
        // so the strict requirement still surfaces.
        val driver = freshDriver()
        val strict = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        val ex = assertFailsWith<TransactionRequiredException> {
            strict.users.deleteByIdOrError(9999L)
        }
        assertEquals(true, ex.message!!.contains("RequiredForAllWrites"))
        assertEquals(true, ex.message!!.contains("delete"))
    }

    @Test
    fun `RequiredForAllWrites rejects deleteMany for an empty match (preflight runs before the candidate query)`() {
        // Without the preflight, deleteMany over a predicate matching
        // no rows would silently return 0 outside a transaction —
        // the candidate query reads no rows, so no per-entity delete
        // is dispatched and the per-entity preflight never runs.
        // deleteMany must check the requirement before the query.
        val driver = freshDriver()
        val strict = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        val ex = assertFailsWith<TransactionRequiredException> {
            // Predicate that can't match anything — even without seeded data,
            // the preflight rejects before the empty query result returns.
            strict.users.deleteMany(entkt.integrationtest.ent.User.email eq "nobody@example.com")
        }
        assertEquals(true, ex.message!!.contains("RequiredForAllWrites"))
        assertEquals(true, ex.message!!.contains("delete"))
    }

    @Test
    fun `RequiredForAllWrites rejects deleteMany before reading any candidate rows`() {
        // Even with matching rows, the deleteMany preflight must fire
        // before the candidate query — otherwise we'd burn a query
        // round-trip before the strict requirement surfaces. Seed a
        // matching row so the candidate query *would* return data,
        // then assert the call still throws without partial work.
        val driver = freshDriver()
        val seed = sysClient(driver) {
            transactionRequirement = TransactionRequirement.Optional
        }
        seed.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        val strict = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        val ex = assertFailsWith<TransactionRequiredException> {
            strict.users.deleteMany(entkt.integrationtest.ent.User.email eq "alice@example.com")
        }
        assertEquals(true, ex.message!!.contains("RequiredForAllWrites"))
        // Row should still exist — the preflight rejected before any delete fired.
        assertEquals(1L, driver.count("users", emptyList()))
    }

    @Test
    fun `RequiredForMultiWrite accepts single-write create outside a transaction`() {
        // Create is a single-write save shape, so RequiredForMultiWrite
        // doesn't apply to it. Link-table M2M update paths trigger
        // the rejection because they perform multiple writes.
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForMultiWrite
        }
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()
        assertNotNull(user)
    }

    @Test
    fun `zero-block createManyOrError short-circuits to Ok(emptyList()) outside a tx`() {
        // createManyOrError is harder than the regular
        // TransactionRequirement: it ALWAYS requires a tx (otherwise
        // its all-or-nothing contract doesn't hold). The empty-blocks
        // short-circuit fires before the tx check, mirroring the
        // "classify syntactically empty before any other observable
        // work" rule from transaction locking.
        val driver = freshDriver()
        val client = sysClient(driver)
        val result = client.users.createManyOrError()
        assertEquals(entkt.runtime.EntResult.Ok<List<entkt.integrationtest.ent.User>>(emptyList()), result)
    }

    @Test
    fun `createManyOrError throws TransactionRequiredException outside a tx (even with one block)`() {
        val driver = freshDriver()
        val client = sysClient(driver)
        val ex = assertFailsWith<TransactionRequiredException> {
            client.users.createManyOrError({
                name = "Alice"
                email = "alice@example.com"
            })
        }
        assertEquals(true, ex.message!!.contains("createManyOrError"))
        // No row was inserted — the preflight rejected before any
        // per-block create() ran.
        assertEquals(0L, driver.count("users", emptyList()))
    }

    @Test
    fun `createManyOrError accepts multi-block calls inside a transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver)
        val result = client.withTransaction { tx ->
            tx.users.createManyOrError(
                {
                    name = "Alice"
                    email = "alice-${java.util.UUID.randomUUID()}@example.com"
                },
                {
                    name = "Bob"
                    email = "bob-${java.util.UUID.randomUUID()}@example.com"
                },
            )
        }
        require(result is entkt.runtime.EntResult.Ok)
        assertEquals(2, result.value.size)
    }

    @Test
    fun `RequiredForMultiWrite rejects deleteMany outside a transaction (classify by operation shape, not result size)`() {
        // deleteMany is a multi-write API regardless of how many rows
        // actually match — classifying by operation shape mirrors the
        // classify-before-normalization rule. Without this,
        // a deleteMany over a predicate that matches 0 rows would
        // silently return 0 outside a tx; matching N rows would loop
        // through N delegated single-write deletes and never trigger
        // the multi-write requirement.
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForMultiWrite
        }
        val ex = assertFailsWith<TransactionRequiredException> {
            client.users.deleteMany(entkt.integrationtest.ent.User.email eq "nobody@example.com")
        }
        assertEquals(true, ex.message!!.contains("RequiredForMultiWrite"))
        assertEquals(true, ex.message!!.contains("deleteMany"))
    }

    @Test
    fun `RequiredForMultiWrite accepts deleteMany inside a transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForMultiWrite
        }
        val deleted = client.withTransaction { tx ->
            tx.users.deleteMany(entkt.integrationtest.ent.User.email eq "nobody@example.com")
        }
        assertEquals(0, deleted)
    }

    @Test
    fun `transaction requirement propagates to the transactional client`() {
        // The transactional sub-client must inherit the configured
        // requirement so nested saves still see it (and the inTransaction
        // gate stops rejecting because tx.driver.inTransaction is true).
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        client.withTransaction { tx ->
            // Should succeed — tx is in-transaction.
            val user = tx.users.create {
                name = "Alice"
                email = "alice@example.com"
            }.save()
            assertNotNull(user)
        }
    }

    @Test
    fun `update with empty patch reports NoChanges before the transaction-requirement preflight`() {
        // Per transaction locking: "syntactically empty update classification — report
        // NoChanges before any other observable work, including
        // transaction requirement checks." This pins that ordering.
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.Optional
        }
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        val strict = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        // Empty patch + strict requirement: NoChanges fires first
        // (EntNoChangesException), TransactionRequiredException would
        // have fired second.
        assertFailsWith<entkt.runtime.EntNoChangesException> {
            strict.users.update(user.id) { /* no changes */ }.save()
        }
    }
}
