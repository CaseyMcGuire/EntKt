package entkt.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientGeneratorTest {

    private val generator = ClientGenerator("com.example.ent")

    private val schemas = listOf(
        SchemaInput("Car", Car),
        SchemaInput("User", User),
    )

    @Test
    fun `generates EntClient class`() {
        val output = generator.generate(schemas).toString()

        assert(output.contains("class EntClient")) { "Should generate EntClient\n$output" }
    }

    @Test
    fun `EntClient takes a Driver in its constructor`() {
        val output = generator.generate(schemas).toString()

        assert(output.contains("import entkt.runtime.Driver")) { "Should import Driver\n$output" }
        assert(output.contains("driver: Driver")) { "Should take Driver in constructor\n$output" }
    }

    @Test
    fun `EntClient exposes a repo property per schema`() {
        val output = generator.generate(schemas).toString()

        // Pluralized, camel-cased property name, initialized with the driver.
        assert(output.contains("val cars: CarRepo = CarRepo(driver)")) {
            "Should expose cars: CarRepo\n$output"
        }
        assert(output.contains("val users: UserRepo = UserRepo(driver)")) {
            "Should expose users: UserRepo\n$output"
        }
    }

    @Test
    fun `EntClient is emitted in the configured package`() {
        val file = generator.generate(schemas)

        assertEquals("com.example.ent", file.packageName)
        assertEquals("EntClient", file.name)
    }

    @Test
    fun `EntClient emits withTransaction that creates a transactional client`() {
        val output = generator.generate(schemas).toString()

        assert(output.contains("fun <T> withTransaction(block: (EntClient) -> T): T")) {
            "Should emit withTransaction method\n$output"
        }
        assert(output.contains("driver.withTransaction")) {
            "Should delegate to driver.withTransaction\n$output"
        }
    }

    @Test
    fun `withTransaction copies hooks from original repos to transactional repos`() {
        val output = generator.generate(schemas).toString()

        assert(output.contains("tx.cars.copyHooksFrom(this.cars)")) {
            "Should copy hooks for cars repo\n$output"
        }
        assert(output.contains("tx.users.copyHooksFrom(this.users)")) {
            "Should copy hooks for users repo\n$output"
        }
    }

    @Test
    fun `pluralize handles the cases the example schemas exercise`() {
        assertEquals("users", pluralize("user"))
        assertEquals("posts", pluralize("post"))
        assertEquals("tags", pluralize("tag"))
        assertEquals("categories", pluralize("category"))
        assertEquals("boxes", pluralize("box"))
        assertEquals("dishes", pluralize("dish"))
    }
}