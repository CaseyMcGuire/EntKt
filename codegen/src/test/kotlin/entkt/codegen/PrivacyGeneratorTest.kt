package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.entity.PrivacyGenerator
import entkt.codegen.fixtures.Car
import entkt.codegen.fixtures.User
import entkt.codegen.mutation.MutationGenerator
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
            .replace("\\s+".toRegex(), " ")

        assertContains(output, "typealias UserLoadPrivacyRule = PrivacyRule<ReadOnlyEntClient, User>")
        assertContains(output, "typealias UserCreatePrivacyRule = PrivacyRule<ReadOnlyEntClient, UserWriteCandidate>")
        assertContains(output, "typealias UserUpdatePrivacyRule = PrivacyRule<ReadOnlyEntClient, UserUpdateRuleInput>")
        assertContains(output, "typealias UserDeletePrivacyRule = PrivacyRule<ReadOnlyEntClient, UserDeleteRuleInput>")
        assertContains(output, "typealias UserLoadBatchPrivacyRule = BatchPrivacyRule<ReadOnlyEntClient, User>")
        assertContains(output, "typealias UserCreateBatchPrivacyRule = BatchPrivacyRule<ReadOnlyEntClient, UserWriteCandidate>")
        assertContains(output, "typealias UserUpdateBatchPrivacyRule = BatchPrivacyRule<ReadOnlyEntClient, UserUpdateRuleInput>")
        assertContains(output, "typealias UserDeleteBatchPrivacyRule = BatchPrivacyRule<ReadOnlyEntClient, UserDeleteRuleInput>")
    }

    @Test
    fun `LOAD rules use the entity directly without a generated wrapper`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assertFalse(
            output.contains("LoadPrivacyItem"),
            "LOAD rules should not require a generated wrapper\n$output",
        )
        assertContains(output, "original entity without defensive copies")
        assertContains(output, "Treat the entity and all nested values as read-only")
        assertFalse(
            output.contains("val client: EntClient"),
            "Privacy artifacts should not carry hook-only client state\n$output",
        )
    }

    @Test
    fun `generates WriteCandidate with all schema fields except id`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assertContains(output, "data class UserWriteCandidate")
        assertContains(output, ": WriteCandidate<User>")
        assertContains(output, "val name: String")
        assertContains(output, "val email: String")
        assertContains(output, "val age: Int?")
        assertFalse(
            output.contains("val id:"),
            "WriteCandidate should not have id\n$output",
        )
    }

    @Test
    fun `privacy config only binds lifecycle rule types to the runtime base`() {
        val user = User()
        finalize(user, Car())
        val config = generator.generate("User", user).members.filterIsInstance<TypeSpec>()
            .single { it.name == "UserPrivacyConfig" }

        assertEquals(
            ClassName("entkt.runtime.privacy", "EntityPrivacyConfig").parameterizedBy(
                ClassName("com.example.ent", "UserLoadBatchPrivacyRule"),
                ClassName("com.example.ent", "UserCreateBatchPrivacyRule"),
                ClassName("com.example.ent", "UserUpdateBatchPrivacyRule"),
                ClassName("com.example.ent", "UserDeleteBatchPrivacyRule"),
            ),
            config.superclass,
        )
        assertTrue(config.propertySpecs.isEmpty(), "Rule storage and derivation flags belong in runtime")
        assertTrue(config.funSpecs.isEmpty(), "Resolution belongs in runtime")
    }

    @Test
    fun `privacy scope only binds the client and lifecycle items and passes config to the runtime base`() {
        val user = User()
        finalize(user, Car())
        val scope = generator.generate("User", user).members.filterIsInstance<TypeSpec>()
            .single { it.name == "UserPrivacyScope" }

        assertEquals(
            ClassName("entkt.runtime.privacy", "EntityPrivacyScope").parameterizedBy(
                ClassName("com.example.ent", "ReadOnlyEntClient"),
                ClassName("com.example.ent", "User"),
                ClassName("com.example.ent", "UserWriteCandidate"),
                ClassName("com.example.ent", "UserUpdateRuleInput"),
                ClassName("com.example.ent", "UserDeleteRuleInput"),
            ),
            scope.superclass,
        )
        val constructor = requireNotNull(scope.primaryConstructor)
        assertEquals(setOf(KModifier.INTERNAL), constructor.modifiers)
        assertEquals("config", constructor.parameters.single().name)
        assertEquals(ClassName("com.example.ent", "UserPrivacyConfig"), constructor.parameters.single().type)
        assertEquals(listOf("config"), scope.superclassConstructorParameters.map { it.toString() })
        assertTrue(scope.propertySpecs.isEmpty(), "The config is stored only by the runtime base")
        assertTrue(scope.funSpecs.isEmpty(), "All registration forms and derivation belong in runtime")
    }

    @Test
    fun `id-only schema emits constructible WriteCandidate class`() {
        val idOnly = object : EntSchema("empties", clientName = "empties") {
            override fun id() = EntId.int()
        }
        finalize(idOnly)
        val output = generator.generate("Empty", idOnly).toString()

        assertContains(output, "class EmptyWriteCandidate")
        assertContains(output, ": WriteCandidate<Empty>")
        assertFalse(
            output.contains("data class EmptyWriteCandidate"),
            "Should not be a data class (no properties)\n$output",
        )
        assertFalse(
            output.contains("object EmptyWriteCandidate"),
            "Should not be an object (must be constructible with parens)\n$output",
        )
    }

    @Test
    fun `generates PolicyScope with privacy block`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assertContains(output, "class UserPolicyScope")
        assertContains(output, "fun privacy(block: UserPrivacyScope.() -> Unit)")
    }

    @Test
    fun `generates PolicyScope with validation block`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assertContains(output, "fun validation(block: UserValidationScope.() -> Unit)")
    }

    @Test
    fun `PolicyScope constructor takes both privacy and validation config`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assertContains(output, "privacyConfig: UserPrivacyConfig")
        assertContains(output, "validationConfig: UserValidationConfig")
    }

    // ---------- link-table M2M helpers PendingEdgeOps aggregator on UpdateHookContext ----------

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
        assertContains(output, "public class UserPendingEdgeOps()")
        assertContains(output, ": UpdatePendingEdges<User>")
        assertFalse(
            output.contains("public data class UserPendingEdgeOps"),
            "Empty aggregator must not be a data class\n$output",
        )
    }

    @Test
    fun `emits typed PendingEdgeOps aggregator with one field per helper-eligible edge`() {
        val output = makeLinkM2MOutput()

        // Data class with typed `tags: PendingEdgeOps<UUID>` field
        // defaulting to empty (so callers can construct without args).
        assertContains(output, "public data class PrivM2MPostPendingEdgeOps")
        assertContains(output, ": UpdatePendingEdges<PrivM2MPost>")
        assertContains(output, "public val tags: PendingEdgeOps<UUID>")
        assertContains(output, "PendingEdgeOps()")
    }

    @Test
    fun `beforeUpdate state gains a pendingEdges field`() {
        val user = User()
        finalize(user, Car())
        val output = MutationGenerator("com.example.ent").generate("User", user)
            .single { it.name == "UserBeforeUpdateState" }.toString()
            .replace("\\s+".toRegex(), " ")

        assertContains(output, "pendingEdges: UserPendingEdgeOps")
        assertContains(output, "public val pendingEdges: UserPendingEdgeOps")
        assertContains(output, ": BeforeUpdateHookState<User>")
    }

    @Test
    fun `beforeSave state implements its lifecycle marker`() {
        val user = User()
        finalize(user, Car())
        val output = MutationGenerator("com.example.ent").generate("User", user)
            .single { it.name == "UserBeforeSaveState" }.toString()

        assertContains(output, ": BeforeSaveHookState<User>")
    }

    @Test
    fun `beforeUpdate state for entity with M2M edge is typed against per-entity aggregator`() {
        val output = makeLinkM2MHookStateOutput()

        // The pendingEdges field is typed to the per-entity aggregator,
        // not the generic PendingEdgeOps<ID>.
        assertContains(output, "pendingEdges: PrivM2MPostPendingEdgeOps")
    }

    // ---------- link-table M2M helpers EdgeChangesView sidecar ----------

    @Test
    fun `emits empty EdgeChangesView for schemas without helper-eligible edges`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        // Uniform-shape pattern: plain class with a no-arg constructor
        // when there are no fields, so the privacy/validation context
        // can default-construct it.
        assertContains(output, "public class UserEdgeChangesView()")
        assertFalse(
            output.contains("public data class UserEdgeChangesView"),
            "Empty EdgeChangesView must not be a data class\n$output",
        )
    }

    @Test
    fun `emits typed EdgeChangesView with one EdgeChanges field per helper-eligible edge`() {
        val output = makeLinkM2MOutput()

        // Data class with typed `tags: EdgeChanges<UUID>` defaulting to
        // empty.
        assertContains(output, "public data class PrivM2MPostEdgeChangesView")
        assertContains(output, "public val tags: EdgeChanges<UUID>")
        assertContains(output, "EdgeChanges()")
    }
}

