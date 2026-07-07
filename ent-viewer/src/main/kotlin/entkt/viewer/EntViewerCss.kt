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
import kotlinx.css.Padding
import kotlinx.css.TextAlign
import kotlinx.css.VerticalAlign
import kotlinx.css.WhiteSpace
import kotlinx.css.backgroundColor
import kotlinx.css.border
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
import kotlinx.css.padding
import kotlinx.css.px
import kotlinx.css.textAlign
import kotlinx.css.textDecoration
import kotlinx.css.verticalAlign
import kotlinx.css.whiteSpace
import kotlinx.css.properties.LineHeight
import kotlinx.css.properties.TextDecoration
import kotlinx.css.properties.TextDecorationLine

/**
 * The viewer stylesheet, built with the kotlin-css DSL (no frontend build
 * step). Utilitarian and dense on purpose: navigation, tables, detail
 * lists, redaction markers — no marketing surface.
 */
internal val viewerCss: String = CssBuilder().apply {
    val ink = Color("#1a1f24")
    val paper = Color("#f7f8f9")
    val line = Color("#d4d9de")
    val panel = Color("#eceff2")
    val mutedInk = Color("#8a939c")
    val link = Color("#0b62c4")

    rule("body") {
        fontFamily = "ui-monospace,SFMono-Regular,Menlo,Consolas,monospace"
        fontSize = 13.px
        lineHeight = LineHeight("1.45")
        color = ink
        backgroundColor = paper
        margin = Margin(0.px)
    }
    rule("nav.ent") {
        backgroundColor = ink
        color = Color("#e8ebee")
        padding = Padding(6.px, 14.px)
    }
    rule("nav.ent a") {
        color = Color("#9ecbff")
        textDecoration = TextDecoration.none
        marginRight = 14.px
    }
    rule("nav.ent a:hover") { textDecoration = TextDecoration(setOf(TextDecorationLine.underline)) }
    rule("nav.ent .brand") {
        color = Color("#e8ebee")
        fontWeight = FontWeight.w700
        marginRight = 20.px
    }
    rule("main") {
        padding = Padding(12.px, 14.px)
        maxWidth = 1200.px
    }
    rule("h1") { fontSize = 16.px; margin = Margin(6.px, 0.px, 10.px, 0.px) }
    rule("h2") { fontSize = 14.px; margin = Margin(16.px, 0.px, 6.px, 0.px) }
    rule("table") {
        borderCollapse = BorderCollapse.collapse
        margin = Margin(8.px, 0.px)
        backgroundColor = Color("#ffffff")
    }
    rule("th,td") {
        border = Border(1.px, BorderStyle.solid, line)
        padding = Padding(3.px, 8.px)
        textAlign = TextAlign.left
        verticalAlign = VerticalAlign.top
    }
    rule("th") {
        backgroundColor = panel
        fontWeight = FontWeight.w600
        whiteSpace = WhiteSpace.nowrap
    }
    rule("th a") { color = ink; textDecoration = TextDecoration.none }
    rule("th a:hover") { textDecoration = TextDecoration(setOf(TextDecorationLine.underline)) }
    rule("td a") { color = link }
    rule(".null") { color = mutedInk; fontStyle = FontStyle.italic }
    rule(".redacted") { color = mutedInk; letterSpacing = 2.px }
    rule(".muted") { color = Color("#6b747d") }
    rule(".flag") {
        display = Display.inlineBlock
        backgroundColor = panel
        borderRadius = 3.px
        padding = Padding(0.px, 5.px)
        marginRight = 4.px
        fontSize = 11.px
    }
    rule(".flag.sensitive") { backgroundColor = Color("#fbe4e4"); color = Color("#8f2727") }
    rule(".chips") { margin = Margin(6.px, 0.px) }
    rule(".chip") {
        display = Display.inlineBlock
        backgroundColor = Color("#e4ecf6")
        borderRadius = 3.px
        padding = Padding(1.px, 7.px)
        marginRight = 6.px
    }
    rule(".chip a") { color = Color("#8f2727"); textDecoration = TextDecoration.none }
    rule("form.filter") { margin = Margin(8.px, 0.px) }
    rule("form.filter select,form.filter input,form.filter button") { marginRight = 6.px }
    rule("select,input") {
        fontFamily = "inherit"
        fontSize = 13.px
        padding = Padding(2.px, 4.px)
        border = Border(1.px, BorderStyle.solid, Color("#c3c9cf"))
        backgroundColor = Color("#ffffff")
    }
    rule("button") {
        fontFamily = "inherit"
        fontSize = 13.px
        padding = Padding(2.px, 10.px)
        border = Border(1.px, BorderStyle.solid, Color("#c3c9cf"))
        backgroundColor = panel
        cursor = Cursor.pointer
    }
    rule(".pager") { margin = Margin(8.px, 0.px) }
    rule(".pager a") { marginRight = 12.px; color = link }
    rule(".error") {
        backgroundColor = Color("#fbe4e4")
        border = Border(1.px, BorderStyle.solid, Color("#e4b6b6"))
        padding = Padding(8.px, 12.px)
        margin = Margin(10.px, 0.px)
    }
}.toString()
