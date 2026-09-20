package entkt.codegen

import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.mutation.MutationGenerator
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MutationGeneratorTest {
    private class Record : EntSchema("records", clientName = "records") {
        override fun id() = EntId.long()
        val name by string("name")
        val description by string("description").nullable()
        val createdAt by instant("created_at").immutable()
    }

    private class Empty : EntSchema("empty", clientName = "empty") {
        override fun id() = EntId.long()
    }

    private class ImmutableOnly : EntSchema("immutable_only", clientName = "immutableOnly") {
        override fun id() = EntId.long()
        val name by string("name").immutable()
    }

    private fun states(schema: EntSchema): Map<String, TypeSpec> =
        MutationGenerator("com.example.ent")
            .generate(schema::class.simpleName!!, schema)
            .associate { it.name to it.members.filterIsInstance<TypeSpec>().single() }

    @Test
    fun `setters share one private reconstruction function per state`() {
        for ((name, state) in states(Record())) {
            val copy = state.funSpecs.single { it.name == "copy" }
            assertEquals(setOf(KModifier.PRIVATE), copy.modifiers, name)

            val expectedFields = if (name == "RecordBeforeCreateState") {
                listOf("name", "description", "createdAt")
            } else {
                listOf("name", "description")
            }
            assertEquals(expectedFields, copy.parameters.map { it.name }, name)
            for (parameter in copy.parameters) {
                assertEquals("this.${parameter.name}", parameter.defaultValue.toString(), name)
            }

            val replacements = state.funSpecs.filterNot { it.name == "copy" }
            assertEquals(expectedFields.size * 2, replacements.size, name)
            for (replacement in replacements) {
                val code = replacement.body.toString()
                assertTrue(code.startsWith("return copy("), "$name.${replacement.name}: $code")
                assertEquals(1, code.lineSequence().count { it.isNotBlank() }, code)
            }
            assertEquals(1, state.funSpecs.count { "$name(" in it.body.toString() }, name)
        }
    }

    @Test
    fun `empty states do not emit unused copy helpers`() {
        for ((name, state) in states(Empty())) {
            assertTrue(state.funSpecs.isEmpty(), name)
        }
    }

    @Test
    fun `immutable-only fields need a copy helper only in before-create state`() {
        val states = states(ImmutableOnly())

        assertTrue(states.getValue("ImmutableOnlyBeforeSaveState").funSpecs.isEmpty())
        assertTrue(states.getValue("ImmutableOnlyBeforeUpdateState").funSpecs.isEmpty())
        assertEquals(
            setOf("setName", "unsetName", "copy"),
            states.getValue("ImmutableOnlyBeforeCreateState").funSpecs.map { it.name }.toSet(),
        )
    }
}
