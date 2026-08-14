package entkt.runtime.validation

/**
 * The result of evaluating a single validation rule.
 *
 * - [Valid] — the rule passes.
 * - [Invalid] — the rule fails with a message and optional field/code.
 *
 * Unlike privacy rules, there is no `Continue` — every rule runs
 * regardless of prior results. Generated mutation pipelines collect all
 * [Invalid] results into an `EntValidationException` carried by
 * `MutationResult.Failed`.
 */
sealed interface ValidationDecision {
    data object Valid : ValidationDecision
    data class Invalid(
        val message: String,
        val field: String? = null,
        val code: String? = null,
    ) : ValidationDecision
}

/**
 * A single validation rule that evaluates an operation context and
 * returns a [ValidationDecision]. All rules run unconditionally —
 * [ValidationDecision.Invalid] results are collected, not short-circuited.
 */
fun interface ValidationRule<in C> {
    fun validate(ctx: C): ValidationDecision
}

/**
 * Legacy standalone representation of one or more invalid validation
 * decisions. Generated mutation terminals do not throw this type; they return
 * `MutationResult.Failed(EntValidationException)` with the canonical
 * validation payload.
 */
class ValidationException(
    val entity: String,
    val violations: List<ValidationDecision.Invalid>,
) : RuntimeException(
    "Validation failed on $entity: ${violations.joinToString("; ") { it.message }}",
)
