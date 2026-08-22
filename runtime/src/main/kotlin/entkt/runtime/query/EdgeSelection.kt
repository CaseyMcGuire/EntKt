package entkt.runtime.query

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity

/** LOAD-privacy behavior requested for one selected edge. */
@EntktInternal
enum class EdgeVisibility {
    /** Fail the complete root read when a selected target is denied. */
    REQUIRE_VISIBLE,

    /** Omit denied targets from this edge without replacement scanning. */
    FILTER_INVISIBLE,
}

/**
 * One selected relationship and the recursively captured query for its target.
 *
 * The edge target and query entity must be the same generated mapping instance so
 * construction cannot pair a relationship with the wrong target entity.
 */
@EntktInternal
class EdgeSelection<
    Source : EntEntity<*>,
    Target : EntEntity<*>,
>(
    val edge: LoadableEdgeMapping<Source, Target>,
    val target: EntityQuery<Target>,
    val visibility: EdgeVisibility,
) {
    init {
        require(edge.target === target.entity) {
            "Edge '${edge.name}' targets '${edge.target.entityName}', " +
                "but its selected query targets '${target.entity.entityName}'"
        }
    }
}
