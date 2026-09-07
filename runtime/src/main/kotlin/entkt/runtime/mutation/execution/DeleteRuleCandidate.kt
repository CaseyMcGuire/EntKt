package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.WriteCandidate

/** Entity and normalized candidate evaluated together by DELETE rules. */
@EntktInternal
data class DeleteRuleCandidate<Entity : EntEntity<*>, Candidate : WriteCandidate<Entity>>(
    val entity: Entity,
    val candidate: Candidate,
)
