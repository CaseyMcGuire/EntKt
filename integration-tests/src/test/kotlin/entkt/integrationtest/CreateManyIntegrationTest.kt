package entkt.integrationtest

import entkt.query.Op
import entkt.query.Predicate
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.ReadOnlyEntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserBeforeCreateState
import entkt.integrationtest.ent.UserBeforeSaveState
import entkt.integrationtest.ent.UserCreateRuleInput
import entkt.integrationtest.ent.UserCreatePrivacyRule
import entkt.integrationtest.ent.UserLoadPrivacyItem
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.integrationtest.support.RecordingDriver
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.hook.batchHook
import entkt.runtime.hook.batchMutationHook
import entkt.runtime.mutation.orElse
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.ViewerContext
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
    private var viewerContext = testViewerContext

    private class WrongCardinalityDriver(
        private val delegate: DatabaseDriver,
    ) : DatabaseDriver by delegate {
        override fun insertMany(
            table: String,
            values: List<Map<String, Any?>>,
        ): List<Map<String, Any?>> = delegate.insertMany(table, values).dropLast(1)

        override fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T> =
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
        private val delegate: DatabaseDriver,
        private val failure: Exception,
        private val classifyAsConstraint: Boolean,
    ) : DatabaseDriver by delegate {
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

        override fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T> =
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
        viewerContext = ViewerContext(viewer)
        val driver = resetAndDriver()
        return EntClient(driver) {

            policies { users(userPolicy) }
        }
    }

    private fun bypassCount(client: EntClient): Long =
        run {
            val sys = client
            val viewerContext = testBypassContext("test")
            sys.users.query().all(viewerContext).getOrThrow().size.toLong()
        }

    // ---- Success ----

    @Test
    fun `zero-block call returns Success(emptyList()) without a transaction`() {
        val recording = RecordingDriver(resetAndDriver())
        var hookCalls = 0
        val client = EntClient(recording) {
            policies { users(OpenUser) }
            hooks {
                users {
                    beforeSave { state -> hookCalls++; state }
                    beforeCreate { state -> hookCalls++; state }
                    afterCreate { hookCalls++ }
                }
            }
        }

        assertEquals(MutationResult.Success(emptyList<User>()), client.users.createMany(viewerContext, ))
        assertEquals(0, recording.callCount("withTransaction"))
        assertEquals(0, recording.callCount("insert:users"))
        assertEquals(0, recording.callCount("insertMany:users"))
        assertEquals(0, hookCalls)
    }

    @Test
    fun `createMany outside a caller transaction succeeds and returns entities in input order`() {
        val client = freshClient()

        val result = client.users.createMany(viewerContext,
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "b@example.com" },
            { name = "C"; email = "c@example.com" },
        )

        val success = assertIs<MutationResult.Success<List<User>>>(result)
        assertEquals(listOf("A", "B", "C"), success.value.map { it.name })
        assertEquals(3L, client.users.query().all(viewerContext).getOrThrow().size.toLong())
    }

    @Test
    fun `createMany inside a caller transaction stages all rows`() {
        val client = freshClient()

        val txResult = client.withTransaction { tx ->
            tx.users.createMany(viewerContext,
                { name = "A"; email = "a@example.com" },
                { name = "B"; email = "b@example.com" },
            ).orRollback()
        }

        val success = assertIs<TransactionResult.Success<List<User>>>(txResult)
        assertEquals(listOf("A", "B"), success.value.map { it.name })
        assertEquals(2L, client.users.query().all(viewerContext).getOrThrow().size.toLong())
    }

    @Test
    fun `createMany runs lifecycle phases once over the full ordered batch and inserts once`() {
        val events = mutableListOf<String>()
        val recording = RecordingDriver(resetAndDriver())
        val capturedPrivacy = ViewerContext(Viewer.User(42L))
        var createPrivacy: ViewerContext? = null
        var loadPrivacy: ViewerContext? = null
        val policy = object : EntityPolicy<User, UserPolicyScope> {
            override fun configure(scope: UserPolicyScope) = scope.run {
                privacy {
                    create(batchPrivacyRule<ReadOnlyEntClient, UserCreateRuleInput> { context, batch ->
                        assertEquals(0, recording.callCount("insertMany:users"))
                        createPrivacy = context.viewerContext
                        events += "createPrivacy:${batch.joinToString { it.candidate.name }}"
                        batch.decideEach { PrivacyDecision.Allow }
                    })
                    load(batchPrivacyRule<ReadOnlyEntClient, UserLoadPrivacyItem> { context, batch ->
                        loadPrivacy = context.viewerContext
                        events += "loadPrivacy:${batch.joinToString { it.entity.name }}"
                        batch.decideEach { PrivacyDecision.Allow }
                    })
                }
                validation {
                    create(batchValidationRule<ReadOnlyEntClient, UserCreateRuleInput> { _, batch ->
                        assertEquals(0, recording.callCount("insertMany:users"))
                        events += "validation:${batch.joinToString { it.candidate.name }}"
                        batch.decideEach { ValidationDecision.Valid }
                    })
                }
            }
        }
        val client = EntClient(recording) {
            policies { users(policy) }
            hooks {
                users {
                    beforeSave(batchMutationHook<UserBeforeSaveState> { states ->
                        events += "beforeSave:${states.joinToString { it.name.orElse(null)!! }}"
                        states
                    })
                    beforeCreate(batchMutationHook<UserBeforeCreateState> { states ->
                        states.forEach { assertSame(capturedPrivacy, it.viewerContext) }
                        events += "beforeCreate:${states.joinToString { it.name.orElse(null)!! }}"
                        states
                    })
                    afterCreate(batchHook<User> { entities ->
                        // Hydration and the set-based write both precede the full-batch callback.
                        assertEquals(1, recording.callCount("insertMany:users"))
                        events += "afterCreate:${entities.joinToString { it.name }}"
                    })
                }
            }
        }

        val result = client.users.createMany(capturedPrivacy,
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
                "createPrivacy:A, B, C",
                "validation:A, B, C",
                "afterCreate:A, B, C",
                "loadPrivacy:A, B, C",
            ),
            events,
        )
        assertSame(capturedPrivacy, createPrivacy)
        assertSame(capturedPrivacy, loadPrivacy)
        assertEquals(1, recording.callCount("withTransaction"))
        assertEquals(1, recording.callCount("insertMany:users"))
        assertEquals(0, recording.callCount("insert:users"))
    }

    // ---- Mid-batch failure: atomic, zero rows committed ----

    @Test
    fun `first-block validation failure keeps its typed identity with zero rows committed`() {
        val client = freshClient()

        // Preparation fails before the set-based write, so the typed
        // exception passes through unchanged (NotPersisted is the whole
        // batch's honest state).
        val result = client.users.createMany(viewerContext,
            { name = "A" }, // email is required → validation failure
            { name = "B"; email = "b@example.com" },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        val ex = assertIs<EntValidationException>(failed.exception)
        assertEquals("User", ex.entityType)
        assertEquals(EntOperation.CREATE, ex.operation)
        assertEquals("email", ex.violations.single().field)
        assertEquals(MutationWriteState.NotPersisted, ex.writeState)

        assertEquals(0L, client.users.query().all(viewerContext).getOrThrow().size.toLong())
    }

    @Test
    fun `later required-field failure keeps typed identity before batch persistence`() {
        val client = freshClient()

        val result = client.users.createMany(viewerContext,
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
        assertEquals(0L, client.users.query().all(viewerContext).getOrThrow().size.toLong())
    }

    @Test
    fun `constraint failure happens after every beforeCreate hook and one set-based insert`() {
        var creates = 0
        val recording = RecordingDriver(resetAndDriver())
        val client = EntClient(recording) {

            policies { users(OpenUser) }
            hooks { users { beforeCreate { state -> creates++; state } } }
        }
        client.users.create { name = "Existing"; email = "dup@example.com" }.save(viewerContext).getOrThrow()
        creates = 0
        recording.reset()

        val result = client.users.createMany(viewerContext,
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
        assertEquals(1L, client.users.query().all(viewerContext).getOrThrow().size.toLong())
    }

    @Test
    fun `classified failure after an internal chunk is TransactionPending for a caller transaction`() {
        val driverFailure = PartialBatchFailure("second physical chunk failed")
        val client = EntClient(
            ChunkThenFailDriver(resetAndDriver(), driverFailure, classifyAsConstraint = true),
        ) {

            policies { users(OpenUser) }
        }

        var inner: MutationResult<List<User>>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.createMany(viewerContext,
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

            policies { users(OpenUser) }
        }

        var inner: MutationResult<List<User>>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.createMany(viewerContext,
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

            policies { users(OpenUser) }
        }

        val result = client.users.createMany(viewerContext,
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

            policies { users(OpenUser) }
        }

        var inner: MutationResult<List<User>>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.createMany(viewerContext,
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

            policies { users(OpenUser) }
        }

        val thrown = assertFailsWith<CancellationException> {
            client.users.createMany(viewerContext,
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

            policies { users(OpenUser) }
            hooks { users { afterCreate { afterCreateCalls++ } } }
        }

        val result = client.users.createMany(viewerContext,
            { name = "A"; email = "a@example.com" },
            { name = "B"; email = "b@example.com" },
        )

        val failed = assertIs<MutationResult.Failed>(result)
        val exception = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, exception.writeState)
        val cause = assertIs<IllegalStateException>(exception.cause)
        assertContains(cause.message.orEmpty(), "DatabaseDriver.insertMany contract violation for User")
        assertEquals(0, afterCreateCalls)
        assertEquals(0L, bypassCount(client))
    }

    @Test
    fun `wrong insertMany cardinality is TransactionPending in a caller transaction`() {
        var afterCreateCalls = 0
        val client = EntClient(WrongCardinalityDriver(resetAndDriver())) {

            policies { users(OpenUser) }
            hooks { users { afterCreate { afterCreateCalls++ } } }
        }

        var inner: MutationResult<List<User>>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.createMany(viewerContext,
                { name = "A"; email = "a@example.com" },
                { name = "B"; email = "b@example.com" },
            )
        }

        val failed = assertIs<MutationResult.Failed>(inner)
        val exception = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.TransactionPending, exception.writeState)
        assertContains(exception.cause?.message.orEmpty(), "DatabaseDriver.insertMany contract violation for User")
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

            policies { users(OpenUser) }
            hooks {
                users {
                    afterCreate(batchHook<User> { throw thrown })
                }
            }
        }

        val result = client.users.createMany(viewerContext,
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
                    create(batchValidationRule<ReadOnlyEntClient, UserCreateRuleInput> { _, batch ->
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

            policies { users(policy) }
        }

        var inner: MutationResult<List<User>>? = null
        val txResult = client.withTransaction { tx ->
            inner = tx.users.createMany(viewerContext,
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
        val viewerContext = ViewerContext(Viewer.User(42L))
        val privacyBatches = mutableListOf<List<String>>()
        var validationCalls = 0
        val recording = RecordingDriver(resetAndDriver())
        val policy = object : EntityPolicy<User, UserPolicyScope> {
            override fun configure(scope: UserPolicyScope) = scope.run {
                privacy {
                    create(batchPrivacyRule<ReadOnlyEntClient, UserCreateRuleInput> { _, batch ->
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
                    create(batchValidationRule<ReadOnlyEntClient, UserCreateRuleInput> { _, batch ->
                        validationCalls++
                        batch.decideEach { ValidationDecision.Valid }
                    })
                }
            }
        }
        val client = EntClient(recording) {

            policies { users(policy) }
        }

        val result = client.users.createMany(viewerContext,
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

        val result = client.users.createMany(viewerContext,
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
        val bId = run {
            val sys = client
            val viewerContext = testBypassContext("test")
            sys.users.query { where(User.name eq "B") }.firstOrNull(viewerContext).getOrThrow()!!.id
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
                    load(batchPrivacyRule<ReadOnlyEntClient, UserLoadPrivacyItem> { _, _ -> throw thrown })
                }
            }
        }
        val client = freshClient(viewer = Viewer.User(1L), userPolicy = policy)

        val result = client.users.createMany(viewerContext,
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
                    load(batchPrivacyRule<ReadOnlyEntClient, UserLoadPrivacyItem> { context, batch ->
                        context.client.users.query {
                            where(Predicate.Leaf<User>("missing_column", Op.EQ, 1))
                        }.firstOrNull(context.viewerContext).getOrThrow()
                        batch.decideEach { PrivacyDecision.Allow }
                    })
                }
            }
        }
        val client = freshClient(viewer = Viewer.User(1L), userPolicy = policy)

        val result = client.users.createMany(viewerContext,
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
                    load(batchPrivacyRule<ReadOnlyEntClient, UserLoadPrivacyItem> { context, batch ->
                        // Deliberately inspect-and-handle the read failure rather
                        // than throwing it. PostgreSQL still marks the transaction
                        // aborted, so the owned boundary confirms rollback.
                        context.client.users.query {
                            where(Predicate.Leaf<User>("missing_column", Op.EQ, 1))
                        }.firstOrNull(context.viewerContext)
                        batch.decideEach { PrivacyDecision.Deny("LOAD dependency unavailable") }
                    })
                }
            }
        }
        val client = freshClient(viewer = Viewer.User(1L), userPolicy = policy)

        val result = client.users.createMany(viewerContext,
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
        val viewerContext = ViewerContext(Viewer.User(1L))
        var launchedNestedMutation = false
        var nestedFailure: EntMutationException? = null
        val client = EntClient(resetAndDriver()) {

            policies { users(createButNoLoad("B")) }
            hooks {
                users {
                    beforeCreate { context ->
                        if (!launchedNestedMutation) {
                            launchedNestedMutation = true
                            val nested = context.client.users.create { }.save(context.viewerContext)
                            nestedFailure = assertIs<MutationResult.Failed>(nested).exception
                        }
                        context
                    }
                }
            }
        }

        val result = client.users.createMany(viewerContext,
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
            inner = tx.users.createMany(viewerContext,
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
