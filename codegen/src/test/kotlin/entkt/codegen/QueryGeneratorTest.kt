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

        // Every overload delegates terminal intent and typed result decoding
        // to the shared runtime evaluator.
        assert(output.contains("_readQueryEvaluator.rawAggregate(")) {
            "aggregate terminals should delegate to ReadQueryEvaluator\n$output"
        }
        assert(output.contains("function = AggregateFunction.SUM")) {
            "aggregate terminals should pass their operation explicitly\n$output"
        }
        assert(!output.contains("driver.aggregate(Car.TABLE,")) {
            "generated queries should not own aggregate execution\n$output"
        }
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
        assert(output.contains("groupBy.decodeKey(row.key)")) { "group keys decode via the column\n$output" }

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
    fun `root terminals pass captured query data to an instance-configured entity loader`() {
        val car = Car()
        finalize(car, User())
        // KotlinPoet wraps long driver.query(...) calls mid-argument-list,
        // so match against a whitespace-normalized rendering.
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "readRootQuery(ReadOperation.FIRST, maximumRows = 1)",
            ),
        ) {
            "firstOrNull should retain its public result type and delegate to runtime\n$output"
        }
        assert(
            output.contains(
                "internal fun readRootQuery( operation: ReadOperation, maximumRows: Int?, " +
                    "structuralPredicates: List<Predicate<Car>> = emptyList(), ): " +
                    "ReadResult<List<Car>> = _readQueryEvaluator.readRootQuery(",
            ),
        ) {
            "the query should delegate root reads to its configured entity loader\n$output"
        }
        assert(
            output.contains(
                "readRootQuery( captureQuery = { captureEntityQuery(structuralPredicates) }, operation = operation, " +
                    "maximumRows = maximumRows, )",
            ),
        ) {
            "the loader should receive captured query data and terminal intent directly\n$output"
        }
        assert(!output.contains("RootQueryRequest")) {
            "root reads should not allocate an argument-bundling request object\n$output"
        }
        assert(
            output.contains(
                    "private val _readQueryEvaluator: ReadQueryEvaluator<Car> = ReadQueryEvaluator( " +
                    "driver = driver, " +
                    "privacyContextProvider = { requireClient().currentPrivacyContext() }, " +
                    "registeredInterceptorsProvider = { requireClient().entityInterceptors }, " +
                    "loadPrivacyEvaluatorProvider = { requireClient() }, )",
            ),
        ) {
            "generated queries should configure one runtime read evaluator\n$output"
        }
        assert(!output.contains("private val _queryPreparation")) {
            "generated queries should not assemble query-preparation dependencies\n$output"
        }
        assert(!output.contains("private val _graphLoader")) {
            "generated queries should not construct graph loaders separately\n$output"
        }
        assert(!output.contains("private val _queryTerminalExecutor")) {
            "generated queries should not construct raw-terminal executors separately\n$output"
        }
        assert(!output.contains("GeneratedEntitySelection")) {
            "the captured entity mapping now owns table metadata and decoding\n$output"
        }
        assert(!output.contains("override fun capturePrivacyContext")) {
            "privacy-context capture should be a named loader dependency\n$output"
        }
        assert(!output.contains("GeneratedRootQueryPreparation")) {
            "root query preparation should be owned by the runtime loader\n$output"
        }
        assert(!output.contains("GeneratedLoadPrivacyEvaluator")) {
            "root LOAD privacy should use a configured runtime evaluator instead of a generated type\n$output"
        }
        assert(!output.contains("override fun freezeQuery(")) {
            "freezing must not conceal interceptor execution behind its name\n$output"
        }
        assert(!output.contains("fun firstOrNull(): ReadResult<Car?> = try {")) {
            "the generated terminal must not retain a second execution algorithm\n$output"
        }
    }

    @Suppress("unused") // Runtime execution coverage: ReadQueryEvaluatorRelationshipTest.
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

    @Suppress("unused") // Runtime execution coverage: ReadQueryEvaluatorRelationshipTest.
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

    @Suppress("unused") // Runtime execution coverage: ReadQueryEvaluatorRelationshipTest.
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
        // The generated class takes a DatabaseDriver in its primary constructor
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
    fun `captures root query state and selected edges as a recursive entity query`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val names = mapOf<EntSchema, String>(car to "Car", user to "User")
        val output = generator.generate("User", user, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "val selectedEdges = buildList<EdgeSelection<User, *>> {",
            ),
        ) {
            "query capture should include a typed selected-edge list\n$output"
        }
        assert(
            output.contains(
                "edge = GeneratedCarsEdgeMapping, " +
                    "target = selectedQuery.captureEntityQuery(), " +
                    "visibility = if (eagerCarsFilterVisible) " +
                    "EdgeVisibility.FILTER_INVISIBLE else EdgeVisibility.REQUIRE_VISIBLE",
            ),
        ) {
            "selected edges should capture their nested query and privacy posture\n$output"
        }
        assert(
            output.contains(
                "return EntityQuery( entity = GeneratedEntityMapping, " +
                    "source = entityQuerySource, predicates = predicates, " +
                    "orderBy = orderFields, limit = queryLimit, offset = queryOffset, " +
                    "edges = selectedEdges, structuralPredicates = structuralPredicates, )",
            ),
        ) {
            "query capture should contain only caller query state and generated mappings\n$output"
        }
    }

    @Test
    fun `generates typed entity and direct-edge mappings`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val names = mapOf<EntSchema, String>(car to "Car", user to "User")
        val output = generator.generate("User", user, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("internal object GeneratedEntityMapping : EntityMapping<User>")) {
            "each query should expose one generated typed entity mapping\n$output"
        }
        assert(output.contains("override val entityName: String = \"User\"")) {
            "entity mapping should carry the generated entity identity\n$output"
        }
        assert(
            output.contains(
                "internal object GeneratedCarsEdgeMapping : ToManyEdgeMapping<User, Car>",
            ),
        ) {
            "hasMany should generate a typed to-many mapping\n$output"
        }
        assert(
            output.contains(
                "EdgeStorage.ForeignKeyOnTarget<User, Car, UUID>(" +
                    "sourceColumn = \"id\", targetColumn = \"user_id\", " +
                    "sourceKey = { it.id }, targetForeignKey = { it.userId })",
            ),
        ) {
            "direct edge mapping should carry typed correlation instead of only column names\n$output"
        }
        assert(
            output.contains(
                "override fun attach(source: User, targets: List<Car>): User = " +
                    "source.copy(edges = source.edges.copy(cars = EdgeState.Loaded(targets)))",
            ),
        ) {
            "edge mapping should own immutable attachment to the generated entity shape\n$output"
        }
    }

    @Test
    fun `traversal captures its recursive source query`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val names = mapOf<EntSchema, String>(car to "Car", user to "User")
        val output = generator.generate("User", user, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "target.setEntityQuerySource(QuerySource.Traversal(source, GeneratedCarsEdgeMapping))",
            ),
        ) {
            "queryCars should retain the immutable source query and typed relationship\n$output"
        }
        assert(output.contains("val source = captureEntityQuery()")) {
            "traversal should freeze its recursively nested source before target configuration\n$output"
        }
        assert(!output.contains("snapshotForTraversal")) {
            "the immutable recursive source should replace generated query cloning\n$output"
        }
    }

    @Test
    fun `query capture checks the bind lower bound before snapshotting operands`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(
            output.contains(
                "// This is a conservative lower bound, not the final rendered bind count.",
            ),
        ) {
            "the generated preflight should explain why the minimum is checked early\n$output"
        }
        val capacityCheck = output.indexOf(
            "driver.requireBindCapacity(minimumRequiredBindParameters, Car.TABLE)",
        )
        val entityCapture = output.indexOf("return EntityQuery(")
        assert(capacityCheck >= 0 && capacityCheck < entityCapture) {
            "bind capacity must be checked before EntityQuery snapshots operands\n$output"
        }
    }

    @Test
    fun `many-to-many mappings carry the explicit junction entity and key extractors`() {
        val team = Team()
        val member = TeamMember()
        val pet = Pet()
        val owner = Owner()
        finalize(team, member, pet, owner)
        val names = mapOf<EntSchema, String>(
            team to "Team",
            member to "TeamMember",
            pet to "Pet",
            owner to "Owner",
        )
        val output = generator.generate("Team", team, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "internal object GeneratedMembersEdgeMapping : ToManyEdgeMapping<Team, Pet>",
            ),
        ) {
            "many-to-many should generate a typed to-many mapping\n$output"
        }
        assert(
            output.contains(
                "EdgeStorage.Junction<Team, Pet, TeamMember, Int, Int>(" +
                    "table = \"team_members\", sourceColumn = \"team_id\", " +
                    "targetColumn = \"member_id\", " +
                    "junctionEntity = TeamMemberQuery.GeneratedEntityMapping, " +
                    "sourceKey = { it.id }, targetKey = { it.id })",
            ),
        ) {
            "junction storage should name its generated entity mapping and typed keys\n$output"
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
    fun `does not emit edge loading machinery when schemaNames is empty`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(!output.contains("loadCars")) {
            "Without schemaNames, load{Edge} should be skipped\n$output"
        }
        assert(!output.contains("internal fun loadEdges(")) {
            "selected graph execution belongs to the runtime loader\n$output"
        }
    }

    @Test
    fun `selected-edge guard protects non-entity terminals and traversal`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val names = mapOf<EntSchema, String>(car to "Car", user to "User")
        val output = generator.generate("Car", car, names).toString().replace("\\s+".toRegex(), " ")

        assert(!output.contains("private fun requireNoSelectedEdges")) {
            "selected-edge validation should live in runtime, not each generated query\n$output"
        }
        assert(output.contains("_readQueryEvaluator.rawCount { captureEntityQuery() }")) {
            "rawCount should delegate its selected-edge validation to runtime\n$output"
        }
        assert(output.contains("_readQueryEvaluator.rawExists { captureEntityQuery() }")) {
            "rawExists should delegate its selected-edge validation to runtime\n$output"
        }
        // Traversal captures the source once, validates that captured graph,
        // and then gives the immutable source to the target query.
        assert(
            output.contains(
                "val source = captureEntityQuery() source.requireNoSelectedEdges(\"queryUser()\", " +
                    "\"traversal changes the result root and " +
                    "cannot carry the source query's selected graph; traverse first and select edge " +
                    "loads on the target query, or materialize the source graph with an entity " +
                    "terminal\") val target = UserQuery(driver, client)",
            ),
        ) {
            "queryUser should reject a source query with selected edge loads\n$output"
        }
    }

    @Test
    fun `entity terminals execute an immutable captured topology`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val names = mapOf<EntSchema, String>(car to "Car", user to "User")
        val output = generator.generate("Car", car, names).toString().replace("\\s+".toRegex(), " ")

        assert(!output.contains("activeTerminals")) {
            "captured immutable graphs should not need a generated in-flight counter\n$output"
        }
        assert(!output.contains("acquireEdgeTopology")) {
            "runtime execution should not walk mutable generated query objects\n$output"
        }
        assert(!output.contains("releaseEdgeTopology")) {
            "there should be no generated topology release phase\n$output"
        }
        assert(output.contains("fun all(): ReadResult<List<Car>> = readRootQuery(ReadOperation.ALL, maximumRows = null)")) {
            "all() should delegate its complete root boundary to runtime\n$output"
        }
        assert(output.contains("readRootQuery(ReadOperation.FIRST, maximumRows = 1)")) {
            "firstOrNull() should delegate its complete root boundary to runtime\n$output"
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
        assert(!output.contains("acquireEdgeTopology")) {
            "captured query graphs need no generated topology acquisition\n$output"
        }
        assert(!output.contains("releaseEdgeTopology")) {
            "captured query graphs need no generated topology release\n$output"
        }
    }

    @Test
    fun `does not emit a generated graph executor for schemas with no edges`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(!output.contains("internal fun loadEdges(")) {
            "selected graph execution should remain runtime-owned\n$output"
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

        assert(output.contains("public fun rawCount(): ReadResult<Long>")) {
            "Should generate rawCount(): ReadResult<Long>\n$output"
        }
        assert(output.contains("= _readQueryEvaluator.rawCount { captureEntityQuery() }")) {
            "rawCount execution and failure capture should be runtime-owned\n$output"
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
        assert(output.contains("public fun rawExists(): ReadResult<Boolean>")) {
            "Should generate rawExists(): ReadResult<Boolean>\n$output"
        }
        assert(output.contains("= _readQueryEvaluator.rawExists { captureEntityQuery() }")) {
            "rawExists probe shape and failure capture should be runtime-owned\n$output"
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
    fun `row terminals delegate privacy and selected-edge completion through loader dependencies`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "fun all(): ReadResult<List<Car>> = " +
                    "readRootQuery(ReadOperation.ALL, maximumRows = null)",
            ),
        ) {
            "all() should preserve its public type while delegating to runtime\n$output"
        }
        assert(output.contains("loadPrivacyEvaluatorProvider = { requireClient() }")) {
            "the evaluator should lazily obtain the runtime-wide typed privacy evaluator\n$output"
        }
        assert(!output.contains("query.loadEdges(entities, privacyContext)")) {
            "generated queries should not retain a second graph-loading algorithm\n$output"
        }
        assert(!output.contains("catch (e: CancellationException)")) {
            "all generated read-terminal failure boundaries should now be runtime-owned\n$output"
        }
    }

    @Test
    fun `captured queries include eager edge subqueries`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val output = generator.generate("User", user, mapOf(user to "User", car to "Car")).toString()

        assert(output.contains("eagerCars?.let { selectedQuery ->")) {
            "captureEntityQuery should recursively capture selected edges\n$output"
        }
        assert(output.contains("target = selectedQuery.captureEntityQuery()")) {
            "selected edge targets should be recursively immutable\n$output"
        }
        assert(!output.contains("buildQueryPlan")) {
            "generated queries should not emit obsolete planning algorithms\n$output"
        }
        assert(!output.contains("runReadInterceptors(ReadOperation.EAGER_LOAD")) {
            "generated queries should not emit eager interceptor execution\n$output"
        }
    }
}
