package entkt.codegen

import entkt.codegen.entity.PrivacyGenerator
import entkt.codegen.mutation.MutationGenerator
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
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("typealias UserLoadPrivacyRule = PrivacyRule<ReadOnlyEntClient, UserLoadPrivacyItem>")) {
            "Should generate load rule typealias\n$output"
        }
        assert(output.contains("typealias UserCreatePrivacyRule = PrivacyRule<ReadOnlyEntClient, UserCreateRuleInput>")) {
            "Should generate create rule typealias\n$output"
        }
        assert(output.contains("typealias UserUpdatePrivacyRule = PrivacyRule<ReadOnlyEntClient, UserUpdateRuleInput>")) {
            "Should generate update rule typealias\n$output"
        }
        assert(output.contains("typealias UserDeletePrivacyRule = PrivacyRule<ReadOnlyEntClient, UserDeleteRuleInput>")) {
            "Should generate delete rule typealias\n$output"
        }
        assert(output.contains("typealias UserLoadBatchPrivacyRule = BatchPrivacyRule<ReadOnlyEntClient, UserLoadPrivacyItem>")) {
            "Should generate load batch rule typealias\n$output"
        }
        assert(output.contains("typealias UserCreateBatchPrivacyRule = BatchPrivacyRule<ReadOnlyEntClient, UserCreateRuleInput>")) {
            "Should generate create batch rule typealias\n$output"
        }
        assert(output.contains("typealias UserUpdateBatchPrivacyRule = BatchPrivacyRule<ReadOnlyEntClient, UserUpdateRuleInput>")) {
            "Should generate update batch rule typealias\n$output"
        }
        assert(output.contains("typealias UserDeleteBatchPrivacyRule = BatchPrivacyRule<ReadOnlyEntClient, UserDeleteRuleInput>")) {
            "Should generate delete batch rule typealias\n$output"
        }
    }

    @Test
    fun `generates load item with only the entity`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("data class UserLoadPrivacyItem")) {
            "Should generate load item\n$output"
        }
        val constructor = constructorOf(output, "UserLoadPrivacyItem")
        assert(constructor.contains("val entity: User")) {
            "Load item should have entity\n$output"
        }
        assert(!constructor.contains("privacy")) {
            "Load item must not duplicate shared privacy state\n$output"
        }
        assert(!constructor.contains("client")) {
            "Load item must not duplicate the shared client\n$output"
        }
        assert(!output.contains("val client: EntClient")) {
            "Privacy artifacts should not carry hook-only client state\n$output"
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
        assert(output.contains("val loadRules: MutableList<UserLoadBatchPrivacyRule>")) {
            "Should store load rules through the shared batch contract\n$output"
        }
        assert(output.contains("val createRules: MutableList<UserCreateBatchPrivacyRule>")) {
            "Should store create rules through the shared batch contract\n$output"
        }
        assert(output.contains("val updateRules: MutableList<UserUpdateBatchPrivacyRule>")) {
            "Should store update rules through the shared batch contract\n$output"
        }
        assert(output.contains("val deleteRules: MutableList<UserDeleteBatchPrivacyRule>")) {
            "Should store delete rules through the shared batch contract\n$output"
        }
        assert(output.contains("var updateDerivesFromCreate: Boolean = false")) {
            "Should have updateDerivesFromCreate flag\n$output"
        }
        assert(output.contains("var deleteDerivesFromCreate: Boolean = false")) {
            "Should have deleteDerivesFromCreate flag\n$output"
        }
        val normalized = output.replace("\\s+".toRegex(), " ")
        assert(
            normalized.contains(
                "fun resolveForInternalUse(): ResolvedEntityPrivacyConfig<" +
                    "UserLoadBatchPrivacyRule, UserCreateBatchPrivacyRule, " +
                    "UserUpdateBatchPrivacyRule, UserDeleteBatchPrivacyRule>",
            ),
        ) { "Mutable privacy config should resolve to the runtime immutable type\n$output" }
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
        assert(output.contains("fun update(vararg rules: UserUpdatePrivacyRule)")) {
            "Should have update method\n$output"
        }
        assert(output.contains("fun delete(vararg rules: UserDeletePrivacyRule)")) {
            "Should have delete method\n$output"
        }
        assert(output.contains("fun load(rule: UserLoadBatchPrivacyRule)")) {
            "Should register a single batch load rule under the existing DSL name\n$output"
        }
        assert(output.contains("fun create(rule: UserCreateBatchPrivacyRule)")) {
            "Should register a single batch create rule under the existing DSL name\n$output"
        }
        assert(output.contains("fun update(rule: UserUpdateBatchPrivacyRule)")) {
            "Should register a single batch update rule under the existing DSL name\n$output"
        }
        assert(output.contains("fun delete(rule: UserDeleteBatchPrivacyRule)")) {
            "Should register a single batch delete rule under the existing DSL name\n$output"
        }
        listOf("load", "create", "update", "delete").forEach { operation ->
            assert(output.contains("@JvmName(\"${operation}BatchRule\")")) {
                "Batch $operation overload should have a distinct Java name\n$output"
            }
            assert(output.contains("config.${operation}Rules.addAll(rules)")) {
                "Scalar $operation overload should append to the shared list\n$output"
            }
            assert(output.contains("config.${operation}Rules.add(rule)")) {
                "Batch $operation overload should append to the shared list\n$output"
            }
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
        val idOnly = object : EntSchema("empties", clientName = "empties") {
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
    fun `beforeUpdate state gains a pendingEdges field`() {
        val user = User()
        finalize(user, Car())
        val output = MutationGenerator("com.example.ent").generate("User", user)
            .single { it.name == "UserBeforeUpdateState" }.toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("pendingEdges: UserPendingEdgeOps")) {
            "BeforeUpdateState should expose `pendingEdges: UserPendingEdgeOps`\n$output"
        }
        assert(output.contains("public val pendingEdges: UserPendingEdgeOps")) {
            "BeforeUpdateState.pendingEdges should be a public val\n$output"
        }
    }

    @Test
    fun `beforeUpdate state for entity with M2M edge is typed against per-entity aggregator`() {
        val output = makeLinkM2MHookStateOutput()

        // The pendingEdges field is typed to the per-entity aggregator,
        // not the generic PendingEdgeOps<ID>.
        assert(output.contains("pendingEdges: PrivM2MPostPendingEdgeOps")) {
            "PrivM2MPostBeforeUpdateState should expose `pendingEdges: PrivM2MPostPendingEdgeOps`\n$output"
        }
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
        assert(output.contains("public class UserEdgeChangesView()")) {
            "Empty EdgeChangesView should be a no-fields class\n$output"
        }
        assert(!output.contains("public data class UserEdgeChangesView")) {
            "Empty EdgeChangesView must not be a data class\n$output"
        }
    }

    @Test
    fun `emits typed EdgeChangesView with one EdgeChanges field per helper-eligible edge`() {
        val output = makeLinkM2MOutput()

        // Data class with typed `tags: EdgeChanges<UUID>` defaulting to
        // empty.
        assert(output.contains("public data class PrivM2MPostEdgeChangesView")) {
            "Non-empty EdgeChangesView should be a data class\n$output"
        }
        assert(output.contains("public val tags: EdgeChanges<UUID>")) {
            "Should expose `tags: EdgeChanges<UUID>` for the M2M target id type\n$output"
        }
        assert(output.contains("EdgeChanges()")) {
            "EdgeChangesView constructor should default each field to an empty EdgeChanges\n$output"
        }
    }

    private fun constructorOf(output: String, className: String): String {
        val classStart = output.indexOf("data class $className")
        check(classStart >= 0) { "Missing $className in generated output" }
        val constructorEnd = output.indexOf("\n)", classStart)
        check(constructorEnd >= 0) { "Missing constructor end for $className" }
        return output.substring(classStart, constructorEnd)
    }
}

// ---------- link-table M2M helpers test schemas (PrivacyGeneratorTest) ----------

private class PrivM2MPost : EntSchema("m2m_priv_posts", clientName = "privM2MPosts") {
    override fun id() = EntId.long()
    val title by string("title")
    val tags by manyToMany<PrivM2MTag>("tags")
        .throughLink<PrivM2MPostTagJunction>(PrivM2MPostTagJunction::post, PrivM2MPostTagJunction::tag)
}
private class PrivM2MTag : EntSchema("m2m_priv_tags", clientName = "privM2MTags") {
    override fun id() = EntId.uuid()
    val name by string("name")
}
private class PrivM2MPostTagJunction : EntSchema("m2m_priv_post_tags", clientName = "privM2MPostTagJunctions") {
    override fun id() = EntId.long()
    val post by belongsTo<PrivM2MPost>("post").onDelete(entkt.schema.OnDelete.CASCADE)
    val tag by belongsTo<PrivM2MTag>("tag").onDelete(entkt.schema.OnDelete.CASCADE)
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
