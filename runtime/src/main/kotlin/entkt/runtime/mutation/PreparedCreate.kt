package entkt.runtime.mutation

import entkt.query.EntktInternal
import entkt.runtime.result.ValidationViolation

/**
 * The normalized handoff produced from one generated create draft.
 *
 * Generated scalar and batch create pipelines share this carrier so defaults,
 * inline field validation, row encoding, and write-candidate construction run
 * exactly once for each draft. Lifecycle hooks, privacy, entity-level
 * validation, and driver I/O are deliberately outside this type and the
 * preparation step that creates it.
 *
 * This is generated-code infrastructure rather than application API. It is
 * public only because generated sources compile in the consuming application
 * module, outside the runtime module's Kotlin `internal` boundary.
 */
@EntktInternal
class PreparedCreate<out C>(
    val values: Map<String, Any?>,
    val candidate: C,
)

/** Result of resolving one generated create draft before lifecycle rules run. */
@EntktInternal
sealed interface CreatePreparation<out C> {
    /** Normalization succeeded with stable storage values and a typed write candidate. */
    class Ready<C>(val value: PreparedCreate<C>) : CreatePreparation<C>

    /** Required or field-level validation rejected the draft values. */
    class Invalid(violations: List<ValidationViolation>) : CreatePreparation<Nothing> {
        val violations: List<ValidationViolation> = violations.toList()

        init {
            require(this.violations.isNotEmpty()) {
                "Invalid create preparation requires at least one violation"
            }
        }
    }
}
