package entkt.codegen

import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the generated shape of the read-client file: `EntReadClient` is
 * an interface carrying every read repository, the two posture wrappers
 * are real distinct classes (not type aliases) with guarded internal
 * constructors delegating to one shared internal `EntReadClientImpl`,
 * and no public generated signature exposes the implementation type.
 *
 * The type-level consequences (cross-posture rejection, shared-interface
 * acceptance, the no-writes surface) are proved by the compile tests in
 * [ReadClientPostureCompileTest], [ValidationReadClientCompileTest], and
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
            .generate(listOf(SchemaInput("Car", car), SchemaInput("User", user)))
    }

    private fun readClientOutput(): String =
        generateFiles().first { it.name == "EntReadClient" }.toString()

    private fun readRuntimeOutput(): String =
        generateFiles().first { it.name == "EntReadRuntime" }.toString()

    /**
     * The findById + explainFindById span of a repo class: from the
     * terminal's declaration through the end of the explain mirror's
     * rejection handler. Both emitters place the two members adjacently
     * (one KDoc between), so the span is contiguous.
     */
    private fun findByIdSpan(source: String): String {
        val start = source.indexOf("public fun findById")
        assert(start >= 0) { "findById not found in\n$source" }
        val endMarker = "QueryPlan.rejected(e)\n    }\n  }"
        val end = source.indexOf(endMarker, start)
        assert(end >= 0) { "explainFindById tail not found in\n$source" }
        return source.substring(start, end + endMarker.length)
    }

    @Test
    fun `EntReadClient is an interface exposing every read repository`() {
        val output = readClientOutput()

        assert(output.contains("public interface EntReadClient {")) {
            "EntReadClient should be an interface\n$output"
        }
        assert(!Regex("\\bclass EntReadClient\\b").containsMatchIn(output)) {
            "EntReadClient must no longer be a class\n$output"
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
    fun `posture wrappers are distinct guarded classes delegating to the shared impl`() {
        val output = readClientOutput()

        // Pin each wrapper's complete declaration, terminating newline
        // included: guarded internal constructor, private impl-typed
        // delegate, and EntReadClient as the ONLY supertype — a wrapper
        // that grew a second supertype (e.g. `, EntReadRuntime`) or any
        // body would no longer match.
        val wrapperDecl = { name: String ->
            "public class $name @EntktInternal internal constructor(\n" +
                "  private val `delegate`: EntReadClientImpl,\n" +
                ") : EntReadClient by delegate\n"
        }
        assert(output.contains(wrapperDecl("EntValidationReadClient"))) {
            "EntValidationReadClient should be exactly the guarded delegating wrapper\n$output"
        }
        assert(output.contains(wrapperDecl("EntPrivacyReadClient"))) {
            "EntPrivacyReadClient should be exactly the guarded delegating wrapper\n$output"
        }
        // Real types, not aliases — aliases would not reject cross-posture
        // helper calls.
        assert(!output.contains("typealias EntValidationReadClient")) {
            "EntValidationReadClient must not be a typealias\n$output"
        }
        assert(!output.contains("typealias EntPrivacyReadClient")) {
            "EntPrivacyReadClient must not be a typealias\n$output"
        }
        // The framework-internal runtime contract stays on the delegate —
        // no wrapper may delegate or implement it.
        assert(!output.contains("EntReadRuntime by")) {
            "No wrapper may delegate EntReadRuntime\n$output"
        }
    }

    @Test
    fun `one internal impl owns repository construction and the runtime contract`() {
        val output = readClientOutput()

        assert(output.contains("@EntktInternal\ninternal class EntReadClientImpl(")) {
            "EntReadClientImpl should be internal and marked @EntktInternal\n$output"
        }
        assert(output.contains(") : EntReadClient,\n    EntReadRuntime {")) {
            "The impl should implement both EntReadClient and EntReadRuntime\n$output"
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
    fun `read surface exposes positional LOAD batches and read repo delegates them to its host`() {
        val runtimeOutput = readRuntimeOutput().replace("\\s+".toRegex(), " ")
        val clientOutput = readClientOutput().replace("\\s+".toRegex(), " ")

        assert(
            runtimeOutput.contains(
                "public interface CarReadSurface { public fun hasLoadPrivacy(): Boolean public fun loadDenials(privacy: PrivacyContext, entities: List<Car>): List<PrivacyDenial?> public fun loadDenialOrNull(",
            ),
        ) {
            "CarReadSurface should expose the plural positional LOAD contract before its singleton projection\n$runtimeOutput"
        }
        assert(
            clientOutput.contains(
                "override fun loadDenials(privacy: PrivacyContext, entities: List<Car>): List<PrivacyDenial?> = host.loadDenials(privacy, entities)",
            ),
        ) {
            "CarReadRepo should delegate the complete LOAD batch to its host surface\n$clientOutput"
        }
        assert(
            clientOutput.contains(
                "override fun loadDenialOrNull(privacy: PrivacyContext, entity: Car): PrivacyDenial? = host.loadDenialOrNull(privacy, entity)",
            ),
        ) {
            "CarReadRepo should keep singleton LOAD delegation behavior aligned with its host\n$clientOutput"
        }
    }

    @Test
    fun `read repo findById is the full repo's findById modulo the runtime host reference`() {
        // The read client's per-entity repos must not re-implement the
        // primary-key read path: CarReadRepo's findById/explainFindById
        // are byte-identical to CarRepo's, with the sole difference that
        // the read repo reaches its host through `runtime` where the full
        // repo uses `client`. Any other difference is behavioral drift
        // between the two read surfaces (privacy, interceptors, capture
        // boundary, or explain shape diverging by construction site).
        val files = generateFiles()
        val readClient = files.first { it.name == "EntReadClient" }.toString()
        val carRepo = files.first { it.name == "CarRepo" }.toString()
        val carReadRepoStart = readClient.indexOf("public class CarReadRepo")
        assert(carReadRepoStart >= 0) { "CarReadRepo not found in\n$readClient" }

        val readSpan = findByIdSpan(readClient.substring(carReadRepoStart))
        val repoSpan = findByIdSpan(carRepo)
        assertEquals(
            repoSpan,
            readSpan.replace("runtime", "client"),
            "read repo findById/explainFindById must match the full repo's modulo the host reference",
        )

        // And the shared span is the canonical primary-key read: a
        // ReadResult terminal running interceptors at BY_ID with the id
        // as a structural predicate, plus its non-throwing explain mirror.
        assert(readSpan.contains("public fun findById(id: Int): ReadResult<Car?> = try {")) {
            "findById should return ReadResult<Car?> through the capture boundary\n$readSpan"
        }
        assert(readSpan.contains("operation = ReadOperation.BY_ID,")) {
            "findById should run interceptors with BY_ID\n$readSpan"
        }
        assert(readSpan.contains("extraStructural = listOf(Predicate.Leaf<Car>(\"id\", Op.EQ, id)),")) {
            "findById should pass the id as a structural predicate\n$readSpan"
        }
        assert(readSpan.contains("public fun explainFindById(id: Int): QueryPlan {")) {
            "explainFindById should mirror findById returning QueryPlan\n$readSpan"
        }
    }

    @Test
    fun `no public signature exposes the impl type`() {
        val output = readClientOutput()

        // Every mention of EntReadClientImpl in the file is either its own
        // internal declaration or a wrapper's private constructor property
        // — three total. A fourth appearance means some public signature
        // started leaking the impl.
        val mentions = Regex("EntReadClientImpl").findAll(output).count()
        val privateDelegates = Regex(Regex.escape("private val `delegate`: EntReadClientImpl")).findAll(output).count()
        assert(mentions == 3 && privateDelegates == 2) {
            "EntReadClientImpl should appear only in its declaration and the two private " +
                "delegate properties; found $mentions mentions, $privateDelegates private delegates\n$output"
        }
    }
}
