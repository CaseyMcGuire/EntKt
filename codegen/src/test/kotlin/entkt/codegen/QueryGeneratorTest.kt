package entkt.codegen

import entkt.codegen.query.QueryGenerator
import entkt.schema.EntSchema
import kotlin.reflect.KClass
import kotlin.test.Test

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

// A 1:1 pair — the shared Car/User fixtures only cover hasMany /
// belongsTo, and hasOne emits a distinct eager-load block.
private class OneOwner : EntSchema("one_owners") {
    override fun id() = entkt.schema.EntId.long()
    val badge = hasOne<OneBadge>("badge")
}

private class OneBadge : EntSchema("one_badges") {
    override fun id() = entkt.schema.EntId.long()
    val owner = belongsTo<OneOwner>("owner").inverse(OneOwner::badge).unique()
}

class QueryGeneratorTest {

    private val generator = QueryGenerator("com.example.ent")

    @Test
    fun `generates raw aggregate terminals`() {
        // Car has year (Int → IntegralColumn), price (Float? → FloatingColumn).
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // The shared helper routes through RAW_AGGREGATE + driver.aggregate.
        assert(output.contains("ReadOperation.RAW_AGGREGATE")) { "aggregateRows uses RAW_AGGREGATE\n$output" }
        assert(output.contains("driver.aggregate(Car.TABLE,")) { "aggregateRows calls driver.aggregate\n$output" }

        // Ungrouped scalar terminals, typed by the column marker.
        assert(output.contains("rawSum(column: IntegralColumn<Car, *>): Long?")) { "rawSum on integral → Long?\n$output" }
        assert(output.contains("rawSum(column: FloatingColumn<Car, *>): Double?")) { "rawSum on floating → Double?\n$output" }
        assert(output.contains("rawAvg(column: NumericColumn<Car, *>): Double?")) { "rawAvg → Double?\n$output" }
        assert(output.contains("rawMin(column: ComparableColumn<Car, T>)")) { "rawMin takes ComparableColumn\n$output" }
        assert(output.contains("rawMax(column: ComparableColumn<Car, T>)")) { "rawMax takes ComparableColumn\n$output" }

        // Grouped terminals: non-null key → K, nullable key → K?.
        assert(output.contains("rawCountBy(groupBy: GroupableColumn<Car, K>)")) { "rawCountBy non-null key overload\n$output" }
        assert(output.contains("AggregateBucket<K, Long>")) { "non-null key bucket is AggregateBucket<K, Long>\n$output" }
        assert(output.contains("rawCountBy(groupBy: NullableGroupableColumn<Car, K>)")) { "rawCountBy nullable key overload\n$output" }
        assert(output.contains("AggregateBucket<K?, Long>")) { "nullable key bucket is AggregateBucket<K?, Long>\n$output" }

        // Group keys are decoded via the column (enum-safe), not a raw cast.
        assert(output.contains("groupBy.decodeKey(it.key)")) { "group keys decode via the column\n$output" }

        // Grouped value terminals + …OrError twins exist.
        assert(output.contains("rawAvgBy")) { "rawAvgBy grouped terminal exists\n$output" }
        assert(output.contains("rawSumOrError")) { "rawSum has an OrError twin\n$output" }
        assert(output.contains("rawCountByOrError")) { "rawCountBy has an OrError twin\n$output" }
    }

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

