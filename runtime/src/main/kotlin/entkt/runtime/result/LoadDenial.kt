package entkt.runtime.result

/**
 * A type-erased runtime wrapper around an entity's generated ID field.
 * Permits trusted callers to correlate a privacy denial or absent
 * target with a concrete row without exposing the hydrated entity or
 * its non-key fields.
 *
 * [field] is the schema field name (e.g. `"id"`); [value] is the key's
 * runtime value. Erased because exception types cannot be generic on
 * the JVM and denial payloads must not depend on generated entity
 * classes.
 */
data class EntityKey(
    val field: String,
    val value: Any,
)

/**
 * One denied root row inside [EntPrivacyDeniedException]. Identifies
 * the selected row that existed but could not be returned under the
 * current viewer.
 *
 * The key and the privacy-rule-supplied [reason] are trusted diagnostic
 * data; applications must not expose them to untrusted clients without
 * an explicit boundary mapping. The payload never contains the hydrated
 * entity or a non-key field value that LOAD privacy withheld.
 *
 * LOAD is implicit from the containing exception type, so the payload
 * does not repeat an operation enum.
 */
data class PrivacyDenial(
    val entityType: String,
    val entityKey: EntityKey,
    val reason: String,
)

/** One selected edge in the path from a query root to a denied related entity. */
data class SelectedEdgeStep(
    /** Entity type on which the selected edge is declared. */
    val sourceEntityType: String,

    /** Declaration-derived name of the selected edge. */
    val edgeName: String,

    /** Entity type reached through the selected edge. */
    val targetEntityType: String,
)

/**
 * Where a LOAD denial arose: the terminal's root selection, or a related entity reached through
 * selected edges.
 *
 * Root privacy completes before selected edges are loaded, so one
 * [EntPrivacyDeniedException] never mixes both origins. The split is
 * what lets [visibleOrNull] map only *root* denial to singular
 * absence — selecting an edge can never turn a visible root into
 * apparent absence.
 */
sealed interface LoadDenialOrigin {
    /** The denied row is the terminal's own selected root. */
    data object Root : LoadDenialOrigin

    /** The denied entity was reached through the non-empty selected-edge [steps]. */
    data class SelectedEdgePath(
        /** Complete path from the query root to the denied entity, outermost edge first. */
        val steps: List<SelectedEdgeStep>,
    ) : LoadDenialOrigin {
        init {
            require(steps.isNotEmpty()) { "SelectedEdgePath requires at least one edge" }
        }
    }
}
