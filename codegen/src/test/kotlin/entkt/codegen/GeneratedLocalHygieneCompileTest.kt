@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals

private class HygieneTarget : EntSchema("hygiene_targets", clientName = "hygieneTargets") {
    override fun id() = EntId.long()
}

/**
 * Every field here is named after a local the mutation generators bind
 * in the same function body. Under the declaration-name contract these
 * are ordinary, legal domain names — `values`, `candidate`, `entity`,
 * and `row` are all things a real schema might model — so generated
 * source must not shadow or redeclare its own locals because of them.
 */
private class Hygiene : EntSchema("hygiene", clientName = "hygienes") {
    override fun id() = EntId.long()

    val values by string("values_col")
    val candidate by string("candidate_col")
    val row by string("row_col")
    val prepared by string("prepared_col")
    val privacy by string("privacy_col")
    val violations by string("violations_col")
    val indexes by string("indexes_col")
    val snapshot by string("snapshot_col")
    val rules by string("rules_col")
    val payload by bytes("payload_col")

    val owner by belongsTo<HygieneTarget>("owner")
}

/**
 * Regression guard for generated-local hygiene.
 *
 * Preparation locals are named with the framework's reserved `_entkt`
 * prefix precisely so a field's declaration name can never collide with
 * a fixed local. A field named `candidate` previously emitted a second
 * `val candidate` in the same body and produced uncompilable source.
 *
 * See `docs/implemented-features/schema/schema-declaration-api-names.md`.
 */
class GeneratedLocalHygieneCompileTest {

    private fun compile(): JvmCompilationResult {
        val target = HygieneTarget()
        val schema = Hygiene()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(
            target::class to target,
            schema::class to schema,
        )
        target.finalize(registry)
        schema.finalize(registry)
        val sources = EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(target), SchemaInput(schema)))
            .toCompileTestSources()
        return KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()
    }

    @Test
    fun `fields named after generated locals still produce compilable source`() {
        val result = compile()
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Generated source must not redeclare its own locals:\n${result.messages}",
        )
    }
}
