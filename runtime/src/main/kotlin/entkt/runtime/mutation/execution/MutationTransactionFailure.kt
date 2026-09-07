@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime.mutation.execution

import entkt.runtime.result.EntMutationException
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntOperation
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.PrivacyDenial
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult

/** Failure precedence shared by mutations that own their transaction. */
internal object MutationTransactionFailure {
    /** Reclassify a failed owned transaction, preserving any returned LOAD failure. */
    fun classify(
        txResult: TransactionResult.Failed,
        completionCapture: MutationCompletionCapture? = null,
    ): EntMutationException {
        val stored = txResult.exception
        val disclosure = completionCapture?.failure
        val denial = completionCapture?.denial
        val exception = when {
            txResult.transactionState == TransactionFailureState.OutcomeUnknown ->
                EntUnexpectedMutationException(
                    MutationWriteState.PersistenceUnknown,
                    stored,
                ).also { unknown ->
                    denial?.let {
                        unknown.addSuppressed(
                            loadDenialException(
                                writeState = MutationWriteState.PersistenceUnknown,
                                denial = it,
                            ),
                        )
                    }
                    if (disclosure != null && disclosure !== stored) {
                        unknown.addSuppressed(disclosure)
                    }
                }

            stored is EntMutationException -> {
                val primary = if (stored.writeState == MutationWriteState.NotPersisted) {
                    stored
                } else {
                    EntUnexpectedMutationException(
                        MutationWriteState.NotPersisted,
                        unexpectedCauseOrSelf(stored),
                    )
                }
                if (primary !== stored) {
                    stored.suppressed.forEach { suppressed ->
                        if (suppressed !== primary && primary.suppressed.none { it === suppressed }) {
                            primary.addSuppressed(suppressed)
                        }
                    }
                }
                denial?.let {
                    primary.addSuppressed(
                        loadDenialException(MutationWriteState.NotPersisted, it),
                    )
                }
                if (
                    disclosure != null &&
                    disclosure !== primary &&
                    primary.suppressed.none { it === disclosure }
                ) {
                    primary.addSuppressed(disclosure)
                }
                primary
            }

            denial != null -> loadDenialException(
                MutationWriteState.NotPersisted,
                denial,
            ).also { rolledBack ->
                if (stored !== rolledBack) rolledBack.addSuppressed(stored)
            }

            disclosure != null -> EntUnexpectedMutationException(
                MutationWriteState.NotPersisted,
                disclosure,
            ).also { rolledBack ->
                if (stored !== disclosure) rolledBack.addSuppressed(stored)
            }

            else -> EntUnexpectedMutationException(
                MutationWriteState.NotPersisted,
                unexpectedCauseOrSelf(stored),
            )
        }
        return exception
    }

    private fun loadDenialException(
        writeState: MutationWriteState,
        denial: PrivacyDenial,
    ): EntMutationPrivacyDeniedException = EntMutationPrivacyDeniedException(
        writeState = writeState,
        entityType = denial.entityType,
        operation = EntOperation.LOAD,
        entityKey = denial.entityKey,
        reason = denial.reason,
    )

    private fun unexpectedCauseOrSelf(exception: Exception): Exception =
        ((exception as? EntUnexpectedMutationException)?.cause as? Exception) ?: exception
}
