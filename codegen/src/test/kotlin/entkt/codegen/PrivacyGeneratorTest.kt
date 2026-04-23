package entkt.codegen

import kotlin.test.Test

class PrivacyGeneratorTest {

    private val generator = PrivacyGenerator("com.example.ent")

    @Test
    fun `generates rule typealiases for all four operations`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("typealias UserLoadPrivacyRule = PrivacyRule<UserLoadPrivacyContext>")) {
            "Should generate load rule typealias\n$output"
        }
        assert(output.contains("typealias UserCreatePrivacyRule = PrivacyRule<UserCreatePrivacyContext>")) {
            "Should generate create rule typealias\n$output"
        }
        assert(output.contains("typealias UserUpdatePrivacyRule = PrivacyRule<UserUpdatePrivacyContext>")) {
            "Should generate update rule typealias\n$output"
        }
        assert(output.contains("typealias UserDeletePrivacyRule = PrivacyRule<UserDeletePrivacyContext>")) {
            "Should generate delete rule typealias\n$output"
        }
    }

    @Test
    fun `generates load context with privacy, client, and entity`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("data class UserLoadPrivacyContext")) {
            "Should generate load context\n$output"
        }
        assert(output.contains("val privacy: PrivacyContext")) {
            "Load context should have privacy\n$output"
        }
        assert(output.contains("val client: EntClient")) {
            "Load context should have client\n$output"
        }
        assert(output.contains("val entity: User")) {
            "Load context should have entity\n$output"
        }
    }

    @Test
    fun `generates create context with privacy, client, and candidate`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("data class UserCreatePrivacyContext")) {
            "Should generate create context\n$output"
        }
        assert(output.contains("val candidate: UserWriteCandidate")) {
            "Create context should have candidate\n$output"
        }
    }

    @Test
    fun `generates update context with privacy, client, before entity, and candidate`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("data class UserUpdatePrivacyContext")) {
            "Should generate update context\n$output"
        }
        assert(output.contains("val before: User")) {
            "Update context should have before entity\n$output"
        }
    }

    @Test
    fun `generates delete context with privacy, client, entity, and candidate`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("data class UserDeletePrivacyContext")) {
            "Should generate delete context\n$output"
        }
    }

    @Test
    fun `generates WriteCandidate with all schema fields except id`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("data class UserWriteCandidate")) {
            "Should generate WriteCandidate\n$output"
        }
        assert(output.contains("val name: String")) {
            "WriteCandidate should have name\n$output"
        }
        assert(output.contains("val email: String")) {
            "WriteCandidate should have email\n$output"
        }
        assert(output.contains("val age: Int?")) {
            "WriteCandidate should have optional age\n$output"
        }
        assert(!output.contains("val id:")) {
            "WriteCandidate should not have id\n$output"
        }
    }

    @Test
    fun `generates PrivacyConfig with mutable rule lists and derivation flags`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("class UserPrivacyConfig")) {
            "Should generate PrivacyConfig\n$output"
        }
        assert(output.contains("val loadRules: MutableList<UserLoadPrivacyRule>")) {
            "Should have loadRules\n$output"
        }
        assert(output.contains("var updateDerivesFromCreate: Boolean = false")) {
            "Should have updateDerivesFromCreate flag\n$output"
        }
        assert(output.contains("var deleteDerivesFromCreate: Boolean = false")) {
            "Should have deleteDerivesFromCreate flag\n$output"
        }
    }

    @Test
    fun `generates PrivacyScope with DSL methods for each operation`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("class UserPrivacyScope")) {
            "Should generate PrivacyScope\n$output"
        }
        assert(output.contains("fun load(vararg rules: UserLoadPrivacyRule)")) {
            "Should have load method\n$output"
        }
        assert(output.contains("fun create(vararg rules: UserCreatePrivacyRule)")) {
            "Should have create method\n$output"
        }
        assert(output.contains("fun updateDerivesFromCreate()")) {
            "Should have updateDerivesFromCreate method\n$output"
        }
        assert(output.contains("fun deleteDerivesFromCreate()")) {
            "Should have deleteDerivesFromCreate method\n$output"
        }
    }

    @Test
    fun `id-only schema emits constructible WriteCandidate class`() {
        val idOnly = object : entkt.schema.EntSchema() {}
        val output = generator.generate("Empty", idOnly).toString()

        assert(output.contains("class EmptyWriteCandidate")) {
            "Should generate a WriteCandidate class\n$output"
        }
        assert(!output.contains("data class EmptyWriteCandidate")) {
            "Should not be a data class (no properties)\n$output"
        }
        assert(!output.contains("object EmptyWriteCandidate")) {
            "Should not be an object (must be constructible with parens)\n$output"
        }
    }

    @Test
    fun `generates PolicyScope with privacy block`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("class UserPolicyScope")) {
            "Should generate PolicyScope\n$output"
        }
        assert(output.contains("fun privacy(block: UserPrivacyScope.() -> Unit)")) {
            "Should have privacy DSL method\n$output"
        }
    }

    @Test
    fun `generates PolicyScope with validation block`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("fun validation(block: UserValidationScope.() -> Unit)")) {
            "Should have validation DSL method\n$output"
        }
    }

    @Test
    fun `PolicyScope constructor takes both privacy and validation config`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("privacyConfig: UserPrivacyConfig")) {
            "PolicyScope should take privacyConfig\n$output"
        }
        assert(output.contains("validationConfig: UserValidationConfig")) {
            "PolicyScope should take validationConfig\n$output"
        }
    }
}
