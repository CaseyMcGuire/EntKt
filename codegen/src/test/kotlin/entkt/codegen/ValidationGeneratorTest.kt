package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.fixtures.Car
import entkt.codegen.fixtures.User
import entkt.codegen.mutation.ValidationGenerator
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `validation config only binds lifecycle rule types to the runtime base`() {
        val user = User()
        finalize(user, Car())
        val config = generator.generate("User", user).members.filterIsInstance<TypeSpec>()
            .single { it.name == "UserValidationConfig" }

        assertEquals(
            ClassName("entkt.runtime.validation", "EntityValidationConfig").parameterizedBy(
                ClassName("com.example.ent", "UserCreateBatchValidationRule"),
                ClassName("com.example.ent", "UserUpdateBatchValidationRule"),
                ClassName("com.example.ent", "UserDeleteBatchValidationRule"),
            ),
            config.superclass,
        )
        assertTrue(config.propertySpecs.isEmpty(), "Rule storage belongs in runtime")
        assertTrue(config.funSpecs.isEmpty(), "Resolution belongs in runtime")
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
    fun `validation scope only binds lifecycle rule types and passes config to the runtime base`() {
        val user = User()
        finalize(user, Car())
        val scope = generator.generate("User", user).members.filterIsInstance<TypeSpec>()
            .single { it.name == "UserValidationScope" }

        assertEquals(
            ClassName("entkt.runtime.validation", "EntityValidationScope").parameterizedBy(
                ClassName("com.example.ent", "UserCreateBatchValidationRule"),
                ClassName("com.example.ent", "UserUpdateBatchValidationRule"),
                ClassName("com.example.ent", "UserDeleteBatchValidationRule"),
            ),
            scope.superclass,
        )
        val constructor = requireNotNull(scope.primaryConstructor)
        assertEquals(setOf(KModifier.INTERNAL), constructor.modifiers)
        assertEquals("config", constructor.parameters.single().name)
        assertEquals(ClassName("com.example.ent", "UserValidationConfig"), constructor.parameters.single().type)
        assertEquals(listOf("config"), scope.superclassConstructorParameters.map { it.toString() })
        assertTrue(scope.propertySpecs.isEmpty(), "The config is stored only by the runtime base")
        assertTrue(scope.funSpecs.isEmpty(), "Registration and derivation belong in runtime")
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
