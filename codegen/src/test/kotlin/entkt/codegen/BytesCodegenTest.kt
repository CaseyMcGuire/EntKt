package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

private class BinaryRecord : EntSchema("binary_records", clientName = "binaryRecords") {
    override fun id() = EntId.long()
    val payload by bytes("payload")
    val thumbnail by bytes("thumbnail").nullable()
}

class BytesCodegenTest {
    private val files: Map<String, String> by lazy {
        val schema = BinaryRecord()
        schema.finalize(mapOf(BinaryRecord::class to schema))
        EntGenerator("com.example.ent", viewer = true)
            .generate(listOf(SchemaInput(schema)))
            .associate { it.name to it.toString().replace(Regex("\\s+"), " ") }
    }

    @Test
    fun `binary types propagate through entities columns drafts and lifecycle values`() {
        val entity = files.getValue("BinaryRecord")
        assertContains(entity, "Column<BinaryRecord, Bytes>(\"payload\")")
        assertContains(entity, "NullableColumn<BinaryRecord, Bytes>(\"thumbnail\")")
        assertContains(entity, "row[\"payload\"] as Bytes")
        assertContains(entity, "row[\"thumbnail\"] as Bytes?")

        for (name in listOf(
            "BinaryRecord", "BinaryRecordCreateDraft", "BinaryRecordUpdateDraft",
            "BinaryRecordPrivacy",
            "BinaryRecordBeforeSaveState", "BinaryRecordBeforeCreateState", "BinaryRecordBeforeUpdateState",
        )) {
            val source = files.getValue(name)
            assertContains(source, "import entkt.types.Bytes", message = name)
            assertFalse("ByteArray" in source, name)
        }
        val privacy = files.getValue("BinaryRecordPrivacy")
        assertContains(privacy, "data class BinaryRecordWriteCandidate")
        assertContains(privacy, "data class BinaryRecordUpdatePatch")
        assertContains(privacy, "val payload: FieldPatch<Bytes>")
        assertContains(privacy, "val thumbnail: FieldPatch<Bytes?>")
    }

    @Test
    fun `binary fields do not generate equality overrides or lifecycle array copies`() {
        for ((name, source) in files) {
            assertFalse("override fun equals" in source, name)
            assertFalse("override fun hashCode" in source, name)
            assertFalse("copyOf" in source, name)
            assertFalse("toByteArray" in source, name)
        }
    }

    @Test
    fun `viewer shows Bytes without changing binary filtering capabilities`() {
        val viewer = files.getValue("BinaryRecordViewerEntity")
        assertContains(
            viewer,
            """EntViewerColumn(name = "payload", type = FieldType.BYTES, nullable = false, unique = false, sensitive = false, filterable = false, orderable = false, entType = "Bytes")""",
        )
        assertContains(viewer, "entity.payload.size")
    }
}
