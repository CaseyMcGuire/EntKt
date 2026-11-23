package entkt.runtime.privacy

import entkt.runtime.rule.EntRuleClient
import entkt.runtime.rule.RuleBatch
import entkt.runtime.rule.RuleDecisions

/**
 * What a generated operation runs as: either a viewer, whose access the
 * privacy rules decide, or an explicit privacy bypass.
 *
 * The context supplied to an operation is threaded through its privacy
 * checks. Scalar operations retain one context; batch-aware collection reads
 * and `createMany` likewise retain the same instance for the complete logical
 * operation rather than once per item.
 *
 * @param Viewer the application's viewer type. The runtime carries a viewer
 * and never inspects it. Generated code binds this parameter to the type named
 * by the `viewerType` code generation option, so every generated terminal
 * accepts that one type. A [PrivacyBypass] carries no viewer and is accepted
 * wherever any `ViewerContext` is.
 */
sealed interface ViewerContext<out Viewer : Any> {
    /** Run as [viewer]. Privacy rules decide what is allowed. */
    data class AsViewer<out Viewer : Any>(val viewer: Viewer) : ViewerContext<Viewer>

    /**
     * Trusted framework/application escape hatch that bypasses generated privacy
     * checks (LOAD / CREATE / UPDATE / DELETE). It does NOT bypass validation,
     * hooks, query interceptors, transactions, or database constraints. Use only
     * for seed data, migrations, validation reads, tests, and similarly explicit
     * internal operations. Created only by
     * [ViewerContext.privacyBypass_DANGEROUS]; the required [reason] forces
     * authors to say why the escape hatch is being used.
     */
    @ConsistentCopyVisibility
    data class PrivacyBypass internal constructor(val reason: String) : ViewerContext<Nothing> {
        init {
            require(reason.isNotBlank()) {
                "ViewerContext.PrivacyBypass requires a non-blank reason"
            }
        }
    }

    companion object {
        /** Construct an explicitly privileged operation context. */
        fun privacyBypass_DANGEROUS(reason: String): PrivacyBypass =
            PrivacyBypass(reason)
    }
}

/**
 * State shared by every item and reached rule in one privacy evaluation phase.
 *
 * Generated lifecycle item types contain only per-item state. The operation's
 * captured [viewerContext] and privacy-capable [client] live here so scalar
 * and batch rules receive the same item shape. The framework passes the exact
 * same instance to every reached rule in that phase.
 *
 * Privacy rules are never evaluated for a privacy bypass, so [viewerContext]
 * is always the [ViewerContext.AsViewer] the operation was given.
 */
class PrivacyRuleContext<out Viewer : Any, out Client : EntRuleClient>(
    val viewerContext: ViewerContext.AsViewer<Viewer>,
    val client: Client,
) {
    /** The viewer the operation runs as. */
    val viewer: Viewer
        get() = viewerContext.viewer
}

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
 * data terminals do not throw this type: singular reads and individual collection
 * entries use `ReadResult.Failed(EntPrivacyDeniedException)`, while mutations return
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
 * Implementations return decisions through [RuleBatch.decideEach],
 * [RuleBatch.decideEachIndexed], or the privacy-specific `batch.allowAll()` and
 * `batch.denyAll(reason)` helpers. Those operations preserve correlation with
 * the supplied items; callers cannot construct or reorder the result directly.
 * Generated lifecycle evaluators never invoke a rule with an empty batch.
 */
interface BatchPrivacyRule<in Viewer : Any, in Client : EntRuleClient, in Item> {
    @JvmSuppressWildcards
    fun runBatch(
        context: PrivacyRuleContext<Viewer, Client>,
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
fun interface PrivacyRule<in Viewer : Any, in Client : EntRuleClient, in Item> : BatchPrivacyRule<Viewer, Client, Item> {
    @JvmSuppressWildcards
    fun run(
        context: PrivacyRuleContext<Viewer, Client>,
        item: Item,
    ): PrivacyDecision

    @JvmSuppressWildcards
    override fun runBatch(
        context: PrivacyRuleContext<Viewer, Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<PrivacyDecision> =
        batch.decideEach { run(context, it) }
}

/**
 * Construct an explicitly batch-aware privacy rule.
 *
 * The ordinary interface plus named factory keeps batch-taking callbacks
 * explicit at Kotlin registration sites. Use [PrivacyRule] for a scalar callback.
 */
fun <Viewer : Any, Client : EntRuleClient, Item> batchPrivacyRule(
    block: (
        context: PrivacyRuleContext<Viewer, Client>,
        batch: RuleBatch<Item>,
    ) -> RuleDecisions<PrivacyDecision>,
): BatchPrivacyRule<Viewer, Client, Item> = object : BatchPrivacyRule<Viewer, Client, Item> {
    override fun runBatch(
        context: PrivacyRuleContext<Viewer, Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<PrivacyDecision> = block(context, batch)
}

/**
 * Allow every item in this batch, including duplicate or equal items.
 *
 * Equivalent to `decideEach { PrivacyDecision.Allow }`: the read-only result
 * remains tied to this exact batch, and an empty batch produces empty decisions.
 * Returning these decisions from a rule finalizes each supplied item; later rules skip it.
 */
fun RuleBatch<*>.allowAll(): RuleDecisions<PrivacyDecision> =
    decideEach { PrivacyDecision.Allow }

/**
 * Deny every item in this batch with [reason], including duplicate or equal items.
 *
 * Equivalent to `decideEach { PrivacyDecision.Deny(reason) }`: the read-only result
 * remains tied to this exact batch, and an empty batch produces empty decisions.
 * Returning these decisions from a rule finalizes each supplied item; later rules skip it.
 */
fun RuleBatch<*>.denyAll(reason: String): RuleDecisions<PrivacyDecision> =
    decideEach { PrivacyDecision.Deny(reason) }

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
val allowAll: PrivacyRule<Any, EntRuleClient, Any?> = PrivacyRule { _, _ -> PrivacyDecision.Allow }

/**
 * An entity-scoped policy that configures rules for entity operations
 * (privacy and validation) through a generated scope object. Policies
 * are registered per-entity on the generated `EntClient`.
 */
interface EntityPolicy<E, Scope> {
    fun configure(scope: Scope)
}
