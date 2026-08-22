package entkt.runtime.query

import entkt.query.EntktInternal
import entkt.runtime.entity.EntEntity
import entkt.runtime.result.EntQueryConfigurationException

/** Reject a query shape on an operation that cannot preserve selected edge loads. */
@EntktInternal
fun EntityQuery<out EntEntity<*>>.requireNoSelectedEdges(
    operation: String,
    reason: String,
) {
    if (edges.isEmpty()) return

    val selected = edges.joinToString { selection ->
        "${entity.entityName}.${selection.edge.name}"
    }
    throw EntQueryConfigurationException(
        entity.entityName,
        "$operation on ${entity.entityName}Query is incompatible with " +
            "the selected edge loads [$selected]: $reason",
    )
}
