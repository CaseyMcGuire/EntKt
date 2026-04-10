package entkt.codegen

import kotlin.test.Test

class RepoGeneratorTest {

    private val generator = RepoGenerator("com.example.ent")

    @Test
    fun `generates repo class`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("class CarRepo")) { "Should generate CarRepo\n$output" }
    }

    @Test
    fun `repo takes a Driver in its constructor`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("import entkt.runtime.Driver")) { "Should import Driver\n$output" }
        assert(output.contains("driver: Driver")) { "Should take Driver in constructor\n$output" }
    }

    @Test
    fun `repo holds the driver as a private property`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("private val driver: Driver")) {
            "Driver should be a private val\n$output"
        }
    }

    @Test
    fun `repo exposes query, create, and update`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun query(block: CarQuery.() -> Unit = {}): CarQuery")) {
            "Should have query with optional DSL block\n$output"
        }
        assert(output.contains("fun create(block: CarCreate.() -> Unit): CarCreate")) {
            "Should have create taking DSL block\n$output"
        }
        assert(output.contains("fun update(entity: Car, block: CarUpdate.() -> Unit): CarUpdate")) {
            "Should have update(entity, block) — entity comes through the repo, not the instance\n$output"
        }
    }

    @Test
    fun `repo exposes byId taking the schema id type`() {
        val output = generator.generate("User", User).toString()

        // User has UUID id; the byId param type must match.
        assert(output.contains("fun byId(id: UUID): User?")) {
            "byId should return nullable entity and take the schema id type\n$output"
        }
    }

    @Test
    fun `byId is a TODO until the runtime exists`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("TODO(\"byId requires the runtime layer\")")) {
            "byId should TODO with a clear message\n$output"
        }
    }
}