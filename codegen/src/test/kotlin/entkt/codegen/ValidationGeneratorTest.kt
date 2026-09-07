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
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("typealias UserCreateValidationRule = ValidationRule<ReadOnlyEntClient, UserWriteCandidate>")) {
            "Should generate create rule typealias\n$output"
        }
        assert(output.contains("typealias UserUpdateValidationRule = ValidationRule<ReadOnlyEntClient, UserUpdateRuleInput>")) {
            "Should generate update rule typealias\n$output"
        }
        assert(output.contains("typealias UserDeleteValidationRule = ValidationRule<ReadOnlyEntClient, UserDeleteRuleInput>")) {
            "Should generate delete rule typealias\n$output"
        }
        assert(output.contains("typealias UserCreateBatchValidationRule = BatchValidationRule<ReadOnlyEntClient, UserWriteCandidate>")) {
            "Should generate create batch rule typealias\n$output"
        }
        assert(output.contains("typealias UserUpdateBatchValidationRule = BatchValidationRule<ReadOnlyEntClient, UserUpdateRuleInput>")) {
            "Should generate update batch rule typealias\n$output"
        }
        assert(output.contains("typealias UserDeleteBatchValidationRule = BatchValidationRule<ReadOnlyEntClient, UserDeleteRuleInput>")) {
            "Should generate delete batch rule typealias\n$output"
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
        assert(!output.contains("LoadValidationItem")) {
            "Should not generate load validation item\n$output"
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
        assert(output.contains("val createRules: MutableList<UserCreateBatchValidationRule>")) {
            "Should store create rules through the shared batch contract\n$output"
        }
        assert(output.contains("val updateRules: MutableList<UserUpdateBatchValidationRule>")) {
            "Should store update rules through the shared batch contract\n$output"
        }
        assert(output.contains("val deleteRules: MutableList<UserDeleteBatchValidationRule>")) {
            "Should store delete rules through the shared batch contract\n$output"
        }
        assert(output.contains("var updateDerivesFromCreate: Boolean = false")) {
            "Should have updateDerivesFromCreate flag\n$output"
        }
        val normalized = output.replace("\\s+".toRegex(), " ")
        assert(
            normalized.contains(
                "fun resolveForInternalUse(): ResolvedEntityValidationConfig<" +
                    "UserCreateBatchValidationRule, UserUpdateBatchValidationRule, " +
                    "UserDeleteBatchValidationRule>",
            ),
        ) { "Mutable validation config should resolve to the runtime immutable type\n$output" }
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
        assert(output.contains("fun create(rule: UserCreateBatchValidationRule)")) {
            "Should register a single batch create rule under the existing DSL name\n$output"
        }
        assert(output.contains("fun update(rule: UserUpdateBatchValidationRule)")) {
            "Should register a single batch update rule under the existing DSL name\n$output"
        }
        assert(output.contains("fun delete(rule: UserDeleteBatchValidationRule)")) {
            "Should register a single batch delete rule under the existing DSL name\n$output"
        }
        listOf("create", "update", "delete").forEach { operation ->
            assert(output.contains("@JvmName(\"${operation}BatchRule\")")) {
                "Batch $operation overload should have a distinct Java name\n$output"
            }
            assert(output.contains("config.${operation}Rules.addAll(rules)")) {
                "Scalar $operation overload should append to the shared list\n$output"
            }
            assert(output.contains("config.${operation}Rules.add(rule)")) {
                "Batch $operation overload should append to the shared list\n$output"
            }
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

}
