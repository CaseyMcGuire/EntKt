package entkt.codegen

import entkt.codegen.query.EntityDescriptorGenerator
import entkt.codegen.query.QueryGenerator
import entkt.codegen.query.QueryScopeGenerator
import entkt.schema.EntSchema
import kotlin.reflect.KClass
import kotlin.test.Test

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class QueryGeneratorTest {

    private val generator = QueryGenerator("com.example.ent")
    private val scopeGenerator = QueryScopeGenerator("com.example.ent")
    private val descriptorGenerator = EntityDescriptorGenerator("com.example.ent")

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
    fun `query inherits immutable state and terminals from runtime base`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("EntityQueryBuilder<Car, CarQuery>"))
        assert(output.contains("driver = driver, executionHost = client, entityQuery = query")) { output }
        assert(output.contains("protected override fun newQuery(query: EntityQuery<Car>): CarQuery")) { output }
        assert(output.contains("CarQuery(driver, client, query)")) { output }
        assert(output.contains("EntityQuery(CarDescriptor)")) { output }
        assert(!output.contains("override fun captureEntityQuery(")) { output }
        assert(!output.contains("private val _readQueryExecutor")) { output }
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
    fun `configuration scopes delegate capture and edge selection to runtime`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val names = mapOf<EntSchema, String>(car to "Car", user to "User")
        val output = scopeGenerator.generate("User", user, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("EntityQueryScope<User, UserQueryScope>")) { output }
        assert(output.contains("initialQuery = query, edgeOrder = UserDescriptor.edgesByStorageName.values")) { output }
        assert(output.contains("loadEdge(UserCarsEdgeDescriptor, CarQueryScope(driver, client), block)")) { output }
        for (algorithm in listOf("buildList", "EdgeSelection(", "override fun captureEntityQuery", "if (", "try {")) {
            assert(!output.contains(algorithm)) { output }
        }
    }

    @Test
    fun `generates typed entity and direct-edge descriptors`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val names = mapOf<EntSchema, String>(car to "Car", user to "User")
        val output = descriptorGenerator.generate("User", user, names).joinToString("\n")
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("internal object UserDescriptor : EntityDescriptor<User, UUID>")) {
            "each entity should expose one canonical typed descriptor\n$output"
        }
        assert(output.contains("override val entityName: String = \"User\"")) {
            "entity descriptor should carry the generated entity identity\n$output"
        }
        assert(
            output.contains(
                "internal object UserCarsEdgeDescriptor : ToManyEdgeMapping<User, Car>",
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
            "direct edge descriptor should carry typed correlation instead of only column names\n$output"
        }
        assert(
            output.contains(
                "override fun attach(source: User, targets: List<Car>): User = " +
                    "source.copy(edges = source.edges.copy(cars = EdgeState.Loaded(targets)))",
            ),
        ) {
            "edge descriptor should own immutable attachment to the generated entity shape\n$output"
        }
    }


    @Test
    fun `traversal delegates source preservation and target configuration to runtime`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val names = mapOf<EntSchema, String>(car to "Car", user to "User")
        val output = generator.generate("User", user, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("queryCars(block: CarQueryScope.() -> Unit")) { output }
        assert(output.contains("traversalQuery(UserCarsEdgeDescriptor, \"queryCars()\")")) { output }
        assert(output.contains(".configure(block)")) { output }
        assert(!output.contains("setEntityQuerySource")) { output }
        assert(!output.contains("snapshotForTraversal")) { output }
    }


    @Test
    fun `snapshot and bind capacity algorithms are not generated`() {
        val car = Car()
        finalize(car, User())
        val query = generator.generate("Car", car).toString()
        val scope = scopeGenerator.generate("Car", car).toString()
        assert(query.contains("configureQuery(CarQueryScope(driver, client, entityQuery), block)")) { query }
        for (output in listOf(query, scope)) {
            assert(!output.contains("requireBindCapacity")) { output }
            assert(!output.contains("minimumBindParameters")) { output }
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
        val output = descriptorGenerator.generate("Team", team, names).joinToString("\n")
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "internal object TeamMembersEdgeDescriptor : ToManyEdgeMapping<Team, Pet>",
            ),
        ) {
            "many-to-many should generate a typed to-many mapping\n$output"
        }
        assert(
            output.contains(
                "EdgeStorage.Junction<Team, Pet, TeamMember, Int, Int>(" +
                    "table = \"team_members\", sourceColumn = \"team_id\", " +
                    "targetColumn = \"member_id\", " +
                    "junctionEntity = TeamMemberDescriptor, " +
                    "sourceKey = { it.id }, targetKey = { it.id })",
            ),
        ) {
            "junction storage should name its generated entity descriptor and typed keys\n$output"
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
    fun `traversal passes its operation name to the runtime selected-edge guard`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val output = generator.generate("Car", car, mapOf(car to "Car", user to "User"))
            .toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("traversalQuery(CarUserEdgeDescriptor, \"queryUser()\")")) { output }
        assert(!output.contains("requireNoSelectedEdges")) { output }
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
    fun `edge configuration is absent from completed queries and typed on scopes`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val names = mapOf<EntSchema, String>(car to "Car", user to "User")
        val query = generator.generate("User", user, names).toString()
        val scope = scopeGenerator.generate("User", user, names).toString().replace("\\s+".toRegex(), " ")

        assert(!query.contains("fun loadCars")) { query }
        assert(query.contains("configure(block: UserQueryScope.() -> Unit)")) { query }
        assert(scope.contains("loadCars(block: CarQueryScope.() -> Unit = {}): EdgeLoad<UserQueryScope>")) { scope }
        for (algorithm in listOf("buildQueryPlan", "runReadInterceptors", "eagerCars", "fun all(", "fun queryCars")) {
            assert(!scope.contains(algorithm)) { scope }
        }
    }

}
