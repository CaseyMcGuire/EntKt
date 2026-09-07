@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.repository

import entkt.query.EntktInternal
import entkt.query.Op
import entkt.query.Predicate
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityDescriptor
import entkt.runtime.mutation.CreateMutationDraft
import entkt.runtime.mutation.CreateMutationRepository
import entkt.runtime.mutation.PendingCreateMutation
import entkt.runtime.mutation.PendingUpdateMutation
import entkt.runtime.mutation.RelationshipLocking
import entkt.runtime.mutation.UpdateConsistency
import entkt.runtime.mutation.UpdateMutationDraft
import entkt.runtime.mutation.UpdateMutationRepository
import entkt.runtime.mutation.UpdateMutationRequest
import entkt.runtime.mutation.execution.CreateMutationInput
import entkt.runtime.mutation.execution.CreateMutationOperations
import entkt.runtime.mutation.execution.DeleteManyMutationInput
import entkt.runtime.mutation.execution.DeleteMutationInput
import entkt.runtime.mutation.execution.MutationExecutor
import entkt.runtime.mutation.execution.MutationOperation
import entkt.runtime.mutation.execution.MutationRuntime
import entkt.runtime.mutation.execution.UpdateMutationInput
import entkt.runtime.privacy.BatchPrivacyRule
import entkt.runtime.privacy.LoadPrivacyEvaluator
import entkt.runtime.privacy.PrivacyEvaluation
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.query.EntityQueryBuilder
import entkt.runtime.query.ReadOperation
import entkt.runtime.result.MutationResult
import entkt.runtime.result.ReadResult
import entkt.runtime.result.TransactionResult
import entkt.runtime.result.TransactionScope
import entkt.runtime.rule.EntRuleClient

/**
 * Shared entry-point behavior for generated writable repositories.
 *
 * Subclasses inject completed operations and supply schema-specific construction functions.
 * [MutationExecutor] still owns transaction policy and failure handling; operations own mutation
 * algorithms. Dependencies are never read through overridable members during construction.
 *
 * [Self] preserves the repository's CREATE capabilities when selecting its transaction-bound twin.
 * The two ID-specific bases supply it so generated repositories do not need a recursive type argument.
 */
abstract class EntityRepository<
    Entity : EntEntity<ID>,
    ID : Any,
    CreateDraft : CreateMutationDraft<Entity>,
    UpdateDraft : UpdateMutationDraft<Entity>,
    Query : EntityQueryBuilder<Entity, Query>,
    RuleClient : EntRuleClient,
    Self : EntityRepository<Entity, ID, CreateDraft, UpdateDraft, Query, RuleClient, Self>,
