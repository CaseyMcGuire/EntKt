package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.reflect.KClass
import kotlin.test.Test

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class PrivacyGeneratorTest {

    private val generator = PrivacyGenerator("com.example.ent")

    @Test
    fun `generates rule typealiases for all four operations`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("typealias UserLoadPrivacyRule = PrivacyRule<UserLoadPrivacyContext>")) {
            "Should generate load rule typealias\n$output"
        }
        assert(output.contains("typealias UserCreatePrivacyRule = PrivacyRule<UserCreatePrivacyContext>")) {
            "Should generate create rule typealias\n$output"
        }
        assert(output.contains("typealias UserUpdatePrivacyRule = PrivacyRule<UserUpdatePrivacyContext>")) {
            "Should generate update rule typealias\n$output"
        }
        assert(output.contains("typealias UserDeletePrivacyRule = PrivacyRule<UserDeletePrivacyContext>")) {
            "Should generate delete rule typealias\n$output"
        }
    }

    @Test
    fun `generates load context with privacy, client, and entity`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("data class UserLoadPrivacyContext")) {
            "Should generate load context\n$output"
        }
        assert(output.contains("val privacy: PrivacyContext")) {
            "Load context should have privacy\n$output"
        }
        assert(output.contains("val client: EntClient")) {
            "Load context should have client\n$output"
        }
        assert(output.contains("val entity: User")) {
            "Load context should have entity\n$output"
        }
    }

    @Test
    fun `generates create context with privacy, client, and candidate`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("data class UserCreatePrivacyContext")) {
            "Should generate create context\n$output"
        }
        assert(output.contains("val candidate: UserWriteCandidate")) {
            "Create context should have candidate\n$output"
        }
    }

    @Test
    fun `generates update context with privacy, client, before entity, and candidate`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("data class UserUpdatePrivacyContext")) {
            "Should generate update context\n$output"
        }
        assert(output.contains("val before: User")) {
            "Update context should have before entity\n$output"
        }
    }

    @Test
    fun `generates delete context with privacy, client, entity, and candidate`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("data class UserDeletePrivacyContext")) {
            "Should generate delete context\n$output"
        }
    }

    @Test
    fun `generates WriteCandidate with all schema fields except id`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("data class UserWriteCandidate")) {
            "Should generate WriteCandidate\n$output"
        }
        assert(output.contains("val name: String")) {
            "WriteCandidate should have name\n$output"
        }
        assert(output.contains("val email: String")) {
            "WriteCandidate should have email\n$output"
        }
        assert(output.contains("val age: Int?")) {
            "WriteCandidate should have optional age\n$output"
        }
        assert(!output.contains("val id:")) {
            "WriteCandidate should not have id\n$output"
        }
    }

    @Test
    fun `generates PrivacyConfig with mutable rule lists and derivation flags`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("class UserPrivacyConfig")) {
            "Should generate PrivacyConfig\n$output"
        }
        assert(output.contains("val loadRules: MutableList<UserLoadPrivacyRule>")) {
            "Should have loadRules\n$output"
        }
        assert(output.contains("var updateDerivesFromCreate: Boolean = false")) {
            "Should have updateDerivesFromCreate flag\n$output"
        }
        assert(output.contains("var deleteDerivesFromCreate: Boolean = false")) {
            "Should have deleteDerivesFromCreate flag\n$output"
        }
    }

    @Test
    fun `generates PrivacyScope with DSL methods for each operation`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("class UserPrivacyScope")) {
            "Should generate PrivacyScope\n$output"
        }
        assert(output.contains("fun load(vararg rules: UserLoadPrivacyRule)")) {
            "Should have load method\n$output"
        }
        assert(output.contains("fun create(vararg rules: UserCreatePrivacyRule)")) {
            "Should have create method\n$output"
        }
        assert(output.contains("fun updateDerivesFromCreate()")) {
            "Should have updateDerivesFromCreate method\n$output"
        }
        assert(output.contains("fun deleteDerivesFromCreate()")) {
            "Should have deleteDerivesFromCreate method\n$output"
        }
    }

    @Test
    fun `id-only schema emits constructible WriteCandidate class`() {
        val idOnly = object : EntSchema("empties") {
            override fun id() = EntId.int()
        }
        finalize(idOnly)
        val output = generator.generate("Empty", idOnly).toString()

        assert(output.contains("class EmptyWriteCandidate")) {
            "Should generate a WriteCandidate class\n$output"
        }
        assert(!output.contains("data class EmptyWriteCandidate")) {
            "Should not be a data class (no properties)\n$output"
        }
        assert(!output.contains("object EmptyWriteCandidate")) {
            "Should not be an object (must be constructible with parens)\n$output"
        }
    }

    @Test
    fun `generates PolicyScope with privacy block`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("class UserPolicyScope")) {
            "Should generate PolicyScope\n$output"
        }
        assert(output.contains("fun privacy(block: UserPrivacyScope.() -> Unit)")) {
            "Should have privacy DSL method\n$output"
        }
    }

    @Test
    fun `generates PolicyScope with validation block`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("fun validation(block: UserValidationScope.() -> Unit)")) {
            "Should have validation DSL method\n$output"
        }
    }

    @Test
    fun `PolicyScope constructor takes both privacy and validation config`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("privacyConfig: UserPrivacyConfig")) {
            "PolicyScope should take privacyConfig\n$output"
        }
        assert(output.contains("validationConfig: UserValidationConfig")) {
            "PolicyScope should take validationConfig\n$output"
        }
    }

    // ---------- RFC #5 Phase 3: PendingEdgeOps aggregator on UpdateHookContext ----------

    @Test
    fun `emits empty PendingEdgeOps aggregator for schemas without helper-eligible edges`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // Aggregator type exists so the hook context has uniform shape.
        // It's a plain class (not data) because Kotlin rejects zero-param
        // data classes. The no-arg constructor lets the hook context
        // default-construct it.
        assert(output.contains("public class UserPendingEdgeOps()")) {
            "Empty aggregator should be a no-fields class with explicit no-arg constructor\n$output"
        }
        assert(!output.contains("public data class UserPendingEdgeOps")) {
            "Empty aggregator must not be a data class\n$output"
        }
    }

    @Test
    fun `emits typed PendingEdgeOps aggregator with one field per helper-eligible edge`() {
        val output = makeLinkM2MOutput()

        // Data class with typed `tags: PendingEdgeOps<UUID>` field
        // defaulting to empty (so callers can construct without args).
        assert(output.contains("public data class PrivM2MPostPendingEdgeOps")) {
            "Non-empty aggregator should be a data class\n$output"
        }
        assert(output.contains("public val tags: PendingEdgeOps<UUID>")) {
            "Should expose `tags: PendingEdgeOps<UUID>` field for the M2M target id type\n$output"
        }
        assert(output.contains("PendingEdgeOps()")) {
            "Aggregator constructor should default each field to empty PendingEdgeOps\n$output"
        }
    }

    @Test
    fun `UpdateHookContext gains a pendingEdges field`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // pendingEdges sits between patch (read-only data) and mutation
        // (the writable view) so the read-only sidecar is grouped with
        // the other read-only fields.
        assert(output.contains("pendingEdges: UserPendingEdgeOps")) {
            "UpdateHookContext should expose `pendingEdges: UserPendingEdgeOps`\n$output"
        }
        assert(output.contains("public val pendingEdges: UserPendingEdgeOps")) {
            "UpdateHookContext.pendingEdges should be a public val\n$output"
        }
    }

    @Test
    fun `UpdateHookContext for entity with M2M edge is typed against per-entity aggregator`() {
        val output = makeLinkM2MOutput()

        // The pendingEdges field is typed to the per-entity aggregator,
        // not the generic PendingEdgeOps<ID>.
        assert(output.contains("pendingEdges: PrivM2MPostPendingEdgeOps")) {
            "PrivM2MPostUpdateHookContext should expose `pendingEdges: PrivM2MPostPendingEdgeOps`\n$output"
        }
    }
}

