package entkt.codegen

import entkt.schema.EntSchema
import kotlin.test.Test

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

    private fun readClientOutput(): String {
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
            .first { it.name == "EntReadClient" }
            .toString()
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
