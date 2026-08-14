package entkt.viewer.html

import kotlinx.html.FlowContent
import kotlinx.html.p

/**
 * Shown when strict LOAD privacy rejects a list window. The whole page is
 * empty rather than partially filtered, and navigation remains available
 * because later windows may still be visible.
 */
internal fun FlowContent.privacyBanner(size: Int) {
    p("muted") {
        +"Row-level privacy applies: this $size-row page is empty because its window included "
        +"a denied row. Later pages may still contain visible rows."
    }
}