// ---------- RFC #5 Phase 3 test schemas (PrivacyGeneratorTest) ----------

private class PrivM2MPost : EntSchema("m2m_priv_posts") {
    override fun id() = EntId.long()
    val title = string("title")
    val tags = manyToMany<PrivM2MTag>("tags")
        .throughLink<PrivM2MPostTagJunction>(PrivM2MPostTagJunction::post, PrivM2MPostTagJunction::tag)
}
private class PrivM2MTag : EntSchema("m2m_priv_tags") {
    override fun id() = EntId.uuid()
    val name = string("name")
}
private class PrivM2MPostTagJunction : EntSchema("m2m_priv_post_tags") {
    override fun id() = EntId.long()
    val post = belongsTo<PrivM2MPost>("post").onDelete(entkt.schema.OnDelete.CASCADE)
    val tag = belongsTo<PrivM2MTag>("tag").onDelete(entkt.schema.OnDelete.CASCADE)
    val pair = index("idx_m2m_priv_post_tags_pair", post.fk, tag.fk).unique()
}

private fun makeLinkM2MOutput(): String {
    val post = PrivM2MPost()
    val tag = PrivM2MTag()
    val postTag = PrivM2MPostTagJunction()
    finalize(post, tag, postTag)
    val names = mapOf<EntSchema, String>(post to "PrivM2MPost", tag to "PrivM2MTag", postTag to "PrivM2MPostTagJunction")
    return PrivacyGenerator("com.example.ent")
        .generate("PrivM2MPost", post, names)
        .toString()
        .replace("\\s+".toRegex(), " ")
}
