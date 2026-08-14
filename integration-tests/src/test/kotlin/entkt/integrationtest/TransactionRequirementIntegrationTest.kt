package entkt.integrationtest

import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.mutation.TransactionRequiredException
import entkt.runtime.mutation.TransactionRequirement
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end coverage for [TransactionRequirement] enforcement.
 *
 * The requirement check runs inside the mutation terminal's capture
 * boundary, so an unsatisfied requirement no longer throws — it is
 * `Failed(EntUnexpectedMutationException(NotPersisted,
 * cause = TransactionRequiredException))` (deliberate reversal of the
 * old propagate contract). The check still fires before hooks,
 * privacy, validation, or any driver work.
 *
 * For `createMany` / `deleteMany` the requirement is evaluated against
 * the CALLER's transaction posture: `RequiredForMultiWrite` outside a
 * caller transaction fails even though those terminals own an internal
 * EntKt transaction — the configured requirement asks the CALLER to
 * hold the transaction.
 */
class TransactionRequirementIntegrationTest : PostgresTestBase() {

    private fun freshDriver(): PostgresDriver = resetAndDriver()

    /** Assert the canonical unsatisfied-requirement failure shape. */
    private fun assertRequirementFailure(
        result: MutationResult<*>,
        vararg messageParts: String,
    ): TransactionRequiredException {
        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)
        val cause = assertIs<TransactionRequiredException>(ex.cause)
        for (part in messageParts) {
            assertTrue(
                cause.message!!.contains(part),
                "cause message should contain '$part'; got: ${cause.message}",
            )
        }
        return cause
    }

    @Test
    fun `Optional permits writes outside a transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver) {
            // default — explicit for clarity
            transactionRequirement = TransactionRequirement.Optional
        }
        val result = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()
        assertIs<MutationResult.Success<Unit>>(result)
    }

    @Test
    fun `RequiredForAllWrites fails create outside a transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        assertRequirementFailure(
            client.users.create {
                name = "Alice"
                email = "alice@example.com"
            }.save(),
            "RequiredForAllWrites",
            "create",
        )
        // Nothing was written.
        assertEquals(0L, driver.count("users", emptyList()))
    }

    @Test
    fun `RequiredForAllWrites accepts create inside a transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        val result = client.withTransaction { tx ->
            tx.users.create {
                name = "Alice"
                email = "alice@example.com"
            }.saveAndLoad().orRollback()
        }
        assertIs<TransactionResult.Success<User>>(result)
    }

    @Test
    fun `RequiredForAllWrites fails update outside a transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.Optional
        }
        // Seed under Optional so the assertion below targets the
        // requirement check, not the seed.
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.saveAndLoad().getOrThrow()

        val strict = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        assertRequirementFailure(
            strict.users.update(user.id) { name = "Renamed" }.save(),
            "RequiredForAllWrites",
            "update",
        )
    }

    @Test
    fun `RequiredForAllWrites fails delete outside a transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.Optional
        }
        val user = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.saveAndLoad().getOrThrow()

        val strict = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        assertRequirementFailure(
            strict.users.deleteById(user.id),
            "RequiredForAllWrites",
            "delete",
        )
        // Row untouched.
        assertEquals(1L, driver.count("users", emptyList()))
    }

    @Test
    fun `RequiredForAllWrites fails deleteById for a missing id (preflight runs before the byId read)`() {
        // Without the preflight ordering, a missing-id deleteById would
        // return Success(false) outside a transaction because the byId
        // read finds no row before any per-entity check could fire.
        val driver = freshDriver()
        val strict = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        assertRequirementFailure(
            strict.users.deleteById(9999L),
            "RequiredForAllWrites",
            "delete",
        )
    }

    @Test
    fun `RequiredForAllWrites fails deleteMany for an empty match (preflight runs before the candidate query)`() {
        // Without the preflight ordering, deleteMany over a predicate
        // matching no rows would return Success(0) outside a
        // transaction — no candidate row means no per-entity check.
        val driver = freshDriver()
        val strict = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        assertRequirementFailure(
            strict.users.deleteMany(User.email eq "nobody@example.com"),
            "RequiredForAllWrites",
            "deleteMany",
        )
    }

    @Test
    fun `RequiredForAllWrites fails deleteMany before deleting any candidate rows`() {
        val driver = freshDriver()
        val seed = sysClient(driver) {
            transactionRequirement = TransactionRequirement.Optional
        }
        seed.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save().getOrThrow()

        val strict = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        assertRequirementFailure(
            strict.users.deleteMany(User.email eq "alice@example.com"),
            "RequiredForAllWrites",
        )
        // Row should still exist — the preflight rejected before any delete fired.
        assertEquals(1L, driver.count("users", emptyList()))
    }

    @Test
    fun `RequiredForMultiWrite accepts single-write create outside a transaction`() {
        // Create is a single-write save shape, so RequiredForMultiWrite
        // doesn't apply to it.
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForMultiWrite
        }
        val result = client.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.save()
        assertIs<MutationResult.Success<Unit>>(result)
    }

    // ---- Caller-posture evaluation for the bulk terminals ----

    @Test
    fun `RequiredForMultiWrite permits a one-row createMany outside a caller transaction`() {
        // multiWrite is classified by the documented "issues more than
        // one driver write" contract: a one-block batch is a single
        // write, so RequiredForMultiWrite does not apply and the batch
        // runs atomically in its EntKt-owned transaction.
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForMultiWrite
        }
        val result = client.users.createMany({
            name = "Alice"
            email = "alice@example.com"
        })
        val success = assertIs<MutationResult.Success<List<User>>>(result)
        assertEquals(1, success.value.size)
        assertEquals(1L, driver.count("users", emptyList()))
    }

    @Test
    fun `RequiredForMultiWrite fails a multi-row createMany outside a caller transaction despite the internal transaction`() {
        // Two blocks = more than one driver write. createMany owns an
        // internal EntKt transaction when the caller has none — but the
        // configured requirement is about the CALLER's posture, so it
        // still fails closed here.
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForMultiWrite
        }
        assertRequirementFailure(
            client.users.createMany(
                { name = "Alice"; email = "alice@example.com" },
                { name = "Bob"; email = "bob@example.com" },
            ),
            "RequiredForMultiWrite",
            "createMany",
        )
        // No row was inserted — the preflight rejected before any
        // per-block create ran.
        assertEquals(0L, driver.count("users", emptyList()))
    }

    @Test
    fun `RequiredForMultiWrite accepts createMany inside a caller transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForMultiWrite
        }
        val result = client.withTransaction { tx ->
            tx.users.createMany(
                { name = "Alice"; email = "alice@example.com" },
                { name = "Bob"; email = "bob@example.com" },
            ).orRollback()
        }
        val success = assertIs<TransactionResult.Success<List<User>>>(result)
        assertEquals(2, success.value.size)
    }

    @Test
    fun `Optional permits createMany outside a caller transaction (atomic via internal transaction)`() {
        // Deliberate reversal of the old contract: createMany no longer
        // demands a caller transaction — under Optional it is atomic via
        // its own EntKt-owned transaction.
        val driver = freshDriver()
        val client = sysClient(driver)
        val result = client.users.createMany(
            { name = "Alice"; email = "alice@example.com" },
            { name = "Bob"; email = "bob@example.com" },
        )
        val success = assertIs<MutationResult.Success<List<User>>>(result)
        assertEquals(2, success.value.size)
        assertEquals(2L, driver.count("users", emptyList()))
    }

    @Test
    fun `zero-block createMany returns Success(emptyList()) outside a tx under Optional`() {
        val driver = freshDriver()
        val client = sysClient(driver)
        assertEquals(MutationResult.Success(emptyList<User>()), client.users.createMany())
    }

    @Test
    fun `RequiredForMultiWrite fails deleteMany outside a caller transaction (classify by operation shape, not result size)`() {
        // deleteMany is a multi-write API regardless of how many rows
        // actually match — the caller-posture requirement fires before
        // the candidate query.
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForMultiWrite
        }
        assertRequirementFailure(
            client.users.deleteMany(User.email eq "nobody@example.com"),
            "RequiredForMultiWrite",
            "deleteMany",
        )
    }

    @Test
    fun `RequiredForMultiWrite accepts deleteMany inside a caller transaction`() {
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForMultiWrite
        }
        val result = client.withTransaction { tx ->
            tx.users.deleteMany(User.email eq "nobody@example.com").orRollback()
        }
        assertEquals(TransactionResult.Success(0), result)
    }

    @Test
    fun `transaction requirement propagates to the transactional client`() {
        // The transactional sub-client must inherit the configured
        // requirement (and its inTransaction posture satisfies it).
        val driver = freshDriver()
        val client = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        val result = client.withTransaction { tx ->
            tx.users.create {
                name = "Alice"
                email = "alice@example.com"
            }.saveAndLoad().orRollback().name
        }
        assertEquals(TransactionResult.Success("Alice"), result)
    }

    @Test
    fun `an assignment-free update under a strict requirement still fails outside a transaction`() {
        // The requirement check is the first observable step of every
        // save — an assignment-free update is not exempt (there is no
        // pre-classification of empty updates anymore; the no-op
        // success path only applies once the requirement is satisfied).
        val driver = freshDriver()
        val seed = sysClient(driver) {
            transactionRequirement = TransactionRequirement.Optional
        }
        val user = seed.users.create {
            name = "Alice"
            email = "alice@example.com"
        }.saveAndLoad().getOrThrow()

        val strict = sysClient(driver) {
            transactionRequirement = TransactionRequirement.RequiredForAllWrites
        }
        assertRequirementFailure(
            strict.users.update(user.id) { /* no assignments */ }.save(),
            "RequiredForAllWrites",
            "update",
        )
    }
}
