package entkt.codegen

import kotlin.test.Test

class UpdateGeneratorTest {

    private val generator = UpdateGenerator("com.example.ent")

    @Test
    fun `generates update builder with mutable properties for mutable fields`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("class UserUpdate")) { "Should generate UserUpdate class\n$output" }
        assert(output.contains("var name: String?")) { "Should have name var\n$output" }
        assert(output.contains("var age: Int?")) { "Should have age var\n$output" }
    }

    @Test
    fun `update builder is annotated as DSL scope`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("@EntktDsl")) { "Should be annotated @EntktDsl\n$output" }
    }

    @Test
    fun `excludes immutable fields from mutable properties`() {
        val output = generator.generate("User", User).toString()

        assert(!output.contains("var createdAt")) { "Should not have mutable createdAt\n$output" }
    }

    @Test
    fun `save preserves immutable fields from entity`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("createdAt = entity.createdAt")) { "Should preserve immutable createdAt\n$output" }
    }

    @Test
    fun `save uses new value or falls back to entity`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("name = this.name ?: entity.name")) { "Should use new value or fallback\n$output" }
    }

    @Test
    fun `takes entity in constructor`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("entity: User")) { "Should take entity parameter\n$output" }
    }
}