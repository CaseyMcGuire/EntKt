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
    fun `raw aggregate terminals return ReadResult and share one gated execution path`() {
        // Car has year (Int → IntegralColumn), price (Float? → FloatingColumn).
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        // The shared helper routes through RAW_AGGREGATE + driver.aggregate,
        // and hosts the privacy-bypass gate so viewer-scoped rejection
        // happens inside every aggregate terminal's capture boundary.
        assert(output.contains("ReadOperation.RAW_AGGREGATE")) { "aggregateRows uses RAW_AGGREGATE\n$output" }
        assert(output.contains("driver.aggregate(Car.TABLE,")) { "aggregateRows calls driver.aggregate\n$output" }
        assert(output.contains("checkPrivacyBypassingRead(\"raw aggregates\")")) {
            "aggregateRows carries the privacy-bypass gate for all aggregate terminals\n$output"
        }

        // Ungrouped scalar terminals, typed by the column marker, wrapped in ReadResult.
        assert(output.contains("rawSum(column: IntegralColumn<Car, *>): ReadResult<Long?>")) { "rawSum on integral → ReadResult<Long?>\n$output" }
        assert(output.contains("rawSum(column: FloatingColumn<Car, *>): ReadResult<Double?>")) { "rawSum on floating → ReadResult<Double?>\n$output" }
        assert(output.contains("rawAvg(column: NumericColumn<Car, *>): ReadResult<Double?>")) { "rawAvg → ReadResult<Double?>\n$output" }
        assert(output.contains("rawMin(column: ComparableColumn<Car, T>): ReadResult<T?>")) { "rawMin takes ComparableColumn → ReadResult<T?>\n$output" }
        assert(output.contains("rawMax(column: ComparableColumn<Car, T>): ReadResult<T?>")) { "rawMax takes ComparableColumn → ReadResult<T?>\n$output" }

        // Grouped terminals: non-null key → K, nullable key → K?; bucket lists in ReadResult.
        assert(output.contains("rawCountBy(groupBy: GroupableColumn<Car, K>): ReadResult<List<AggregateBucket<K, Long>>>")) {
            "rawCountBy non-null key overload → ReadResult of buckets\n$output"
        }
        assert(output.contains("rawCountBy(groupBy: NullableGroupableColumn<Car, K>): ReadResult<List<AggregateBucket<K?, Long>>>")) {
            "rawCountBy nullable key overload → ReadResult of nullable-key buckets\n$output"
        }

        // Group keys are decoded via the column (enum-safe), not a raw cast.
        assert(output.contains("groupBy.decodeKey(it.key)")) { "group keys decode via the column\n$output" }

        // Grouped value terminals exist; the *OrError twins are gone —
        // ReadResult is the single return shape, projections live on it.
        assert(output.contains("rawAvgBy")) { "rawAvgBy grouped terminal exists\n$output" }
        assert(!output.contains("OrError")) { "no aggregate *OrError twins survive\n$output" }
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
    fun `firstOrNull returns ReadResult and bounds the fetch by the caller's limit`() {
        val car = Car()
        finalize(car, User())
        // KotlinPoet wraps long driver.query(...) calls mid-argument-list,
        // so match against a whitespace-normalized rendering.
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("public fun firstOrNull(): ReadResult<Car?> = try {")) {
            "firstOrNull is the sole first-row terminal, returning ReadResult<Car?>\n$output"
        }
        // `limit(0)` means "no rows" on every terminal family (all()
        // passes spec.limit straight through; rawExists uses
        // `minOf(1, spec.limit ?: 1)`). The first-row terminal must not
        // send a hardwired 1 and return a row anyway.
        //
        // Interceptor limit mutators are silent no-ops at FIRST
        // (InterceptorEngine.limitOpsApply), so spec.limit here holds
        // nothing but the caller's own bound.
        assert(!output.contains("spec.orderBy, 1, spec.offset")) {
            "the first-row terminal should not hardwire limit 1\n$output"
        }
        assert(output.contains("val limit = minOf(1, spec.limit ?: 1)")) {
            "firstOrNull should clamp its window to minOf(1, spec.limit ?: 1)\n$output"
        }
        // The exists probe passes `emptyList()` for orderBy, so
        // `spec.orderBy, limit, spec.offset` picks out exactly the one
        // first-row driver call: firstOrNull. (The firstVisibleOrNull
        // scanning twin is deleted with the visible* family.)
        val bounded = Regex(Regex.escape("spec.orderBy, limit, spec.offset")).findAll(output).count()
        assert(bounded == 1) {
            "expected exactly firstOrNull to clamp by spec.limit; found $bounded\n$output"
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
            "explainFirstOrNull should mirror the runtime clamp\n$output"
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
        // so the round trip is pure waste — likewise when there are no
        // parents at all and the IN could match nothing. The interceptor
        // pass above it still runs — it fires on every eager subquery
        // regardless of bounds or data — so only the fetch is conditional.
        assert(output.contains("val targetRows = if (perGroupLimit > 0 && sourceIds.isNotEmpty()) driver.query(")) {
            "to-many eager fetch should be skipped when limit(0) or an empty parent set admits nothing\n$output"
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
        assert(output.contains("val targetRows = if (targetInWindow && sourceIds.isNotEmpty()) driver.query(")) {
            "hasOne eager fetch should be gated on that window and a non-empty parent set\n$output"
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
        // (currently not eager-loadable via withMethod). The eager
        // blocks all open with an `eagerX?.let { subQuery ->` guard,
        // so its absence pins a no-op body.
        assert(!output.contains("?.let { subQuery ->")) {
            "Body should not reference any eagerProp fields for a schema with no eager edges\n$output"
        }
    }

    @Test
    fun `visible scanning terminals and result-variant twins are gone from the query surface`() {
        // The visible* family (visibleCount / visibleExists / visibleAll /
        // firstVisibleOrNull) scanned rows under LOAD privacy with an
        // overfetch cap; the operation-result algebra deletes the whole
        // family. Privacy-as-absence is now the `visibleOrNull()` runtime
        // projection on a ReadResult, not a separate scanning terminal —
        // and no terminal keeps OrError/OrThrow/OrNull variant twins.
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        for (legacy in listOf(
            "visibleCount",
            "visibleExists",
            "visibleAll",
            "firstVisibleOrNull",
            "visibleOverfetchLimit",
            "evaluateLoadPrivacy",
            "OrError",
            "OrThrow",
        )) {
            assert(!output.contains(legacy)) {
                "removed legacy surface '$legacy' must not be emitted\n$output"
            }
        }
        // The privacy-bypassing raw family is what remains for
        // count/exists/aggregates, and every raw terminal preflights
        // through the client's capability gate.
        val gates = Regex(Regex.escape("requireClient().checkPrivacyBypassingRead(")).findAll(output).count()
        assert(gates == 3) {
            "rawCount, rawExists, and the shared aggregateRows helper should each gate " +
                "via checkPrivacyBypassingRead; found $gates\n$output"
        }
    }

    @Test
    fun `generates rawCount terminal method`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        // The gate sits INSIDE the `= try {` capture boundary, so a
        // viewer-scoped capability rejection is Failed, not a throw.
        assert(
            output.contains(
                "public fun rawCount(): ReadResult<Long> = try { " +
                    "requireClient().checkPrivacyBypassingRead(\"rawCount\")"
            )
        ) {
            "Should generate rawCount(): ReadResult<Long> gated inside the capture boundary\n$output"
        }
        assert(output.contains("runReadInterceptors(ReadOperation.RAW_COUNT)")) {
            "rawCount runs interceptors with RAW_COUNT\n$output"
        }
        assert(output.contains("ReadResult.Success(driver.count(Car.TABLE, spec.predicates))")) {
            "rawCount() should delegate to driver.count with the post-interceptor spec\n$output"
        }
    }

    @Test
    fun `rawExists is the only existence terminal — legacy exists() and visibleExists removed`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        // The legacy `exists()` that fetched the first row and threw
        // PrivacyDeniedException if it was denied is gone, and the
        // visible-scanning twin was deleted with the visible* family.
        assert(!output.contains("fun exists(")) {
            "Legacy exists() should be removed in favor of rawExists\n$output"
        }
        assert(!output.contains("visibleExists")) {
            "visibleExists is deleted with the visible* family\n$output"
        }
        // rawExists bypasses LOAD privacy behind the capability gate,
        // inside the capture boundary.
        assert(
            output.contains(
                "public fun rawExists(): ReadResult<Boolean> = try { " +
                    "requireClient().checkPrivacyBypassingRead(\"rawExists\")"
            )
        ) {
            "Should generate rawExists(): ReadResult<Boolean> gated inside the capture boundary\n$output"
        }
        assert(output.contains("runReadInterceptors(ReadOperation.RAW_EXISTS)")) {
            "rawExists runs interceptors with RAW_EXISTS\n$output"
        }
        // It probes one storage row via driver.query (no orderBy) and
        // checks emptiness. Uses the post-interceptor spec so interceptor
        // predicates (e.g. tenant_id = X) are honored on the probe, and
        // the caller's limit(0) window is respected via the clamp.
        assert(
            output.contains(
                "ReadResult.Success(driver.query(Car.TABLE, spec.predicates, emptyList(), limit, " +
                    "spec.offset).isNotEmpty())"
            )
        ) {
            "rawExists should probe via driver.query with the post-interceptor spec\n$output"
        }
    }

    @Test
    fun `explain roster is exactly one mirror per canonical terminal`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        // Every canonical terminal gets its own explain method named after
        // the terminal; there is no shared "explain()" entry, and the
        // mirrors of the deleted visible* / OrThrow / OrError terminals
        // are gone with them.
        for (name in listOf(
            "public fun explainAll(): QueryPlan = try {",
            "public fun explainFirstOrNull(): QueryPlan = try {",
            "public fun explainRawCount(): QueryPlan = try {",
            "public fun explainRawExists(): QueryPlan = try {",
        )) {
            assert(output.contains(name)) { "Should generate $name\n$output" }
        }
        val explainMethods = Regex("public fun explain\\w*\\(").findAll(output).count()
        assert(explainMethods == 4) {
            "the explain roster should be exactly explainAll / explainFirstOrNull / " +
                "explainRawCount / explainRawExists; found $explainMethods explain methods\n$output"
        }
        // Row-shaped explains delegate through buildQueryPlan to
        // driver.explainQuery with the post-interceptor spec; the count
        // mirror delegates to driver.explainCount.
        assert(output.contains("driver.explainQuery(Car.TABLE, spec.predicates, spec.orderBy,")) {
            "row-shaped explain should delegate to driver.explainQuery with the post-interceptor spec\n$output"
        }
        assert(output.contains("QueryPlan(driver.explainCount(Car.TABLE, spec.predicates), annotations = spec.annotations)")) {
            "explainRawCount should delegate to driver.explainCount and carry annotations\n$output"
        }
        // Rejection produces a rejected plan carrying the typed
        // exception, not a throw — explain does NOT throw.
        val rejections = Regex(
            Regex.escape("} catch (e: EntQueryRejectedException) { QueryPlan.rejected(e) }")
        ).findAll(output).count()
        assert(rejections == 4) {
            "each explain mirror should map EntQueryRejectedException to QueryPlan.rejected(e); " +
                "found $rejections rejection handlers\n$output"
        }
    }

    @Test
    fun `exists explain drops orderBy and preserves caller offset (match runtime driver call)`() {
        // Runtime rawExists calls driver.query(TABLE, spec.predicates,
        // emptyList(), minOf(1, spec.limit ?: 1), spec.offset). The
        // explain mirror must match exactly so the plan doesn't lie about
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
            "explainRawExists should pass spec.copy(orderBy = emptyList(), limit = ...) to buildQueryPlan without forcing offset = null\n$output"
        }
        // Negative guards: pre-fix shape ("offset = null") must
        // NOT appear on the exists path.
        assert(!output.contains("spec.copy(limit = minOf(1, spec.limit ?: 1), offset = null)")) {
            "exists explain must NOT force offset = null (runtime preserves caller offset)\n$output"
        }
    }

    @Test
    fun `row terminals report root LOAD denials through the canonical capture boundary`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("public fun all(): ReadResult<List<Car>> = try {")) {
            "all() returns ReadResult<List<Car>>\n$output"
        }
        // Strict all() evaluates every row in the selected window and
        // aggregates EVERY denied root row into one typed failure...
        assert(output.contains("val denials = results.mapNotNull { c.cars.loadDenialOrNull(privacy, it) }")) {
            "all() should collect denials via the read surface's loadDenialOrNull\n$output"
        }
        assert(
            output.contains(
                "return ReadResult.failedForInternalUse(EntPrivacyDeniedException(LoadDenialOrigin.Root, denials))"
            )
        ) {
            "all() should fail with the aggregated Root denial list\n$output"
        }
        // ...while firstOrNull reports exactly its one keyed denial.
        assert(
            output.contains(
                "return ReadResult.failedForInternalUse(EntPrivacyDeniedException(LoadDenialOrigin.Root, listOf(denial)))"
            )
        ) {
            "firstOrNull should fail with its single keyed Root denial\n$output"
        }
        // Every data terminal shares the canonical capture boundary:
        // rethrow cancellation, capture Exception. Car emits 21 of them —
        // all, firstOrNull, rawCount, rawExists, and 17 raw aggregate
        // overloads. Explains are NOT data terminals and stay outside
        // this count (they only convert interceptor rejection).
        val boundaries = Regex(
            Regex.escape(
                "} catch (e: CancellationException) { throw e } " +
                    "catch (e: Exception) { ReadResult.failedForInternalUse(e) }"
            )
        ).findAll(output).count()
        assert(boundaries == 21) {
            "every data terminal should end in the canonical capture boundary; found $boundaries\n$output"
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
