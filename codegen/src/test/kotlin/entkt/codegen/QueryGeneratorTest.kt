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
    fun `does not generate storage aggregate terminals`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        for (removed in listOf(
            "rawCount",
            "rawExists",
            "rawMin",
            "rawMax",
            "rawSum",
            "rawAvg",
        )) {
            assert(!output.contains(removed)) {
                "removed storage terminal '$removed' must not be generated\n$output"
            }
        }
    }

    @Test
    fun `generates query builder class`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("class CarQuery")) { "Should generate CarQuery\n$output" }
        assert(!output.contains("/**")) {
            "Generated query builders should not emit framework KDoc\n$output"
        }
    }

    @Test
    fun `query inherits reusable state and terminals from runtime base`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains(") : EntityQueryBuilder<Car, CarQuery>(")) {
            "generated queries should wire schema-specific values into EntityQueryBuilder\n$output"
        }
        assert(
            output.contains(
                "driver = driver, executionHost = client, entityName = \"Car\"",
            ),
        ) {
            "the base should receive the query's driver, contextless host, and entity name\n$output"
        }
        assert(output.contains("protected override val self: CarQuery get() = this")) {
            "the generated self type should preserve concrete fluent return types\n$output"
        }
        assert(
            output.contains(
                "override fun captureEntityQuery(structuralPredicates: List<Predicate<Car>>): " +
                    "EntityQuery<Car>",
            ),
        ) {
            "generated capture should implement the base's schema-specific adapter\n$output"
        }
        assert(!output.contains("private val _readQueryExecutor")) {
            "query execution belongs to the runtime base\n$output"
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
    fun `runtime-owned query DSL and execution members are not re-emitted`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        for (runtimeOwned in listOf(
            "fun `where`(",
            "fun orderBy(",
            "fun limit(",
            "fun offset(",
            "override fun combinedPredicate(",
            "fun all(",
            "fun firstOrNull(",
            "fun readRootQuery(",
            "fun compileEntityQuery(",
            "_readQueryExecutor",
            "requireClient(",
        )) {
            assert(!output.contains(runtimeOwned)) {
                "runtime-owned member '$runtimeOwned' must not be emitted per entity\n$output"
            }
        }
        assert(!output.contains("RootQueryRequest")) {
            "root reads should not allocate an argument-bundling request object\n$output"
        }
        assert(!output.contains("private val _queryCompiler")) {
            "generated queries should not assemble query-compilation dependencies\n$output"
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
        assert(!output.contains("override fun captureViewerContext")) {
            "privacy-context capture should be a named loader dependency\n$output"
        }
        assert(!output.contains("GeneratedRootQueryPreparation")) {
            "root query compilation should be owned by the runtime loader\n$output"
        }
        assert(!output.contains("GeneratedLoadPrivacyEvaluator")) {
            "root LOAD privacy should use a configured runtime evaluator instead of a generated type\n$output"
        }
        assert(!output.contains("override fun freezeQuery(")) {
            "freezing must not conceal interceptor execution behind its name\n$output"
        }
    }

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
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

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
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

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
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

        // All predicate construction is done via typed column refs on
        // the entity's companion object; the reusable builder DSL is inherited.
        assert(!output.contains("whereModelEq")) { "Should not have whereModelEq\n$output" }
        assert(!output.contains("whereYearGt")) { "Should not have whereYearGt\n$output" }
        assert(!output.contains("whereModelContains")) { "Should not have whereModelContains\n$output" }
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

        assert(!output.contains("// This is a conservative lower bound")) {
            "generated query implementation should not emit framework comments\n$output"
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
    fun `selected-edge guard protects traversal`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val names = mapOf<EntSchema, String>(car to "Car", user to "User")
        val output = generator.generate("Car", car, names).toString().replace("\\s+".toRegex(), " ")

        assert(!output.contains("private fun requireNoSelectedEdges")) {
            "selected-edge validation should live in runtime, not each generated query\n$output"
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
    fun `generated query retains no mutable execution topology`() {
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
        assert(!output.contains("fun all(") && !output.contains("fun firstOrNull(")) {
            "row terminals should be inherited from EntityQueryBuilder\n$output"
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
    fun `scanning aggregate and result-variant terminals are gone from the query surface`() {
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
            "rawCount",
            "rawExists",
            "rawMin",
            "rawMax",
            "rawSum",
            "rawAvg",
            "OrError",
            "OrThrow",
        )) {
            assert(!output.contains(legacy)) {
                "removed legacy surface '$legacy' must not be emitted\n$output"
            }
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
