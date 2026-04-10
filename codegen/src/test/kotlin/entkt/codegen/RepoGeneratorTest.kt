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
    fun `byId delegates to the driver and hydrates via fromRow`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("driver.byId(Car.TABLE, id)")) {
            "byId should call driver.byId with the entity's TABLE constant\n$output"
        }
        assert(output.contains("Car.fromRow(it)")) {
            "byId should hydrate the driver's row via Car.fromRow\n$output"
        }
    }

    @Test
    fun `repo exposes delete taking the entity and deleteById taking the id`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun delete(entity: Car): Boolean")) {
            "Should have delete(entity)\n$output"
        }
        assert(output.contains("fun deleteById(id: Int): Boolean")) {
            "Should have deleteById taking the schema id type\n$output"
        }
    }

    @Test
    fun `delete delegates to the driver`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("driver.delete(Car.TABLE, entity.id)")) {
            "delete should call driver.delete with entity.id\n$output"
        }
        assert(output.contains("driver.delete(Car.TABLE, id)")) {
            "deleteById should call driver.delete with the id\n$output"
        }
    }

    @Test
    fun `deleteById uses the correct id type for UUID schemas`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("fun deleteById(id: UUID): Boolean")) {
            "deleteById should use UUID for User's id type\n$output"
        }
    }

    @Test
    fun `repo registers the entity schema in its init block`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("driver.register(Car.SCHEMA)")) {
            "Repo should register Car.SCHEMA with the driver on construction\n$output"
        }
    }
}