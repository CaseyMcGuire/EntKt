package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.runtime.InMemoryDriver
import entkt.runtime.TransactionRequiredException
import entkt.runtime.TransactionRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * End-to-end coverage for [TransactionRequirement] enforcement (RFC #4
 * Phase 2). Pins that the generated-save preflight throws
 * [TransactionRequiredException] *before* hooks, privacy, validation,
 * or driver writes when the configured requirement isn't satisfied —
 * and conversely that the same operation succeeds inside
 * `withTransaction { }`.
 *
 * `Article` gives us a single-write create/update/delete shape.
 * `RequiredForMultiWrite` only fires for multi-write saves
 * (link-table M2M helpers etc., once they land in RFC #5), so the
 * single-write shapes here treat it the same as `Optional`.
 */
class TransactionRequirementIntegrationTest {

    private fun freshDriver(): InMemoryDriver = InMemoryDriver().also {
        // Repos auto-register on construction.
    }

    @Test
    fun `Optional permits writes outside a transaction`() {
        val driver = freshDriver()
        val client = EntClient(driver) {
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
        val client = EntClient(driver) {
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
        val client = EntClient(driver) {
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
        val client = EntClient(driver) {
            transactionRequirement = TransactionRequirement.Optional
        }
        // Seed inside Optional so the assertion below targets the
        // requirement check, not the seed.
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        // Re-create the client with the strict requirement and try to update.
        val strict = EntClient(driver) {
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
        val client = EntClient(driver) {
            transactionRequirement = TransactionRequirement.Optional
        }
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        val strict = EntClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        val ex = assertFailsWith<TransactionRequiredException> {
            strict.users.deleteById(user.id)
        }
        assertEquals(true, ex.message!!.contains("RequiredForAllWrites"))
        assertEquals(true, ex.message!!.contains("delete"))
    }

    @Test
    fun `RequiredForMultiWrite accepts single-write create outside a transaction`() {
        // Create is a single-write save shape, so RequiredForMultiWrite
        // doesn't apply to it. Once link-table M2M helpers land (RFC #5)
        // those multi-write paths will trigger the rejection.
        val driver = freshDriver()
        val client = EntClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForMultiWrite
        }
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()
        assertNotNull(user)
    }

    @Test
    fun `transaction requirement propagates to the transactional client`() {
        // The transactional sub-client must inherit the configured
        // requirement so nested saves still see it (and the inTransaction
        // gate stops rejecting because tx.driver.inTransaction is true).
        val driver = freshDriver()
        val client = EntClient(driver) {
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
        // Per RFC #4: "syntactically empty update classification — report
        // NoChanges before any other observable work, including
        // transaction requirement checks." This pins that ordering.
        val driver = freshDriver()
        val client = EntClient(driver) {
            transactionRequirement = TransactionRequirement.Optional
        }
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()

        val strict = EntClient(driver) {
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
