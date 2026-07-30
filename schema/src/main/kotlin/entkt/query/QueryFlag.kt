package entkt.query

/**
 * Public flags carried on a query. V1 has two members, both consumed
 * exclusively by the generated soft-delete interceptor. Public flags
 * do NOT propagate across query steps — each step's `query { ... }`
 * (or eager-load configuration block) sets its own flags.
 *
 * Lives in `entkt.query` (not the runtime interceptor package) so
 * traversal predicates can embed a [TraversalSourceShape] — which
 * carries the source step's flags — without `:schema` depending on
 * `:runtime`.
 *
 * `internalSystemQuery` lives in a separate package-private slot
 * that never appears in this enum and is never visible to
 * interceptors.
 */
@Suppress("EnumEntryName")
enum class QueryFlag {
    withDeleted,
    onlyDeleted,
}
