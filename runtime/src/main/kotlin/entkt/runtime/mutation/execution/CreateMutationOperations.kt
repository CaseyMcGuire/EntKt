package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.CreateMutationDraft
import entkt.runtime.rule.EntRuleClient

/** Scalar and bulk CREATE operations assembled from one shared batch implementation. */
@EntktInternal
class CreateMutationOperations<
    Viewer : Any,
    RuleClient : EntRuleClient,
    Draft : CreateMutationDraft<Entity>,
    Entity : EntEntity<*>,
> internal constructor(
    val single: MutationOperation<RuleClient, CreateMutationInput<Viewer, Draft>, Entity>,
    val many: MutationOperation<RuleClient, CreateManyMutationInput<Viewer, Draft>, List<Entity>>,
)
