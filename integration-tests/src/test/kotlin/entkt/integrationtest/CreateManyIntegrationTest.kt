package entkt.integrationtest

import entkt.query.Op
import entkt.query.Predicate
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.EntPrivacyReadClient
import entkt.integrationtest.ent.EntValidationReadClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserCreate
import entkt.integrationtest.ent.UserCreateHookContext
import entkt.integrationtest.ent.UserCreatePrivacyItem
import entkt.integrationtest.ent.UserCreatePrivacyRule
import entkt.integrationtest.ent.UserCreateValidationItem
import entkt.integrationtest.ent.UserLoadPrivacyItem
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserMutation
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.integrationtest.support.RecordingDriver
import entkt.runtime.driver.Driver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.hook.batchHook
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.result.EntConstraintViolationException
import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.ValidationViolation
import entkt.runtime.validation.ValidationDecision
import entkt.runtime.validation.batchValidationRule
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * End-to-end coverage for `createMany(vararg blocks): MutationResult<List<E>>`.
 *
 * The batch is atomic WITHOUT a caller transaction: when the caller has
 * none, createMany owns an internal EntKt transaction, so a preflight,
 * persistence, or write-side callback failure leaves zero committed
 * rows. Lifecycle work is phase-major across the ordered batch:
 * before hooks, preparation, CREATE privacy, validation, one set-based
 * insert, hydration, after hooks, then returned LOAD disclosure. An
 * EntKt-owned batch reports a disclosure failure as `Committed` only
 * after a confirmed commit; disclosure SQL can instead abort the
 * transaction and produce `NotPersisted` after confirmed rollback. In
 * a caller-owned transaction the same failure is `TransactionPending`
 * and marks the scope rollback-only.
 */
class CreateManyIntegrationTest : PostgresTestBase() {

    private class WrongCardinalityDriver(
        private val delegate: Driver,
    ) : Driver by delegate {
        override fun insertMany(
            table: String,
            values: List<Map<String, Any?>>,
        ): List<Map<String, Any?>> = delegate.insertMany(table, values).dropLast(1)

        override fun <T> withTransaction(block: (Driver) -> T): DriverTransactionResult<T> =
            delegate.withTransaction { transactionDriver ->
                block(WrongCardinalityDriver(transactionDriver))
            }
    }

    private class PartialBatchFailure(message: String) : RuntimeException(message)

    /**
     * Simulates a driver failure from insertMany. For multi-input calls it
     * physically stages the first chunk on the transaction connection before
     * the second fails; a one-input call fails before staging anything.
     */
    private class ChunkThenFailDriver(
        private val delegate: Driver,
        private val failure: Exception,
        private val classifyAsConstraint: Boolean,
    ) : Driver by delegate {
        override fun insertMany(
            table: String,
            values: List<Map<String, Any?>>,
        ): List<Map<String, Any?>> {
            if (values.size > 1) {
                delegate.insertMany(table, listOf(values.first()))
            }
            throw failure
        }

        override fun classifyMutationException(
            exception: Exception,
            entity: String,
            operation: EntOperation,
        ): EntMutationException? =
            if (classifyAsConstraint && exception === failure) {
                EntConstraintViolationException(
                    entityType = entity,
                    operation = operation,
                    constraint = "chunk_failure",
                    field = "email",
                    driverCode = "test",
                    message = exception.message ?: "chunk failed",
                    cause = exception,
                )
            } else {
                delegate.classifyMutationException(exception, entity, operation)
            }

        override fun <T> withTransaction(block: (Driver) -> T): DriverTransactionResult<T> =
            delegate.withTransaction { transactionDriver ->
                block(ChunkThenFailDriver(transactionDriver, failure, classifyAsConstraint))
            }
    }

    private object OpenUser : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy { load(UserLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
        }
    }

