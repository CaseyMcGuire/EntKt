package entkt.explorer.html

import entkt.explorer.EntExplorerResponse
import entkt.explorer.explorerCss
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.main
import kotlinx.html.nav
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe

/**
 * The layout component every page renders inside: document head with the
 * Kotlin-generated stylesheet (the single `unsafe` block), the top nav, and
 * a `main` for the page's children.
 */
internal fun pageShell(
    status: Int,
    pageTitle: String,
    urls: ExplorerUrls,
    content: MAIN.() -> Unit,
): EntExplorerResponse {
    val body = createHTML().html {
        head {
            title { +"$pageTitle - EntKt Explorer" }
            style { unsafe { +explorerCss } }
        }
        body {
            nav("ent") {
                span("brand") { +"EntKt" }
                a(href = urls.home()) { +"Home" }
                a(href = urls.schema()) { +"Schema" }
                a(href = urls.entities()) { +"Entities" }
            }
            main { content() }
        }
    }
    return EntExplorerResponse(status = status, body = "<!DOCTYPE html>\n$body")
}
