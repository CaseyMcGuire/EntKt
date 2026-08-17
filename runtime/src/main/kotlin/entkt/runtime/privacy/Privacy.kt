package entkt.runtime.privacy

import entkt.query.EntktInternal
import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.RuleDecisions
import entkt.runtime.rule.decisionsForInternalUse
import entkt.runtime.rule.ruleBatchForInternalUse
import entkt.runtime.result.EntBatchRuleContractException
import java.util.UUID

/**
 * Represents the identity of the current viewer performing an operation.
 */
sealed interface Viewer {
    /** An unauthenticated viewer. */
    data object Anonymous : Viewer
    /** An authenticated user identified by their primary key. */
    data class User(val id: Any) : Viewer

    /**
     * Trusted framework/application escape hatch that bypasses generated privacy
     * checks (LOAD / CREATE / UPDATE / DELETE). It does NOT bypass validation,
     * hooks, query interceptors, transactions, or database constraints. Use only
     * for seed data, migrations, validation reads, tests, and similarly explicit
     * internal operations — prefer the generated `bypassPrivacy_DANGEROUS(reason)`
     * client helper at application call sites. The required [reason] forces
     * authors to say why the escape hatch is being used.
     */
    data class PrivacyBypass(val reason: String) : Viewer {
        init {
            require(reason.isNotBlank()) {
                "Viewer.PrivacyBypass requires a non-blank reason"
            }
        }
    }
}

fun Viewer.userOrNull(): Viewer.User? =
    this as? Viewer.User

fun Viewer.userIdOrNull(): Any? =
    userOrNull()?.id

fun Viewer.stringIdOrNull(): String? =
    userOrNull()?.id as? String

fun Viewer.intIdOrNull(): Int? =
    userOrNull()?.id as? Int

fun Viewer.longIdOrNull(): Long? =
    userOrNull()?.id as? Long

fun Viewer.uuidIdOrNull(): UUID? =
    userOrNull()?.id as? UUID

/**
 * Privacy context captured for a generated operation and threaded
 * through its privacy checks. Scalar operations capture one context;
 * batch-aware collection reads and `createMany` likewise capture once
 * for the complete logical operation rather than once per item.
 */
data class PrivacyContext(
    val viewer: Viewer,
)

fun PrivacyContext.userOrNull(): Viewer.User? =
    viewer.userOrNull()

fun PrivacyContext.userIdOrNull(): Any? =
    viewer.userIdOrNull()

fun PrivacyContext.stringIdOrNull(): String? =
    viewer.stringIdOrNull()

fun PrivacyContext.intIdOrNull(): Int? =
    viewer.intIdOrNull()

fun PrivacyContext.longIdOrNull(): Long? =
    viewer.longIdOrNull()

fun PrivacyContext.uuidIdOrNull(): UUID? =
    viewer.uuidIdOrNull()

/**
 * State shared by every item and reached rule in one privacy evaluation phase.
 *
 * Generated lifecycle item types contain only per-item state. The operation's
 * captured [privacy] context and privacy-capable [client] live here so scalar
 * and batch rules receive the same item shape. The framework passes the exact
 * same instance to every reached rule in that phase.
 */
class PrivacyRuleContext<out Client>(
    val privacy: PrivacyContext,
    val client: Client,
)

/**
 * The kind of operation being privacy-checked.
 */
enum class PrivacyOperation {
    LOAD,
    CREATE,
    UPDATE,
    DELETE,
}

/**
 * The result of evaluating a single privacy rule.
 *
 * - [Allow] — stop evaluation and permit the operation.
 * - [Continue] — defer to the next rule in the list.
 * - [Deny] — stop evaluation and reject the operation.
 */
sealed interface PrivacyDecision {
    data object Allow : PrivacyDecision
    data object Continue : PrivacyDecision
    data class Deny(val reason: String) : PrivacyDecision
}

/**
 * Legacy standalone representation of a denied privacy decision. Generated
 * data terminals do not throw this type: reads return
 * `ReadResult.Failed(EntPrivacyDeniedException)`, while mutations return
 * `MutationResult.Failed(EntMutationPrivacyDeniedException)`.
 */
class PrivacyDeniedException(
    val entity: String,
    val operation: PrivacyOperation,
    val reason: String,
) : RuntimeException("$operation denied on $entity: $reason")

/**
 * A privacy rule that can evaluate an ordered batch of operation items.
 *
 * Implementations return decisions through [RuleBatch.decideEach] or
 * [RuleBatch.decideEachIndexed]. Those operations preserve correlation with
 * the supplied items; callers cannot construct or reorder the result directly.
 * Generated lifecycle evaluators never invoke a rule with an empty batch.
 */