// ---------- link-table M2M helpers test schemas (PrivacyGeneratorTest) ----------

private class PrivM2MPost : EntSchema("m2m_priv_posts", clientName = "privM2MPosts") {
    override fun id() = EntId.long()
    val title by string("title")
    val tags by manyToMany<PrivM2MTag>()
        .throughLink<PrivM2MPostTagJunction>(PrivM2MPostTagJunction::post, PrivM2MPostTagJunction::tag)
}
private class PrivM2MTag : EntSchema("m2m_priv_tags", clientName = "privM2MTags") {
    override fun id() = EntId.uuid()
    val name by string("name")
}
private class PrivM2MPostTagJunction : EntSchema("m2m_priv_post_tags", clientName = "privM2MPostTagJunctions") {
    override fun id() = EntId.long()
    val post by belongsTo<PrivM2MPost>("post_id").onDelete(entkt.schema.OnDelete.CASCADE)
    val tag by belongsTo<PrivM2MTag>("tag_id").onDelete(entkt.schema.OnDelete.CASCADE)
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

private fun makeLinkM2MHookStateOutput(): String {
    val post = PrivM2MPost()
    val tag = PrivM2MTag()
    val postTag = PrivM2MPostTagJunction()
    finalize(post, tag, postTag)
    val names = mapOf<EntSchema, String>(
        post to "PrivM2MPost",
        tag to "PrivM2MTag",
        postTag to "PrivM2MPostTagJunction",
    )
    return MutationGenerator("com.example.ent")
        .generate("PrivM2MPost", post, names)
        .single { it.name == "PrivM2MPostBeforeUpdateState" }
        .toString()
        .replace("\\s+".toRegex(), " ")
}
