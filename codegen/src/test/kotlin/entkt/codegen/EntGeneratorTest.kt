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
private class M2mGroupSchema : EntSchema("groups", clientName = "m2mGroupSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val members by manyToMany<M2mPersonSchema>("members")
        .throughEntity<M2mMembershipSchema>(M2mMembershipSchema::group, M2mMembershipSchema::person)
}

private class M2mPersonSchema : EntSchema("persons", clientName = "m2mPersonSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
}

private class M2mMembershipSchema : EntSchema("memberships", clientName = "m2mMembershipSchemas") {
    override fun id() = EntId.int()
    val groupId by int("group_id")
    val personId by int("person_id")
    val group by belongsTo<M2mGroupSchema>("group").field(groupId)
    val person by belongsTo<M2mPersonSchema>("person").field(personId)
}

// Derived-name collision fixtures. Entity names come from the class, so
// each class is literally named after the collision it provokes:
// `Account` + `AccountCreateDraft` collide on the generated AccountCreateDraft.kt
// file, and `Post` + `PostLoadBatchPrivacyRule` collide on a generated
// top-level alias.
private class Account : EntSchema("artifact_bases", clientName = "accounts") {
    override fun id() = EntId.int()
    val name by string("name")
}

private class AccountCreateDraft : EntSchema("artifact_suffixed", clientName = "accountCreateDrafts") {
    override fun id() = EntId.int()
    val note by string("note")
}

private class Post : EntSchema("collide_posts", clientName = "collidePosts") {
    override fun id() = EntId.int()
    val name by string("name")
}

private class PostLoadBatchPrivacyRule : EntSchema("collide_aliases", clientName = "collideAliases") {
    override fun id() = EntId.int()
    val note by string("note")
}

private class Box : EntSchema("boxes_one", clientName = "boxes") {
    override fun id() = EntId.int()
    val label by string("label")
}

private class Boxe : EntSchema("boxes_two", clientName = "boxes") {
    override fun id() = EntId.int()
    val label by string("label")
}

// Reserved generated client capability type names.
private class EntTransactionClient : EntSchema("reserved_txn", clientName = "reservedTxns") {
    override fun id() = EntId.int()
    val label by string("label")
}

private class EntClientScope : EntSchema("reserved_scope", clientName = "reservedScopes") {
    override fun id() = EntId.int()
    val label by string("label")
}

class EntGeneratorTest {

    private val generator = EntGenerator("com.example.ent")

    @Test
    fun `generates eight files per schema plus the client-level files`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val schemas = listOf(
            SchemaInput(car),
            SchemaInput(user),
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
                "Car", "CarMutation", "CarCreateDraft", "CarUpdate", "CarQuery", "CarRepo", "CarPrivacy", "CarValidation",
                "User", "UserMutation", "UserCreateDraft", "UserUpdate", "UserQuery", "UserRepo", "UserPrivacy", "UserValidation",
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
        val base = Account()
        val suffixed = AccountCreateDraft()
        finalize(base, suffixed)
        val error = assertFailsWith<IllegalStateException> {
            generator.generate(
                listOf(
                    SchemaInput(base),
                    SchemaInput(suffixed),
                ),
            )
        }
        assertContains(error.message!!, "AccountCreateDraft.kt")
    }

    @Test
    fun `schema name colliding with a generated batch rule alias is rejected`() {
        val post = Post()
        val aliasNamedEntity = PostLoadBatchPrivacyRule()
        finalize(post, aliasNamedEntity)

        val error = assertFailsWith<IllegalStateException> {
            generator.generate(
                listOf(
                    SchemaInput(post),
                    SchemaInput(aliasNamedEntity),
                ),
            )
        }

        assertContains(error.message!!, "Generated top-level type collisions")
        assertContains(error.message!!, "'PostLoadBatchPrivacyRule'")
        assertContains(error.message!!, "PostPrivacy.kt")
        assertContains(error.message!!, "PostLoadBatchPrivacyRule.kt")
    }

    @Test
    fun `the inspector and the generator agree on clientName errors`() {
        // The inspector must never report a schema as valid that
        // generation then rejects. Asserting agreement — rather than
        // asserting each side separately — is what keeps a future check
        // from being added to one entry point only.
        for (inputs in listOf(
            listOf(SchemaInput(DupClientA()), SchemaInput(DupClientB())),
            listOf(SchemaInput(ReservedClientName())),
        )) {
            inputs.forEach { finalize(it.schema) }
            val inspectorErrors = SchemaInspector.validate(inputs).errors
            val generatorError = assertFailsWith<IllegalStateException> {
                EntGenerator("com.example.ent").generate(inputs)
            }.message!!

            assertTrue(
                inspectorErrors.isNotEmpty(),
                "inspector reported valid but generation failed with: $generatorError",
            )
            // Same diagnostic text on both paths.
            assertTrue(
                inspectorErrors.any { it in generatorError },
                "inspector and generator disagree.\ninspector: $inspectorErrors\ngenerator: $generatorError",
            )
        }
    }

