package entkt.viewer.html

import entkt.viewer.EntViewerValue
import kotlinx.html.FlowContent
import kotlinx.html.span

/** Truncation for list cells; the detail page renders full values. */
internal const val LIST_CELL_MAX = 120

/**
 * One cell: redacted (`***`), null (distinct from empty string), absent
 * (`-`), or the display text — truncated in list cells, full in detail.
 */
internal fun FlowContent.valueCell(cell: EntViewerValue?, truncateAt: Int?) {
    when {
        cell == null -> span("muted") { +"-" }
        cell.redacted -> span("redacted") { +"***" }
        cell.isNull -> span("null") { +"null" }
        else -> {
            val display = cell.display.orEmpty()
            if (truncateAt != null && display.length > truncateAt) {
                +display.take(truncateAt)
                span("muted") { +"… (${display.length} chars)" }
            } else {
                +display
            }
        }
    }
}
