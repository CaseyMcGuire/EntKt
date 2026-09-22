@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SchemaCommentTest {
    private class Commented(comment: String?) : EntSchema(
        "commented",
        clientName = "commented",
        comment = comment,
    ) {
        override fun id() = EntId.long()
        val name by string("name").comment("Display name.")
    }

    private class Uncommented : EntSchema("uncommented", clientName = "uncommented") {
        override fun id() = EntId.long()
        val name by string("name")
    }

    private val comment = "A \"quoted\" schema comment.\n\tPreserve \\ and \${literal}, <b>HTML</b>, and café."

    @Test
    fun `direct schema metadata preserves absent blank and multiline comments`() {
        for (value in listOf(null, "", " \n\t", comment)) {
            val schema = buildEntitySchemas(listOf(SchemaInput(Commented(value)))).single()

            assertEquals(value, schema.comment)
            assertEquals("Display name.", schema.columns.single { it.name == "name" }.comment)
        }
    }

    @Test
    fun `uncommented schemas retain the default without emitting an extra argument`() {
        val inputs = listOf(SchemaInput(Uncommented()))
        val generated = EntGenerator("com.example.ent").generate(inputs)
        val entity = generated.single { it.name == "Uncommented" }.toString()

        assertNull(buildEntitySchemas(inputs).single().comment)
        assertFalse("comment =" in entity, entity)
    }

    @Test
    fun `compiled generated comments match direct metadata without interpreting their text`() {
        val inputs = listOf(SchemaInput(Commented(comment)), SchemaInput(Uncommented()))
        val generated = EntGenerator("com.example.ent").generate(inputs)
        val application = SourceFile.kotlin(
            "SchemaCommentProbe.kt",
            """
            package com.example.app

            import com.example.ent.Commented
            import com.example.ent.Uncommented
            import entkt.runtime.driver.EntitySchema

            fun schemas(): List<EntitySchema> = listOf(Commented.SCHEMA, Uncommented.SCHEMA)
            """.trimIndent(),
        )

        val result = compileSources(generated.toCompileTestSources() + application)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals(
            buildEntitySchemas(inputs),
            result.classLoader.loadClass("com.example.app.SchemaCommentProbeKt").getMethod("schemas").invoke(null),
        )
    }
}
