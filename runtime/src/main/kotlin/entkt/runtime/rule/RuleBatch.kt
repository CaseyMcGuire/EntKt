package entkt.runtime.rule

import entkt.runtime.result.EntBatchRuleContractException
import java.util.Collections

/**
 * The immutable, read-only container supplied to one batch privacy or
 * validation rule.
 *
 * The container copies its input list; it does not by itself make arbitrary
 * item objects transitively immutable. LOAD rules receive the original entities;
 * mutation privacy and validation rules receive defensive snapshots.
 * Rules must treat their inputs as read-only. This behaves as a read-only
 * [List] for query preparation. Decisions must be
 * returned through [decideEach] or [decideEachIndexed]. Those methods invoke the
 * decision block in this batch's encounter order and bind the resulting value
 * to this batch. The rule remains responsible for returning the right decision
 * for the item supplied to its block.
 */
sealed interface RuleBatch<out Item> : List<Item> {
    /** Build one correlated decision for every item in original order. */
    fun <Decision> decideEach(block: (Item) -> Decision): RuleDecisions<Decision>

    /**
     * Build one correlated decision for every item in original order while
     * exposing its stable index within this callback invocation. Privacy rules
     * later in a chain can receive a filtered batch, so this is not an index
     * into the original logical operation. The index supports duplicate or
     * otherwise equal items without making an entity ID the correlation key.
     */
    fun <Decision> decideEachIndexed(
        block: (index: Int, item: Item) -> Decision,
    ): RuleDecisions<Decision>

    companion object {
        /**
         * Create an immutable batch, primarily for directly testing or
         * composing application-defined batch rules.
         *
         * Generated operations supply their own batches. Decisions created
         * from this batch are rejected if returned for a different batch.
         */
        @JvmStatic
        fun <Item> from(items: List<Item>): RuleBatch<Item> =
            RuleBatchImplementations.create(items)
    }
}

/**
 * Read-only decisions correlated to the [RuleBatch] that created them.
 *
 * Callers may inspect and compare decisions, which keeps batch rules directly
 * testable and allows decorators to transform a delegated result through the
 * provenance-preserving [mapDecisions] operation. Rule implementations cannot
 * construct this type from an arbitrary list; use [RuleBatch.decideEach] or
 * [RuleBatch.decideEachIndexed].
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
    fun <Item> create(items: List<Item>): RuleBatch<Item> = DefaultRuleBatch(items)

    fun <Item, Decision> decisions(
        batch: RuleBatch<Item>,
        lifecycle: String,
        result: RuleDecisions<Decision>,
    ): List<Decision> {
        val concreteBatch = batch as DefaultRuleBatch<Item>
        val concrete = result as DefaultRuleDecisions<Decision>
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

    private class DefaultRuleBatch<out Item>(
        items: List<Item>,
    ) : AbstractList<Item>(), RuleBatch<Item> {
        private val items: List<Item> =
            Collections.unmodifiableList(ArrayList(items))
        val batchIdentity: Any = Any()

        override val size: Int
            get() = items.size

        override fun get(index: Int): Item = items[index]

        override fun <Decision> decideEach(
            block: (Item) -> Decision,
        ): RuleDecisions<Decision> =
            DefaultRuleDecisions(
                batchIdentity = batchIdentity,
                values = items.map(block),
            )

        override fun <Decision> decideEachIndexed(
            block: (index: Int, item: Item) -> Decision,
        ): RuleDecisions<Decision> =
            DefaultRuleDecisions(
                batchIdentity = batchIdentity,
                values = items.mapIndexed(block),
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
internal fun <Item> ruleBatchForInternalUse(items: List<Item>): RuleBatch<Item> =
    RuleBatch.from(items)

/** Extract and validate a batch-bound result at the framework callback boundary. */
@JvmSynthetic
internal fun <Item, Decision> RuleBatch<Item>.decisionsForInternalUse(
    lifecycle: String,
    result: RuleDecisions<Decision>,
): List<Decision> {
    return RuleBatchImplementations.decisions(this, lifecycle, result)
}