    /** CREATE allowed for everyone; LOAD denied for users named [hiddenName]. */
    private fun createButNoLoad(hiddenName: String) = object : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy {
                load(UserLoadPrivacyRule { _, item ->
                    if (item.entity.name == hiddenName) PrivacyDecision.Deny("$hiddenName is sealed")
                    else PrivacyDecision.Allow
                })
                create(UserCreatePrivacyRule { _, _ -> PrivacyDecision.Allow })
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
        val recording = RecordingDriver(resetAndDriver())
        var privacyCaptures = 0
        var hookCalls = 0
        val client = EntClient(recording) {
            privacyContext {
                privacyCaptures++
                PrivacyContext(Viewer.PrivacyBypass("test"))
            }
            policies { users(OpenUser) }
            hooks {
                users {
                    beforeSave { hookCalls++ }
                    beforeCreate { hookCalls++ }
                    afterCreate { hookCalls++ }
                }
            }
        }

        assertEquals(MutationResult.Success(emptyList<User>()), client.users.createMany())
        assertEquals(0, recording.callCount("withTransaction"))
        assertEquals(0, recording.callCount("insert:users"))
        assertEquals(0, recording.callCount("insertMany:users"))
        assertEquals(0, privacyCaptures)
        assertEquals(0, hookCalls)
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

    @Test
    fun `createMany runs lifecycle phases once over the full ordered batch and inserts once`() {
        val events = mutableListOf<String>()
        val recording = RecordingDriver(resetAndDriver())
        var privacyCaptures = 0
        val capturedPrivacy = PrivacyContext(Viewer.User(42L))
        var createPrivacy: PrivacyContext? = null
        var loadPrivacy: PrivacyContext? = null
        val policy = object : EntityPolicy<User, UserPolicyScope> {
            override fun configure(scope: UserPolicyScope) = scope.run {
                privacy {
                    create(batchPrivacyRule<EntPrivacyReadClient, UserCreatePrivacyItem> { context, batch ->
                        assertEquals(0, recording.callCount("insertMany:users"))
                        createPrivacy = context.privacy
                        events += "createPrivacy:${batch.joinToString { it.candidate.name }}"
                        batch.decideEach { PrivacyDecision.Allow }
                    })
                    load(batchPrivacyRule<EntPrivacyReadClient, UserLoadPrivacyItem> { context, batch ->
                        loadPrivacy = context.privacy
                        events += "loadPrivacy:${batch.joinToString { it.entity.name }}"
                        batch.decideEach { PrivacyDecision.Allow }
                    })
                }
                validation {
                    create(batchValidationRule<EntValidationReadClient, UserCreateValidationItem> { _, batch ->
                        assertEquals(0, recording.callCount("insertMany:users"))
                        events += "validation:${batch.joinToString { it.candidate.name }}"
                        batch.decideEach { ValidationDecision.Valid }
                    })
                }
            }
        }
        val client = EntClient(recording) {
            privacyContext {
                privacyCaptures++
                events += "capturePrivacy"
                capturedPrivacy
            }
            policies { users(policy) }
            hooks {
                users {
                    beforeSave(batchHook<UserMutation> { mutations ->
                        events += "beforeSave:${mutations.joinToString { it.name!! }}"
                    })
                    beforeCreate(batchHook<UserCreateHookContext> { contexts ->
                        events += "beforeCreate:${contexts.joinToString { it.mutation.name!! }}"
                    })
                    afterCreate(batchHook<User> { entities ->
                        // Hydration and the set-based write both precede the full-batch callback.
                        assertEquals(1, recording.callCount("insertMany:users"))
                        events += "afterCreate:${entities.joinToString { it.name }}"
                    })
                }
            }
        }

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "b@example.com" },
            { name = "C"; email = "c@example.com" },
        )

