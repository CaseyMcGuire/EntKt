package entkt.explorer.html

import entkt.explorer.EntExplorerEntity
import entkt.explorer.EntExplorerResponse
import kotlinx.html.h1
import kotlinx.html.p

internal fun homePage(entities: List<EntExplorerEntity<*>>, urls: ExplorerUrls): EntExplorerResponse =
    pageShell(200, "EntKt Explorer", urls) {
        h1 { +"EntKt Explorer" }
        p("muted") { +"Read-only. Rows are what the configured viewer context can read." }
        entityTable(entities, urls)
    }
