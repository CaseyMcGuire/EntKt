package entkt.viewer.html

import entkt.viewer.EntViewerFilter
import entkt.viewer.EntViewerOrder

/** List-page state threaded through table headers, chips, form, and pager. */
internal data class ListContext(
    val route: String,
    val filters: List<EntViewerFilter>,
    val order: EntViewerOrder?,
    val page: Int,
    val size: Int,
)
