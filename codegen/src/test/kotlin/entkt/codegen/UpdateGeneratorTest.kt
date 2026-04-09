package entkt.codegen

import kotlin.test.Test

class UpdateGeneratorTest {

    private val generator = UpdateGenerator("com.example.ent")

    @Test
    fun `generates update builder with setters for mutable fields`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("class UserUpdate")) { "Should generate UserUpdate class\n$output" }
        assert(output.contains("fun setName(")) { "Should have setName\n$output" }
        assert(output.contains("fun setAge(")) { "Should have setAge\n$output" }
    }

    @Test
    fun `excludes immutable fields from setters`() {
        val output = generator.generate("User", User).toString()

        assert(!output.contains("fun setCreatedAt")) { "Should not have setter for immutable field\n$output" }
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