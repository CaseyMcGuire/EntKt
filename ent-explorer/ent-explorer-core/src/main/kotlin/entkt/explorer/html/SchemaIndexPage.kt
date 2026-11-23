package entkt.explorer.html

import entkt.explorer.EntExplorerEntity
import entkt.explorer.EntExplorerResponse
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/**
 * One schema-index row: the entity plus which of its members (columns,
 * edges) matched the search, when the hit wasn't on the name itself.
 */
internal data class SchemaMatch(
    val entity: EntExplorerEntity<*, *>,
    val matchedMembers: List<String>,
)

/** Searchable schema list; each row drills down to `/schema/{type}`. */
internal fun schemaIndexPage(
    matches: List<SchemaMatch>,
    q: String,
    urls: ExplorerUrls,
): EntExplorerResponse = pageShell(200, "Schema", urls) {
    h1 { +"Schema" }
    searchForm(q, urls.schema(), placeholder = "Search entities, tables, columns, edges…")
    if (matches.isEmpty()) {
        p("muted") { +"No schemas match \"$q\"." }
    } else {
        card {
            table {
                thead {
                    tr {
                        th { +"Entity" }; th { +"Table" }; th { +"Columns" }; th { +"Edges" }
                        if (q.isNotEmpty()) th { +"Matches" }
                    }
                }
                tbody {
                    for (match in matches) {
                        val entity = match.entity
                        tr {
                            td { a(href = urls.schemaDetail(entity.routeName)) { +entity.displayName } }
                            td { +entity.schema.table }
                            td { +entity.columns.size.toString() }
                            td { +entity.edges.size.toString() }
                            if (q.isNotEmpty()) {
                                td {
                                    if (match.matchedMembers.isEmpty()) span("muted") { +"name" }
                                    else +match.matchedMembers.joinToString(", ")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
