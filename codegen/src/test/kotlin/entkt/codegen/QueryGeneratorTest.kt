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

    @Test
    fun `query implements EdgeQuery`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("import entkt.query.EdgeQuery")) {
            "Should import EdgeQuery\n$output"
        }
        // The generated class takes a Driver in its primary constructor
        // now, so the EdgeQuery supertype moves to after the closing paren.
        assert(output.contains(": EdgeQuery") && output.contains("class CarQuery")) {
            "Query class should implement EdgeQuery\n$output"
        }
    }

    @Test
    fun `query implements combinedPredicate by ANDing accumulated wheres`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("override fun combinedPredicate(): Predicate?")) {
            "Should override combinedPredicate\n$output"
        }
        assert(output.contains("predicates.reduceOrNull")) {
            "Should fold predicates with reduceOrNull\n$output"
        }
        assert(output.contains("Predicate.And(acc, p)")) {
            "Should AND consecutive predicates\n$output"
        }
    }

    @Test
    fun `does not emit traversal methods for schemas with no edges`() {
        val output = generator.generate("Car", Car).toString()

        // Car has no edges in EntityGeneratorTest fixtures, so the
        // generated query should have no `queryX()` methods at all.
        assert(!output.contains("queryCars")) { "Car has no edges → no traversal\n$output" }
        assert(!output.contains("queryUsers")) { "Car has no edges → no traversal\n$output" }
    }

    @Test
    fun `does not emit traversal when schemaNames is empty`() {
        // User declares `to("cars", Car)`, but without a schemaNames map
        // we can't resolve the target's class name → no traversal method.
        val output = generator.generate("User", User).toString()

        assert(!output.contains("queryCars")) {
            "Without schemaNames, traversal should be skipped\n$output"
        }
    }

    @Test
    fun `does not emit eager loading methods when schemaNames is empty`() {
        val output = generator.generate("User", User).toString()

        assert(!output.contains("withCars")) {
            "Without schemaNames, with{Edge} should be skipped\n$output"
        }
        assert(!output.contains("loadEdges")) {
            "Without schemaNames, loadEdges should be skipped\n$output"
        }
    }

    @Test
    fun `does not emit eager loading methods for schema with no edges`() {
        val output = generator.generate("Car", Car).toString()

        assert(!output.contains("loadEdges")) {
            "Schema with no edges should not have loadEdges\n$output"
        }
    }
}