        assert(output.contains("`where`(predicate: Predicate<Car>)")) {
            "Should have where(Predicate<Car>)\n$output"
        }
    }

    @Test
    fun `generates orderBy that takes an OrderField`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("fun orderBy(`field`: OrderField<Car>)")) {
            "Should have orderBy(OrderField<Car>)\n$output"
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
    fun `first-row terminals bound the fetch by the caller's limit`() {
        val car = Car()
        finalize(car, User())
        // KotlinPoet wraps long driver.query(...) calls mid-argument-list,
        // so match against a whitespace-normalized rendering.
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        // `limit(0)` means "no rows" on every other terminal family
        // (allOrThrow passes spec.limit straight through; rawExists /
        // visibleExists use `minOf(1, spec.limit ?: 1)`). The first-row
        // family used to send a hardwired 1 and return a row anyway.
        //
        // Interceptor limit mutators are silent no-ops at FIRST
        // (InterceptorEngine.limitOpsApply), so spec.limit here holds
        // nothing but the caller's own bound.
        assert(!output.contains("spec.orderBy, 1, spec.offset")) {
            "no first-row terminal should hardwire limit 1\n$output"
        }
        // The exists family passes `emptyList()` for orderBy, so
        // `spec.orderBy, limit, spec.offset` picks out exactly the two
        // first-row driver calls: firstOrNull and firstVisibleOrNull's
        // no-privacy branch. (firstVisibleOrNull's privacy branch already
        // derived its scan from spec.limit, and firstOrThrow /
        // firstOrError delegate rather than query directly.)
        val bounded = Regex(Regex.escape("spec.orderBy, limit, spec.offset")).findAll(output).count()
        assert(bounded == 2) {
            "expected firstOrNull and firstVisibleOrNull's no-privacy branch to clamp by spec.limit; found $bounded\n$output"
        }
    }

    @Test
    fun `first-shaped explain plans mirror the clamped runtime limit`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        // explain* is only useful if it reports what the terminal will
        // actually send. A plan pinned at `limit = 1` would hide the
        // caller's limit(0) rather than surface it.
        assert(!output.contains("spec.copy(limit = 1)")) {
            "explain should not pin a first-shaped plan at limit 1\n$output"
        }
        assert(output.contains("spec.copy(limit = minOf(1, spec.limit ?: 1))")) {
            "explainFirst / explainFirstVisibleOrNull should mirror the runtime clamp\n$output"
        }
    }

    @Test
    fun `eager loads skip the target fetch when the window admits nothing`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        // Eager blocks are only emitted when the edge target can be
        // named, so the schemaNames map is required here.
        val output = generator.generate("User", user, mapOf(user to "User", car to "Car"))
            .toString().replace("\\s+".toRegex(), " ")

        // A window that admits nothing would discard every fetched row,
        // so the round trip is pure waste. The interceptor pass above it
        // still runs — it fires on every eager subquery regardless of
        // bounds — so only the fetch is conditional.
        assert(output.contains("val targetRows = if (perGroupLimit > 0) driver.query(")) {
            "to-many eager fetch should be skipped when limit(0) admits nothing\n$output"
        }
    }

    @Test
    fun `a hasOne eager load also skips the fetch for a positive offset`() {
        // hasOne requires its inverse belongsTo to declare `.unique()`
        // (SchemaMetadata enforces it), so the unique index guarantees at
        // most one row per source — `drop(1)` provably leaves nothing.
        // The to-many paths can't use offset that way: skipping rows in a
        // group of many still leaves others.
        val owner = OneOwner()
        val badge = OneBadge()
        finalize(owner, badge)
        val output = generator
            .generate("OneOwner", owner, mapOf(owner to "OneOwner", badge to "OneBadge"))
            .toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("val targetInWindow = perGroupOffset == 0 && perGroupLimit > 0")) {
            "hasOne should treat a positive offset as an empty window\n$output"
        }
        assert(output.contains("val targetRows = if (targetInWindow) driver.query(")) {
            "hasOne eager fetch should be gated on that window\n$output"
        }
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

        assert(output.contains("override fun combinedPredicate(): Predicate<Car>?")) {
            "Should override combinedPredicate with typed return\n$output"
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
        // loadEdges is always emitted (link-table M2M helpers fix): an M2M
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
    fun `generates per-terminal explain mirrors`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // Every terminal gets its own explain method named after the
        // terminal; there is no shared "explain()" entry.
        for (name in listOf(
            "fun explainAllOrThrow(): QueryPlan",
            "fun explainAllOrError(): QueryPlan",
            "fun explainVisibleAll(): QueryPlan",
            "fun explainVisibleAllOrError(): QueryPlan",
            "fun explainFirstOrThrow(): QueryPlan",
            "fun explainFirstOrNull(): QueryPlan",
            "fun explainFirstOrError(): QueryPlan",
            "fun explainFirstVisibleOrNull(): QueryPlan",
            "fun explainRawCount(): QueryPlan",
            "fun explainVisibleCount(): QueryPlan",
            "fun explainRawExists(): QueryPlan",
            "fun explainVisibleExists(): QueryPlan",
        )) {
            assert(output.contains(name)) { "Should generate $name\n$output" }
        }
        assert(output.contains("driver.explainQuery(Car.TABLE, spec.predicates, spec.orderBy,")) {
            "row-shaped explain should delegate to driver.explainQuery with the post-interceptor spec\n$output"
        }
        // Rejection produces a rejected plan, not a throw.
        assert(output.contains("QueryPlan.rejected(e.queryRejected)")) {
            "explain methods should map EntQueryRejectedException to a rejected QueryPlan\n$output"
        }
    }

    @Test
    fun `exists explains drop orderBy and preserve caller offset (match runtime driver call)`() {
        // Runtime rawExists / visibleExists no-privacy fast path
        // calls driver.query(TABLE, spec.predicates, emptyList(),
        // minOf(1, spec.limit ?: 1), spec.offset). The explain
        // mirror must match exactly so the plan doesn't lie about
        // what the terminal would actually send. Pre-fix the
        // explain passed spec.orderBy and forced offset = null,
        // which silently disagreed with `query { orderBy(...);
        // offset(N) }.rawExists()`.
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // explainRawExists body should copy spec with orderBy =
        // emptyList() + limit clamp; offset must NOT be reset.
        assert(output.contains("spec.copy(orderBy = emptyList(), limit = minOf(1, spec.limit ?: 1))")) {
            "explainRawExists / explainVisibleExists no-privacy path should pass spec.copy(orderBy = emptyList(), limit = ...) to buildQueryPlan without forcing offset = null\n$output"
        }
        // Negative guards: pre-fix shape ("offset = null") must
        // NOT appear on the exists path.
        assert(!output.contains("spec.copy(limit = minOf(1, spec.limit ?: 1), offset = null)")) {
            "exists explains must NOT force offset = null (runtime preserves caller offset)\n$output"
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
        // After + the eager-load explain interceptor fix,
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
