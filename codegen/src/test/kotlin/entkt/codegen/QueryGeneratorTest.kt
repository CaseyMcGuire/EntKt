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
    fun `generates where, order, limit, offset`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("`where`(predicate:")) { "Should have where\n$output" }
        assert(output.contains("fun orderAsc(")) { "Should have orderAsc\n$output" }
        assert(output.contains("fun orderDesc(")) { "Should have orderDesc\n$output" }
        assert(output.contains("fun limit(")) { "Should have limit\n$output" }
        assert(output.contains("fun offset(")) { "Should have offset\n$output" }
    }

    @Test
    fun `generates eq and neq for all fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun whereModelEq(")) { "Should have whereModelEq\n$output" }
        assert(output.contains("fun whereModelNeq(")) { "Should have whereModelNeq\n$output" }
        assert(output.contains("fun whereYearEq(")) { "Should have whereYearEq\n$output" }
        assert(output.contains("fun wherePriceEq(")) { "Should have wherePriceEq\n$output" }
    }

    @Test
    fun `generates in and notIn for all fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun whereModelIn(")) { "Should have whereModelIn\n$output" }
        assert(output.contains("fun whereModelNotIn(")) { "Should have whereModelNotIn\n$output" }
    }

    @Test
    fun `generates comparison predicates for numeric fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun whereYearGt(")) { "Should have whereYearGt\n$output" }
        assert(output.contains("fun whereYearGte(")) { "Should have whereYearGte\n$output" }
        assert(output.contains("fun whereYearLt(")) { "Should have whereYearLt\n$output" }
        assert(output.contains("fun whereYearLte(")) { "Should have whereYearLte\n$output" }
    }

    @Test
    fun `does not generate comparison predicates for string fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(!output.contains("fun whereModelGt(")) { "Should not have whereModelGt\n$output" }
    }

    @Test
    fun `generates string predicates for string fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun whereModelContains(")) { "Should have whereModelContains\n$output" }
        assert(output.contains("fun whereModelHasPrefix(")) { "Should have whereModelHasPrefix\n$output" }
        assert(output.contains("fun whereModelHasSuffix(")) { "Should have whereModelHasSuffix\n$output" }
    }

    @Test
    fun `does not generate string predicates for numeric fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(!output.contains("fun whereYearContains(")) { "Should not have whereYearContains\n$output" }
    }

    @Test
    fun `generates null predicates for optional fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun wherePriceIsNull(): CarQuery")) { "Should have wherePriceIsNull\n$output" }
        assert(output.contains("fun wherePriceIsNotNull(): CarQuery")) { "Should have wherePriceIsNotNull\n$output" }
    }

    @Test
    fun `does not generate null predicates for required fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(!output.contains("fun whereModelIsNull")) { "Should not have whereModelIsNull\n$output" }
    }

    @Test
    fun `includes mixin fields`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("fun whereCreatedAtGt(")) { "Should have mixin time field predicates\n$output" }
        assert(output.contains("fun whereUpdatedAtLte(")) { "Should have mixin time field predicates\n$output" }
    }
}