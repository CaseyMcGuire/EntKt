package entkt.viewer.html

import entkt.viewer.EntViewerFilter
import entkt.viewer.EntViewerOrder
import java.net.URLEncoder

/**
 * Link construction for viewer components — the one place that knows the
 * mount path and the URL grammar (filter tokens, order/page/size params,
 * path-segment vs query encoding). Handed to components as a prop so they
 * stay pure functions of their inputs.
 */
internal class ViewerUrls(basePath: String) {

    val base = basePath.trimEnd('/')

    fun home(): String = base.ifEmpty { "/" }
    fun schema(): String = "$base/schema"
    fun entities(): String = "$base/entities"

    fun schemaDetail(route: String): String = "$base/schema/$route"

    fun entityDetail(route: String, id: String): String =
        "$base/entities/$route/${pathEncode(id)}"

    fun entityList(
        route: String,
        filters: List<EntViewerFilter> = emptyList(),
        order: EntViewerOrder? = null,
        page: Int = 1,
        size: Int? = null,
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

    fun filterToken(filter: EntViewerFilter): String =
        if (filter.op.needsValue) "${filter.column}:${filter.op.token}:${filter.value.orEmpty()}"
        else "${filter.column}:${filter.op.token}"

    fun filterLabel(filter: EntViewerFilter): String =
        if (filter.op.needsValue) "${filter.column} ${filter.op.token} ${filter.value.orEmpty()}"
        else "${filter.column} ${filter.op.token}"

    /** Query-string encoding (application/x-www-form-urlencoded). */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    /**
     * Path-segment percent-encoding: form encoding turns space into '+',
     * which is a literal plus in a path. The router decodes segments with
     * the mirror rule, so string ids round-trip through their own links.
     */
    private fun pathEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
