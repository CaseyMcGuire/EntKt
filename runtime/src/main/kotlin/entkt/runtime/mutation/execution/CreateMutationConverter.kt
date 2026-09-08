package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.mutation.BeforeCreateHookState
import entkt.runtime.mutation.CreateMutationDraft
import entkt.runtime.mutation.PreparedCreate
import entkt.runtime.mutation.WriteCandidate
import entkt.runtime.result.ValidationViolation

/** Schema-specific hook-state resolution and storage-shape checks; no lifecycle ordering or I/O. */
@EntktInternal
interface CreateMutationConverter<
    Draft : CreateMutationDraft<Entity>,
    Candidate : WriteCandidate<Entity>,
    Entity : EntEntity<*>,
    BeforeCreateState : BeforeCreateHookState<Entity>,
> {
    fun requiredInputViolations(state: BeforeCreateState): List<ValidationViolation>

    /** Resolve fields from [state]; [originalDraft] supplies only the explicit ID, when configured. */
    fun resolve(originalDraft: Draft, state: BeforeCreateState): PreparedCreate<Candidate>

    /** Structural storage checks, such as vector dimensions; configured field rules run separately. */
    fun fieldViolations(candidate: Candidate): List<ValidationViolation>
}
