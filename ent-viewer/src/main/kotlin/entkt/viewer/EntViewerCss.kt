package entkt.viewer

/**
 * The viewer stylesheet, generated from Kotlin (no frontend build step).
 * Utilitarian and dense on purpose: navigation, tables, detail lists,
 * redaction markers — no marketing surface.
 */
internal val viewerCss: String = buildString {
    fun rule(selector: String, vararg decls: String) {
        append(selector).append('{')
        decls.forEach { append(it).append(';') }
        append('}').append('\n')
    }

    rule(
        "body",
        "font:13px/1.45 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace",
        "color:#1a1f24", "background:#f7f8f9", "margin:0",
    )
    rule("nav.ent", "background:#1a1f24", "color:#e8ebee", "padding:6px 14px")
    rule("nav.ent a", "color:#9ecbff", "text-decoration:none", "margin-right:14px")
    rule("nav.ent a:hover", "text-decoration:underline")
    rule("nav.ent .brand", "color:#e8ebee", "font-weight:700", "margin-right:20px")
    rule("main", "padding:12px 14px", "max-width:1200px")
    rule("h1", "font-size:16px", "margin:6px 0 10px")
    rule("h2", "font-size:14px", "margin:16px 0 6px")
    rule("table", "border-collapse:collapse", "margin:8px 0", "background:#fff")
    rule("th,td", "border:1px solid #d4d9de", "padding:3px 8px", "text-align:left", "vertical-align:top")
    rule("th", "background:#eceff2", "font-weight:600", "white-space:nowrap")
    rule("th a", "color:#1a1f24", "text-decoration:none")
    rule("th a:hover", "text-decoration:underline")
    rule("td a", "color:#0b62c4")
    rule(".null", "color:#8a939c", "font-style:italic")
    rule(".redacted", "color:#8a939c", "letter-spacing:2px")
    rule(".muted", "color:#6b747d")
    rule(".flag", "display:inline-block", "background:#eceff2", "border-radius:3px", "padding:0 5px", "margin-right:4px", "font-size:11px")
    rule(".flag.sensitive", "background:#fbe4e4", "color:#8f2727")
    rule(".chips", "margin:6px 0")
    rule(".chip", "display:inline-block", "background:#e4ecf6", "border-radius:3px", "padding:1px 7px", "margin-right:6px")
    rule(".chip a", "color:#8f2727", "text-decoration:none", "margin-left:5px")
    rule("form.filter", "margin:8px 0", "display:flex", "gap:6px", "align-items:center")
    rule("select,input", "font:inherit", "padding:2px 4px", "border:1px solid #c3c9cf", "background:#fff")
    rule("button", "font:inherit", "padding:2px 10px", "border:1px solid #c3c9cf", "background:#eceff2", "cursor:pointer")
    rule(".pager", "margin:8px 0")
    rule(".pager a", "margin-right:12px", "color:#0b62c4")
    rule(".error", "background:#fbe4e4", "border:1px solid #e4b6b6", "padding:8px 12px", "margin:10px 0")
}
