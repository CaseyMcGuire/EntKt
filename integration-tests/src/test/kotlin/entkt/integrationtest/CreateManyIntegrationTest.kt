package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserCreatePrivacyRule
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntConstraintViolationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.getOrThrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end coverage for `createMany(vararg blocks): MutationResult<List<E>>`.
 *
 * The batch is atomic WITHOUT a caller transaction: when the caller has
 * none, createMany owns an internal EntKt transaction, so a mid-batch
 * failure leaves zero committed rows. Hook-required hydration is
 * write-side work and happens per row; return processing (LOAD
 * disclosure plus any additional return materialization, input order,
 * fail-fast) runs after all writes: an
 * EntKt-owned batch still COMMITS when only disclosure fails
 * (`Failed(..., Committed)`), while in a caller-owned transaction the
 * same failure is `TransactionPending` and marks the scope
 * rollback-only.
 */
class CreateManyIntegrationTest : PostgresTestBase() {

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { PrivacyDecision.Allow }) }
        }
    }

    /** CREATE allowed for everyone; LOAD denied for users named [hiddenName]. */
    private fun createButNoLoad(hiddenName: String) = object : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy {
                load(UserLoadPrivacyRule { ctx ->
                    if (ctx.entity.name == hiddenName) PrivacyDecision.Deny("$hiddenName is sealed")
                    else PrivacyDecision.Allow
                })
                create(UserCreatePrivacyRule { PrivacyDecision.Allow })
            }
        }
    }

    private fun freshClient(
        viewer: Viewer = Viewer.PrivacyBypass("test"),
        userPolicy: EntityPolicy<User, UserPolicyScope> = OpenUser,
    ): EntClient {
        val driver = resetAndDriver()
        return EntClient(driver) {
            privacyContext { PrivacyContext(viewer) }
            policies { users(userPolicy) }
        }
    }

    private fun bypassCount(client: EntClient): Long =
        client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.query().rawCount().getOrThrow()
        }

    // ---- Success ----

    @Test
    fun `zero-block call returns Success(emptyList()) without a transaction`() {
        val client = freshClient()
        assertEquals(MutationResult.Success(emptyList<User>()), client.users.createMany())
    }

    @Test
    fun `createMany outside a caller transaction succeeds and returns entities in input order`() {
        val client = freshClient()

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "b@example.com" },
            { name = "C"; email = "c@example.com" },
        )

        val success = assertIs<MutationResult.Success<List<User>>>(result)
        assertEquals(listOf("A", "B", "C"), success.value.map { it.name })
        assertEquals(3L, client.users.query().rawCount().getOrThrow())
    }

    @Test
    fun `createMany inside a caller transaction stages all rows`() {
        val client = freshClient()

        val txResult = client.withTransaction { tx ->
            tx.users.createMany(
                { name = "A"; email = "a@example.com" },
                { name = "B"; email = "b@example.com" },
            ).orRollback()
        }

        val success = assertIs<TransactionResult.Success<List<User>>>(txResult)
        assertEquals(listOf("A", "B"), success.value.map { it.name })
        assertEquals(2L, client.users.query().rawCount().getOrThrow())
    }

    // ---- Mid-batch failure: atomic, zero rows committed ----

    @Test
    fun `first-block validation failure keeps its typed identity with zero rows committed`() {
        val client = freshClient()

        // The FIRST block fails before any row staged a write, so the
        // typed exception passes through unchanged (NotPersisted is the
        // whole batch's honest state).
        val result = client.users.createMany(
            { name = "A" }, // email is required → validation failure
            { name = "B"; email = "b@example.com" },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntValidationException>(failed.exception)
        assertEquals("User", ex.entityType)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals("email", ex.violations.single().field)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)

        assertEquals(0L, client.users.query().rawCount().getOrThrow())
    }

    @Test
    fun `mid-batch validation failure keeps typed identity after the confirmed internal rollback`() {
        val client = freshClient()

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B" }, // email is required → validation failure
            { name = "C"; email = "c@example.com" },
        )

        // The EntKt-owned transaction rolled back and was confirmed, so
        // NotPersisted is the honest batch state and the typed row
        // failure passes through unchanged.
        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntValidationException>(failed.exception)
        assertEquals("User", ex.entityType)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals("email", ex.violations.single().field)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)

        // Confirmed rollback of the EntKt-owned transaction: A is gone too.
        assertEquals(0L, client.users.query().rawCount().getOrThrow())
    }

    @Test
    fun `mid-batch constraint violation is Failed, short-circuits, and commits nothing`() {
        var creates = 0
        val driver = resetAndDriver()
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(OpenUser) }
            hooks { users { beforeCreate { creates++ } } }
        }
        client.users.create { name = "Existing"; email = "dup@example.com" }.save().getOrThrow()
        creates = 0

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "dup@example.com" }, // unique violation
            { name = "C"; email = "c@example.com" },   // must NOT run
        )

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals("23505", ex.driverCode)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)

        // Fail-fast: C's block never ran.
        assertEquals(2, creates)
        // Atomicity: only the pre-existing row survives.
        assertEquals(1L, client.users.query().rawCount().getOrThrow())
    }

    @Test
    fun `inside a caller transaction a mid-batch failure after staged rows reports TransactionPending`() {
        val client = freshClient()

        var inner: MutationResult<List<User>>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.createMany(
                { name = "A"; email = "a@example.com" }, // stages a write
                { name = "B" },                          // validation failure
            )
            "block completed"
        }

        // A staged a write in the CALLER's still-open transaction before
        // B failed, so the typed NotPersisted row failure would lie about
        // the batch's state — it is re-reported as TransactionPending
        // with the typed failure preserved as the direct cause.
        val innerFailed = assertIs<MutationResult.Failed>(inner!!)
        val staged = assertIs<EntUnexpectedMutationException>(innerFailed.exception)
        assertEquals(MutationWriteState.TransactionPending, staged.writeState)
        val cause = assertIs<EntValidationException>(staged.cause)
        assertEquals("email", cause.violations.single().field)

        // Rollback-only backstop: the boundary reports it after rollback.
        val txFailed = assertIs<TransactionResult.Failed>(txResult)
        assertSame(staged, txFailed.exception)
        assertEquals(TransactionFailureState.NotCommitted, txFailed.transactionState)
        assertEquals(0L, bypassCount(client))
    }

    // ---- Return-processing LOAD denial ----

    @Test
    fun `EntKt-owned batch COMMITS when only return disclosure is denied`() {
        val client = freshClient(viewer = Viewer.User(1L), userPolicy = createButNoLoad("B"))

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "b@example.com" },
            { name = "C"; email = "c@example.com" },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.LOAD, ex.operation)
        assertEquals(MutationWriteState.Committed, ex.writeState)
        assertEquals("User", ex.entityType)
        assertEquals("B is sealed", ex.reason)

        // The whole batch IS committed — disclosure, not persistence, failed.
        assertEquals(3L, bypassCount(client))
        // The one denied entity is identified by key (input order,
        // fail-fast on the first denial).
        val bId = client.withPrivacyContext(PrivacyContext(Viewer.PrivacyBypass("test"))) { sys ->
            sys.users.query { where(User.name eq "B") }.firstOrNull().getOrThrow()!!.id
        }
        assertEquals("id", ex.entityKey?.field)
        assertEquals(bId, ex.entityKey?.value)
    }

    @Test
    fun `caller-owned transaction reports TransactionPending and rolls back`() {
        val client = freshClient(viewer = Viewer.User(1L), userPolicy = createButNoLoad("B"))

        var inner: MutationResult<List<User>>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.createMany(
                { name = "A"; email = "a@example.com" },
                { name = "B"; email = "b@example.com" },
            )
            // Ignore the failure deliberately: the fail-closed backstop
            // must still roll the transaction back.
            "block completed"
        }

        val innerFailed = assertIs<MutationResult.Failed>(inner!!)
        val ex = assertIs<EntMutationPrivacyDeniedException>(innerFailed.exception)
        assertEquals(EntOperation.LOAD, ex.operation)
        assertEquals(MutationWriteState.TransactionPending, ex.writeState)

        // Scope went rollback-only: the boundary reports the recorded
        // failure with confirmed rollback.
        val txFailed = assertIs<TransactionResult.Failed>(txResult)
        assertSame(ex, txFailed.exception)
        assertEquals(TransactionFailureState.NotCommitted, txFailed.transactionState)

        // Nothing committed.
        assertEquals(0L, bypassCount(client))
    }
}
