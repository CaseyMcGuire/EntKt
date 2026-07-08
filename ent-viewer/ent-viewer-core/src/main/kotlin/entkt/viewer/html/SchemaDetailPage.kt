package entkt.viewer.html

import entkt.viewer.EntViewerColumn
import entkt.viewer.EntViewerEntity
import entkt.viewer.EntViewerResponse
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.span

/** One entity's full schema: columns, edges (cross-linked), indexes. */
internal fun schemaDetailPage(
    entity: EntViewerEntity<*>,
    columns: List<EntViewerColumn>,
    visibleRoutes: Set<String>,
    urls: ViewerUrls,
): EntViewerResponse = pageShell(200, "${entity.displayName} schema", urls) {
    h1 { +"${entity.displayName} " ; span("muted") { +"(${entity.schema.table})" } }
    p {
        a(href = urls.entityList(entity.routeName)) { +"Browse rows" }
        span("muted") { +"  ·  " }
        a(href = urls.schema()) { +"All schemas" }
    }
    h2 { +"Columns" }
    columnsTable(columns)
    if (entity.edges.isNotEmpty()) {
        h2 { +"Edges" }
        schemaEdgesTable(entity.edges, visibleRoutes, urls)
    }
    if (entity.schema.indexes.isNotEmpty()) {
        h2 { +"Indexes" }
        indexesTable(entity.schema.indexes)
    }
}
