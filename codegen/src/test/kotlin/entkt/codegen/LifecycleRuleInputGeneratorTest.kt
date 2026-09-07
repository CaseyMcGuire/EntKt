package entkt.codegen

import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.mutation.LifecycleRuleInputGenerator
import kotlin.test.Test
import kotlin.test.assertEquals

class LifecycleRuleInputGeneratorTest {
    private val generator = LifecycleRuleInputGenerator("com.example.ent")

    @Test
    fun `generates only the compound update and delete inputs in same-named files`() {
        val files = generator.generate("User")

        assertEquals(
            listOf("UserUpdateRuleInput", "UserDeleteRuleInput"),
            files.map { it.name },
        )
        files.forEach { file ->
            assertEquals(
                listOf(file.name),
                file.members.filterIsInstance<TypeSpec>().mapNotNull { it.name },
            )
        }
    }

    @Test
    fun `create rules need no generated wrapper`() {
        assert(generator.generate("User").none { it.name == "UserCreateRuleInput" })
    }

    @Test
    fun `update input contains before patches candidate and edge changes`() {
        val output = output("UserUpdateRuleInput")
        val constructor = constructorOf(output, "UserUpdateRuleInput")

        assert(constructor.contains("val before: User"))
        assert(constructor.contains("val requestedPatch: UserUpdatePatch"))
        assert(constructor.contains("val effectivePatch: UserUpdatePatch"))
        assert(constructor.contains("val candidate: UserWriteCandidate"))
        assert(constructor.contains("val edgeChanges: UserEdgeChangesView"))
    }

    @Test
    fun `delete input contains the existing entity and normalized candidate`() {
        val output = output("UserDeleteRuleInput")
        val constructor = constructorOf(output, "UserDeleteRuleInput")

        assert(constructor.contains("val entity: User"))
        assert(constructor.contains("val candidate: UserWriteCandidate"))
    }

    private fun output(fileName: String): String =
        generator.generate("User").single { it.name == fileName }.toString()

    private fun constructorOf(output: String, className: String): String {
        val classStart = output.indexOf("data class $className")
        check(classStart >= 0) { "Missing $className in generated output" }
        val constructorEnd = output.indexOf("\n)", classStart)
        check(constructorEnd >= 0) { "Missing constructor end for $className" }
        return output.substring(classStart, constructorEnd)
    }
}
