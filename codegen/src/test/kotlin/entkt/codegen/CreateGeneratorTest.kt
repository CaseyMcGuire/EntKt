package entkt.codegen

import kotlin.test.Test

class CreateGeneratorTest {

    private val generator = CreateGenerator("com.example.ent")

    @Test
    fun `generates create builder with setters for each field`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("class CarCreate")) { "Should generate CarCreate class\n$output" }
        assert(output.contains("fun setModel(")) { "Should have setModel\n$output" }
        assert(output.contains("fun setYear(")) { "Should have setYear\n$output" }
        assert(output.contains("fun setPrice(")) { "Should have setPrice\n$output" }
    }

    @Test
    fun `setters return builder for chaining`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("): CarCreate")) { "Should return CarCreate for chaining\n$output" }
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
    fun `includes mixin fields`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("fun setCreatedAt(")) { "Should have mixin setter\n$output" }
        assert(output.contains("fun setUpdatedAt(")) { "Should have mixin setter\n$output" }
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