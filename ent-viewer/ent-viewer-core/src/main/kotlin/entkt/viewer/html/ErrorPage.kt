package entkt.viewer.html

import entkt.viewer.EntViewerResponse

internal fun errorPage(status: Int, message: String, urls: ViewerUrls): EntViewerResponse =
    pageShell(status, "error", urls) {
        errorBox(message)
    }
