package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Deliberately named after Kotlin declarations that generated sources
// reference through default imports. Legal Kotlin class declarations —
// the shadowing only bites inside the generated package, which is why
// validation rejects them up front. The bodies avoid the shadowed
// bare names on purpose.
private class Int : EntSchema("int_rows", clientName = "intRows") {
    override fun id() = EntId.long()

    val label by string("label")
}

private class MutableSet : EntSchema("mutable_set_rows", clientName = "mutableSetRows") {
    override fun id() = EntId.long()

    val label by string("label")
}

// Conditionally-emitted references count too: the regex validator
// emits `Regex(...)` / `RegexOption.X` only for schemas that use
// `matches(...)`, so the guard cannot be derived from any one
// schema's generated output.
private class Regex : EntSchema("regex_rows", clientName = "regexRows") {
    override fun id() = EntId.long()

    val label by string("label")
}

private class Plain : EntSchema("plain_rows", clientName = "plainRows") {
    override fun id() = EntId.long()

    val label by string("label")
}

/**
 * A schema class named after a Kotlin declaration that generated
 * sources reference as a bare, unimported name (`Int`, `MutableSet`,
 * `Regex`, ...) would shadow it wherever an emitter writes the name
 * as raw text — the reference carries no import, so the same-package
 * entity wins over the default import and the output does not
 * compile. Both codegen entry points reject such names up front:
 * `SchemaInspector.validate` collects the diagnostic,
 * `EntGenerator.generate` throws it.
 */
class ShadowedKotlinNameValidationTest {

    private fun assertShadowDiagnostic(errors: List<String>, schemaName: String) {
        assertTrue(
            errors.any {
                it.contains("'$schemaName'") &&
                    it.contains("Kotlin declaration") &&
                    it.contains("Rename the schema class")
            },
            "expected a shadowed-name diagnostic for '$schemaName'; got: $errors",
        )
    }

    @Test
    fun `validate rejects schema names that shadow Kotlin default imports`() {
        val result = SchemaInspector.validate(
            listOf(SchemaInput(Int()), SchemaInput(MutableSet()), SchemaInput(Regex()), SchemaInput(Plain())),
        )
        assertTrue(!result.valid)
        assertShadowDiagnostic(result.errors, "Int")
        assertShadowDiagnostic(result.errors, "MutableSet")
        assertShadowDiagnostic(result.errors, "Regex")
    }

    @Test
    fun `generate throws the same diagnostic`() {
        val schema = Int()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(schema::class to schema)
        schema.finalize(registry)
        val ex = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(SchemaInput(schema)))
        }
        assertTrue(
            "Kotlin declaration" in (ex.message ?: ""),
            "expected the shadowed-name diagnostic; got: ${ex.message}",
        )
    }

    @Test
    fun `ordinary schema names pass the shadowed-name check`() {
        val result = SchemaInspector.validate(listOf(SchemaInput(Plain())))
        assertTrue(
            result.errors.none { it.contains("Kotlin declaration") },
            "no shadowed-name diagnostic expected; got: ${result.errors}",
        )
    }
}
