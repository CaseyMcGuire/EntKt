package entkt.explorer.html

import entkt.explorer.EntExplorerEntity
import entkt.explorer.EntExplorerResponse
import kotlinx.html.h1

internal fun entityIndexPage(entities: List<EntExplorerEntity<*>>, urls: ExplorerUrls): EntExplorerResponse =
    pageShell(200, "Entities", urls) {
        h1 { +"Entities" }
        entityTable(entities, urls)
    }
