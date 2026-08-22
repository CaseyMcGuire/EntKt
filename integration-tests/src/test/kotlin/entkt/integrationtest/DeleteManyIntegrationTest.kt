package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.EntPrivacyReadClient
import entkt.integrationtest.ent.EntValidationReadClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserCreatePrivacyItem
import entkt.integrationtest.ent.UserDeletePrivacyItem
import entkt.integrationtest.ent.UserDeleteValidationItem
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.query.OrderField
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.hook.batchHook
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.batchPrivacyRule
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.query.ReadOperation
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
import entkt.runtime.validation.ValidationDecision
import entkt.runtime.validation.batchValidationRule
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Focused end-to-end coverage for phase-major `deleteMany`.
 *
 * Candidate selection, DELETE privacy, validation, and before-delete hooks all
 * finish over the ordered logical batch before one ID-returning driver call.
 * The returned IDs are acknowledgements rather than an ordering source:
 * after-delete hooks are restored to candidate encounter order and run only
 * for rows the statement actually removed.
 */
class DeleteManyIntegrationTest : PostgresTestBase() {

    private data class SeededUser(
        val name: String,
        val id: Long,
    )

    private class DeleteProbe {
        val events = mutableListOf<String>()
        val candidatePredicates = mutableListOf<List<Predicate<*>>>()
        val writePredicates = mutableListOf<List<Predicate<*>>>()
        val approvedIdBatches = mutableListOf<List<Any>>()
        val namesById = mutableMapOf<Any, String>()

        var withTransactionCalls = 0
        var candidateQueryCalls = 0
        var deleteManyByIdsCalls = 0
        var scalarDeleteCalls = 0
        var legacyDeleteManyCalls = 0
        var orderCandidatesByDescendingId = true
        var orderReturnedIdsByAscendingId = false
        var beforeDeleteManyByIds: (DatabaseDriver, List<Any>) -> Unit = { _, _ -> }
        var transformReturnedIds: (List<Any>) -> List<Any> = { it }

        fun names(ids: List<Any>): String = ids.joinToString { namesById.getValue(it) }
    }

    /**
     * A transaction-preserving decorator around the real Postgres driver.
     * Transaction children share [probe], so the assertions observe work done
     * by an EntKt-owned or caller-owned transaction without replacing storage
     * semantics with a fake database.
     */
    private class DeleteProbeDriver private constructor(
        private val delegate: DatabaseDriver,
        val probe: DeleteProbe,
    ) : DatabaseDriver by delegate {

        constructor(delegate: DatabaseDriver) : this(delegate, DeleteProbe())

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            val rows = delegate.query(table, predicates, orderBy, limit, offset)
            if (table != User.TABLE) return rows

            probe.candidateQueryCalls++
            probe.candidatePredicates += predicates.toList()
            val ordered = if (probe.orderCandidatesByDescendingId) {
                rows.sortedByDescending { (it.getValue("id") as Number).toLong() }
            } else {
                rows
            }
            for (row in ordered) {
                probe.namesById[checkNotNull(row["id"])] = row.getValue("name") as String
            }
            probe.events += "query:${ordered.joinToString { it.getValue("name") as String }}"
            return ordered
        }

        override fun delete(table: String, id: Any): Boolean {
            probe.scalarDeleteCalls++
            return delegate.delete(table, id)
        }

        override fun deleteMany(table: String, predicates: List<Predicate<*>>): Int {
            probe.legacyDeleteManyCalls++
            return delegate.deleteMany(table, predicates)
        }

        override fun deleteManyByIds(
            table: String,
            idColumn: String,
            ids: List<Any>,
            predicates: List<Predicate<*>>,
        ): List<Any> {
            probe.deleteManyByIdsCalls++
            probe.approvedIdBatches += ids.toList()
            probe.writePredicates += predicates.toList()
            probe.events += "delete:${probe.names(ids)}"
            probe.beforeDeleteManyByIds(delegate, ids)
            val returned = delegate.deleteManyByIds(table, idColumn, ids, predicates)
            val ordered = if (probe.orderReturnedIdsByAscendingId) {
                returned.sortedBy { (it as Number).toLong() }
            } else {
                returned
            }
            return probe.transformReturnedIds(ordered)
        }

