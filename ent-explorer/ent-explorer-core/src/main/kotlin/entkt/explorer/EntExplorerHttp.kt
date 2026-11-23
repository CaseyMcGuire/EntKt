package entkt.explorer

/**
 * Framework-neutral request. Host frameworks (Spring, Ktor, ...) adapt their
 * native request into this shape and hand it to [EntExplorer.handle]; the
 * explorer never sees the web stack.
 */
data class EntExplorerRequest(
    /** Full request path including the configured prefix, e.g. `/_ent/entities/user/123`. */
    val path: String,
    /** HTTP method; the read-only explorer rejects everything but GET. */
    val method: String = "GET",
    /** Query parameters (repeatable keys). */
    val query: Map<String, List<String>> = emptyMap(),
    /**
     * Application-supplied identity for the `authorize` and `viewerContext`
     * callbacks — typically the authenticated principal. Opaque to the explorer.
     */
    val principal: Any? = null,
) {
    fun queryFirst(name: String): String? = query[name]?.firstOrNull()
}

/** Framework-neutral response for the host to write out. */
data class EntExplorerResponse(
    val status: Int,
    val contentType: String = "text/html; charset=utf-8",
    val body: String,
    /**
     * True when this response should be indistinguishable from an unmapped
     * route (the unauthorized cloak). Hosts that can should discard [body]
     * and render their framework's own native 404 instead — the Spring
     * adapter throws `ResponseStatusException(NOT_FOUND)` — so probing the
     * mount path reveals nothing. The fallback [body] is unbranded.
     */
    val unmapped: Boolean = false,
)
