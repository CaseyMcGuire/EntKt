package entkt.codegen

import kotlin.test.Test

class QueryGeneratorTest {

    private val generator = QueryGenerator("com.example.ent")

    @Test
    fun `generates query builder class`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("class CarQuery")) { "Should generate CarQuery\n$output" }
    }

    @Test
    fun `query builder is annotated as DSL scope`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("@EntktDsl")) { "Should be annotated @EntktDsl\n$output" }
    }

    @Test
    fun `generates where that takes a Predicate`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("`where`(predicate: Predicate)")) {
            "Should have where(Predicate)\n$output"
        }
    }

    @Test
    fun `generates orderBy that takes an OrderField`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun orderBy(`field`: OrderField)")) {
            "Should have orderBy(OrderField)\n$output"
        }
    }

    @Test
    fun `generates limit and offset`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun limit(n: Int)")) { "Should have limit\n$output" }
        assert(output.contains("fun offset(n: Int)")) { "Should have offset\n$output" }
    }

    @Test
    fun `does not emit per-field predicate methods`() {
        val output = generator.generate("Car", Car).toString()

        // All predicate construction is now done via typed column refs on
        // the entity's companion object — the query class should only
        // carry where/orderBy/limit/offset.
        assert(!output.contains("whereModelEq")) { "Should not have whereModelEq\n$output" }
        assert(!output.contains("whereYearGt")) { "Should not have whereYearGt\n$output" }
        assert(!output.contains("whereModelContains")) { "Should not have whereModelContains\n$output" }
    }
}