package entkt.viewer.html

import kotlinx.html.FlowContent
import kotlinx.html.p

/**
 * Shown on privacy-windowed list pages: rows are a window over stored rows,
 * so sparse pages are expected and navigation is offered unconditionally.
 */
internal fun FlowContent.privacyBanner(size: Int) {
    p("muted") {
        +"Row-level privacy applies: pages window over stored rows, so a page may "
        +"show fewer than $size rows and later pages may still contain visible rows."
    }
}
