package entkt.explorer.html

import entkt.explorer.EntExplorerResponse

internal fun errorPage(status: Int, message: String, urls: ExplorerUrls): EntExplorerResponse =
    pageShell(status, "Error", urls) {
        errorBox(message)
    }
