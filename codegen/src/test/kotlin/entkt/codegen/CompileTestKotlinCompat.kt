package entkt.codegen

import com.squareup.kotlinpoet.FileSpec
import com.tschuchort.compiletesting.SourceFile

/**
 * Kotlin-version compatibility shim for the kctfork compile tests.
 *
 * The project compiles generated output with Kotlin 2.3.x, where a
 * function-level `return` inside an expression body
 * (`fun f(): T = try { ... return x ... }`) is legal. The in-process
 * compile-testing harness (kctfork 0.5.1) bundles
 * kotlin-compiler-embeddable 2.0.0, which still rejects that shape with
 * "Returns are prohibited for functions with an expression body", so the
 * real emitted text cannot be fed to the harness verbatim.
 *
 * [rewriteReturnBearingExpressionBodies] converts every `fun`/accessor
 * expression body of the form `= try { ... } catch ... [finally ...]`
 * into the semantically identical block body
 * `{ return try { ... } catch ... }` before handing sources to kctfork.
 * Local initializers (`val row = try { ... }`) are left untouched. The
 * transform is purely syntactic sugar-removal: the compile tests still
 * exercise the real generated declarations, call sites, and result
 * construction; the untransformed text is proven compilable by the
 * `:integration-tests:compileKotlin` gate and pinned by the
 * string-assertion generator tests.
 *
 * Remove this shim once the codegen test harness compiler is aligned
 * with the project compiler (kctfork release built on Kotlin >= 2.3, or
 * an explicit kotlin-compiler-embeddable 2.3.x test dependency).
 */
internal fun List<FileSpec>.toCompileTestSources(): List<SourceFile> =
    map { SourceFile.kotlin("${it.name}.kt", rewriteReturnBearingExpressionBodies(it.toString())) }