> @EntktInternal protected constructor(
    private val entity: EntityDescriptor<Entity, ID>,
    driver: DatabaseDriver,
    mutationRuntime: MutationRuntime,
    loadPrivacyRules: List<BatchPrivacyRule<RuleClient, Entity>>,
    protected val createOperations: CreateMutationOperations<RuleClient, CreateDraft, Entity>,
    protected val updateOperation: MutationOperation<RuleClient, UpdateMutationInput<UpdateDraft>, Entity>,
    protected val deleteOperation: MutationOperation<RuleClient, DeleteMutationInput, Boolean>,
    protected val deleteManyOperation: MutationOperation<RuleClient, DeleteManyMutationInput<Entity>, Int>,
    private val defaultUpdateConsistency: UpdateConsistency = UpdateConsistency.ReadCurrent,
    private val defaultRelationshipLocking: RelationshipLocking = RelationshipLocking.OwnerOnly,
) {
    init {
        driver.register(entity.schema)
    }

    private val mutationExecutor = MutationExecutor(driver, mutationRuntime)
    private val loadPrivacyEvaluator = LoadPrivacyEvaluator(entity, loadPrivacyRules)

    protected abstract val self: Self

    /**
     * Resolve only when an entry point is called, after client/repository construction is complete.
     *
     * The read-only client currently delegates LOAD privacy to the client's repositories, so
     * constructor injection would resolve it before those repositories are initialized. Generated
     * subclasses use a getter to defer access until the repository graph and transaction scope
     * are ready.
     */
    protected abstract val ruleClient: RuleClient

    protected abstract fun newQuery(): Query

    protected abstract fun newUpdateDraft(): UpdateDraft

    /**
     * Bind the block to this repository's counterpart inside the client's transaction boundary.
     * Generated wiring only selects that counterpart; the base supplies execution and rollback.
     * The executor calls this only when an operation needs an owned transaction.
     */
    protected abstract fun <Result> withTransaction(
        block: TransactionScope.(Self) -> Result,
    ): TransactionResult<Result>

    fun query(block: Query.() -> Unit = {}): Query = newQuery().apply(block)

    fun findById(viewerContext: ViewerContext, id: ID): ReadResult<Entity?> {
        val result = newQuery().readRootQuery(
            viewerContext = viewerContext,
            operation = ReadOperation.BY_ID,
            maximumRows = 1,
            structuralPredicates = listOf(Predicate.Leaf<Entity>(entity.idColumn, Op.EQ, id)),
        )
        return when (result) {
            is ReadResult.Success -> ReadResult.Success(result.value.firstOrNull())
            is ReadResult.Failed -> result
        }
    }

    fun update(
        id: ID,
        consistency: UpdateConsistency = defaultUpdateConsistency,
        relationshipLocking: RelationshipLocking = defaultRelationshipLocking,
        block: UpdateDraft.() -> Unit,
    ): PendingUpdateMutation<UpdateDraft, Entity> {
        val draft = newUpdateDraft().apply(block)
        val request = UpdateMutationRequest(id, draft, consistency, relationshipLocking)
        val executor = mutationExecutor
        val operation = updateOperation
        val client = ruleClient
        val execution = UpdateMutationRepository<UpdateDraft, Entity> { context, input, applyLoadPrivacy ->
            executor.execute(
                operation = operation,
                ruleClient = client,
                input = UpdateMutationInput(context, input, applyLoadPrivacy),
            )
        }
        return PendingUpdateMutation(request, execution)
    }

    fun delete(viewerContext: ViewerContext, entity: Entity): MutationResult<Unit> =
        deleteById(viewerContext, entity.id).withoutValue()

    fun deleteById(viewerContext: ViewerContext, id: ID): MutationResult<Boolean> =
        mutationExecutor.execute(
            operation = deleteOperation,
            ruleClient = ruleClient,
            input = DeleteMutationInput(viewerContext, id),
        )

    fun deleteMany(viewerContext: ViewerContext, vararg predicates: Predicate<Entity>): MutationResult<Int> =
        executeMutation(
            input = DeleteManyMutationInput(viewerContext, predicates.asList()),
            operation = { it.deleteManyOperation },
        )

    @EntktInternal
    fun hasLoadPrivacy(): Boolean = true

    @EntktInternal
    fun evaluateLoadPrivacy(viewerContext: ViewerContext, entities: List<Entity>): PrivacyEvaluation<Entity> =
        loadPrivacyEvaluator.evaluate(PrivacyRuleContext(viewerContext, ruleClient), entities)

    /** Bind immutable execution dependencies now; saving does not call back into this repository. */
    protected fun pendingCreate(draft: CreateDraft): PendingCreateMutation<CreateDraft, Entity> {
        val executor = mutationExecutor
        val operation = createOperations.single
        val client = ruleClient
        val execution = object : CreateMutationRepository<CreateDraft, Entity> {
            override fun saveCreation(viewerContext: ViewerContext, draft: CreateDraft): MutationResult<Unit> =
                executor.execute(
                    operation = operation,
                    ruleClient = client,
                    input = CreateMutationInput(viewerContext, draft, checkReturnedEntityPrivacy = false),
                ).withoutValue()

            override fun saveAndLoadCreation(viewerContext: ViewerContext, draft: CreateDraft): MutationResult<Entity> =
                executor.execute(
                    operation = operation,
                    ruleClient = client,
                    input = CreateMutationInput(viewerContext, draft, checkReturnedEntityPrivacy = true),
                )
        }
        return PendingCreateMutation(draft, execution)
    }

    /** Select the operation again on the transaction-bound repository, never reuse the root binding. */
    protected fun <Input, Result> executeMutation(
        input: Input,
        operation: (Self) -> MutationOperation<RuleClient, Input, Result>,
    ): MutationResult<Result> = mutationExecutor.execute(
        operation = operation(self),
        ruleClient = ruleClient,
        input = input,
        ownedTransaction = { transactionInput, completionCapture ->
            withTransaction { transactionRepository ->
                val bound: EntityRepository<Entity, ID, CreateDraft, UpdateDraft, Query, RuleClient, Self> =
                    transactionRepository
                bound.mutationExecutor.executeInOwnedTransactionForInternalUse(
                    operation = operation(transactionRepository),
                    ruleClient = bound.ruleClient,
                    input = transactionInput,
                    completionCapture = completionCapture,
                ).orRollback()
            }
        },
    )
}
