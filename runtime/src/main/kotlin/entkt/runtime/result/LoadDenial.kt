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

/**
 * One step of the generated schema-edge path from a query root to a
 * denied eager-load target. Contains only schema type and edge names;
 * never hydrated entities, field values, or viewer data.
 */
data class EagerEdgeStep(
    val sourceEntityType: String,
    val edgeName: String,
    val targetEntityType: String,
)

/**
 * Where a LOAD denial arose: the terminal's root selection, or an
 * eagerly loaded target reached from it.
 *
 * Root privacy completes before eager loading begins, so one
 * [EntPrivacyDeniedException] never mixes both origins. The split is
 * what lets [visibleOrNull] map only *root* denial to singular
 * absence — adding an eager load can never turn a visible root into
 * apparent absence.
 */
sealed interface LoadDenialOrigin {
    /** The denied row is the terminal's own selected root. */
    data object Root : LoadDenialOrigin

    /**
     * The denied row is an eagerly loaded target. [path] is the
     * complete generated schema-edge path from the root to the denied
     * target, outermost edge first.
     */
    data class EagerEdge(
        val path: List<EagerEdgeStep>,
    ) : LoadDenialOrigin {
        init {
            require(path.isNotEmpty()) { "EagerEdge origin requires a non-empty edge path" }
        }
    }
}
