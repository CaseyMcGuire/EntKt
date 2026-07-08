package entkt.viewer.html

import entkt.runtime.driver.EntitySchema
import entkt.viewer.EntViewerColumn
import kotlinx.html.FlowContent
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/**
 * Schema-page column listing: the Kotlin-facing ent type, the underlying
 * database type, and nullable/unique/sensitive flags.
 */
internal fun FlowContent.columnsTable(columns: List<EntViewerColumn>, schema: EntitySchema) {
    val metadataByName = schema.columns.associateBy { it.name }
    card {
        table {
            thead {
                tr { th { +"Column" }; th { +"Ent Type" }; th { +"DB Type" }; th { +"Flags" } }
            }
            tbody {
                for (col in columns) {
                    tr {
                        td { +col.name }
                        td { +col.entType.ifEmpty { col.type.name.lowercase() } }
                        td {
                            val metadata = metadataByName[col.name]
                            +(metadata?.let { dbDisplayType(schema, it) } ?: "-")
                        }
                        td {
                            if (col.nullable) span("flag") { +"Nullable" }
                            if (col.unique) span("flag") { +"Unique" }
                            if (col.sensitive) span("flag sensitive") { +"Sensitive" }
                        }
                    }
                }
            }
        }
    }
}
