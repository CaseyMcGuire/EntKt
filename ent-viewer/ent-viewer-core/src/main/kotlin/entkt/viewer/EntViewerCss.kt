package entkt.viewer

import kotlinx.css.Border
import kotlinx.css.BorderCollapse
import kotlinx.css.BorderStyle
import kotlinx.css.Color
import kotlinx.css.CssBuilder
import kotlinx.css.Cursor
import kotlinx.css.Display
import kotlinx.css.FontStyle
import kotlinx.css.FontWeight
import kotlinx.css.Margin
import kotlinx.css.Overflow
import kotlinx.css.Padding
import kotlinx.css.TextAlign
import kotlinx.css.VerticalAlign
import kotlinx.css.WhiteSpace
import kotlinx.css.backgroundColor
import kotlinx.css.border
import kotlinx.css.borderBottom
import kotlinx.css.borderCollapse
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.cursor
import kotlinx.css.display
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.fontStyle
import kotlinx.css.fontWeight
import kotlinx.css.letterSpacing
import kotlinx.css.lineHeight
import kotlinx.css.margin
import kotlinx.css.marginRight
import kotlinx.css.maxWidth
import kotlinx.css.overflow
import kotlinx.css.padding
import kotlinx.css.pct
import kotlinx.css.px
import kotlinx.css.textAlign
import kotlinx.css.textDecoration
import kotlinx.css.verticalAlign
import kotlinx.css.whiteSpace
import kotlinx.css.width
import kotlinx.css.LinearDimension
import kotlinx.css.properties.LineHeight
import kotlinx.css.properties.TextDecoration
import kotlinx.css.properties.TextDecorationLine

/**
 * The viewer stylesheet, built with the kotlin-css DSL (no frontend build
 * step). Flat and white-first: content sits on rounded white cards over a
 * near-white page, tables use row separators instead of grid borders, and
 * chrome is a system UI sans face with data values in mono. Utilitarian on
 * purpose — no marketing surface.
 */
internal val viewerCss: String = buildCss()

