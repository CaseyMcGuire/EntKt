package entkt.runtime.mutation.execution

import entkt.query.EntktInternal
import entkt.runtime.result.PrivacyDenial

/**
 * An operation has finished its lifecycle, but its enclosing transaction may not have finished.
 *
 * Return failures concern only disclosure of the result. Preparation, hook, validation, and
 * storage failures reject or throw instead, so an owned transaction rolls them back. A return
 * failure must not mark an owned transaction rollback-only before commit is attempted.
 */
@EntktInternal
sealed interface MutationCompletion<out Result> {
    data class Ready<Result>(val value: Result) : MutationCompletion<Result>

    data class ReturnDenied(val denial: PrivacyDenial) : MutationCompletion<Nothing>

    data class ReturnFailed(val cause: Exception) : MutationCompletion<Nothing>
}
