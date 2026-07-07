package entkt.viewer.html

import entkt.viewer.EntViewerColumn
import entkt.viewer.EntViewerEntity
import entkt.viewer.EntViewerResponse
import kotlinx.html.h1
import kotlinx.html.h2

internal fun schemaPage(
    entities: List<Pair<EntViewerEntity<*>, List<EntViewerColumn>>>,
    urls: ViewerUrls,
): EntViewerResponse = pageShell(200, "Schema", urls) {
    h1 { +"Schema" }
    for ((entity, columns) in entities) {
        h2 { +"${entity.displayName} (${entity.schema.table})" }
        columnsTable(columns)
        if (entity.edges.isNotEmpty()) schemaEdgesTable(entity.edges)
        if (entity.schema.indexes.isNotEmpty()) indexesTable(entity.schema.indexes)
    }
}
