@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.repository

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.entity.EntityDescriptor
import entkt.runtime.mutation.CreateMutationDraft
import entkt.runtime.mutation.PendingCreateMutation
import entkt.runtime.mutation.RelationshipLocking
import entkt.runtime.mutation.UpdateConsistency
import entkt.runtime.mutation.UpdateMutationDraft
import entkt.runtime.mutation.execution.MutationExecutor
import entkt.runtime.query.EntityQueryBuilder

/** CREATE entry point for caller-assigned IDs; intentionally has no no-ID create or batch terminal. */
abstract class ExplicitIdRepository<
    Entity : EntEntity<ID>,
    ID : Any,
    CreateDraft : CreateMutationDraft<Entity>,
    UpdateDraft : UpdateMutationDraft<Entity>,
    Query : EntityQueryBuilder<Entity, Query>,
    RuleClient,
> @EntktInternal protected constructor(
    entity: EntityDescriptor<Entity, ID>,
    mutationExecutor: MutationExecutor,
    defaultUpdateConsistency: UpdateConsistency = UpdateConsistency.ReadCurrent,
    defaultRelationshipLocking: RelationshipLocking = RelationshipLocking.OwnerOnly,
) : EntityRepository<
    Entity, ID, CreateDraft, UpdateDraft, Query, RuleClient,
    ExplicitIdRepository<Entity, ID, CreateDraft, UpdateDraft, Query, RuleClient>,
>(entity, mutationExecutor, defaultUpdateConsistency, defaultRelationshipLocking) {
    final override val self: ExplicitIdRepository<Entity, ID, CreateDraft, UpdateDraft, Query, RuleClient>
        get() = this

    protected abstract fun newCreateDraft(id: ID): CreateDraft

    fun create(id: ID, block: CreateDraft.() -> Unit): PendingCreateMutation<CreateDraft, Entity> =
        pendingCreate(newCreateDraft(id).apply(block))
}
