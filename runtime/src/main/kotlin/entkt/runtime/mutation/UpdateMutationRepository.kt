package entkt.runtime.mutation

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.result.MutationResult

/** Per-operation state passed from [UpdateMutation] to an entity repository. */
@EntktInternal
data class UpdateMutationRequest<Draft : UpdateMutationDraft<*>>(
    val id: Any,
    val draft: Draft,
    val consistency: UpdateConsistency,
    val relationshipLocking: RelationshipLocking,
)

/** Repository operation used by the generic [UpdateMutation] wrapper. */
@EntktInternal
fun interface UpdateMutationRepository<Draft : UpdateMutationDraft<Entity>, Entity : EntEntity<*>> {
    /** Execute one update, optionally enforcing LOAD privacy on the returned entity. */
    fun executeUpdate(
        viewerContext: ViewerContext,
        request: UpdateMutationRequest<Draft>,
        applyLoadPrivacy: Boolean,
    ): MutationResult<Entity>
}
