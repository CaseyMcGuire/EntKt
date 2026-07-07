package entkt.viewer

import kotlinx.html.FlowContent
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.html
import kotlinx.html.main
import kotlinx.html.nav
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import java.net.URLEncoder

/**
 * Server-side HTML rendering for the viewer. All dynamic text flows through
 * kotlinx.html text nodes (escaped by the DSL); the only `unsafe` block is
 * the Kotlin-generated stylesheet. Sensitive values never reach this class —
 * adapters hand over pre-redacted cells — so they cannot end up in HTML
 * attributes or text.
 */
internal class EntViewerHtml(basePath: String) {

    private val base = basePath.trimEnd('/')

    /** Truncation for list cells; the detail page renders full values. */
    private val listCellMax = 120

    // ---------- pages ----------

    fun error(status: Int, message: String): EntViewerResponse =
        respond(status, "error") {
            div("error") { +message }
        }

    fun home(entities: List<EntViewerEntity<*>>): EntViewerResponse =
        respond(200, "entkt viewer") {
            h1 { +"entkt viewer" }
            p("muted") { +"Read-only. Rows are what the configured privacy context can read." }
            entityTable(entities)
        }

    fun entityIndex(entities: List<EntViewerEntity<*>>): EntViewerResponse =
        respond(200, "entities") {
            h1 { +"Entities" }
            entityTable(entities)
        }

    fun schemaPage(entities: List<Pair<EntViewerEntity<*>, List<EntViewerColumn>>>): EntViewerResponse =
        respond(200, "schema") {
            h1 { +"Schema" }
            for ((entity, columns) in entities) {
                h2 { +"${entity.displayName} (${entity.schema.table})" }
                table {
                    thead {
                        tr { th { +"column" }; th { +"type" }; th { +"flags" } }
                    }
                    tbody {
                        for (col in columns) {
                            tr {
                                td { +col.name }
                                td { +col.type.name.lowercase() }
                                td {
                                    if (col.nullable) span("flag") { +"nullable" }
                                    if (col.unique) span("flag") { +"unique" }
                                    if (col.sensitive) span("flag sensitive") { +"sensitive" }
                                }
                            }
                        }
                    }
                }
                if (entity.edges.isNotEmpty()) {
                    table {
                        thead { tr { th { +"edge" }; th { +"cardinality" }; th { +"target" } } }
                        tbody {
                            for (edge in entity.edges) {
                                tr {
                                    td { +edge.name }
                                    td { +edge.cardinality }
                                    td { +(edge.targetRouteName ?: "-") }
                                }
                            }
                        }
                    }
                }
                if (entity.schema.indexes.isNotEmpty()) {
                    table {
                        thead { tr { th { +"index" }; th { +"columns" }; th { +"unique" } } }
                        tbody {
                            for (idx in entity.schema.indexes) {
                                tr {
                                    td { +idx.name }
                                    td { +idx.columns.joinToString(", ") }
                                    td { +if (idx.unique) "yes" else "no" }
                                }
                            }
                        }
                    }
                }
            }
        }

