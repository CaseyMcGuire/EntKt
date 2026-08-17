package entkt.runtime.rule

import entkt.runtime.result.EntBatchRuleContractException
import java.util.Collections

/**
 * The immutable, read-only container supplied to one batch privacy or
 * validation rule.
 *
 * The container copies its input list; it does not by itself make arbitrary
 * context objects transitively immutable. Generated lifecycle contexts are
 * separately constructed as defensive snapshots. This behaves as a read-only
 * [List] for query preparation. Decisions must be
 * returned through [decide] or [decideIndexed]. Those methods invoke the
 * decision block in this batch's encounter order and bind the resulting value
 * to this batch. The rule remains responsible for returning the right decision
 * for the context supplied to its block.
 */
sealed interface RuleBatch<out C> : List<C> {
    /** Build one correlated decision for every context in original order. */
    fun <D> decide(block: (C) -> D): RuleDecisions<D>

    /**
     * Build one correlated decision for every context in original order while
     * exposing its stable index within this callback invocation. Privacy rules
     * later in a chain can receive a filtered batch, so this is not an index
     * into the original logical operation. The index supports duplicate or
     * otherwise equal contexts without making an entity ID the correlation key.
     */
    fun <D> decideIndexed(block: (index: Int, context: C) -> D): RuleDecisions<D>

    companion object {
        /**
         * Create an immutable batch, primarily for directly testing or
         * composing application-defined batch rules.
         *
         * Generated operations supply their own batches. Decisions created
         * from this batch are rejected if returned for a different batch.
         */
        @JvmStatic
        fun <C> from(contexts: List<C>): RuleBatch<C> =
            RuleBatchImplementations.create(contexts)
    }
}

/**
 * Read-only decisions correlated to the [RuleBatch] that created them.
 *
 * Callers may inspect and compare decisions, which keeps batch rules directly
 * testable and allows decorators to transform a delegated result through the
 * provenance-preserving [mapDecisions] operation. Rule implementations cannot
 * construct this type from an arbitrary list; use [RuleBatch.decide] or
 * [RuleBatch.decideIndexed].
 */
sealed interface RuleDecisions<out D> : List<D> {
    /**
     * Transform every decision while retaining this result's originating
     * batch. Decorators should use this instead of copying indexed values into
     * a new batch, which would discard stale-result detection.
     */
    fun <R> mapDecisions(transform: (D) -> R): RuleDecisions<R>
}

/** JVM-hidden implementations and their provenance check. */
private object RuleBatchImplementations {
    fun <C> create(contexts: List<C>): RuleBatch<C> = DefaultRuleBatch(contexts)

    fun <C, D> decisions(
        batch: RuleBatch<C>,
        lifecycle: String,
        result: RuleDecisions<D>,
    ): List<D> {
        val concreteBatch = batch as DefaultRuleBatch<C>
        val concrete = result as DefaultRuleDecisions<D>
        if (concrete.batchIdentity !== concreteBatch.batchIdentity) {
            throw EntBatchRuleContractException(
                lifecycle = lifecycle,
                expectedSize = batch.size,
                actualSize = concrete.size,
                foreignBatchResult = true,
            )
        }
        return concrete
    }

    private class DefaultRuleBatch<out C>(
        contexts: List<C>,
    ) : AbstractList<C>(), RuleBatch<C> {
        private val contexts: List<C> =
            Collections.unmodifiableList(ArrayList(contexts))
        val batchIdentity: Any = Any()

        override val size: Int
            get() = contexts.size

        override fun get(index: Int): C = contexts[index]

        override fun <D> decide(block: (C) -> D): RuleDecisions<D> =
            DefaultRuleDecisions(
                batchIdentity = batchIdentity,
                values = contexts.map(block),
            )

        override fun <D> decideIndexed(block: (index: Int, context: C) -> D): RuleDecisions<D> =
            DefaultRuleDecisions(
                batchIdentity = batchIdentity,
                values = contexts.mapIndexed(block),
            )
    }

    private class DefaultRuleDecisions<out D>(
        val batchIdentity: Any,
        values: List<D>,
    ) : AbstractList<D>(), RuleDecisions<D> {
        private val values: List<D> =
            Collections.unmodifiableList(ArrayList(values))

        override val size: Int
            get() = values.size

        override fun get(index: Int): D = values[index]

        override fun <R> mapDecisions(transform: (D) -> R): RuleDecisions<R> =
            DefaultRuleDecisions(
                batchIdentity = batchIdentity,
                values = values.map(transform),
            )
    }
}

/** Create the private implementation supplied to one framework callback. */
@JvmSynthetic
internal fun <C> ruleBatchForInternalUse(contexts: List<C>): RuleBatch<C> =
    RuleBatch.from(contexts)

/** Extract and validate a batch-bound result at the framework callback boundary. */
@JvmSynthetic
internal fun <C, D> RuleBatch<C>.decisionsForInternalUse(
    lifecycle: String,
    result: RuleDecisions<D>,
): List<D> {
    return RuleBatchImplementations.decisions(this, lifecycle, result)
}
