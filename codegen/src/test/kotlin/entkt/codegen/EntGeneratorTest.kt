package entkt.codegen

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntGeneratorTest {

    private val generator = EntGenerator("com.example.ent")

    private val schemas = listOf(
        SchemaInput("Car", Car),
        SchemaInput("User", User),
    )

    @Test
    fun `generates eight files per schema plus one EntClient`() {
        val files = generator.generate(schemas)

        // Per schema: entity, mutation, create, update, query, repo, privacy, validation.
        // Plus a single EntClient that wires every repo together.
        assertEquals(8 * schemas.size + 1, files.size)
        val names = files.map { it.name }.toSet()
        assertEquals(
            setOf(
                "Car", "CarMutation", "CarCreate", "CarUpdate", "CarQuery", "CarRepo", "CarPrivacy", "CarValidation",
                "User", "UserMutation", "UserCreate", "UserUpdate", "UserQuery", "UserRepo", "UserPrivacy", "UserValidation",
                "EntClient",
            ),
            names,
        )
    }

    @Test
    fun `all generated files have correct package`() {
        val files = generator.generate(schemas)

        files.forEach { file ->
            assertEquals("com.example.ent", file.packageName)
        }
    }

    @Test
    fun `writeTo writes files to output directory`() {
        val outputDir = Files.createTempDirectory("entkt-test")
        try {
            generator.writeTo(outputDir, schemas)

            val packageDir = outputDir.resolve("com/example/ent")
            assertTrue(Files.exists(packageDir.resolve("Car.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarMutation.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarCreate.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarUpdate.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarQuery.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarRepo.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarPrivacy.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarValidation.kt")))
            assertTrue(Files.exists(packageDir.resolve("User.kt")))
            assertTrue(Files.exists(packageDir.resolve("UserMutation.kt")))
            assertTrue(Files.exists(packageDir.resolve("UserCreate.kt")))
            assertTrue(Files.exists(packageDir.resolve("UserUpdate.kt")))
            assertTrue(Files.exists(packageDir.resolve("UserQuery.kt")))
            assertTrue(Files.exists(packageDir.resolve("UserRepo.kt")))
            assertTrue(Files.exists(packageDir.resolve("UserPrivacy.kt")))
            assertTrue(Files.exists(packageDir.resolve("UserValidation.kt")))
            assertTrue(Files.exists(packageDir.resolve("EntClient.kt")))
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }
}