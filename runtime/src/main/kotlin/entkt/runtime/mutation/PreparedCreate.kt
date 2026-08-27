package entkt.runtime.mutation

import entkt.query.EntktInternal
import entkt.runtime.result.ValidationViolation

/**
 * The normalized handoff produced from one generated create draft.
 *
 * Generated scalar and batch create pipelines share this carrier so defaults,
 * inline field validation, row encoding, and write-candidate construction run
 * exactly once for each draft. Phase adapters create detached privacy and
 * validation views from [candidate] for every reached rule. Lifecycle hooks,
 * rule evaluation, and driver I/O remain outside this type.
 *
 * This is generated-code infrastructure rather than application API. It is
 * public only because generated sources compile in the consuming application
 * module, outside the runtime module's Kotlin `internal` boundary.
 */
@EntktInternal
class PreparedCreate<out Candidate>(
    val values: Map<String, Any?>,
    val candidate: Candidate,
)

/** Result of resolving one generated create draft before lifecycle rules run. */
@EntktInternal
sealed interface CreatePreparation<out Candidate> {
    /** Normalization succeeded with stable storage values and a write candidate. */
    class Ready<Candidate>(
        val value: PreparedCreate<Candidate>,
    ) : CreatePreparation<Candidate>

    /** Required or field-level validation rejected the draft values. */
    class Invalid(violations: List<ValidationViolation>) :
        CreatePreparation<Nothing> {
        val violations: List<ValidationViolation> = violations.toList()

        init {
            require(this.violations.isNotEmpty()) {
                "Invalid create preparation requires at least one violation"
            }
        }
    }
}