interface BatchPrivacyRule<in Client, in Item> {
    @JvmSuppressWildcards
    fun runBatch(
        context: PrivacyRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<PrivacyDecision>
}

/**
 * A scalar privacy rule that evaluates one operation item.
 *
 * Scalar rules are also [BatchPrivacyRule]s: the default adapter evaluates
 * items serially in encounter order. Rules are evaluated in registration
 * order; the first non-[PrivacyDecision.Continue] result wins for each item.
 */
fun interface PrivacyRule<in Client, in Item> : BatchPrivacyRule<Client, Item> {
    @JvmSuppressWildcards
    fun run(
        context: PrivacyRuleContext<Client>,
        item: Item,
    ): PrivacyDecision

    @JvmSuppressWildcards
    override fun runBatch(
        context: PrivacyRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<PrivacyDecision> =
        batch.decideEach { run(context, it) }
}

/**
 * Construct an explicitly batch-aware privacy rule.
 *
 * The ordinary interface plus named factory keeps batch-taking callbacks
 * explicit at registration sites instead of making a lambda ambiguous between
 * one item and a batch of items.
 */
fun <Client, Item> batchPrivacyRule(
    block: (
        context: PrivacyRuleContext<Client>,
        batch: RuleBatch<Item>,
    ) -> RuleDecisions<PrivacyDecision>,
): BatchPrivacyRule<Client, Item> = object : BatchPrivacyRule<Client, Item> {
    override fun runBatch(
        context: PrivacyRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<PrivacyDecision> = block(context, batch)
}

/**
 * Evaluate ordered privacy rules across [items], retaining positional
 * correlation while removing finalized items from later rule invocations.
 *
 * [freshItem] is invoked afresh for every item that reaches every rule.
 * Unresolved items remain [PrivacyDecision.Continue] so generated operations
 * can apply their operation-specific fail-closed denial.
 */
@EntktInternal
fun <I, Client, Item> evaluateBatchPrivacyRulesForInternalUse(
    lifecycle: String,
    items: List<I>,
    rules: List<BatchPrivacyRule<Client, Item>>,
    context: PrivacyRuleContext<Client>,
    freshItem: (I) -> Item,
): List<PrivacyDecision> {
    if (items.isEmpty()) return emptyList()

    val itemSnapshot = items.toList()
    val ruleSnapshot = rules.toList()
    val decisions = MutableList<PrivacyDecision>(itemSnapshot.size) { PrivacyDecision.Continue }
    var activeIndexes = itemSnapshot.indices.toList()

    for (rule in ruleSnapshot) {
        if (activeIndexes.isEmpty()) break

        val ruleItems = activeIndexes.map { freshItem(itemSnapshot[it]) }
        val batch = ruleBatchForInternalUse(ruleItems)
        val returnedDecisions: RuleDecisions<PrivacyDecision>? = rule.runBatch(context, batch)
        val ruleDecisions: List<*> = returnedDecisions
            ?.let { batch.decisionsForInternalUse(lifecycle, it) }
            ?: throw EntBatchRuleContractException(lifecycle, ruleItems.size, actualSize = null)
        if (ruleDecisions.size != ruleItems.size) {
            throw EntBatchRuleContractException(lifecycle, ruleItems.size, ruleDecisions.size)
        }

        val remainingIndexes = ArrayList<Int>(activeIndexes.size)
        activeIndexes.forEachIndexed { position, itemIndex ->
            when (val decision = ruleDecisions[position]) {
                PrivacyDecision.Allow -> decisions[itemIndex] = PrivacyDecision.Allow
                is PrivacyDecision.Deny -> decisions[itemIndex] = decision
                PrivacyDecision.Continue -> {
                    decisions[itemIndex] = PrivacyDecision.Continue
                    remainingIndexes += itemIndex
                }
                else -> throw EntBatchRuleContractException(
                    lifecycle = lifecycle,
                    expectedSize = ruleItems.size,
                    actualSize = ruleDecisions.size,
                    invalidDecisionIndex = position,
                )
            }
        }
        activeIndexes = remainingIndexes
    }

    return decisions.toList()
}

/**
 * A stock rule that allows any operation on any entity. Because [PrivacyRule]
 * is contravariant in both client and item, this single value satisfies every
 * typed rule slot — `load(allowAll)`, `create(allowAll)`, `update(allowAll)`,
 * `delete(allowAll)` — on any schema, so a public or trusted entity doesn't
 * need a per-type "allow everything" rule.
 *
 * Under fail-closed privacy this is the explicit opt-in to "no restriction" for
 * an operation; use it deliberately.
 */
val allowAll: PrivacyRule<Any?, Any?> = PrivacyRule { _, _ -> PrivacyDecision.Allow }

/**
 * An entity-scoped policy that configures rules for entity operations
 * (privacy and validation) through a generated scope object. Policies
 * are registered per-entity on the generated `EntClient`.
 */
interface EntityPolicy<E, Scope> {
    fun configure(scope: Scope)
}