        override fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T> {
            probe.withTransactionCalls++
            return delegate.withTransaction { transactionDriver ->
                block(DeleteProbeDriver(transactionDriver, probe))
            }
        }
    }

    private class DefaultFallbackFailure(message: String) : RuntimeException(message)

    /** Forces DatabaseDriver's correctness fallback to stage one per-ID delete, then fail. */
    private class FallbackThenFailDriver private constructor(
        private val delegate: DatabaseDriver,
        private val failure: DefaultFallbackFailure,
        private val deleteManyCalls: IntArray,
    ) : DatabaseDriver by delegate {

        constructor(delegate: DatabaseDriver, failure: DefaultFallbackFailure) :
            this(delegate, failure, intArrayOf(0))

        override fun deleteMany(table: String, predicates: List<Predicate<*>>): Int {
            deleteManyCalls[0]++
            if (deleteManyCalls[0] == 1) return delegate.deleteMany(table, predicates)
            throw failure
        }

        override fun deleteManyByIds(
            table: String,
            idColumn: String,
            ids: List<Any>,
            predicates: List<Predicate<*>>,
        ): List<Any> = super<DatabaseDriver>.deleteManyByIds(table, idColumn, ids, predicates)

        override fun classifyMutationException(
            exception: Exception,
            entity: String,
            operation: EntOperation,
        ): EntMutationException? =
            if (exception === failure) {
                EntConstraintViolationException(
                    entityType = entity,
                    operation = operation,
                    constraint = "fallback_failure",
                    field = null,
                    driverCode = "test",
                    message = failure.message.orEmpty(),
                    cause = failure,
                )
            } else {
                delegate.classifyMutationException(exception, entity, operation)
            }

        override fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T> =
            delegate.withTransaction { transactionDriver ->
                block(FallbackThenFailDriver(transactionDriver, failure, deleteManyCalls))
            }
    }

    private fun policy(block: UserPolicyScope.() -> Unit): EntityPolicy<User, UserPolicyScope> =
        object : EntityPolicy<User, UserPolicyScope> {
            override fun configure(scope: UserPolicyScope) = scope.block()
        }

    private fun seedUsers(driver: DatabaseDriver, vararg names: String): List<SeededUser> =
        names.map { name ->
            val row = driver.insert(
                User.TABLE,
                mapOf(
                    "name" to name,
                    "email" to "${name.lowercase()}@example.com",
                ),
            )
            SeededUser(name, (row.getValue("id") as Number).toLong())
        }

    private fun assertMalformedReturnedIdsRollBack(
        transform: (List<Any>) -> List<Any>,
    ) {
        val storage = resetAndDriver()
        seedUsers(storage, "A", "B", "C")
        val driver = DeleteProbeDriver(storage)
        driver.probe.transformReturnedIds = transform
        var afterDeleteCalls = 0
        val configuredPolicy = policy {
            privacy {
                delete(batchPrivacyRule<EntPrivacyReadClient, UserDeletePrivacyItem> { _, batch ->
                    batch.decideEach { PrivacyDecision.Allow }
                })
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(7L)) }
            policies { users(configuredPolicy) }
            hooks { users { afterDelete { afterDeleteCalls++ } } }
        }

        val result = client.users.deleteMany()

        val failed = assertIs<MutationResult.Failed>(result)
        val rolledBack = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, rolledBack.writeState)
        assertIs<IllegalStateException>(rolledBack.cause)
        assertEquals(0, afterDeleteCalls)
        assertEquals(1, driver.probe.deleteManyByIdsCalls)
        assertEquals(3L, storage.count(User.TABLE, emptyList()))
    }

    @Test
    fun `deleteMany runs each lifecycle phase once over candidate order then uses one set-based write`() {
        val storage = resetAndDriver()
        val seeded = seedUsers(storage, "A", "B", "C")
        val driver = DeleteProbeDriver(storage)
        driver.probe.orderReturnedIdsByAscendingId = true
        val callerPredicate = User.name neq "caller-filtered"
        val interceptorPredicate = User.email neq "interceptor-filtered@example.com"
        val capturedPrivacy = PrivacyContext(Viewer.User(7L))
        var privacyCaptures = 0
        var interceptorCalls = 0
        val configuredPolicy = policy {
            privacy {
                delete(batchPrivacyRule<EntPrivacyReadClient, UserDeletePrivacyItem> { context, batch ->
                    assertEquals(0, driver.probe.deleteManyByIdsCalls)
                    assertSame(capturedPrivacy, context.privacy)
                    driver.probe.events +=
                        "privacy:${batch.joinToString { it.entity.name }}"
                    batch.decideEach { PrivacyDecision.Allow }
                })
            }
            validation {
                delete(batchValidationRule<EntValidationReadClient, UserDeleteValidationItem> { _, batch ->
                    assertEquals(0, driver.probe.deleteManyByIdsCalls)
                    driver.probe.events +=
                        "validation:${batch.joinToString { it.entity.name }}"
                    batch.decideEach { ValidationDecision.Valid }
                })
            }
        }
        val client = EntClient(driver) {
            privacyContext {
                privacyCaptures++
                driver.probe.events += "capturePrivacy"
                capturedPrivacy
            }
            policies { users(configuredPolicy) }
            interceptors {
                users(
                    QueryInterceptor { scope, context ->
                        interceptorCalls++
                        assertEquals(ReadOperation.DELETE_CANDIDATES, context.operation)
                        driver.probe.events += "interceptor"
                        scope.addPredicate(interceptorPredicate)
                    },
                    name = "delete-scope",
                )
            }
            hooks {
                users {
                    beforeDelete(batchHook<User> { entities ->
                        assertEquals(0, driver.probe.deleteManyByIdsCalls)
                        driver.probe.events +=
                            "beforeDelete:${entities.joinToString { it.name }}"
                    })
                    afterDelete(batchHook<User> { entities ->
                        assertEquals(1, driver.probe.deleteManyByIdsCalls)
                        driver.probe.events +=
                            "afterDelete:${entities.joinToString { it.name }}"
                    })
                }
            }
        }

        val result = client.users.deleteMany(callerPredicate)

        assertEquals(MutationResult.Success(3), result)
        assertEquals(
            listOf(
                "capturePrivacy",
                "interceptor",
                "query:C, B, A",
                "privacy:C, B, A",
                "validation:C, B, A",
                "beforeDelete:C, B, A",
                "delete:C, B, A",
                // The probe deliberately returns A, B, C. Generated code must
                // restore the preflight encounter order before after-hooks.
                "afterDelete:C, B, A",
            ),
            driver.probe.events,
        )
        assertEquals(1, privacyCaptures)
        assertEquals(1, interceptorCalls)
        assertEquals(1, driver.probe.withTransactionCalls)
        assertEquals(1, driver.probe.candidateQueryCalls)
        assertEquals(1, driver.probe.deleteManyByIdsCalls)
        assertEquals(0, driver.probe.scalarDeleteCalls)
        assertEquals(0, driver.probe.legacyDeleteManyCalls)
        assertEquals(seeded.map { it.id }.reversed(), driver.probe.approvedIdBatches.single())
        assertEquals(driver.probe.candidatePredicates.single(), driver.probe.writePredicates.single())
        assertEquals(
            listOf(callerPredicate, interceptorPredicate),
            driver.probe.writePredicates.single(),
        )
        assertEquals(0L, storage.count(User.TABLE, emptyList()))
    }

    @Test
    fun `empty candidate query skips every lifecycle callback and delete statement`() {
        val storage = resetAndDriver()
        val driver = DeleteProbeDriver(storage)
        var privacyCaptures = 0
        var interceptorCalls = 0
        var privacyCalls = 0
        var validationCalls = 0
        var hookCalls = 0
        val configuredPolicy = policy {
            privacy {
                delete(batchPrivacyRule<EntPrivacyReadClient, UserDeletePrivacyItem> { _, batch ->
                    privacyCalls++
                    batch.decideEach { PrivacyDecision.Allow }
                })
            }
            validation {
                delete(batchValidationRule<EntValidationReadClient, UserDeleteValidationItem> { _, batch ->
                    validationCalls++
                    batch.decideEach { ValidationDecision.Valid }
                })
            }
        }
        val client = EntClient(driver) {
            privacyContext {
                privacyCaptures++
                PrivacyContext(Viewer.User(7L))
            }
            policies { users(configuredPolicy) }
            interceptors {
                users(
                    QueryInterceptor { _, context ->
                        interceptorCalls++
                        assertEquals(ReadOperation.DELETE_CANDIDATES, context.operation)
                    },
                    name = "observe-empty-delete",
                )
            }
            hooks {
                users {
                    beforeDelete { hookCalls++ }
                    afterDelete { hookCalls++ }
                }
            }
        }

        assertEquals(MutationResult.Success(0), client.users.deleteMany())
        assertEquals(1, driver.probe.withTransactionCalls)
        assertEquals(1, privacyCaptures)
        assertEquals(1, interceptorCalls)
        assertEquals(1, driver.probe.candidateQueryCalls)
        assertEquals(0, privacyCalls)
        assertEquals(0, validationCalls)
        assertEquals(0, hookCalls)
        assertEquals(0, driver.probe.deleteManyByIdsCalls)
        assertEquals(0, driver.probe.scalarDeleteCalls)
        assertEquals(0, driver.probe.legacyDeleteManyCalls)
    }

    @Test
    fun `first privacy denial follows candidate encounter order and prevents every delete`() {
        val storage = resetAndDriver()
        val seeded = seedUsers(storage, "A", "B", "C")
        val driver = DeleteProbeDriver(storage)
        val privacyBatches = mutableListOf<List<String>>()
        var validationCalls = 0
        var beforeDeleteCalls = 0
        val configuredPolicy = policy {
            privacy {
                delete(batchPrivacyRule<EntPrivacyReadClient, UserDeletePrivacyItem> { _, batch ->
                    privacyBatches += batch.map { it.entity.name }
                    batch.decideEach { item ->
                        when (item.entity.name) {
                            "B", "C" -> PrivacyDecision.Deny("${item.entity.name} blocked")
                            else -> PrivacyDecision.Allow
                        }
                    }
                })
            }
            validation {
                delete(batchValidationRule<EntValidationReadClient, UserDeleteValidationItem> { _, batch ->
                    validationCalls++
                    batch.decideEach { ValidationDecision.Invalid("should not run") }
                })
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(7L)) }
            policies { users(configuredPolicy) }
            hooks { users { beforeDelete { beforeDeleteCalls++ } } }
        }

        val result = client.users.deleteMany()

        val failed = assertIs<MutationResult.Failed>(result)
        val denial = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, denial.writeState)
        assertEquals(EntOperation.DELETE, denial.operation)
        assertEquals("C blocked", denial.reason)
        assertEquals("id", denial.entityKey?.field)
        assertEquals(seeded.single { it.name == "C" }.id, denial.entityKey?.value)
        assertEquals(listOf(listOf("C", "B", "A")), privacyBatches)
        assertEquals(0, validationCalls)
        assertEquals(0, beforeDeleteCalls)
        assertEquals(0, driver.probe.deleteManyByIdsCalls)
        assertEquals(0, driver.probe.scalarDeleteCalls)
        assertEquals(0, driver.probe.legacyDeleteManyCalls)
        assertEquals(3L, storage.count(User.TABLE, emptyList()))
    }

    @Test
    fun `derived CREATE privacy receives only unresolved DELETE candidates and preserves indexes`() {
        val storage = resetAndDriver()
        val seeded = seedUsers(storage, "A", "B", "C")
        val driver = DeleteProbeDriver(storage)
        val deleteBatches = mutableListOf<List<String>>()
        val derivedCreateBatches = mutableListOf<List<String>>()
        val configuredPolicy = policy {
            privacy {
                delete(batchPrivacyRule<EntPrivacyReadClient, UserDeletePrivacyItem> { _, batch ->
                    deleteBatches += batch.map { it.entity.name }
                    batch.decideEach { item ->
                        when (item.entity.name) {
                            "A" -> PrivacyDecision.Allow
                            "B" -> PrivacyDecision.Deny("B blocked")
                            else -> PrivacyDecision.Continue
                        }
                    }
                })
                create(batchPrivacyRule<EntPrivacyReadClient, UserCreatePrivacyItem> { _, batch ->
                    derivedCreateBatches += batch.map { it.candidate.name }
                    batch.decideEach { PrivacyDecision.Allow }
                })
                deleteDerivesFromCreate()
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(7L)) }
            policies { users(configuredPolicy) }
        }

        val result = client.users.deleteMany()

        val failed = assertIs<MutationResult.Failed>(result)
        val denial = assertIs<EntMutationPrivacyDeniedException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, denial.writeState)
        assertEquals("B blocked", denial.reason)
        assertEquals(seeded.single { it.name == "B" }.id, denial.entityKey?.value)
        assertEquals(listOf(listOf("C", "B", "A")), deleteBatches)
        assertEquals(listOf(listOf("C")), derivedCreateBatches)
        assertEquals(0, driver.probe.deleteManyByIdsCalls)
        assertEquals(3L, storage.count(User.TABLE, emptyList()))
    }

    @Test
    fun `first validation failure follows candidate encounter order and prevents the write`() {
        val storage = resetAndDriver()
        seedUsers(storage, "A", "B", "C")
        val driver = DeleteProbeDriver(storage)
        val privacyBatches = mutableListOf<List<String>>()
        val validationBatches = mutableListOf<List<String>>()
        var beforeDeleteCalls = 0
        val configuredPolicy = policy {
            privacy {
                delete(batchPrivacyRule<EntPrivacyReadClient, UserDeletePrivacyItem> { _, batch ->
                    privacyBatches += batch.map { it.entity.name }
                    batch.decideEach { PrivacyDecision.Allow }
                })
            }
            validation {
                delete(batchValidationRule<EntValidationReadClient, UserDeleteValidationItem> { _, batch ->
                    validationBatches += batch.map { it.entity.name }
                    batch.decideEach { item ->
                        if (item.entity.name == "A") ValidationDecision.Valid
                        else ValidationDecision.Invalid("${item.entity.name} invalid", field = "name")
                    }
                })
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(7L)) }
            policies { users(configuredPolicy) }
            hooks { users { beforeDelete { beforeDeleteCalls++ } } }
        }

        val result = client.users.deleteMany()

        val failed = assertIs<MutationResult.Failed>(result)
        val validation = assertIs<EntValidationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, validation.writeState)
        assertEquals(EntOperation.DELETE, validation.operation)
        assertEquals("C invalid", validation.violations.single().message)
        assertEquals("name", validation.violations.single().field)
        assertEquals(listOf(listOf("C", "B", "A")), privacyBatches)
        assertEquals(listOf(listOf("C", "B", "A")), validationBatches)
        assertEquals(0, beforeDeleteCalls)
        assertEquals(0, driver.probe.deleteManyByIdsCalls)
        assertEquals(0, driver.probe.scalarDeleteCalls)
        assertEquals(0, driver.probe.legacyDeleteManyCalls)
        assertEquals(3L, storage.count(User.TABLE, emptyList()))
    }

    @Test
    fun `afterDelete failure in a caller transaction is TransactionPending and marks rollback-only`() {
        val storage = resetAndDriver()
        seedUsers(storage, "A", "B", "C")
        val driver = DeleteProbeDriver(storage)
        val thrown = IllegalStateException("afterDelete failed")
        val afterBatches = mutableListOf<List<String>>()
        val configuredPolicy = policy {
            privacy {
                delete(batchPrivacyRule<EntPrivacyReadClient, UserDeletePrivacyItem> { _, batch ->
                    batch.decideEach { PrivacyDecision.Allow }
                })
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(7L)) }
            policies { users(configuredPolicy) }
            hooks {
                users {
                    afterDelete(batchHook<User> { entities ->
                        afterBatches += entities.map { it.name }
                        throw thrown
                    })
                }
            }
        }

        var inner: MutationResult<Int>? = null
        val transaction = client.withTransaction { tx ->
            inner = tx.users.deleteMany()
            "ignored failure"
        }

        val innerFailed = assertIs<MutationResult.Failed>(inner)
        val pending = assertIs<EntUnexpectedMutationException>(innerFailed.exception)
        assertEquals(MutationWriteState.TransactionPending, pending.writeState)
        assertSame(thrown, pending.cause)
        val transactionFailed = assertIs<TransactionResult.Failed>(transaction)
        assertSame(pending, transactionFailed.exception)
        assertEquals(TransactionFailureState.NotCommitted, transactionFailed.transactionState)
        assertEquals(listOf(listOf("C", "B", "A")), afterBatches)
        assertEquals(1, driver.probe.deleteManyByIdsCalls)
        assertEquals(3L, storage.count(User.TABLE, emptyList()))
    }

    @Test
    fun `classified default-fallback failure after one staged ID is TransactionPending`() {
        val storage = resetAndDriver()
        seedUsers(storage, "A", "B", "C")
        val failure = DefaultFallbackFailure("second fallback statement failed")
        val driver = FallbackThenFailDriver(storage, failure)
        val configuredPolicy = policy {
            privacy {
                delete(batchPrivacyRule<EntPrivacyReadClient, UserDeletePrivacyItem> { _, batch ->
                    batch.decideEach { PrivacyDecision.Allow }
                })
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(7L)) }
            policies { users(configuredPolicy) }
        }

        var inner: MutationResult<Int>? = null
        val transaction = client.withTransaction { tx ->
            inner = tx.users.deleteMany()
            "ignored failure"
        }

        val innerFailed = assertIs<MutationResult.Failed>(inner)
        val pending = assertIs<EntUnexpectedMutationException>(innerFailed.exception)
        assertEquals(MutationWriteState.TransactionPending, pending.writeState)
        val constraint = assertIs<EntConstraintViolationException>(pending.cause)
        assertEquals(MutationWriteState.NotPersisted, constraint.writeState)
        assertSame(failure, constraint.cause)
        val transactionFailed = assertIs<TransactionResult.Failed>(transaction)
        assertSame(pending, transactionFailed.exception)
        assertEquals(TransactionFailureState.NotCommitted, transactionFailed.transactionState)
        assertEquals(3L, storage.count(User.TABLE, emptyList()))
    }

    @Test
    fun `classified default-fallback failure is typed NotPersisted after owned rollback`() {
        val storage = resetAndDriver()
        seedUsers(storage, "A", "B", "C")
        val failure = DefaultFallbackFailure("second fallback statement failed")
        val driver = FallbackThenFailDriver(storage, failure)
        val configuredPolicy = policy {
            privacy {
                delete(batchPrivacyRule<EntPrivacyReadClient, UserDeletePrivacyItem> { _, batch ->
                    batch.decideEach { PrivacyDecision.Allow }
                })
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(7L)) }
            policies { users(configuredPolicy) }
        }

        val failed = assertIs<MutationResult.Failed>(client.users.deleteMany())
        val constraint = assertIs<EntConstraintViolationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, constraint.writeState)
        assertSame(failure, constraint.cause)
        assertEquals(3L, storage.count(User.TABLE, emptyList()))
    }

    @Test
    fun `afterDelete failure in an EntKt-owned transaction is NotPersisted after rollback`() {
        val storage = resetAndDriver()
        seedUsers(storage, "A", "B", "C")
        val driver = DeleteProbeDriver(storage)
        val thrown = IllegalStateException("afterDelete failed")
        val configuredPolicy = policy {
            privacy {
                delete(batchPrivacyRule<EntPrivacyReadClient, UserDeletePrivacyItem> { _, batch ->
                    batch.decideEach { PrivacyDecision.Allow }
                })
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(7L)) }
            policies { users(configuredPolicy) }
            hooks {
                users {
                    afterDelete(batchHook<User> { throw thrown })
                }
            }
        }

        val result = client.users.deleteMany()

        val failed = assertIs<MutationResult.Failed>(result)
        val rolledBack = assertIs<EntUnexpectedMutationException>(failed.exception)
        assertEquals(MutationWriteState.NotPersisted, rolledBack.writeState)
        assertSame(thrown, rolledBack.cause)
        assertEquals(1, driver.probe.withTransactionCalls)
        assertEquals(1, driver.probe.deleteManyByIdsCalls)
        assertEquals(3L, storage.count(User.TABLE, emptyList()))
    }

    @Test
    fun `duplicate returned id fails before afterDelete and the owned transaction rolls back`() {
        assertMalformedReturnedIdsRollBack { ids -> ids + ids.first() }
    }

    @Test
    fun `unapproved returned id fails before afterDelete and the owned transaction rolls back`() {
        assertMalformedReturnedIdsRollBack { ids -> ids + Long.MIN_VALUE }
    }

    @Test
    fun `candidate disappearance reduces the count and omits only that candidate from afterDelete`() {
        val storage = resetAndDriver()
        val seeded = seedUsers(storage, "A", "B", "C")
        val driver = DeleteProbeDriver(storage)
        val disappearedId = seeded.single { it.name == "B" }.id
        driver.probe.beforeDeleteManyByIds = { transactionDriver, _ ->
            // Simulate a concurrent disappearance at the driver boundary,
            // after preflight and before the returning delete statement.
            check(transactionDriver.delete(User.TABLE, disappearedId))
        }
        val beforeBatches = mutableListOf<List<String>>()
        val afterBatches = mutableListOf<List<String>>()
        val configuredPolicy = policy {
            privacy {
                delete(batchPrivacyRule<EntPrivacyReadClient, UserDeletePrivacyItem> { _, batch ->
                    batch.decideEach { PrivacyDecision.Allow }
                })
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(7L)) }
            policies { users(configuredPolicy) }
            hooks {
                users {
                    beforeDelete(batchHook<User> { beforeBatches += it.map(User::name) })
                    afterDelete(batchHook<User> { afterBatches += it.map(User::name) })
                }
            }
        }

        val result = client.users.deleteMany()

        assertEquals(MutationResult.Success(2), result)
        assertEquals(listOf(listOf("C", "B", "A")), beforeBatches)
        assertEquals(listOf(listOf("C", "A")), afterBatches)
        assertEquals(1, driver.probe.deleteManyByIdsCalls)
        assertEquals(0L, storage.count(User.TABLE, emptyList()))
    }

    @Test
    fun `frozen caller predicate is reasserted when a candidate stops matching before deletion`() {
        val storage = resetAndDriver()
        val seeded = seedUsers(storage, "A", "B", "C")
        val driver = DeleteProbeDriver(storage)
        val escapedId = seeded.single { it.name == "B" }.id
        driver.probe.beforeDeleteManyByIds = { transactionDriver, _ ->
            checkNotNull(transactionDriver.update(User.TABLE, escapedId, mapOf("name" to "escaped")))
        }
        val afterBatches = mutableListOf<List<String>>()
        val configuredPolicy = policy {
            privacy {
                delete(batchPrivacyRule<EntPrivacyReadClient, UserDeletePrivacyItem> { _, batch ->
                    batch.decideEach { PrivacyDecision.Allow }
                })
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(7L)) }
            policies { users(configuredPolicy) }
            hooks {
                users {
                    afterDelete(batchHook<User> { afterBatches += it.map(User::name) })
                }
            }
        }
        val predicate = User.name neq "escaped"

        val result = client.users.deleteMany(predicate)

        assertEquals(MutationResult.Success(2), result)
        assertEquals(listOf(listOf("C", "A")), afterBatches)
        assertEquals(driver.probe.candidatePredicates.single(), driver.probe.writePredicates.single())
        assertEquals(listOf(predicate), driver.probe.writePredicates.single())
        val survivor = storage.query(User.TABLE, emptyList(), emptyList(), null, null).single()
        assertEquals(escapedId, survivor.getValue("id"))
        assertEquals("escaped", survivor.getValue("name"))
    }

    @Test
    fun `frozen caller predicate detaches a mutable Number before delete hooks run`() {
        val storage = resetAndDriver()
        seedUsers(storage, "A", "B", "C")
        val driver = DeleteProbeDriver(storage).also {
            it.probe.orderCandidatesByDescendingId = false
        }
        val threshold = AtomicLong(2L)
        val configuredPolicy = policy {
            privacy {
                delete(batchPrivacyRule<EntPrivacyReadClient, UserDeletePrivacyItem> { _, batch ->
                    batch.decideEach { PrivacyDecision.Allow }
                })
            }
        }
        val client = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.User(7L)) }
            policies { users(configuredPolicy) }
            hooks {
                users {
                    beforeDelete { threshold.set(3L) }
                }
            }
        }

        val result = client.users.deleteMany(Predicate.Leaf("id", Op.GTE, threshold))

        assertEquals(MutationResult.Success(2), result)
        val candidateValue = (driver.probe.candidatePredicates.single().single() as Predicate.Leaf).value
        val writeValue = (driver.probe.writePredicates.single().single() as Predicate.Leaf).value
        assertEquals(BigDecimal("2"), candidateValue)
        assertEquals(BigDecimal("2"), writeValue)
        assertEquals(listOf("A"), storage.query(User.TABLE, emptyList(), emptyList(), null, null).map { it["name"] })
    }
}
