package entkt.viewer.html

import entkt.viewer.EntViewerEntity
import entkt.viewer.EntViewerResponse
import kotlinx.html.h1

internal fun entityIndexPage(entities: List<EntViewerEntity<*>>, urls: ViewerUrls): EntViewerResponse =
    pageShell(200, "entities", urls) {
        h1 { +"Entities" }
        entityTable(entities, urls)
    }
