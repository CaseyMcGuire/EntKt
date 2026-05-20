package entkt.codegen

import entkt.schema.EntSchema
import kotlin.reflect.KClass
import kotlin.test.Test

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class QueryGeneratorTest {

    private val generator = QueryGenerator("com.example.ent")

    @Test
    fun `generates query builder class`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("class CarQuery")) { "Should generate CarQuery\n$output" }
    }

    @Test
    fun `query builder is annotated as DSL scope`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("@EntktDsl")) { "Should be annotated @EntktDsl\n$output" }
    }

    @Test
    fun `generates where that takes a Predicate`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("`where`(predicate: Predicate)")) {
            "Should have where(Predicate)\n$output"
        }
    }

    @Test
    fun `generates orderBy that takes an OrderField`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("fun orderBy(`field`: OrderField)")) {
            "Should have orderBy(OrderField)\n$output"
        }
    }

    @Test
    fun `generates limit and offset`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("fun limit(n: Int)")) { "Should have limit\n$output" }
        assert(output.contains("fun offset(n: Int)")) { "Should have offset\n$output" }
    }

    @Test
    fun `does not emit per-field predicate methods`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // All predicate construction is now done via typed column refs on
        // the entity's companion object — the query class should only
        // carry where/orderBy/limit/offset.
        assert(!output.contains("whereModelEq")) { "Should not have whereModelEq\n$output" }
        assert(!output.contains("whereYearGt")) { "Should not have whereYearGt\n$output" }
        assert(!output.contains("whereModelContains")) { "Should not have whereModelContains\n$output" }
    }

    @Test
    fun `query implements EdgeQuery`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

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
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

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
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // Car has no edges in EntityGeneratorTest fixtures, so the
        // generated query should have no `queryX()` methods at all.
        assert(!output.contains("queryCars")) { "Car has no edges -> no traversal\n$output" }
        assert(!output.contains("queryUsers")) { "Car has no edges -> no traversal\n$output" }
    }

    @Test
    fun `does not emit traversal when schemaNames is empty`() {
        // User declares `hasMany<Car>("cars")`, but without a schemaNames map
        // we can't resolve the target's class name -> no traversal method.
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(!output.contains("queryCars")) {
            "Without schemaNames, traversal should be skipped\n$output"
        }
    }

    @Test
    fun `does not emit per-edge withMethods when schemaNames is empty but always emits loadEdges`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(!output.contains("withCars")) {
            "Without schemaNames, with{Edge} should be skipped\n$output"
        }
        // loadEdges is always emitted (RFC #5 Phase 7 fix): an M2M
        // eager-load block on a source query unconditionally calls
        // `subQuery.loadEdges(...)` on the target's query class, so
        // every query class needs the method even when its own schema
        // has no eager-loadable edges. The body is a no-op for such
        // schemas (the loop over schema.edges() iterates zero times).
        assert(output.contains("internal fun loadEdges(")) {
            "loadEdges should always be emitted, even when schemaNames is empty\n$output"
        }
    }

    @Test
    fun `always emits loadEdges as a no-op for schemas with no edges`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("internal fun loadEdges(")) {
            "loadEdges should always be emitted — needed by M2M eager-load callers from other queries\n$output"
        }
        // No per-edge eager block since Car has only a belongsTo
        // (currently not eager-loadable via withMethod).
        assert(!output.contains("if (results.isEmpty()) return results\n        var entities = results\n        eager")) {
            "Body should not reference any eagerProp fields for a schema with no eager edges\n$output"
        }
    }

    @Test
    fun `generates visibleCount terminal method`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("fun visibleCount(): Long")) {
            "Should generate visibleCount(): Long\n$output"
        }
        assert(output.contains("evaluateLoadPrivacy")) {
            "visibleCount() should evaluate LOAD privacy\n$output"
        }
    }

    @Test
    fun `generates rawCount terminal method`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("fun rawCount(): Long")) {
            "Should generate rawCount(): Long\n$output"
        }
        assert(output.contains("driver.count(Car.TABLE, spec.predicates)")) {
            "rawCount() should delegate to driver.count with the post-interceptor spec\n$output"
        }
    }

    @Test
    fun `generates rawExists and visibleExists terminal methods — legacy exists() removed`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // The legacy `exists()` that fetched the first row and threw
        // PrivacyDeniedException if it was denied has been removed
        // (neither "any row exists?" nor "row I can see exists?" was
        // the answer you got).
        assert(!output.contains("public fun exists(): Boolean")) {
            "Legacy exists() should be removed in favor of rawExists / visibleExists\n$output"
        }
        assert(output.contains("public fun rawExists(): Boolean")) {
            "Should generate rawExists(): Boolean\n$output"
        }
        assert(output.contains("public fun visibleExists(): Boolean")) {
            "Should generate visibleExists(): Boolean\n$output"
        }
        // rawExists bypasses LOAD privacy — it just probes one
        // storage row via driver.query and checks emptiness. Uses
        // the post-interceptor spec so interceptor predicates
        // (e.g. tenant_id = X) are honored on the existence probe.
        assert(output.contains("driver.query(Car.TABLE, spec.predicates, emptyList(), limit, spec.offset)")) {
            "rawExists should probe via driver.query with the post-interceptor spec\n$output"
        }
        // visibleExists still calls evaluateLoadPrivacy on the
        // privacy path.
        assert(output.contains("evaluateLoadPrivacy")) {
            "visibleExists should evaluate LOAD privacy on the privacy path\n$output"
        }
    }

    @Test
    fun `generates explain terminal method`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("fun explain(): QueryPlan")) {
            "Should generate explain(): QueryPlan\n$output"
        }
        assert(output.contains("driver.explainQuery(Car.TABLE, spec.predicates, spec.orderBy,")) {
            "explain() should delegate to driver.explainQuery with the post-interceptor spec\n$output"
        }
    }

    @Test
    fun `explain includes eager edge subqueries`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val output = generator.generate("User", user, mapOf(user to "User", car to "Car")).toString()

        assert(output.contains("eagerCars?.let { subQuery ->")) {
            "explain() should iterate eager edges\n$output"
        }
        // After Phase 5b + the eager-load explain interceptor fix,
        // the parent's eager block runs EAGER_LOAD interceptors on
        // the sub-query and delegates the actual driver.explainQuery
        // call to the sub-query's own buildQueryPlan. So the parent
        // *Query no longer directly references the target table in
        // driver.explainQuery; instead it builds a subSpec and
        // delegates.
        assert(output.contains("subQuery.runReadInterceptors(ReadOperation.EAGER_LOAD")) {
            "eager explain should fire target interceptors with EAGER_LOAD before building the plan\n$output"
        }
        assert(output.contains("subQuery.buildQueryPlan(subSpec")) {
            "eager explain should delegate plan construction to the sub-query's buildQueryPlan\n$output"
        }
        assert(output.contains("edges[\"cars\"]")) {
            "explain() should store edge subquery under edge name\n$output"
        }
        assert(output.contains("QueryExplanation.EXPLAIN_PLACEHOLDER")) {
            "eager explain should use EXPLAIN_PLACEHOLDER for IN predicates, not emptyList()\n$output"
        }
        assert(!output.contains("emptyList<Any>()")) {
            "eager explain should not use emptyList<Any>() — it causes the driver to render IN as FALSE\n$output"
        }
    }
}
