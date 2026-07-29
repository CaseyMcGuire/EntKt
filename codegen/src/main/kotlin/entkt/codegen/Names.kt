package entkt.codegen

import entkt.schema.Field

// ------------------------------------------------------------------
// Naming conventions shared by every generator: how schema names
// (snake_case tables, columns, edges) become generated Kotlin member
// names, and how a field maps to its column.
// ------------------------------------------------------------------

/** The database column name for this field. */
internal val Field.columnName: String get() = name

internal fun toCamelCase(snakeCase: String): String {
    return snakeCase.split("_").mapIndexed { index, part ->
        if (index == 0) part.lowercase()
        else part.replaceFirstChar { it.uppercase() }
    }.joinToString("")
}

internal fun toPascalCase(snakeCase: String): String =
    toCamelCase(snakeCase).replaceFirstChar { it.uppercase() }

/**
 * Naive English pluralization good enough for the small surface area of
 * generated repo property names. Handles the cases the example schemas
 * exercise (`user` → `users`, `post` → `posts`, `tag` → `tags`,
 * `category` → `categories`) and is conservative everywhere else: if
 * the rule is unclear, it just appends `s`.
 */
internal fun pluralize(word: String): String {
    if (word.isEmpty()) return word
    return when {
        word.endsWith("y") && word.length > 1 && word[word.length - 2] !in "aeiou" ->
            word.dropLast(1) + "ies"
        word.endsWith("s") || word.endsWith("x") || word.endsWith("z") ||
            word.endsWith("ch") || word.endsWith("sh") -> word + "es"
        else -> word + "s"
    }
}
