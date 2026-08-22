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
private class OneOwner : EntSchema("one_owners", clientName = "oneOwners") {
    override fun id() = entkt.schema.EntId.long()
    val badge by hasOne<OneBadge>("badge")
}

private class OneBadge : EntSchema("one_badges", clientName = "oneBadges") {
    override fun id() = entkt.schema.EntId.long()
    val owner by belongsTo<OneOwner>("owner").inverse(OneOwner::badge).unique()
}

class QueryGeneratorTest {

    private val generator = QueryGenerator("com.example.ent")

    @Test
    fun `raw aggregate terminals return ReadResult and share one execution path`() {
        // Car has year (Int → IntegralColumn), price (Float? → FloatingColumn).
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        // The shared helper routes through RAW_AGGREGATE + driver.aggregate.
        assert(output.contains("ReadOperation.RAW_AGGREGATE")) { "aggregateRows uses RAW_AGGREGATE\n$output" }
        assert(output.contains("driver.aggregate(Car.TABLE,")) { "aggregateRows calls driver.aggregate\n$output" }
        assert(!output.contains("checkPrivacyBypassingRead")) {
            "raw aggregates should be available in every read posture\n$output"
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
        assert(output.contains("query builders are not thread-safe")) {
            "Generated query KDoc should state the mutable builder's concurrency contract\n$output"
        }
        assert(output.contains("a separate query builder for each concurrent operation")) {
            "Generated query KDoc should give callers an actionable concurrent-use alternative\n$output"
        }
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
        // parents at all and the IN could match nothing. Those data
        // gates live in the runtime's executeDirectToMany; generated
        // code hands it the parent keys and the frozen window so the
        // gate decision stays driver-independent, while the
        // interceptor pass above always runs.
        assert(output.contains("val related = executeDirectToMany(")) {
            "to-many eager fetch should route through the runtime direct to-many executor\n$output"
        }
        assert(output.contains("window = PerParentWindow(offset = perGroupOffset, limit = subSpec.limit)")) {
            "the runtime executor should receive the frozen per-parent window\n$output"
        }
        assert(output.contains("emulationPredicates = subSpec.predicates,")) {
            "the emulated fallback should receive the complete frozen predicate list\n$output"
        }
    }

    @Test
    fun `to-many eager loads probe the driver's native window capability`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val output = generator.generate("User", user, mapOf(user to "User", car to "Car"))
            .toString().replace("\\s+".toRegex(), " ")

        // The capability is sampled ONCE, BEFORE the interceptor
        // chain: a native driver transports the structural
        // relationship IN as one typed-array bind, so the running
        // bind budget must not charge one scalar bind per parent key
        // — and the SAME sample routes the fetch, so budgeting and
        // routing cannot disagree against an unstable capability.
        assert(output.contains("val toManyWindowCapability = driver.directToManyWindowCapability()")) {
            "to-many eager block should sample the driver capability once\n$output"
        }
        assert(
            output.contains("val nativeToManyWindows = toManyWindowCapability == DirectToManyWindowCapability.NATIVE"),
        ) {
            "the bind-budget flag should derive from the one sample\n$output"
        }
        assert(output.contains("structuralSingleBindTransport = nativeToManyWindows")) {
            "the capability should drive the structural bind-budget accounting\n$output"
        }
        assert(output.contains("capability = toManyWindowCapability,")) {
            "the runtime executor should receive the same capability sample\n$output"
        }
        // The driver receives the frozen predicates minus the
        // separately-attributed relationship constraint on the native
        // path.
        assert(output.contains("targetPredicates = subSpec.nonStructuralPredicates,")) {
            "the native path should hand the driver only non-structural predicates\n$output"
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
    fun `does not emit per-edge loadMethods when schemaNames is empty but always emits loadEdges`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(!output.contains("loadCars")) {
            "Without schemaNames, load{Edge} should be skipped\n$output"
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
    fun `selected-edge guard protects non-entity terminals and traversal`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val names = mapOf<EntSchema, String>(car to "Car", user to "User")
        val output = generator.generate("Car", car, names).toString().replace("\\s+".toRegex(), " ")

        // The shared private guard enumerates every selected
        // declaration-derived edge path and throws the configuration
        // exception naming the rejected operation.
        assert(
            output.contains(
                "private fun requireNoSelectedEdges(operation: String, reason: String) { " +
                    "val selected = listOfNotNull( if (eagerUser != null) \"Car.user\" else null, ) " +
                    "if (selected.isEmpty()) return " +
                    "throw EntQueryConfigurationException( \"Car\", " +
                    "operation + \" on CarQuery is incompatible with the selected edge loads [\" + " +
                    "selected.joinToString(\", \") + \"]: \" + reason, ) }",
            ),
        ) {
            "Should emit the requireNoSelectedEdges guard listing Car.user\n$output"
        }

        // Result-bearing non-entity terminals open with the guard
        // inside the capture boundary — the throw becomes
        // ReadResult.Failed before requireClient / interceptor /
        // driver work.
        for (terminal in listOf("rawCount", "rawExists")) {
            assert(
                output.contains(
                    "fun $terminal(): ReadResult<" ,
                ) && output.contains(
                    "= try { requireNoSelectedEdges(\"$terminal()\", \"this terminal does not return " +
                        "entities and cannot expose loaded edges; use an entity terminal such as all() " +
                        "or firstOrNull(), or remove the load calls\") val c = requireClient()",
                ),
            ) {
                "$terminal should open with the selected-edge guard inside the capture boundary\n$output"
            }
        }
        // Ungrouped and grouped aggregates carry the same guard,
        // named per terminal.
        for (terminal in listOf("rawMin", "rawMax", "rawSum", "rawAvg", "rawCountBy", "rawMinBy", "rawMaxBy", "rawSumBy", "rawAvgBy")) {
            assert(output.contains("requireNoSelectedEdges(\"$terminal()\"")) {
                "$terminal should carry the selected-edge guard\n$output"
            }
        }

        // Traversal is a configuration operation: queryX() itself
        // throws before constructing the target query.
        assert(
            output.contains(
                "requireNoSelectedEdges(\"queryUser()\", \"traversal changes the result root and " +
                    "cannot carry the source query's selected graph; traverse first and select edge " +
                    "loads on the target query, or materialize the source graph with an entity " +
                    "terminal\") val target = UserQuery(driver, client)",
            ),
        ) {
            "queryUser should reject a source query with selected edge loads\n$output"
        }
    }

    @Test
    fun `topology-consuming terminals hold the activeTerminals guard for their duration`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val names = mapOf<EntSchema, String>(car to "Car", user to "User")
        val output = generator.generate("Car", car, names).toString().replace("\\s+".toRegex(), " ")

        // The private counter backs terminal-entry isolation: while a
        // terminal is executing, load{Name} and
        // filterVisible() on this query are rejected.
        assert(output.contains("private var activeTerminals: Int = 0")) {
            "Should emit the private activeTerminals counter\n$output"
        }
        // Acquire/release walk the entire selected graph: the guard
        // covers retained nested target queries, not just the root.
        assert(output.contains("internal fun acquireEdgeTopology() { activeTerminals++ eagerUser?.acquireEdgeTopology() }")) {
            "acquire should guard this query and recurse into selected targets\n$output"
        }
        assert(output.contains("internal fun releaseEdgeTopology() { activeTerminals-- eagerUser?.releaseEdgeTopology() }")) {
            "release should mirror the acquire walk\n$output"
        }
        // all() / firstOrNull() acquire on entry, before any
        // interceptor or driver work, and release in a finally.
        assert(output.contains("fun all(): ReadResult<List<Car>> { acquireEdgeTopology() return try { val c = requireClient()")) {
            "all() should hold the guard from terminal entry\n$output"
        }
        assert(output.contains("fun firstOrNull(): ReadResult<Car?> { acquireEdgeTopology() return try { val c = requireClient()")) {
            "firstOrNull() should hold the guard from terminal entry\n$output"
        }
        assert(output.contains("} finally { releaseEdgeTopology() }")) {
            "guard release must sit in a finally\n$output"
        }
        // Non-entity terminals reject selected topology outright and
        // do not consume it, so they never take the guard.
        assert(!output.contains("fun rawCount(): ReadResult<Long> { acquireEdgeTopology()")) {
            "rawCount must not take the guard — it rejects selected edges instead\n$output"
        }
    }

    @Test
    fun `no selected-edge guard is emitted for queries without load-capable edges`() {
        val car = Car()
        finalize(car, User())
        // Empty schemaNames: no edge is codegen-visible, so no edge
        // can be selected and the guards would be dead weight.
        val output = generator.generate("Car", car).toString()

        assert(!output.contains("requireNoSelectedEdges")) {
            "Guard should not exist when nothing can be selected\n$output"
        }
        assert(!output.contains("activeTerminals")) {
            "Isolation counter should not exist when nothing can be selected\n$output"
        }
        // The acquire/release pair still exists as no-ops — a parent
        // query's guard recursion calls them on every selected target
        // class unconditionally, mirroring loadEdges.
        assert(output.contains("internal fun acquireEdgeTopology()")) {
            "No-op acquire must exist for parent guard recursion\n$output"
        }
        assert(output.contains("internal fun releaseEdgeTopology()")) {
            "No-op release must exist for parent guard recursion\n$output"
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
        // (currently not eager-loadable via loadMethod). The eager
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
        // The storage-level raw family is available in every read posture.
        assert(!output.contains("checkPrivacyBypassingRead")) {
            "raw terminals should not carry a posture gate\n$output"
        }
    }

    @Test
    fun `generates rawCount terminal method`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "public fun rawCount(): ReadResult<Long> = try { " +
                    "val c = requireClient() val privacy = c.currentPrivacyContext()"
            )
        ) {
            "Should generate rawCount(): ReadResult<Long> inside the capture boundary\n$output"
        }
        assert(output.contains("runReadInterceptors(ReadOperation.RAW_COUNT, privacy)")) {
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
        // rawExists bypasses LOAD privacy in every read posture.
        assert(
            output.contains(
                "public fun rawExists(): ReadResult<Boolean> = try { " +
                    "val c = requireClient() val privacy = c.currentPrivacyContext()"
            )
        ) {
            "Should generate rawExists(): ReadResult<Boolean> inside the capture boundary\n$output"
        }
        assert(output.contains("runReadInterceptors(ReadOperation.RAW_EXISTS, privacy)")) {
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
    fun `query builders do not expose an explain API`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        val explainMethods = Regex("public fun explain\\w*\\(").findAll(output).count()
        assert(explainMethods == 0) {
            "generated query builders should not expose explain methods; found $explainMethods\n$output"
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
        // Strict all() submits the selected window to LOAD privacy as
        // one positional batch, then aggregates every denied root row
        // into one typed failure.
        assert(output.contains("val denials = c.cars.loadDenials(privacy, results).filterNotNull()")) {
            "all() should collect denials through one plural read-surface call\n$output"
        }
        assert(!output.contains("results.mapNotNull { c.cars.loadDenialOrNull(privacy, it) }")) {
            "all() must not regress to per-row LOAD privacy evaluation\n$output"
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
        // overloads.
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

}
