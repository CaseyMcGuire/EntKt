package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

// M2M test schemas for through.target identity validation
private class M2mGroupSchema : EntSchema("groups") {
    override fun id() = EntId.int()
    val name = string("name")
    val members = manyToMany<M2mPersonSchema>("members")
        .throughEntity<M2mMembershipSchema>(M2mMembershipSchema::group, M2mMembershipSchema::person)
}

private class M2mPersonSchema : EntSchema("persons") {
    override fun id() = EntId.int()
    val name = string("name")
}

private class M2mMembershipSchema : EntSchema("memberships") {
    override fun id() = EntId.int()
    val groupId = int("group_id")
    val personId = int("person_id")
    val group = belongsTo<M2mGroupSchema>("group").field(groupId)
    val person = belongsTo<M2mPersonSchema>("person").field(personId)
}

// Derived-name collision test schemas: raw names stay unique, but the
// derived artifact file / client property collides.
private class ArtifactBaseSchema : EntSchema("artifact_bases") {
    override fun id() = EntId.int()
    val name = string("name")
}

private class ArtifactSuffixedSchema : EntSchema("artifact_suffixed") {
    override fun id() = EntId.int()
    val note = string("note")
}

private class BoxSchema : EntSchema("boxes_one") {
    override fun id() = EntId.int()
    val label = string("label")
}

private class BoxeSchema : EntSchema("boxes_two") {
    override fun id() = EntId.int()
    val label = string("label")
}

class EntGeneratorTest {

    private val generator = EntGenerator("com.example.ent")

    @Test
    fun `generates eight files per schema plus the client-level files`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val schemas = listOf(
            SchemaInput("Car", car),
            SchemaInput("User", user),
        )
        val files = generator.generate(schemas)

        // Per schema: entity, mutation, create, update, query, repo, privacy, validation.
        // Plus the schema-set-level files: EntReadRuntime (the read contract
        // + per-entity read surfaces), EntReadClient (the shared read-only
        // interface, the EntValidationReadClient / EntPrivacyReadClient
        // posture wrappers, the internal impl, and the per-entity read
        // repos), and the EntClient that wires every repo together. User
        // also gets a UserIndexes file (it declares eligible indexes); Car
        // has none, so it gets no index-helper file.
        assertEquals(8 * schemas.size + 3 + 1, files.size)
        val names = files.map { it.name }.toSet()
        assertEquals(
            setOf(
                "Car", "CarMutation", "CarCreate", "CarUpdate", "CarQuery", "CarRepo", "CarPrivacy", "CarValidation",
                "User", "UserMutation", "UserCreate", "UserUpdate", "UserQuery", "UserRepo", "UserPrivacy", "UserValidation",
                "UserIndexes",
                "EntReadRuntime",
                "EntReadClient",
                "EntClient",
            ),
            names,
        )
    }

    @Test
    fun `schema name colliding with a derived artifact name is rejected`() {
        val base = ArtifactBaseSchema()
        val suffixed = ArtifactSuffixedSchema()
        finalize(base, suffixed)
        val error = assertFailsWith<IllegalStateException> {
            generator.generate(
                listOf(
                    SchemaInput("Account", base),
                    SchemaInput("AccountCreate", suffixed),
                ),
            )
        }
        assertContains(error.message!!, "AccountCreate.kt")
    }

    @Test
    fun `schemas deriving the same pluralized client property are rejected`() {
        val box = BoxSchema()
        val boxe = BoxeSchema()
        finalize(box, boxe)
        val error = assertFailsWith<IllegalStateException> {
            generator.generate(
                listOf(
                    SchemaInput("Box", box),
                    SchemaInput("Boxe", boxe),
                ),
            )
        }
        assertContains(error.message!!, "'boxes'")
        assertContains(error.message!!, "'Box'")
        assertContains(error.message!!, "'Boxe'")
    }

    @Test
    fun `all generated files have correct package`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val schemas = listOf(
            SchemaInput("Car", car),
            SchemaInput("User", user),
        )
        val files = generator.generate(schemas)

        files.forEach { file ->
            assertEquals("com.example.ent", file.packageName)
        }
    }

    @Test
    fun `writeTo writes files to output directory`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val schemas = listOf(
            SchemaInput("Car", car),
            SchemaInput("User", user),
        )
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

    @Test
    fun `ensureFinalized rejects duplicate index names across schemas`() {
        class Users : EntSchema("users") {
            override fun id() = EntId.int()
            val email = string("email")
            val byEmail = index("idx_email", email)
        }
        class Orgs : EntSchema("orgs") {
            override fun id() = EntId.int()
            val email = string("email")
            val byEmail = index("idx_email", email)
        }
        val users = Users()
        val orgs = Orgs()
        val schemas = listOf(
            SchemaInput("Users", users),
            SchemaInput("Orgs", orgs),
        )
        val err = assertFailsWith<IllegalStateException> {
            ensureFinalized(schemas)
        }
        assertContains(err.message!!, "idx_email")
        assertContains(err.message!!, "globally unique")
    }

    @Test
    fun `ensureFinalized rejects index name colliding with table name`() {
        class Users : EntSchema("users") {
            override fun id() = EntId.int()
            val email = string("email")
        }
        class Orgs : EntSchema("orgs") {
            override fun id() = EntId.int()
            val email = string("email")
            val byEmail = index("users", email)
        }
        val users = Users()
        val orgs = Orgs()
        val schemas = listOf(
            SchemaInput("Users", users),
            SchemaInput("Orgs", orgs),
        )
        val err = assertFailsWith<IllegalStateException> {
            ensureFinalized(schemas)
        }
        assertContains(err.message!!, "users")
        assertContains(err.message!!, "collides with")
    }

    @Test
    fun `ensureFinalized rejects index name colliding with synthesized unique index`() {
        class Users : EntSchema("users") {
            override fun id() = EntId.int()
            val email = string("email").unique()
        }
        class Orgs : EntSchema("orgs") {
            override fun id() = EntId.int()
            val name = string("name")
            // This explicit name matches the synthesized idx_users_email_unique
            val byName = index("idx_users_email_unique", name)
        }
        val users = Users()
        val orgs = Orgs()
        val schemas = listOf(
            SchemaInput("Users", users),
            SchemaInput("Orgs", orgs),
        )
        val err = assertFailsWith<IllegalStateException> {
            ensureFinalized(schemas)
        }
        assertContains(err.message!!, "idx_users_email_unique")
        assertContains(err.message!!, "collides with")
    }

    @Test
    fun `ensureFinalized rejects stale M2M junction instance`() {
        // Finalize group, person, membership together (registry 1).
        val group = M2mGroupSchema()
        val person = M2mPersonSchema()
        val membership1 = M2mMembershipSchema()
        finalize(group, person, membership1)

        // Now build a schema set with the same group/person but a *new* junction instance.
        // group.edges() still holds through.target = membership1 from registry 1,
        // but the schema set contains membership2 — identity mismatch.
        val membership2 = M2mMembershipSchema()
        val schemas = listOf(
            SchemaInput("M2mGroup", group),
            SchemaInput("M2mPerson", person),
            SchemaInput("M2mMembership", membership2),
        )
        val err = assertFailsWith<IllegalStateException> {
            ensureFinalized(schemas)
        }
        assertContains(err.message!!, "junction schema instance")
        assertContains(err.message!!, "memberships")
    }
}
