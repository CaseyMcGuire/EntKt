package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.WriteCandidate

/** Convert a loaded entity into the schema-specific candidate used by DELETE rules. */
@EntktInternal
interface DeleteMutationConverter<Entity : EntEntity<*>, Candidate : WriteCandidate<Entity>> {
    fun toCandidate(entity: Entity): Candidate
}
