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
    fun `generates four files per schema`() {
        val files = generator.generate(schemas)

        assertEquals(8, files.size)
        val names = files.map { it.name }.toSet()
        assertEquals(
            setOf("Car", "CarCreate", "CarUpdate", "CarQuery", "User", "UserCreate", "UserUpdate", "UserQuery"),
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
            assertTrue(Files.exists(packageDir.resolve("CarCreate.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarUpdate.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarQuery.kt")))
            assertTrue(Files.exists(packageDir.resolve("User.kt")))
            assertTrue(Files.exists(packageDir.resolve("UserCreate.kt")))
            assertTrue(Files.exists(packageDir.resolve("UserUpdate.kt")))
            assertTrue(Files.exists(packageDir.resolve("UserQuery.kt")))
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }
}