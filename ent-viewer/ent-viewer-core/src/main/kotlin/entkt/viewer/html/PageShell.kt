package entkt.viewer.html

import entkt.viewer.EntViewerResponse
import entkt.viewer.viewerCss
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
    urls: ViewerUrls,
    content: MAIN.() -> Unit,
): EntViewerResponse {
    val body = createHTML().html {
        head {
            title { +"$pageTitle - entkt viewer" }
            style { unsafe { +viewerCss } }
        }
        body {
            nav("ent") {
                span("brand") { +"entkt" }
                a(href = urls.home()) { +"home" }
                a(href = urls.schema()) { +"schema" }
                a(href = urls.entities()) { +"entities" }
            }
            main { content() }
        }
    }
    return EntViewerResponse(status = status, body = "<!DOCTYPE html>\n$body")
}