    fun listPage(
        entity: EntViewerEntity<*>,
        columns: List<EntViewerColumn>,
        rows: List<EntViewerRow>,
        filters: List<EntViewerFilter>,
        order: EntViewerOrder?,
        page: Int,
        size: Int,
        hasNext: Boolean,
    ): EntViewerResponse = respond(200, entity.displayName) {
        h1 { +"${entity.displayName} " ; span("muted") { +"(${entity.schema.table})" } }

        // Active filters as removable chips.
        if (filters.isNotEmpty()) {
            div("chips") {
                filters.forEachIndexed { index, filter ->
                    span("chip") {
                        +filterLabel(filter)
                        a(href = listUrl(entity.routeName, filters.filterIndexed { i, _ -> i != index }, order, 1, size)) {
                            +"x"
                        }
                    }
                }
            }
        }

        // Add-filter form (GET; state carried in hidden inputs).
        val filterable = columns.filter { it.filterable }
        if (filterable.isNotEmpty()) {
            form(action = "$base/entities/${entity.routeName}", classes = "filter") {
                for (filter in filters) hiddenInput(name = "f") { value = filterToken(filter) }
                if (order != null) {
                    hiddenInput(name = "order") { value = order.column }
                    if (order.descending) hiddenInput(name = "dir") { value = "desc" }
                }
                hiddenInput(name = "size") { value = size.toString() }
                select {
                    attributes["name"] = "fc"
                    for (col in filterable) option { value = col.name; +col.name }
                }
                select {
                    attributes["name"] = "fo"
                    for (op in EntViewerFilterOp.entries) option { value = op.token; +op.token }
                }
                textInput(name = "fv") {}
                button { +"filter" }
            }
        }

        table {
            thead {
                tr {
                    for (col in columns) {
                        th {
                            if (col.orderable) {
                                val descending = order?.column == col.name && !order.descending
                                a(href = listUrl(entity.routeName, filters, EntViewerOrder(col.name, descending), 1, size)) {
                                    +col.name
                                    if (order?.column == col.name) +(if (order.descending) " v" else " ^")
                                }
                            } else {
                                +col.name
                            }
                        }
                    }
                }
            }
            tbody {
                for (row in rows) {
                    tr {
                        for (col in columns) {
                            val cell = row.values.firstOrNull { it.column == col.name }
                            td {
                                if (col.name == entity.schema.idColumn) {
                                    a(href = "$base/entities/${entity.routeName}/${encode(row.id)}") { +row.id }
                                } else {
                                    value(cell, truncateAt = listCellMax)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (rows.isEmpty()) p("muted") { +"No visible rows." }

        div("pager") {
            if (page > 1) a(href = listUrl(entity.routeName, filters, order, page - 1, size)) { +"< prev" }
            span("muted") { +"page $page" }
            if (hasNext) a(href = listUrl(entity.routeName, filters, order, page + 1, size)) { +"next >" }
        }
    }

    fun detailPage(
        entity: EntViewerEntity<*>,
        columns: List<EntViewerColumn>,
        row: EntViewerRow,
        visibleRoutes: Set<String>,
    ): EntViewerResponse = respond(200, "${entity.displayName} #${row.id}") {
        h1 { +"${entity.displayName} #${row.id}" }

        h2 { +"Fields" }
        table {
            tbody {
                for (col in columns) {
                    val cell = row.values.firstOrNull { it.column == col.name }
                    tr {
                        th { +col.name }
                        td { value(cell, truncateAt = null) }
                    }
                }
            }
        }

        if (entity.edges.isNotEmpty()) {
            h2 { +"Edges" }
            table {
                tbody {
                    for (edge in entity.edges) {
                        tr {
                            th { +edge.name }
                            td {
                                edgeCell(edge, row, visibleRoutes)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- fragments ----------

    private fun FlowContent.entityTable(entities: List<EntViewerEntity<*>>) {
        table {
            thead { tr { th { +"entity" }; th { +"table" }; th { +"columns" }; th { +"edges" } } }
            tbody {
                for (entity in entities) {
                    tr {
                        td { a(href = "$base/entities/${entity.routeName}") { +entity.displayName } }
                        td { +entity.schema.table }
                        td { +entity.columns.size.toString() }
                        td { +entity.edges.size.toString() }
                    }
                }
            }
        }
    }

    private fun FlowContent.value(cell: EntViewerValue?, truncateAt: Int?) {
        when {
            cell == null -> span("muted") { +"-" }
            cell.redacted -> span("redacted") { +"***" }
            cell.isNull -> span("null") { +"null" }
            else -> {
                val display = cell.display.orEmpty()
                if (truncateAt != null && display.length > truncateAt) {
                    +display.take(truncateAt)
                    span("muted") { +"… (${display.length} chars)" }
                } else {
                    +display
                }
            }
        }
    }

    private fun FlowContent.edgeCell(edge: EntViewerEdge, row: EntViewerRow, visibleRoutes: Set<String>) {
        val target = edge.targetRouteName
        if (target == null || target !in visibleRoutes) {
            span("muted") { +"${edge.cardinality} (not viewable)" }
            return
        }
        when {
            edge.localFkColumn != null -> {
                val fk = row.values.firstOrNull { it.column == edge.localFkColumn }
                when {
                    fk == null || fk.redacted -> span("muted") { +"${edge.cardinality} (redacted)" }
                    fk.isNull -> span("null") { +"null" }
                    else -> a(href = "$base/entities/$target/${encode(fk.display.orEmpty())}") {
                        +"$target #${fk.display}"
                    }
                }
            }
            edge.targetFilterColumn != null -> {
                val filter = EntViewerFilter(edge.targetFilterColumn, EntViewerFilterOp.EQ, row.id)
                a(href = listUrl(target, listOf(filter), null, 1, null)) {
                    +"${edge.cardinality} -> $target"
                }
            }
            else -> span("muted") { +"${edge.cardinality} (no generated traversal link in V1)" }
        }
    }

    // ---------- helpers ----------

    private fun respond(status: Int, pageTitle: String, content: MAIN.() -> Unit): EntViewerResponse {
        val body = createHTML().html {
            head {
                title { +"$pageTitle - entkt viewer" }
                style { unsafe { +viewerCss } }
            }
            body {
                nav("ent") {
                    span("brand") { +"entkt" }
                    a(href = base.ifEmpty { "/" }) { +"home" }
                    a(href = "$base/schema") { +"schema" }
                    a(href = "$base/entities") { +"entities" }
                }
                main { content() }
            }
        }
        return EntViewerResponse(status = status, body = "<!DOCTYPE html>\n$body")
    }

    private fun filterToken(filter: EntViewerFilter): String =
        if (filter.op.needsValue) "${filter.column}:${filter.op.token}:${filter.value.orEmpty()}"
        else "${filter.column}:${filter.op.token}"

    private fun filterLabel(filter: EntViewerFilter): String =
        if (filter.op.needsValue) "${filter.column} ${filter.op.token} ${filter.value.orEmpty()}"
        else "${filter.column} ${filter.op.token}"

    private fun listUrl(
        route: String,
        filters: List<EntViewerFilter>,
        order: EntViewerOrder?,
        page: Int,
        size: Int?,
    ): String {
        val params = mutableListOf<Pair<String, String>>()
        for (filter in filters) params.add("f" to filterToken(filter))
        if (order != null) {
            params.add("order" to order.column)
            if (order.descending) params.add("dir" to "desc")
        }
        if (page > 1) params.add("page" to page.toString())
        if (size != null && size != 50) params.add("size" to size.toString())
        val query = params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        return "$base/entities/$route" + if (query.isEmpty()) "" else "?$query"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
