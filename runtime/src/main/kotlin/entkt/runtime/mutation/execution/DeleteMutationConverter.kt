package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity

/** Convert a loaded entity into the schema-specific candidate used by DELETE rules. */
@EntktInternal
interface DeleteMutationConverter<Entity : EntEntity<*>, Candidate> {
    fun toCandidate(entity: Entity): Candidate
}
