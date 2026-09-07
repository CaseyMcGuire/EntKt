package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.CreateMutationDraft

/** Scalar and bulk CREATE operations assembled from one shared batch implementation. */
@EntktInternal
class CreateMutationOperations<
    RuleClient,
    Draft : CreateMutationDraft<Entity>,
    Entity : EntEntity<*>,
> internal constructor(
    val single: MutationOperation<RuleClient, CreateMutationInput<Draft>, Entity>,
    val many: MutationOperation<RuleClient, CreateManyMutationInput<Draft>, List<Entity>>,
)
