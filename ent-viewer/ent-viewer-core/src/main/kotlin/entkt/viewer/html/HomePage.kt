package entkt.viewer.html

import entkt.viewer.EntViewerEntity
import entkt.viewer.EntViewerResponse
import kotlinx.html.h1
import kotlinx.html.p

internal fun homePage(entities: List<EntViewerEntity<*>>, urls: ViewerUrls): EntViewerResponse =
    pageShell(200, "EntKt Viewer", urls) {
        h1 { +"EntKt Viewer" }
        p("muted") { +"Read-only. Rows are what the configured privacy context can read." }
        entityTable(entities, urls)
    }
