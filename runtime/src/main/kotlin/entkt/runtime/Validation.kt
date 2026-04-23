package entkt.runtime

/**
 * The result of evaluating a single validation rule.
 *
 * - [Valid] — the rule passes.
 * - [Invalid] — the rule fails with a message and optional field/code.
 *
 * Unlike privacy rules, there is no `Continue` — every rule runs
 * regardless of prior results. All [Invalid] results are collected
 * and thrown together as a [ValidationException].
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
 * Thrown when one or more validation rules return [ValidationDecision.Invalid].
 * Contains all violations so consumers can display every problem at once.
 */
class ValidationException(
    val entity: String,
    val violations: List<ValidationDecision.Invalid>,
) : RuntimeException(
    "Validation failed on $entity: ${violations.joinToString("; ") { it.message }}",
)
