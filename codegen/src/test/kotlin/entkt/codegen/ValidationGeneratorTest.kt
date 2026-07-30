package entkt.codegen

import entkt.codegen.mutation.ValidationGenerator
import entkt.schema.EntSchema
import kotlin.reflect.KClass
import kotlin.test.Test

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class ValidationGeneratorTest {

    private val generator = ValidationGenerator("com.example.ent")

    @Test
    fun `generates rule typealiases for three operations`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("typealias UserCreateValidationRule = ValidationRule<UserCreateValidationContext>")) {
            "Should generate create rule typealias\n$output"
        }
        assert(output.contains("typealias UserUpdateValidationRule = ValidationRule<UserUpdateValidationContext>")) {
            "Should generate update rule typealias\n$output"
        }
        assert(output.contains("typealias UserDeleteValidationRule = ValidationRule<UserDeleteValidationContext>")) {
            "Should generate delete rule typealias\n$output"
        }
    }

    @Test
    fun `does not generate load typealias`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(!output.contains("LoadValidationRule")) {
            "Should not generate load validation rule\n$output"
        }
        assert(!output.contains("LoadValidationContext")) {
            "Should not generate load validation context\n$output"
        }
    }

    @Test
    fun `generates create context with client and candidate but no privacy`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("data class UserCreateValidationContext")) {
            "Should generate create context\n$output"
        }
        assert(output.contains("val client: EntValidationReadClient")) {
            "Create context should expose the validation-posture read client\n$output"
        }
        // All three contexts (create/update/delete) live in this file and
        // each exposes the validation-posture client — nothing here may
        // fall back to the shared interface or the privacy posture.
        val clientDecls = Regex("val client: EntValidationReadClient").findAll(output).count()
        assert(clientDecls == 3) {
            "All three validation contexts should expose EntValidationReadClient; found $clientDecls\n$output"
        }
        assert(!output.contains("val client: EntReadClient")) {
            "No validation context may expose the interface-typed client\n$output"
        }
        assert(output.contains("val candidate: UserWriteCandidate")) {
            "Create context should have candidate\n$output"
        }
        // Validation contexts must NOT have privacy
        assert(!output.contains("UserCreateValidationContext") || !contextContainsPrivacy(output, "UserCreateValidationContext")) {
            "Create context should not have privacy\n$output"
        }
    }

    @Test
    fun `generates update context with client, before, and candidate`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("data class UserUpdateValidationContext")) {
            "Should generate update context\n$output"
        }
        assert(output.contains("val before: User")) {
            "Update context should have before entity\n$output"
        }
    }

    @Test
    fun `generates delete context with client, entity, and candidate`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("data class UserDeleteValidationContext")) {
            "Should generate delete context\n$output"
        }
        assert(output.contains("val entity: User")) {
            "Delete context should have entity\n$output"
        }
    }

    @Test
    fun `generates ValidationConfig with mutable rule lists and updateDerivesFromCreate`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("class UserValidationConfig")) {
            "Should generate ValidationConfig\n$output"
        }
        assert(output.contains("val createRules: MutableList<UserCreateValidationRule>")) {
            "Should have createRules\n$output"
        }
        assert(output.contains("val updateRules: MutableList<UserUpdateValidationRule>")) {
            "Should have updateRules\n$output"
        }
        assert(output.contains("val deleteRules: MutableList<UserDeleteValidationRule>")) {
            "Should have deleteRules\n$output"
        }
        assert(output.contains("var updateDerivesFromCreate: Boolean = false")) {
            "Should have updateDerivesFromCreate flag\n$output"
        }
    }

    @Test
    fun `ValidationConfig does not have deleteDerivesFromCreate`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(!output.contains("deleteDerivesFromCreate")) {
            "Should not have deleteDerivesFromCreate\n$output"
        }
    }

    @Test
    fun `generates ValidationScope with DSL methods`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("class UserValidationScope")) {
            "Should generate ValidationScope\n$output"
        }
        assert(output.contains("fun create(vararg rules: UserCreateValidationRule)")) {
            "Should have create method\n$output"
        }
        assert(output.contains("fun update(vararg rules: UserUpdateValidationRule)")) {
            "Should have update method\n$output"
        }
        assert(output.contains("fun delete(vararg rules: UserDeleteValidationRule)")) {
            "Should have delete method\n$output"
        }
        assert(output.contains("fun updateDerivesFromCreate()")) {
            "Should have updateDerivesFromCreate method\n$output"
        }
    }

    @Test
    fun `ValidationScope has no load method`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        // Check there's no load method on the ValidationScope
        assert(!output.contains("fun load(")) {
            "ValidationScope should not have load method\n$output"
        }
    }

    @Test
    fun `does not generate WriteCandidate`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(!output.contains("WriteCandidate(")) {
            "Should not generate WriteCandidate (reused from privacy)\n$output"
        }
        assert(!output.contains("data class UserWriteCandidate")) {
            "Should not generate WriteCandidate class\n$output"
        }
    }

    @Test
    fun `does not generate PolicyScope`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(!output.contains("PolicyScope")) {
            "Should not generate PolicyScope (handled by privacy generator)\n$output"
        }
    }

    // ---------- link-table M2M helpers edgeChanges sidecar on UpdateValidationContext ----------

    @Test
    fun `UpdateValidationContext references edgeChanges sidecar of the per-entity view type`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // ValidationGenerator references UserEdgeChangesView; the type
        // itself is emitted by PrivacyGenerator. Both generators are
        // independently invoked, so the sibling-file reference compiles
        // when the full ent package is generated.
        assert(output.contains("edgeChanges: UserEdgeChangesView")) {
            "UpdateValidationContext should expose `edgeChanges: UserEdgeChangesView`\n$output"
        }
        assert(output.contains("public val edgeChanges: UserEdgeChangesView")) {
            "UpdateValidationContext.edgeChanges should be a public val\n$output"
        }
    }

    /**
     * Checks whether the specific context class declaration contains "privacy"
     * in its constructor parameters.
     */
    private fun contextContainsPrivacy(output: String, className: String): Boolean {
        val classStart = output.indexOf("data class $className")
        if (classStart == -1) return false
        val parenEnd = output.indexOf(")", classStart)
        if (parenEnd == -1) return false
        val ctorBlock = output.substring(classStart, parenEnd)
        return ctorBlock.contains("privacy")
    }
}
