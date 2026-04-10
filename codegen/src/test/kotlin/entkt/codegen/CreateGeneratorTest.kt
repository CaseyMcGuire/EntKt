package entkt.codegen

import kotlin.test.Test

class CreateGeneratorTest {

    private val generator = CreateGenerator("com.example.ent")

    @Test
    fun `generates create builder with mutable properties for each field`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("class CarCreate")) { "Should generate CarCreate class\n$output" }
        assert(output.contains("var model: String?")) { "Should have model var\n$output" }
        assert(output.contains("var year: Int?")) { "Should have year var\n$output" }
        assert(output.contains("var price: Float?")) { "Should have price var\n$output" }
    }

    @Test
    fun `create builder is annotated as DSL scope`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("@EntktDsl")) { "Should be annotated @EntktDsl\n$output" }
    }

    @Test
    fun `save validates required fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun save(): Car")) { "Should have save method returning entity\n$output" }
        assert(output.contains(""""model is required"""")) { "Should validate model is required\n$output" }
        assert(output.contains(""""year is required"""")) { "Should validate year is required\n$output" }
    }

    @Test
    fun `save does not validate optional fields`() {
        val output = generator.generate("Car", Car).toString()

        assert(!output.contains(""""price is required"""")) { "Should not validate optional price\n$output" }
    }

    @Test
    fun `includes mixin fields as properties`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("var createdAt: Instant?")) { "Should have mixin property\n$output" }
        assert(output.contains("var updatedAt: Instant?")) { "Should have mixin property\n$output" }
    }

    @Test
    fun `save falls back to default literal for fields with a default`() {
        val output = generator.generate("User", User).toString()

        // User.active has .default(true). The constructor param is non-null,
        // so save() must coalesce to the default rather than passing this.active.
        assert(output.contains("active = this.active ?: true")) {
            "Should coalesce to default literal for active\n$output"
        }
        assert(!output.contains(""""active is required"""")) {
            "Should not validate a field with a default\n$output"
        }
    }
}