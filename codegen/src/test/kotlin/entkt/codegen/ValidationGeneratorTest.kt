package entkt.codegen

import kotlin.test.Test

class ValidationGeneratorTest {

    private val generator = ValidationGenerator("com.example.ent")

    @Test
    fun `generates rule typealiases for three operations`() {
        val output = generator.generate("User", User).toString()

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
        val output = generator.generate("User", User).toString()

        assert(!output.contains("LoadValidationRule")) {
            "Should not generate load validation rule\n$output"
        }
        assert(!output.contains("LoadValidationContext")) {
            "Should not generate load validation context\n$output"
        }
    }

    @Test
    fun `generates create context with client and candidate but no privacy`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("data class UserCreateValidationContext")) {
            "Should generate create context\n$output"
        }
        assert(output.contains("val client: EntClient")) {
            "Create context should have client\n$output"
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
        val output = generator.generate("User", User).toString()

        assert(output.contains("data class UserUpdateValidationContext")) {
            "Should generate update context\n$output"
        }
        assert(output.contains("val before: User")) {
            "Update context should have before entity\n$output"
        }
    }

    @Test
    fun `generates delete context with client, entity, and candidate`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("data class UserDeleteValidationContext")) {
            "Should generate delete context\n$output"
        }
        assert(output.contains("val entity: User")) {
            "Delete context should have entity\n$output"
        }
    }

    @Test
    fun `generates ValidationConfig with mutable rule lists and updateDerivesFromCreate`() {
        val output = generator.generate("User", User).toString()

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
        val output = generator.generate("User", User).toString()

        assert(!output.contains("deleteDerivesFromCreate")) {
            "Should not have deleteDerivesFromCreate\n$output"
        }
    }

    @Test
    fun `generates ValidationScope with DSL methods`() {
        val output = generator.generate("User", User).toString()

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
        val output = generator.generate("User", User).toString()

        // Check there's no load method on the ValidationScope
        assert(!output.contains("fun load(")) {
            "ValidationScope should not have load method\n$output"
        }
    }

    @Test
    fun `does not generate WriteCandidate`() {
        val output = generator.generate("User", User).toString()

        assert(!output.contains("WriteCandidate(")) {
            "Should not generate WriteCandidate (reused from privacy)\n$output"
        }
        assert(!output.contains("data class UserWriteCandidate")) {
            "Should not generate WriteCandidate class\n$output"
        }
    }

    @Test
    fun `does not generate PolicyScope`() {
        val output = generator.generate("User", User).toString()

        assert(!output.contains("PolicyScope")) {
            "Should not generate PolicyScope (handled by privacy generator)\n$output"
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
