package entkt.codegen

import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.mutation.LifecycleRuleInputGenerator
import kotlin.test.Test
import kotlin.test.assertEquals

class LifecycleRuleInputGeneratorTest {
    private val generator = LifecycleRuleInputGenerator("com.example.ent")

    @Test
    fun `generates only the update input class and the delete input alias in same-named files`() {
        val files = generator.generate("User")

        assertEquals(
            listOf("UserUpdateRuleInput", "UserDeleteRuleInput"),
            files.map { it.name },
        )
        val (update, delete) = files
        assertEquals(listOf("UserUpdateRuleInput"), update.members.map { (it as TypeSpec).name })
        assertEquals(listOf("UserDeleteRuleInput"), delete.members.map { (it as TypeAliasSpec).name })
    }

    @Test
    fun `create rules need no generated wrapper`() {
        assert(generator.generate("User").none { it.name == "UserCreateRuleInput" })
    }

    @Test
    fun `update input implements the rule input marker for its entity`() {
        assert(output("UserUpdateRuleInput").contains(") : UpdateRuleInput<User>"))
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
    fun `delete input aliases the runtime pair of existing entity and normalized candidate`() {
        val output = output("UserDeleteRuleInput")

        assert(output.contains("typealias UserDeleteRuleInput = DeleteRuleInput<User, UserWriteCandidate>")) { output }
        assert(output.contains("import entkt.runtime.mutation.DeleteRuleInput")) { output }
        assert(!output.contains("class ")) { output }
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
