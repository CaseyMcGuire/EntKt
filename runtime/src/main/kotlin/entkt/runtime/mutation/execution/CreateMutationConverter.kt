package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.CreateMutationDraft
import entkt.runtime.mutation.PreparedCreate
import entkt.runtime.result.ValidationViolation

/** Schema-specific draft resolution and field constraints; no lifecycle ordering or I/O. */
@EntktInternal
interface CreateMutationConverter<Draft : CreateMutationDraft<Entity>, Candidate, Entity : EntEntity<*>> {
    fun requiredInputViolations(draft: Draft): List<ValidationViolation>

    fun resolve(draft: Draft): PreparedCreate<Candidate>

    fun fieldViolations(candidate: Candidate): List<ValidationViolation>
}
