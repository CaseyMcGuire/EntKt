@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class DateRecord : EntSchema("date_records", clientName = "dateRecords") {
    override fun id() = EntId.long()
    val startsOn by date("starts_on").default(LocalDate.of(2024, 2, 29)).unique()
    val endsOn by date("ends_on").nullable()
    val epochOn by date("epoch_on").default(LocalDate.of(0, 1, 1))
}

class DateCompileTest {
    private fun compile(body: String): JvmCompilationResult {
        val schema = DateRecord()
        schema.finalize(mapOf(DateRecord::class to schema))
        val generated = EntGenerator("com.example.ent").generate(listOf(SchemaInput(schema)))
        return KotlinCompilation().apply {
            sources = generated.toCompileTestSources() + SourceFile.kotlin(
                "DateApiProbe.kt",
                """
                import com.example.ent.DateRecord
                import com.example.ent.EntClient
                import entkt.query.isNotNull
                import entkt.runtime.privacy.ViewerContext
                import java.time.Instant
                import java.time.LocalDate

                fun checkApis(client: EntClient, context: ViewerContext) {
                    $body
                }
                """.trimIndent(),
            )
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()
    }

    @Test
    fun `generated date create update read and indexed query APIs compile with LocalDate`() {
        val result = compile(
            """
            val date = LocalDate.of(2024, 2, 29)
            val created: DateRecord = client.dateRecords.create {
                startsOn = date
                endsOn = null
            }.saveAndLoad(context).getOrThrow()
            val actualStartsOn: LocalDate = created.startsOn
            val actualEndsOn: LocalDate? = created.endsOn
            val updated: DateRecord = client.dateRecords.update(created.id) {
                startsOn = date.plusDays(1)
                endsOn = date.plusDays(2)
            }.saveAndLoad(context).getOrThrow()
            val rows: List<DateRecord> = client.dateRecords.query {
                where(DateRecord.startsOn gte date)
                where(DateRecord.endsOn.isNotNull())
                orderBy(DateRecord.startsOn.desc())
            }.all(context).getOrThrow()
            val found: DateRecord? = client.dateRecords.indexes.startsOn(date).find(context).getOrThrow()
            val range: List<DateRecord> = client.dateRecords.indexes.startsOn { gte(date) }
                .query().all(context).getOrThrow()
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `generated setters and predicates do not accept timestamps as dates`() {
        for (body in listOf(
            "client.dateRecords.create { startsOn = Instant.EPOCH }",
            "client.dateRecords.update(1L) { endsOn = Instant.EPOCH }",
            "client.dateRecords.query { where(DateRecord.startsOn gt Instant.EPOCH) }",
            "client.dateRecords.indexes.startsOn { gte(Instant.EPOCH) }",
        )) {
            val result = compile(body)
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertTrue(result.messages.contains("mismatch"), result.messages)
            assertTrue(result.messages.contains("LocalDate"), result.messages)
        }
    }
}
