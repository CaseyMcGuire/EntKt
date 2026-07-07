package entkt.viewer.html

import kotlinx.html.FlowContent
import kotlinx.html.div

internal fun FlowContent.errorBox(message: String) {
    div("error") { +message }
}
