package entkt.explorer.html

import entkt.explorer.EntExplorerFilter
import entkt.explorer.EntExplorerOrder

/** List-page state threaded through table headers, chips, form, and pager. */
internal data class ListContext(
    val route: String,
    val filters: List<EntExplorerFilter>,
    val order: EntExplorerOrder?,
    val page: Int,
    val size: Int,
)
