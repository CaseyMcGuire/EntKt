package entkt.viewer.html

import kotlinx.html.FlowContent
import kotlinx.html.div

/**
 * Flat, rounded, white surface that tables and sections render inside.
 * `overflow: hidden` lets the table's square corners clip to the card's
 * radius, so the tables themselves stay borderless with row separators.
 */
internal fun FlowContent.card(block: FlowContent.() -> Unit) {
    div("card") { block() }
}
