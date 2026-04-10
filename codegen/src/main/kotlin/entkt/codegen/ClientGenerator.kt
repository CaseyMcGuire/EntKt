package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

private val DRIVER = ClassName("entkt.runtime", "Driver")

/**
 * Emits the top-level `EntClient` that wires every per-schema repo
 * together. The client takes a [Driver] in its constructor and exposes
 * each repo as a property — this is the dependency-injection seam:
 * production code constructs `EntClient(realDriver)`, tests construct
 * `EntClient(StubDriver)` (or a fake), and consumer code never touches
 * a static entry point.
 *
 * The repo property names are derived from the schema name by
 * camel-casing and pluralizing (`User` → `users`, `Post` → `posts`).
 */
class ClientGenerator(
    private val packageName: String,
) {

    fun generate(schemas: List<SchemaInput>): FileSpec {
        val typeSpec = TypeSpec.classBuilder("EntClient")
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("driver", DRIVER)
                    .build()
            )
            .addProperties(schemas.map { buildRepoProperty(it) })
            .build()

        return FileSpec.builder(packageName, "EntClient")
            .addType(typeSpec)
            .build()
    }

    private fun buildRepoProperty(input: SchemaInput): PropertySpec {
        val repoClass = ClassName(packageName, "${input.name}Repo")
        val propertyName = pluralize(input.name.replaceFirstChar { it.lowercase() })
        return PropertySpec.builder(propertyName, repoClass)
            .initializer("%T(driver)", repoClass)
            .build()
    }
}

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