    @Test
    fun `clientName colliding with a fixed client member is rejected`() {
        // `driver` is a real property on the generated EntClient, so a
        // schema claiming it would emit a second declaration of that name.
        val schema = ReservedClientName()
        finalize(schema)
        val error = assertFailsWith<IllegalStateException> {
            generator.generate(listOf(SchemaInput(schema)))
        }
        assertContains(error.message!!, "collides with a fixed member of the generated client")
        assertContains(error.message!!, "'driver'")
        assertContains(error.message!!, "ReservedClientName")
    }

    @Test
    fun `schemas declaring the same clientName are rejected`() {
        val box = Box()
        val boxe = Boxe()
        finalize(box, boxe)
        val error = assertFailsWith<IllegalStateException> {
            generator.generate(
                listOf(
                    SchemaInput(box),
                    SchemaInput(boxe),
                ),
            )
        }
        assertContains(error.message!!, "clientName = 'boxes'")
        assertContains(error.message!!, "'Box'")
        assertContains(error.message!!, "'Boxe'")
    }

    @Test
    fun `schema named EntTransactionClient is rejected as a reserved client type`() {
        val schema = EntTransactionClient()
        finalize(schema)

        val error = assertFailsWith<IllegalStateException> {
            generator.generate(listOf(SchemaInput(schema)))
        }

        assertContains(error.message!!, "reserved generated client capability type")
    }

    @Test
    fun `schema named EntClientScope is rejected as a reserved client type`() {
        val schema = EntClientScope()
        finalize(schema)

        val error = assertFailsWith<IllegalStateException> {
            generator.generate(listOf(SchemaInput(schema)))
        }

        assertContains(error.message!!, "reserved generated client capability type")
    }

    @Test
    fun `all generated files have correct package`() {
        val car = Car()
        val user = User()
        finalize(car, user)
        val schemas = listOf(
            SchemaInput(car),
            SchemaInput(user),
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
            SchemaInput(car),
            SchemaInput(user),
        )
        val outputDir = Files.createTempDirectory("entkt-test")
        try {
            generator.writeTo(outputDir, schemas)

            val packageDir = outputDir.resolve("com/example/ent")
            assertTrue(Files.exists(packageDir.resolve("Car.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarMutation.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarCreateDraft.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarUpdate.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarQuery.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarRepo.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarPrivacy.kt")))
            assertTrue(Files.exists(packageDir.resolve("CarValidation.kt")))
            assertTrue(Files.exists(packageDir.resolve("User.kt")))
            assertTrue(Files.exists(packageDir.resolve("UserMutation.kt")))
            assertTrue(Files.exists(packageDir.resolve("UserCreateDraft.kt")))
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
        class Users : EntSchema("users", clientName = "userses") {
            override fun id() = EntId.int()
            val email by string("email")
            val byEmail = index("idx_email", email)
        }
        class Orgs : EntSchema("orgs", clientName = "orgses") {
            override fun id() = EntId.int()
            val email by string("email")
            val byEmail = index("idx_email", email)
        }
        val users = Users()
        val orgs = Orgs()
        val schemas = listOf(
            SchemaInput(users),
            SchemaInput(orgs),
        )
        val err = assertFailsWith<IllegalStateException> {
            ensureFinalized(schemas)
        }
        assertContains(err.message!!, "idx_email")
        assertContains(err.message!!, "globally unique")
    }

    @Test
    fun `ensureFinalized rejects index name colliding with table name`() {
        class Users : EntSchema("users", clientName = "userses") {
            override fun id() = EntId.int()
            val email by string("email")
        }
        class Orgs : EntSchema("orgs", clientName = "orgses") {
            override fun id() = EntId.int()
            val email by string("email")
            val byEmail = index("users", email)
        }
        val users = Users()
        val orgs = Orgs()
        val schemas = listOf(
            SchemaInput(users),
            SchemaInput(orgs),
        )
        val err = assertFailsWith<IllegalStateException> {
            ensureFinalized(schemas)
        }
        assertContains(err.message!!, "users")
        assertContains(err.message!!, "collides with")
    }

    @Test
    fun `ensureFinalized rejects index name colliding with synthesized unique index`() {
        class Users : EntSchema("users", clientName = "userses") {
            override fun id() = EntId.int()
            val email by string("email").unique()
        }
        class Orgs : EntSchema("orgs", clientName = "orgses") {
            override fun id() = EntId.int()
            val name by string("name")
            // This explicit name matches the synthesized idx_users_email_unique
            val byName = index("idx_users_email_unique", name)
        }
        val users = Users()
        val orgs = Orgs()
        val schemas = listOf(
            SchemaInput(users),
            SchemaInput(orgs),
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
            SchemaInput(group),
            SchemaInput(person),
            SchemaInput(membership2),
        )
        val err = assertFailsWith<IllegalStateException> {
            ensureFinalized(schemas)
        }
        assertContains(err.message!!, "junction schema instance")
        assertContains(err.message!!, "memberships")
    }
}

// `driver` is a fixed member of the generated client.
private class ReservedClientName : EntSchema("reserved_client", clientName = "driver") {
    override fun id() = EntId.int()
    val label by string("label")
}

// Two schemas claiming the same client property.
private class DupClientA : EntSchema("dup_client_a", clientName = "records") {
    override fun id() = EntId.int()
    val label by string("label")
}

private class DupClientB : EntSchema("dup_client_b", clientName = "records") {
    override fun id() = EntId.int()
    val label by string("label")
}
