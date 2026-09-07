package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.mutation.WriteCandidate
import entkt.runtime.result.ValidationViolation

/** Result of generated update preparation before privacy and entity validation. */
@EntktInternal
sealed interface UpdatePreparation<out State, out Candidate : WriteCandidate<*>> {
    data class Ready<State, Candidate : WriteCandidate<*>>(
        val value: PreparedUpdate<State, Candidate>,
    ) : UpdatePreparation<State, Candidate>

    data class Invalid(val violations: List<ValidationViolation>) : UpdatePreparation<Nothing, Nothing> {
        init {
            require(violations.isNotEmpty()) {
                "an invalid update preparation requires at least one violation"
            }
        }
    }
}
