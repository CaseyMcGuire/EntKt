package entkt.codegen

import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the generated shape of the read-client file: `ReadOnlyEntClient` is
 * an interface carrying every read repository, one guarded internal
 * `ReadOnlyEntClientImpl` owns the runtime wiring,
 * and no public generated signature exposes the implementation type.
 *
 * The shared-context acceptance and no-writes surface are proved by the compile tests in
 * [ReadOnlyEntClientCompileTest], [ValidationReadClientCompileTest], and
 * [PrivacyReadClientCompileTest]; this test pins the emitted text so a
 * shape regression fails fast without a compiler invocation.
 */
class ReadClientGeneratorTest {

    private fun generateFiles(): List<com.squareup.kotlinpoet.FileSpec> {
        val car = Car()
        val user = User()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(
            car::class to car,
            user::class to user,
        )
        car.finalize(registry)
        user.finalize(registry)
        return EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(car), SchemaInput(user)))
    }

    private fun readClientOutput(): String =
        generateFiles().first { it.name == "ReadOnlyEntClient" }.toString()

    private fun readRuntimeOutput(): String =
        generateFiles().first { it.name == "EntReadRuntime" }.toString()

    /** The generated findById member of one repository class. */
    private fun findByIdSpan(source: String): String {
        val start = source.indexOf("public fun findById")
        assert(start >= 0) { "findById not found in\n$source" }
        val closingBrace = "\n  }"
        val end = source.indexOf(closingBrace, start)
        check(end >= 0) { "findById tail not found in\n$source" }
        return source.substring(start, end + closingBrace.length)
    }

    @Test
    fun `ReadOnlyEntClient is an interface exposing every read repository`() {
        val output = readClientOutput()

        assert(output.contains("public interface ReadOnlyEntClient {")) {
            "ReadOnlyEntClient should be an interface\n$output"
        }
        assert(!Regex("\\bclass ReadOnlyEntClient\\b").containsMatchIn(output)) {
            "ReadOnlyEntClient must no longer be a class\n$output"
        }
        // Interface accessors carry no initializer — the newline-anchored
        // match excludes the impl's `override val ... = ...` lines.
        assert(output.contains("public val cars: CarReadRepo\n")) {
            "Interface should declare the cars read repo\n$output"
        }
        assert(output.contains("public val users: UserReadRepo\n")) {
            "Interface should declare the users read repo\n$output"
        }
    }

    @Test
    fun `posture-specific wrappers are absent`() {
        val output = readClientOutput()

        assert(!output.contains("EntPrivacyReadClient"))
        assert(!output.contains("EntValidationReadClient"))
        assert(!Regex("\\bEntReadClient\\b").containsMatchIn(output))
        assert(!output.contains(" by delegate"))
    }

    @Test
    fun `one internal impl owns repository construction and the runtime contract`() {
        val output = readClientOutput()

        assert(output.contains("@EntktInternal\ninternal class ReadOnlyEntClientImpl(")) {
            "ReadOnlyEntClientImpl should be internal and marked @EntktInternal\n$output"
        }
        assert(output.contains(") : ReadOnlyEntClient,\n    EntReadRuntime {")) {
            "The impl should implement both ReadOnlyEntClient and EntReadRuntime\n$output"
        }
        assert(output.contains("override val cars: CarReadRepo = CarReadRepo(driver, carsHost)")) {
            "The impl should own repository construction\n$output"
        }
        // One shared repo type per entity — no posture-specific copies.
        val carRepos = Regex("public class \\w*CarReadRepo").findAll(output).count()
        assert(carRepos == 1 && !output.contains("CarValidationReadRepo") && !output.contains("CarPrivacyReadRepo")) {
            "Exactly one shared CarReadRepo should exist\n$output"
        }
    }

    @Test
    fun `read surface exposes correlated LOAD evaluation and read repo delegates it to its host`() {
        val runtimeOutput = readRuntimeOutput().replace("\\s+".toRegex(), " ")
        val clientOutput = readClientOutput().replace("\\s+".toRegex(), " ")

        assert(
            runtimeOutput.contains(
                "public interface CarReadSurface { public fun hasLoadPrivacy(): Boolean public fun evaluateLoadPrivacy(viewerContext: ViewerContext, entities: List<Car>): PrivacyEvaluation<Car>",
            ),
        ) {
            "CarReadSurface should expose the correlated LOAD evaluation contract\n$runtimeOutput"
        }
        assert(
            clientOutput.contains(
                "override fun evaluateLoadPrivacy(viewerContext: ViewerContext, entities: List<Car>): PrivacyEvaluation<Car> = host.evaluateLoadPrivacy(viewerContext, entities)",
            ),
        ) {
            "CarReadRepo should delegate the complete LOAD evaluation to its host surface\n$clientOutput"
        }
    }

    @Test
    fun `read runtime dispatches recursive LOAD privacy by entity descriptor`() {
        val output = readRuntimeOutput().replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "public interface EntReadRuntime : ReadQueryExecutionHost",
            ),
        ) {
            "the runtime host should provide explicit read guards and LOAD evaluation\n$output"
        }
        assert(!output.contains("public fun checkReadExecution()")) {
            "the generated runtime should inherit the reusable execution-host contract\n$output"
        }
        assert(!output.contains("currentViewerContext") && !output.contains("ViewerContextProvider"))
        assert(
            output.contains(
                "CarDescriptor -> cars.hasLoadPrivacy()",
            ),
        ) {
            "LOAD configuration should dispatch through generated descriptor identity\n$output"
        }
        assert(
            output.contains(
                "CarDescriptor -> cars.evaluateLoadPrivacy( " +
                    "viewerContext, entities as List<Car>, ) as PrivacyEvaluation<Entity>",
            ),
        ) {
            "recursive targets should retain the evaluator's correlated result\n$output"
        }
    }

    @Test
    fun `read repo findById is the full repo's findById modulo the runtime host reference`() {
        // The read client's per-entity repos must not re-implement the
        // primary-key read path: CarReadRepo's findById is byte-identical
        // to CarRepo's, with the sole difference that
        // the read repo reaches its host through `runtime` where the full
        // repo uses `client`. Any other difference is behavioral drift
        // between the two read surfaces (privacy, interceptors, capture
        // boundary diverging by construction site).
        val files = generateFiles()
        val readClient = files.first { it.name == "ReadOnlyEntClient" }.toString()
        val carRepo = files.first { it.name == "CarRepo" }.toString()
        val carReadRepoStart = readClient.indexOf("public class CarReadRepo")
        assert(carReadRepoStart >= 0) { "CarReadRepo not found in\n$readClient" }

        val readSpan = findByIdSpan(readClient.substring(carReadRepoStart))
        val repoSpan = findByIdSpan(carRepo)
        assertEquals(
            repoSpan,
            readSpan.replace("runtime", "client"),
            "read repo findById must match the full repo's modulo the host reference",
        )

        // The shared span contributes the id predicate and delegates
        // execution to the runtime query pipeline.
        assert(readSpan.contains("public fun findById(viewerContext: ViewerContext, id: Int): ReadResult<Car?> {")) {
            "findById should retain its nullable ReadResult surface\n$readSpan"
        }
        assert(
            readSpan.contains("query.readRootQuery(") &&
                readSpan.contains("operation = ReadOperation.BY_ID,") &&
                readSpan.contains("maximumRows = 1,"),
        ) {
            "findById should delegate BY_ID execution to ReadQueryExecutor\n$readSpan"
        }
        assert(
            readSpan.contains(
                "structuralPredicates = listOf(Predicate.Leaf<Car>(\"id\", Op.EQ, id))",
            ),
        ) {
            "findById should contribute the id predicate as framework-owned query structure\n$readSpan"
        }
        assert(!readSpan.contains("explain")) {
            "read repositories should not expose a query explain API\n$readSpan"
        }
    }

    @Test
    fun `no public signature exposes the impl type`() {
        val output = readClientOutput()

        // The implementation type appears only in its own internal declaration.
        // Any additional occurrence means a generated signature started leaking it.
        val mentions = Regex("ReadOnlyEntClientImpl").findAll(output).count()
        assert(mentions == 1) {
            "ReadOnlyEntClientImpl should appear only in its declaration; found $mentions mentions\n$output"
        }
    }
}