private fun buildCss(): String = CssBuilder().apply {
    val ink = Color("#1c2024")
    val muted = Color("#6a737d")
    val line = Color("#e4e7eb")
    val faintLine = Color("#eef1f4")
    val page = Color("#f8f9fa")
    val white = Color("#ffffff")
    val accent = Color("#2563eb")
    val sans = "-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Inter,Helvetica,Arial,sans-serif"
    val mono = "ui-monospace,SFMono-Regular,Menlo,Consolas,monospace"

    rule("body") {
        fontFamily = sans
        fontSize = 14.px
        lineHeight = LineHeight("1.5")
        color = ink
        backgroundColor = page
        margin = Margin(0.px)
    }

    // Flat white nav: bottom hairline instead of a dark bar.
    rule("nav.ent") {
        backgroundColor = white
        borderBottom = Border(1.px, BorderStyle.solid, line)
        // The hairline spans the page; the links align with the centered
        // 1200px content column.
        padding = Padding(10.px, LinearDimension("max(18px, calc((100% - 1200px) / 2))"))
    }
    rule("nav.ent a") {
        color = ink
        textDecoration = TextDecoration.none
        marginRight = 16.px
    }
    rule("nav.ent a:hover") { color = accent }
    rule("nav.ent .brand") {
        color = accent
        fontWeight = FontWeight.w700
        marginRight = 22.px
    }

    rule("main") {
        padding = Padding(16.px, 18.px)
        maxWidth = 1200.px
        // Center the content column.
        margin = Margin(0.px, LinearDimension.auto)
    }
    rule("h1") { fontSize = 18.px; fontWeight = FontWeight.w600; margin = Margin(8.px, 0.px, 12.px, 0.px) }
    rule("h2") { fontSize = 14.px; fontWeight = FontWeight.w600; color = muted; margin = Margin(20.px, 0.px, 8.px, 0.px) }

    // Rounded white card; tables clip to its radius.
    rule(".card") {
        backgroundColor = white
        border = Border(1.px, BorderStyle.solid, line)
        borderRadius = 10.px
        overflow = Overflow.hidden
        margin = Margin(10.px, 0.px)
    }

    // Flat tables: no grid, row separators only.
    rule("table") {
        borderCollapse = BorderCollapse.collapse
        width = 100.pct
    }
    rule("th,td") {
        padding = Padding(7.px, 12.px)
        textAlign = TextAlign.left
        verticalAlign = VerticalAlign.top
        borderBottom = Border(1.px, BorderStyle.solid, faintLine)
    }
    rule("th") {
        fontSize = 12.px
        fontWeight = FontWeight.w600
        color = muted
        whiteSpace = WhiteSpace.nowrap
        borderBottom = Border(1.px, BorderStyle.solid, line)
        backgroundColor = white
    }
    rule("tbody tr:last-child td") { borderBottom = Border.none }
    rule("tbody tr:hover td") { backgroundColor = Color("#fafbfc") }
    // Data values read best in mono; chrome stays sans.
    rule("td") { fontFamily = mono; fontSize = 13.px }
    rule("th a") { color = muted; textDecoration = TextDecoration.none }
    rule("th a:hover") { color = accent }
    rule("td a") { color = accent; textDecoration = TextDecoration.none }
    rule("td a:hover") { textDecoration = TextDecoration(setOf(TextDecorationLine.underline)) }

    rule(".null") { color = muted; fontStyle = FontStyle.italic }
    rule(".redacted") { color = muted; letterSpacing = 2.px }
    rule(".muted") { color = muted }

    // Pill badges for column flags.
    rule(".flag") {
        display = Display.inlineBlock
        backgroundColor = Color("#f1f3f5")
        color = muted
        borderRadius = 999.px
        padding = Padding(1.px, 9.px)
        marginRight = 4.px
        fontSize = 11.px
        fontFamily = sans
    }
    rule(".flag.sensitive") { backgroundColor = Color("#fef3f2"); color = Color("#b42318") }

    // Filter chips as rounded pills.
    rule(".chips") { margin = Margin(8.px, 0.px) }
    rule(".chip") {
        display = Display.inlineBlock
        backgroundColor = Color("#eff4ff")
        color = Color("#1d4ed8")
        borderRadius = 999.px
        padding = Padding(2.px, 11.px)
        marginRight = 6.px
        fontSize = 12.px
    }
    rule(".chip a") { color = Color("#b42318"); textDecoration = TextDecoration.none }

    rule("form.filter") { margin = Margin(10.px, 0.px) }
    rule("form.filter select,form.filter input,form.filter button") { marginRight = 6.px }
    rule("select,input") {
        fontFamily = sans
        fontSize = 13.px
        padding = Padding(4.px, 8.px)
        border = Border(1.px, BorderStyle.solid, line)
        borderRadius = 7.px
        backgroundColor = white
        color = ink
    }
    rule("button") {
        fontFamily = sans
        fontSize = 13.px
        fontWeight = FontWeight.w600
        padding = Padding(4.px, 14.px)
        border = Border(1.px, BorderStyle.solid, line)
        borderRadius = 7.px
        backgroundColor = white
        color = accent
        cursor = Cursor.pointer
    }
    rule("button:hover") { backgroundColor = Color("#eff4ff") }

    rule(".pager") { margin = Margin(10.px, 0.px) }
    rule(".pager a") { marginRight = 12.px; color = accent; textDecoration = TextDecoration.none }
    rule(".pager a:hover") { textDecoration = TextDecoration(setOf(TextDecorationLine.underline)) }

    rule(".error") {
        backgroundColor = Color("#fef3f2")
        color = Color("#b42318")
        border = Border(1.px, BorderStyle.solid, Color("#f7cfcb"))
        borderRadius = 10.px
        padding = Padding(10.px, 14.px)
        margin = Margin(12.px, 0.px)
    }
}.toString()
