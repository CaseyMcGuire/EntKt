package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.result.ValidationViolation

/** Result of generated update preparation before privacy and entity validation. */
@EntktInternal
sealed interface UpdatePreparation<out State> {
    data class Ready<State>(val value: PreparedUpdate<State>) : UpdatePreparation<State>
    data class Invalid(val violations: List<ValidationViolation>) : UpdatePreparation<Nothing> {
        init {
            require(violations.isNotEmpty()) {
                "an invalid update preparation requires at least one violation"
            }
        }
    }
}
