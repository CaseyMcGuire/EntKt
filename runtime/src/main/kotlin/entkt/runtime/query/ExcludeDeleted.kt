package entkt.runtime.query

import entkt.query.Op
import entkt.query.Predicate

/**
 * Reusable read-path interceptor that hides rows whose timestamp
 * column is non-null. The runtime half of the soft-delete
 * convention (see
 * `docs/possible-features/schema/soft-delete.md`).
 *
 * Register per-entity through the generated client interceptor DSL:
 *
 *   val client = EntClient(driver) {
 *       interceptors {
 *           posts(ExcludeDeleted(), name = "soft-delete")
 *           comments(ExcludeDeleted(), name = "soft-delete")
 *       }
 *   }
 *
 * Once installed, every read path that already routes through the
 * read-interceptor framework (root queries, terminals, by-id
 * lookups via query code, eager loads, non-M2M edge predicates,
 * edge traversals, and the `DELETE_CANDIDATES` candidate fetch)
 * picks up `column IS NULL` as an additional ANDed predicate, so
 * soft-deleted rows are invisible by default.
 *
 * The default [column] (`"deleted_at"`) matches the
 * [entkt.schema.DeletedAt] mixin so the canonical pairing is
 * zero-argument. Applications that pair the interceptor with a
 * differently-named timestamp column (`archived_at`,
 * `tombstoned_at`, …) pass the column name explicitly. A wrong
 * column name fails loudly when the driver runs the query against
 * an unknown column — no silent mis-filter risk.
 *
 * **Per-query opt-out.** V1 does not expose a `withDeleted()` /
 * `onlyDeleted()` DSL on generated query builders. Workflows that
 * need to see soft-deleted rows (restore, admin tooling, audit)
 * use a separate `EntClient` without this interceptor installed.
 * Adding per-query flag DSL later is purely additive.
 */
class ExcludeDeleted<E : Any>(
    private val column: String = "deleted_at",
) : QueryInterceptor<E> {
    override fun intercept(scope: InterceptScope<E>, context: QueryContext) {
        scope.addPredicate(Predicate.Leaf(column, Op.IS_NULL, null))
    }
}
