package entkt.explorer

import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.Viewer

/**
 * Explorer configuration, safe by construction:
 *
 *  - [authorize] defaults to deny-all — installing the explorer without an
 *    authorization callback yields 403 for every request, never a public
 *    route;
 *  - [viewerContext] defaults to [Viewer.Anonymous];
 *  - all generated entities are included; hiding one is the explicit action
 *    ([EntityVisibility.exclude]) — there is deliberately no `include(...)`
 *    allow-list in V1;
 *  - `.sensitive()` schema fields are always redacted; [Redaction.extra]
 *    only adds to that set and nothing can remove from it.
 */
class EntExplorerConfig internal constructor() {

    /** Route prefix the explorer is mounted under. */
    var path: String = "/_ent"

    internal var authorize: (EntExplorerRequest) -> Boolean = { false }
    internal var viewerContext: (EntExplorerRequest) -> ViewerContext<Viewer> =
        { ViewerContext.AsViewer(Viewer.Anonymous) }
    internal val visibility = EntityVisibility()
    internal val redaction = Redaction()

    /**
     * Gate access to the explorer endpoint. This controls access to the
     * *explorer*, not to rows — row visibility is [viewerContext]'s job.
     * Defaults to deny-all; the application must supply it.
     */
    fun authorize(check: (EntExplorerRequest) -> Boolean) {
        authorize = check
    }

    /**
     * Viewer context every explorer read runs under, resolved per request.
     * The data displayed is the data this context is allowed to read —
     * the explorer adds no privileges.
     */
    fun viewerContext(provider: (EntExplorerRequest) -> ViewerContext<Viewer>) {
        viewerContext = provider
    }

    fun entities(block: EntityVisibility.() -> Unit) {
        visibility.block()
    }

    fun redaction(block: Redaction.() -> Unit) {
        redaction.block()
    }

    class EntityVisibility internal constructor() {
        internal val excludedRoutes = mutableSetOf<String>()

        /** Exclude by explorer route name (`"user"`) — the entity 404s like an unknown route. */
        fun exclude(routeName: String) {
            excludedRoutes.add(routeName)
        }

        /** Exclude by generated adapter reference. */
        fun exclude(entity: EntExplorerEntity<*>) {
            excludedRoutes.add(entity.routeName)
        }
    }

    class Redaction internal constructor() {
        internal val extra = mutableSetOf<Pair<String, String>>()

        /**
         * Redact an additional (table, column) beyond the schema's
         * `.sensitive()` declarations — for legacy columns that should have
         * been sensitive. Extra-redacted columns also stop being filterable
         * and orderable.
         */
        fun extra(table: String, column: String) {
            extra.add(table to column)
        }
    }
}
