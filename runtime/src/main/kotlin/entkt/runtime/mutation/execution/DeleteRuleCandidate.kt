package entkt.runtime.mutation.execution

import entkt.query.EntktInternal

/** Entity and normalized candidate evaluated together by DELETE rules. */
@EntktInternal
data class DeleteRuleCandidate<Entity, Candidate>(
    val entity: Entity,
    val candidate: Candidate,
)