internal fun rewriteReturnBearingExpressionBodies(source: String): String {
    val mask = codeMask(source)

    fun isCode(i: Int) = i in source.indices && mask[i]

    fun prevCode(from: Int): Int {
        var i = from
        while (i >= 0 && (!mask[i] || source[i].isWhitespace())) i--
        return i
    }

    fun nextCode(from: Int): Int {
        var i = from
        while (i < source.length && (!mask[i] || source[i].isWhitespace())) i++
        return i
    }

    fun matchDelimiter(open: Int, openCh: Char, closeCh: Char): Int {
        var depth = 0
        var i = open
        while (i < source.length) {
            if (mask[i]) {
                when (source[i]) {
                    openCh -> depth++
                    closeCh -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        error("Unbalanced '$openCh' starting at offset $open")
    }

    fun wordAt(i: Int): String {
        if (i >= source.length || !isCode(i) || !(source[i].isLetter() || source[i] == '_')) return ""
        var j = i
        while (j < source.length && isCode(j) && (source[j].isLetterOrDigit() || source[j] == '_')) j++
        return source.substring(i, j)
    }

    // Collected as (position-sorted) edits; applied back-to-front.
    data class Edit(val start: Int, val endExclusive: Int, val replacement: String)

    val edits = mutableListOf<Edit>()
    var i = 0
    while (i < source.length) {
        if (isCode(i) && source.startsWith("try", i) && wordAt(i) == "try" &&
            (i == 0 || !isCode(i - 1) || !(source[i - 1].isLetterOrDigit() || source[i - 1] == '_'))
        ) {
            val eq = prevCode(i - 1)
            val openBrace = nextCode(i + 3)
            if (eq >= 0 && source[eq] == '=' && openBrace < source.length && source[openBrace] == '{' &&
                // not `==`, `!=`, `<=`, `>=`, `+=` etc.
                (eq == 0 || source[eq - 1] !in "=!<>+-*/%")
            ) {
                // Distinguish a fun/accessor expression body from an
                // ordinary assignment or local initializer. A generated
                // fun expression body's '=' always follows the declared
                // return type (or a parameter list): the previous code
                // char is '>' / '?' / ')' for generic, nullable, and
                // untyped-accessor shapes, or a plain type identifier
                // that is itself introduced by ':'. Everything else —
                // `val row = try {`, `edges["author"] = try {`,
                // `x.y = try {`, `count = try {` — is an initializer or
                // assignment and must keep its expression form.
                val beforeEq = prevCode(eq - 1)
                val beforeEqCh = if (beforeEq >= 0) source[beforeEq] else ' '
                var isFunBody = beforeEqCh == '>' || beforeEqCh == '?' || beforeEqCh == ')'
                if (!isFunBody && (beforeEqCh.isLetterOrDigit() || beforeEqCh == '_')) {
                    var identStart = beforeEq
                    while (identStart > 0 && isCode(identStart - 1) &&
                        (source[identStart - 1].isLetterOrDigit() || source[identStart - 1] == '_')
                    ) identStart--
                    val beforeIdent = prevCode(identStart - 1)
                    isFunBody = beforeIdent >= 0 && source[beforeIdent] == ':'
                }
                if (isFunBody) {
                    // Find the end of the try/catch/finally chain.
                    var end = matchDelimiter(openBrace, '{', '}')
                    while (true) {
                        val next = nextCode(end + 1)
                        when (wordAt(next)) {
                            "catch" -> {
                                val paren = nextCode(next + 5)
                                check(source[paren] == '(') { "expected ( after catch at $paren" }
                                val parenClose = matchDelimiter(paren, '(', ')')
                                val body = nextCode(parenClose + 1)
                                check(source[body] == '{') { "expected { after catch(...) at $body" }
                                end = matchDelimiter(body, '{', '}')
                            }
                            "finally" -> {
                                val body = nextCode(next + 7)
                                check(source[body] == '{') { "expected { after finally at $body" }
                                end = matchDelimiter(body, '{', '}')
                            }
                            else -> break
                        }
                    }
                    // Replace from '=' through the 'try' keyword so no
                    // original line break survives between `return` and
                    // `try` (a newline there would parse as a bare
                    // Unit-returning `return` statement).
                    edits.add(Edit(eq, i + 3, "{\n    return try"))
                    edits.add(Edit(end + 1, end + 1, "\n  }"))
                    i = openBrace
                }
            }
        }
        i++
    }

    if (edits.isEmpty()) return source
    val sb = StringBuilder(source)
    for (edit in edits.sortedByDescending { it.start }) {
        sb.replace(edit.start, edit.endExclusive, edit.replacement)
    }
    return sb.toString()
}

/**
 * Marks which characters of [source] are code, as opposed to the
 * interiors of comments, string/char literals, and KDoc. String-template
 * expressions (`${ ... }`) are treated as code so brace matching stays
 * balanced; nested strings, lambdas, and comments inside templates
 * recurse correctly.
 */
private fun codeMask(source: String): BooleanArray {
    val mask = BooleanArray(source.length)

    // skipCode and skipString are mutually recursive (strings may contain
    // ${code}; code may contain strings); a local lateinit reference
    // breaks the declaration-order cycle.
    lateinit var skipString: (Int, Boolean) -> Int

    // Returns the index just past the construct starting at i.
    fun skipLineComment(start: Int): Int {
        var i = start
        while (i < source.length && source[i] != '\n') i++
        return i
    }

    fun skipBlockComment(start: Int): Int {
        var i = start + 2
        var depth = 1
        while (i < source.length && depth > 0) {
            if (source.startsWith("/*", i)) {
                depth++; i += 2
            } else if (source.startsWith("*/", i)) {
                depth--; i += 2
            } else i++
        }
        return i
    }

    fun skipCharLiteral(start: Int): Int {
        var i = start + 1
        while (i < source.length && source[i] != '\'') {
            if (source[i] == '\\') i++
            i++
        }
        return i + 1
    }

    fun skipCode(start: Int, stopAtUnbalancedCloseBrace: Boolean): Int {
        var i = start
        var depth = 0
        while (i < source.length) {
            val c = source[i]
            when {
                source.startsWith("//", i) -> i = skipLineComment(i)
                source.startsWith("/*", i) -> i = skipBlockComment(i)
                c == '\'' -> i = skipCharLiteral(i)
                source.startsWith("\"\"\"", i) -> i = skipString(i, true)
                c == '"' -> i = skipString(i, false)
                else -> {
                    if (c == '{') depth++
                    if (c == '}') {
                        if (depth == 0 && stopAtUnbalancedCloseBrace) return i + 1
                        depth--
                    }
                    mask[i] = true
                    i++
                }
            }
        }
        return i
    }

    skipString = fun(start: Int, raw: Boolean): Int {
        var i = start + if (raw) 3 else 1
        while (i < source.length) {
            when {
                !raw && source[i] == '\\' -> i += 2
                source.startsWith("\${", i) -> i = skipCode(i + 2, stopAtUnbalancedCloseBrace = true)
                raw && source.startsWith("\"\"\"", i) && (i + 3 >= source.length || source[i + 3] != '"') ->
                    return i + 3
                !raw && source[i] == '"' -> return i + 1
                else -> i++
            }
        }
        return i
    }

    skipCode(0, stopAtUnbalancedCloseBrace = false)
    return mask
}
