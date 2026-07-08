package entkt.viewer.html

import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.textInput

/** GET search box; submits `?q=` back to [action]. */
internal fun FlowContent.searchForm(q: String, action: String, placeholder: String) {
    form(action = action, classes = "search") {
        textInput(name = "q") {
            value = q
            this.placeholder = placeholder
        }
        button { +"Search" }
    }
}
