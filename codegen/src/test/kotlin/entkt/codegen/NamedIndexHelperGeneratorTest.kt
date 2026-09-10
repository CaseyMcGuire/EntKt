package entkt.codegen

import entkt.codegen.query.IndexHelperGenerator
import entkt.codegen.query.QuerySurface
import entkt.codegen.query.indexHelperPathsByName
import entkt.postgres.vector.VectorMetric
import entkt.postgres.vector.hnsw
import entkt.postgres.vector.postgresVector
import entkt.postgres.vector.postgresVectorIndex
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NamedIndexHelperGeneratorTest {
    private class Problem : EntSchema("problems", clientName = "problems") {
        override fun id() = EntId.long()
    }

    private class TestCase : EntSchema("test_cases", clientName = "testCases") {
        override fun id() = EntId.long()
        val problem by belongsTo<Problem>("problem")
        val position by int("sort_position")
        val label by string("label")
        val byProblemAndPosition by index("uq_problem_position", problem.fk, position).unique()
        val byProblemAndLabel by index("idx_problem_label", problem.fk, label)
        val byLabel = index("idx_label", label)
    }

    private fun finalized(vararg schemas: EntSchema) {
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
    }

    private fun output(schema: EntSchema): String {
        finalized(schema)
        return IndexHelperGenerator("com.example.ent").generate("Record", schema)!!.toString()
            .replace("\\s+".toRegex(), " ")
    }

    @Test
    fun `named FK composite helpers return existing full-key stages on both read surfaces`() {
        val problem = Problem()
        val testCase = TestCase()
        finalized(problem, testCase)
        val names = mapOf(problem to "Problem", testCase to "TestCase")
        for (surface in QuerySurface.entries) {
            val out = IndexHelperGenerator("com.example.ent", surface)
                .generate("TestCase", testCase, names)!!.toString().replace("\\s+".toRegex(), " ")
            assertContains(out, "fun byProblemAndPosition(problemId: Long, position: Int): ProblemIdPosition")
            assertContains(out, "= this.problemId(problemId).position(position)")
            assertContains(out, "fun byProblemAndLabel(problemId: Long, label: String): ProblemIdLabel")
            assertContains(out, "= this.problemId(problemId).label(label)")
            assertContains(out, "fun problemId(problemId: Long)")
            assertContains(out, "fun position(position: Int)")
            assertContains(out, "fun find(viewerContext: ViewerContext): ReadResult<TestCase?>")
            assertFalse("fun byLabel(" in out, out)
            assertFalse("driver.query" in out, out)
            assertEquals(1, out.split("class ProblemId internal").size - 1, out)
        }
        assertContains(
            indexHelperPathsByName(testCase, names).getValue("uq_problem_position"),
            "indexes.byProblemAndPosition(problemId = problemId, position = position).query()",
        )
    }

    @Test
    fun `nullable unique and nonunique named helpers keep the existing query-only surface`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val nickname by string("nickname").nullable()
            val status by string("status")
            val byNickname by index("uq_nickname", nickname).unique()
            val byStatus by index("idx_status", status)
        }
        val out = output(Record())
        assertContains(out, "fun byNickname(nickname: String): Nickname")
        assertContains(out, "fun byStatus(status: String): Status")
        assertContains(out, "fun query(")
        assertFalse("fun find(" in out, out)
    }

    @Test
    fun `field-backed FK accessor arguments use the field declaration name`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val writer by long("problem_ref")
            val problem by belongsTo<Problem>("problem").field(writer)
            val byWriter by index("idx_problem_ref", problem.fk)
        }
        val record = Record()
        val problem = Problem()
        finalized(record, problem)
        val out = IndexHelperGenerator("com.example.ent")
            .generate("Record", record, mapOf(record to "Record", problem to "Problem"))!!.toString()
        assertContains(out, "fun byWriter(writer: Long): Writer")
        assertContains(out, "= this.writer(writer)")
    }

    @Test
    fun `named accessor arguments need no stage-local name suffixes`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val driver by long("driver_col")
            val driverValue by long("driver_value_col")
            val byDrivers by index("idx_drivers", driver, driverValue)
        }
        val out = output(Record())
        assertContains(out, "fun byDrivers(driver: Long, driverValue: Long): DriverDriverValue")
        assertContains(out, "= this.driver(driver).driverValue(driverValue)")
    }

    @Test
    fun `named accessors cannot overload root column helpers`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val problem by belongsTo<Problem>("problem")
            val position by int("position")
            val problemId by index("idx_problem_position", problem.fk, position)
        }
        val record = Record()
        val problem = Problem()
        finalized(record, problem)
        val error = assertFailsWith<IllegalStateException> {
            IndexHelperGenerator("com.example.ent")
                .generate("Record", record, mapOf(record to "Record", problem to "Problem"))
        }
        assertContains(error.message!!, "collides with column helper 'problemId'")
    }

    @Test
    fun `named accessors cannot overload existing namespace members`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val duration by long("duration")
            val wait by index("idx_duration", duration)
        }
        val error = assertFailsWith<IllegalStateException> { output(Record()) }
        assertContains(error.message!!, "collides with existing member 'wait'")
    }

    @Test
    fun `repeated indexed columns cannot produce duplicate accessor parameters`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val code by string("code")
            val byCode by index("idx_code_twice", code, code)
        }
        val error = assertFailsWith<IllegalStateException> { output(Record()) }
        assertContains(error.message!!, "would have duplicate parameters")
    }

    @Test
    fun `delegated partial indexes report an error instead of silently dropping the accessor`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val code by string("code")
            val byCode by index("idx_code", code).where("active = true")
        }
        val error = assertFailsWith<IllegalStateException> { output(Record()) }
        assertContains(error.message!!, "Index 'idx_code' declared as 'byCode'")
        assertContains(error.message!!, "partial indexes do not support equality helpers")
        assertContains(error.message!!, "Use `=` instead of `by`")

        val validation = SchemaInspector.validate(listOf(SchemaInput(Record())))
        assertFalse(validation.valid)
        assertContains(validation.errors.joinToString(), "cannot generate a named accessor")
    }

    @Test
    fun `delegated unsupported column types and native indexes are rejected`() {
        class Bytes : EntSchema("blobs", clientName = "blobs") {
            override fun id() = EntId.long()
            val payload by bytes("payload")
            val byPayload by index("idx_payload", payload)
        }
        class Vector : EntSchema("vectors", clientName = "vectors") {
            override fun id() = EntId.long()
            val embedding by postgresVector("embedding", dimensions = 3)
            val byEmbedding by postgresVectorIndex("idx_embedding", embedding).hnsw(VectorMetric.Cosine)
        }
        for (schema in listOf(Bytes(), Vector())) {
            val error = assertFailsWith<IllegalStateException> { output(schema) }
            assertContains(error.message!!, "cannot generate a named accessor")
            assertContains(error.message!!, "Use `=` instead of `by`")
        }
    }
}