        val entities = assertIs<MutationResult.Success<List<User>>>(result).value
        assertEquals(listOf("A", "B", "C"), entities.map { it.name })
        assertEquals(
            listOf(
                "beforeSave:A, B, C",
                "beforeCreate:A, B, C",
                "capturePrivacy",
                "createPrivacy:A, B, C",
                "validation:A, B, C",
                "afterCreate:A, B, C",
                "loadPrivacy:A, B, C",
            ),
            events,
        )
        assertEquals(1, privacyCaptures)
        assertSame(capturedPrivacy, createPrivacy)
        assertSame(capturedPrivacy, loadPrivacy)
        assertEquals(1, recording.callCount("withTransaction"))
        assertEquals(1, recording.callCount("insertMany:users"))
        assertEquals(0, recording.callCount("insert:users"))
    }

    @Test
    fun `managed builder save failures use encounter order across createMany blocks`() {
        val recording = RecordingDriver(resetAndDriver())
        var privacyCaptures = 0
        var hookCalls = 0
        val client = EntClient(recording) {
            privacyContext {
                privacyCaptures++
                PrivacyContext(Viewer.PrivacyBypass("test"))
            }
            policies { users(OpenUser) }
            hooks {
                users {
                    beforeSave { hookCalls++ }
                    beforeCreate { hookCalls++ }
                    afterCreate { hookCalls++ }
                }
            }
        }

        lateinit var first: UserCreate
        lateinit var second: UserCreate
        var firstSave: MutationResult<Unit>? = null
        var secondSave: MutationResult<Unit>? = null
        var inner: MutationResult<List<User>>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.createMany(
                {
                    first = this
                    name = "A"
                    email = "a@example.com"
                },
                {
                    second = this
                    name = "B"
                    email = "b@example.com"
                },
                {
                    name = "C"
                    email = "c@example.com"
                    // Encounter order deliberately differs from builder order.
                    secondSave = second.save()
                    firstSave = first.save()
                },
            )
            "ignored batch failure"
        }

        val failed = assertIs<MutationResult.Failed>(inner)
        val exception = assertIs<EntUnexpectedMutationException>(failed.exception)
        val secondFailure = assertIs<MutationResult.Failed>(secondSave).exception
        val firstFailure = assertIs<MutationResult.Failed>(firstSave).exception
        assertSame(secondFailure, exception)
        assertEquals(MutationWriteState.NotPersisted, exception.writeState)
        val cause = assertIs<IllegalStateException>(exception.cause)
        assertContains(cause.message.orEmpty(), "cannot be persisted independently")
        val transactionFailed = assertIs<TransactionResult.Failed>(txResult)
        assertSame(secondFailure, transactionFailed.exception)
        assertEquals(TransactionFailureState.NotCommitted, transactionFailed.transactionState)
        assertEquals(listOf(firstFailure), secondFailure.suppressed.toList())
        assertEquals(0, privacyCaptures)
        assertEquals(0, hookCalls)
        assertEquals(0, recording.callCount("insert:users"))
        assertEquals(0, recording.callCount("insertMany:users"))
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `ignored managed builder save from a before hook fails createMany before persistence`() {
        val recording = RecordingDriver(resetAndDriver())
        lateinit var builder: UserCreate
        var nestedSave: MutationResult<Unit>? = null
        val client = sysClient(recording) {
            hooks {
                users {
                    beforeSave {
                        nestedSave = builder.save()
                    }
                }
            }
        }

        val result = client.users.createMany(
            {
                builder = this
                name = "A"
                email = "managed-before-hook@example.com"
            },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        assertSame(assertIs<MutationResult.Failed>(nestedSave).exception, failed.exception)
        assertEquals(MutationWriteState.NotPersisted, failed.exception.writeState)
        assertEquals(0, recording.callCount("insert:users"))
        assertEquals(0, recording.callCount("insertMany:users"))
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `ignored managed builder save from afterCreate fails pending caller batch and rolls back`() {
        val recording = RecordingDriver(resetAndDriver())
        lateinit var builder: UserCreate
        var nestedSave: MutationResult<Unit>? = null
        val client = sysClient(recording) {
            hooks {
                users {
                    afterCreate {
                        nestedSave = builder.save()
                    }
                }
            }
        }

        var inner: MutationResult<List<User>>? = null
        val transaction = client.withTransaction { tx ->
            inner = tx.users.createMany(
                {
                    builder = this
                    name = "A"
                    email = "managed-after-hook@example.com"
                },
            )
            "ignored result"
        }

        val nestedFailure = assertIs<MutationResult.Failed>(nestedSave).exception
        val batchFailure = assertIs<MutationResult.Failed>(inner).exception
        val unexpected = assertIs<EntUnexpectedMutationException>(batchFailure)
        assertEquals(MutationWriteState.TransactionPending, unexpected.writeState)
        assertSame(nestedFailure, unexpected.cause)
        val transactionFailure = assertIs<TransactionResult.Failed>(transaction)
        assertSame(batchFailure, transactionFailure.exception)
        assertEquals(TransactionFailureState.NotCommitted, transactionFailure.transactionState)
        assertEquals(1, recording.callCount("insertMany:users"))
        assertEquals(0L, bypassCount(client))
    }

    // ---- Mid-batch failure: atomic, zero rows committed ----

    @Test
    fun `first-block validation failure keeps its typed identity with zero rows committed`() {
        val client = freshClient()

        // Preparation fails before the set-based write, so the typed
        // exception passes through unchanged (NotPersisted is the whole
        // batch's honest state).
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
    fun `later required-field failure keeps typed identity before batch persistence`() {
        val client = freshClient()

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B" }, // email is required → validation failure
            { name = "C"; email = "c@example.com" },
        )

        // Every row is prepared before persistence, so NotPersisted is
        // the honest batch state and the typed row failure passes through
        // unchanged.
        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntValidationException>(failed.exception)
        assertEquals("User", ex.entityType)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals("email", ex.violations.single().field)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)

        // No row reached the set-based insert.
        assertEquals(0L, client.users.query().rawCount().getOrThrow())
    }

    @Test
    fun `constraint failure happens after every beforeCreate hook and one set-based insert`() {
        var creates = 0
        val recording = RecordingDriver(resetAndDriver())
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(OpenUser) }
            hooks { users { beforeCreate { creates++ } } }
        }
        client.users.create { name = "Existing"; email = "dup@example.com" }.save().getOrThrow()
        creates = 0
        recording.reset()

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "dup@example.com" }, // unique violation
            { name = "C"; email = "c@example.com" },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals("23505", ex.driverCode)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)

        // Phase-major preflight: all builders and hooks finish before persistence starts.
        assertEquals(3, creates)
        assertEquals(1, recording.callCount("insertMany:users"))
        assertEquals(0, recording.callCount("insert:users"))
        // Atomicity: only the pre-existing row survives.
        assertEquals(1L, client.users.query().rawCount().getOrThrow())
    }

    @Test
    fun `classified failure after an internal chunk is TransactionPending for a caller transaction`() {
        val driverFailure = PartialBatchFailure("second physical chunk failed")
        val client = EntClient(
            ChunkThenFailDriver(resetAndDriver(), driverFailure, classifyAsConstraint = true),
        ) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(OpenUser) }
        }

        var inner: MutationResult<List<User>>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.createMany(
                { name = "A"; email = "a@example.com" },
                { name = "B"; email = "b@example.com" },
            )
            "ignored failure"
        }

        val failed = assertIs<MutationResult.Failed>(inner)
        val pending = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.TransactionPending, pending.writeState)
        val constraint = assertIs<EntConstraintViolationException>(pending.cause)
        assertEquals(MutationWriteState.NotPersisted, constraint.writeState)
        assertSame(driverFailure, constraint.cause)

        val transactionFailed = assertIs<TransactionResult.Failed>(txResult)
        assertSame(pending, transactionFailed.exception)
        assertEquals(TransactionFailureState.NotCommitted, transactionFailed.transactionState)
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `one-input caller batch preserves a typed NotPersisted constraint`() {
        val driverFailure = PartialBatchFailure("single statement failed")
        val client = EntClient(
            ChunkThenFailDriver(resetAndDriver(), driverFailure, classifyAsConstraint = true),
        ) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(OpenUser) }
        }

        var inner: MutationResult<List<User>>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.createMany(
                { name = "A"; email = "a@example.com" },
            )
            "ignored failure"
        }

        val failed = assertIs<MutationResult.Failed>(inner)
        val constraint = assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, constraint.writeState)
        assertSame(driverFailure, constraint.cause)
        val transactionFailed = assertIs<TransactionResult.Failed>(txResult)
        assertSame(constraint, transactionFailed.exception)
        assertEquals(TransactionFailureState.NotCommitted, transactionFailed.transactionState)
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `owned batch restores a typed constraint after rolling back an internal chunk`() {
        val driverFailure = PartialBatchFailure("second physical chunk failed")
        val client = EntClient(
            ChunkThenFailDriver(resetAndDriver(), driverFailure, classifyAsConstraint = true),
        ) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(OpenUser) }
        }

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "b@example.com" },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        val constraint = assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, constraint.writeState)
        assertSame(driverFailure, constraint.cause)
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `unclassified failure after an internal chunk is TransactionPending`() {
        val driverFailure = PartialBatchFailure("unclassified second-chunk failure")
        val client = EntClient(
            ChunkThenFailDriver(resetAndDriver(), driverFailure, classifyAsConstraint = false),
        ) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(OpenUser) }
        }

        var inner: MutationResult<List<User>>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.createMany(
                { name = "A"; email = "a@example.com" },
                { name = "B"; email = "b@example.com" },
            )
            "ignored failure"
        }

        val failed = assertIs<MutationResult.Failed>(inner)
        val pending = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.TransactionPending, pending.writeState)
        assertSame(driverFailure, pending.cause)
        val transactionFailed = assertIs<TransactionResult.Failed>(txResult)
        assertSame(pending, transactionFailed.exception)
        assertEquals(TransactionFailureState.NotCommitted, transactionFailed.transactionState)
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `uncaught cancellation after an internal chunk rolls the owned batch back`() {
        val cancellation = CancellationException("cancel after first chunk")
        val client = EntClient(
            ChunkThenFailDriver(resetAndDriver(), cancellation, classifyAsConstraint = false),
        ) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(OpenUser) }
        }

        val thrown = assertFailsWith<CancellationException> {
            client.users.createMany(
                { name = "A"; email = "a@example.com" },
                { name = "B"; email = "b@example.com" },
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `wrong insertMany cardinality fails before hydration and afterCreate then rolls back`() {
        var afterCreateCalls = 0
        val client = EntClient(WrongCardinalityDriver(resetAndDriver())) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(OpenUser) }
            hooks { users { afterCreate { afterCreateCalls++ } } }
        }

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "b@example.com" },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        val exception = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, exception.writeState)
        val cause = assertIs<IllegalStateException>(exception.cause)
        assertContains(cause.message.orEmpty(), "Driver.insertMany contract violation for User")
        assertEquals(0, afterCreateCalls)
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `wrong insertMany cardinality is TransactionPending in a caller transaction`() {
        var afterCreateCalls = 0
        val client = EntClient(WrongCardinalityDriver(resetAndDriver())) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(OpenUser) }
            hooks { users { afterCreate { afterCreateCalls++ } } }
        }

        var inner: MutationResult<List<User>>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.createMany(
                { name = "A"; email = "a@example.com" },
                { name = "B"; email = "b@example.com" },
            )
        }

        val failed = assertIs<MutationResult.Failed>(inner)
        val exception = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.TransactionPending, exception.writeState)
        assertContains(exception.cause?.message.orEmpty(), "Driver.insertMany contract violation for User")
        assertEquals(0, afterCreateCalls)
        val transactionFailed = assertIs<TransactionResult.Failed>(txResult)
        assertSame(exception, transactionFailed.exception)
        assertEquals(TransactionFailureState.NotCommitted, transactionFailed.transactionState)
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `afterCreate application exceptions stay foreign after owned rollback`() {
        val thrown = EntValidationException(
            entityType = "ApplicationConstructed",
            operation = EntOperation.CREATE,
            violations = listOf(ValidationViolation("hook failed")),
        )
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(OpenUser) }
            hooks {
                users {
                    afterCreate(batchHook<User> { throw thrown })
                }
            }
        }

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "b@example.com" },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        val unexpected = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, unexpected.writeState)
        assertSame(thrown, unexpected.cause)
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `later batch validation failure in a caller transaction is NotPersisted and writes nothing`() {
        val validatedNames = mutableListOf<String>()
        val recording = RecordingDriver(resetAndDriver())
        val policy = object : EntityPolicy<User, UserPolicyScope> {
            override fun configure(scope: UserPolicyScope) = scope.run {
                privacy { load(UserLoadPrivacyRule { _, _ -> PrivacyDecision.Allow }) }
                validation {
                    create(batchValidationRule<EntValidationReadClient, UserCreateValidationItem> { _, batch ->
                        validatedNames += batch.map { it.candidate.name }
                        batch.decideEach { item ->
                            if (item.candidate.name == "B") {
                                ValidationDecision.Invalid("B is invalid", field = "name")
                            } else {
                                ValidationDecision.Valid
                            }
                        }
                    })
                }
            }
        }
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            policies { users(policy) }
        }

        var inner: MutationResult<List<User>>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.createMany(
                { name = "A"; email = "a@example.com" },
                { name = "B"; email = "b@example.com" },
                { name = "C"; email = "c@example.com" },
            )
            "block completed"
        }

        val innerFailed = assertIs<MutationResult.Failed>(inner!!)
        val validation = assertIs<EntValidationException>(innerFailed.exception)
        assertEquals(MutationWriteState.NotPersisted, validation.writeState)
        assertEquals("name", validation.violations.single().field)
        assertEquals(listOf("A", "B", "C"), validatedNames)
        assertEquals(0, recording.callCount("insertMany:users"))
        assertEquals(0, recording.callCount("insert:users"))

        // Rollback-only backstop: the boundary reports it after rollback.
        val txFailed = assertIs<TransactionResult.Failed>(txResult)
        assertSame(validation, txFailed.exception)
        assertEquals(TransactionFailureState.NotCommitted, txFailed.transactionState)
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `a later CREATE privacy denial evaluates the full batch before validation or insert`() {
        val privacyBatches = mutableListOf<List<String>>()
        var validationCalls = 0
        val recording = RecordingDriver(resetAndDriver())
        val policy = object : EntityPolicy<User, UserPolicyScope> {
            override fun configure(scope: UserPolicyScope) = scope.run {
                privacy {
                    create(batchPrivacyRule<EntPrivacyReadClient, UserCreatePrivacyItem> { _, batch ->
                        privacyBatches += batch.map { it.candidate.name }
                        batch.decideEach { item ->
                            if (item.candidate.name == "B") {
                                PrivacyDecision.Deny("B cannot be created")
                            } else {
                                PrivacyDecision.Allow
                            }
                        }
                    })
                }
                validation {
                    create(batchValidationRule<EntValidationReadClient, UserCreateValidationItem> { _, batch ->
                        validationCalls++
                        batch.decideEach { ValidationDecision.Valid }
                    })
                }
            }
        }
        val client = EntClient(recording) {
            privacyContext { PrivacyContext(Viewer.User(42L)) }
            policies { users(policy) }
        }

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "b@example.com" },
            { name = "C"; email = "c@example.com" },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        val denial = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.CREATE, denial.operation)
        assertEquals(MutationWriteState.NotPersisted, denial.writeState)
        assertEquals("B cannot be created", denial.reason)
        assertEquals(listOf(listOf("A", "B", "C")), privacyBatches)
        assertEquals(0, validationCalls)
        assertEquals(0, recording.callCount("insertMany:users"))
        assertEquals(0, recording.callCount("insert:users"))
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
    fun `EntKt-owned batch commits when returned LOAD rule throws an ordinary exception`() {
        val thrown = IllegalStateException("LOAD policy dependency failed")
        val policy = object : EntityPolicy<User, UserPolicyScope> {
            override fun configure(scope: UserPolicyScope) = scope.run {
                privacy {
                    create(UserCreatePrivacyRule { _, _ -> PrivacyDecision.Allow })
                    load(batchPrivacyRule<EntPrivacyReadClient, UserLoadPrivacyItem> { _, _ -> throw thrown })
                }
            }
        }
        val client = freshClient(viewer = Viewer.User(1L), userPolicy = policy)

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "b@example.com" },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        val exception = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.Committed, exception.writeState)
        assertSame(thrown, exception.cause)
        assertEquals(2L, bypassCount(client))
    }

    @Test
    fun `EntKt-owned batch preserves a returned LOAD SQL failure after rollback`() {
        val policy = object : EntityPolicy<User, UserPolicyScope> {
            override fun configure(scope: UserPolicyScope) = scope.run {
                privacy {
                    create(UserCreatePrivacyRule { _, _ -> PrivacyDecision.Allow })
                    load(batchPrivacyRule<EntPrivacyReadClient, UserLoadPrivacyItem> { context, batch ->
                        context.client.users.query {
                            where(Predicate.Leaf<User>("missing_column", Op.EQ, 1))
                        }.rawExists().getOrThrow()
                        batch.decideEach { PrivacyDecision.Allow }
                    })
                }
            }
        }
        val client = freshClient(viewer = Viewer.User(1L), userPolicy = policy)

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "b@example.com" },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        val exception = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, exception.writeState)
        assertContains(exception.cause?.message.orEmpty(), "missing_column")
        assertEquals(1, exception.suppressed.size)
        assertContains(exception.suppressed.single().message.orEmpty(), "aborted")
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `EntKt-owned batch preserves a returned LOAD denial when its handled read failure aborts the transaction`() {
        val policy = object : EntityPolicy<User, UserPolicyScope> {
            override fun configure(scope: UserPolicyScope) = scope.run {
                privacy {
                    create(UserCreatePrivacyRule { _, _ -> PrivacyDecision.Allow })
                    load(batchPrivacyRule<EntPrivacyReadClient, UserLoadPrivacyItem> { context, batch ->
                        // Deliberately inspect-and-handle the read failure rather
                        // than throwing it. PostgreSQL still marks the transaction
                        // aborted, so the owned boundary confirms rollback.
                        context.client.users.query {
                            where(Predicate.Leaf<User>("missing_column", Op.EQ, 1))
                        }.rawExists()
                        batch.decideEach { PrivacyDecision.Deny("LOAD dependency unavailable") }
                    })
                }
            }
        }
        val client = freshClient(viewer = Viewer.User(1L), userPolicy = policy)

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "b@example.com" },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        val denial = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(EntOperation.LOAD, denial.operation)
        assertEquals(MutationWriteState.NotPersisted, denial.writeState)
        assertEquals("LOAD dependency unavailable", denial.reason)
        assertEquals(1, denial.suppressed.size)
        assertContains(denial.suppressed.single().message.orEmpty(), "aborted")
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `owned batch keeps an earlier ignored hook mutation failure primary over returned LOAD denial`() {
        var launchedNestedMutation = false
        var nestedFailure: EntMutationException? = null
        val client = EntClient(resetAndDriver()) {
            privacyContext { PrivacyContext(Viewer.User(1L)) }
            policies { users(createButNoLoad("B")) }
            hooks {
                users {
                    beforeCreate { context ->
                        if (!launchedNestedMutation) {
                            launchedNestedMutation = true
                            val nested = context.client.users.create { }.save()
                            nestedFailure = assertIs<MutationResult.Failed>(nested).exception
                        }
                    }
                }
            }
        }

        val result = client.users.createMany(
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "b@example.com" },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        assertSame(nestedFailure, failed.exception)
        val disclosure = assertIs<EntMutationPrivacyDeniedException>(
            failed.exception.suppressed.single(),
        )
        assertEquals(MutationWriteState.NotPersisted, disclosure.writeState)
        assertEquals(EntOperation.LOAD, disclosure.operation)
        assertEquals("B is sealed", disclosure.reason)
        assertEquals(0L, bypassCount(client))
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
