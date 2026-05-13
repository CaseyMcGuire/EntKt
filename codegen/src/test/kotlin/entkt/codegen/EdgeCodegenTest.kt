package entkt.codegen

import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.OnDelete
import entkt.schema.Through
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class Owner : EntSchema("owners") {
    override fun id() = EntId.long()

    val name = string("name")

    val pets = hasMany<Pet>("pets")
}

class Pet : EntSchema("pets") {
    override fun id() = EntId.int()
    val name = string("name")

    val owner = belongsTo<Owner>("owner").inverse(Owner::pets).nullable()
}

class RequiredPet : EntSchema("required_pets") {
    override fun id() = EntId.int()
    val name = string("name")

    val owner = belongsTo<Owner>("owner")
}

// ---------- M2M test schemas ----------

class Team : EntSchema("teams") {
    override fun id() = EntId.int()
    val name = string("name")

    val members = manyToMany<Pet>("members").through<TeamMember>(TeamMember::team, TeamMember::member)
}

class TeamMember : EntSchema("team_members") {
    override fun id() = EntId.int()
    val joinedAt = time("joined_at")
    val teamId = int("team_id")
    val memberId = int("member_id")

    val team = belongsTo<Team>("team").field(teamId)
    val member = belongsTo<Pet>("member").field(memberId)
}

// ---------- Self-referential M2M test schemas ----------

class Person : EntSchema("persons") {
    override fun id() = EntId.int()
    val name = string("name")

    val friends = manyToMany<Person>("friends").through<Friendship>(Friendship::person, Friendship::friend)
}

class Friendship : EntSchema("friendships") {
    override fun id() = EntId.int()
    val createdAt = time("created_at")
    val personId = int("person_id")
    val friendId = int("friend_id")

    val person = belongsTo<Person>("person").field(personId)
    val friend = belongsTo<Person>("friend").field(friendId)
}

// ---------- Ambiguous junction test schemas ----------

class Project : EntSchema("projects") {
    override fun id() = EntId.int()
    val name = string("name")

    val assignees = manyToMany<Pet>("assignees").through<ProjectAssignment>(ProjectAssignment::project, ProjectAssignment::assignee)
}

class ProjectAssignment : EntSchema("project_assignments") {
    override fun id() = EntId.int()
    val assignedAt = time("assigned_at")
    val projectId = int("project_id")
    val assigneeId = int("assignee_id")
    val reviewerId = int("reviewer_id").nullable()

    val project = belongsTo<Project>("project").field(projectId)
    val assignee = belongsTo<Pet>("assignee").field(assigneeId)
    val reviewer = belongsTo<Pet>("reviewer").field(reviewerId)
}

// ---------- Test schemas for "ambiguous ref" test ----------

private class AmbigPostSchema : EntSchema("posts") {
    override fun id() = EntId.int()
    val title = string("title")
    val author = belongsTo<AmbigUserSchema>("author").inverse(AmbigUserSchema::posts)
    val editor = belongsTo<AmbigUserSchema>("editor").inverse(AmbigUserSchema::posts)
}

private class AmbigUserSchema : EntSchema("users") {
    override fun id() = EntId.int()
    val name = string("name")
    val posts = hasMany<AmbigPostSchema>("posts")
}

// ---------- Test schemas for self-ref M2M "same edge" tests ----------

private class SameEdgeJunctionSchema : EntSchema("friendships") {
    override fun id() = EntId.int()
    val personId = int("person_id")
    val friendId = int("friend_id")
    val person = belongsTo<SameEdgePersonSchema>("person").field(personId)
    val friend = belongsTo<SameEdgePersonSchema>("friend").field(friendId)
}

private class SameEdgePersonSchema : EntSchema("persons") {
    override fun id() = EntId.int()
    val name = string("name")
    val friends = manyToMany<SameEdgePersonSchema>("friends")
        .through<SameEdgeJunctionSchema>(SameEdgeJunctionSchema::person, SameEdgeJunctionSchema::person)
}

// ---------- Test schemas for onDelete / .field() tests ----------

private class FkParentSchema : EntSchema("parents") {
    override fun id() = EntId.long()
    val name = string("name")
}

private class FkChildCascadeSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val ownerId = long("owner_id")
    val owner = belongsTo<FkParentSchema>("owner").field(ownerId).onDelete(OnDelete.CASCADE)
}

private class FkChildUniqueSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val ownerId = long("owner_id")
    val owner = belongsTo<FkParentSchema>("owner").unique().field(ownerId)
}

private class FkChildSetNullSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val ownerId = long("owner_id") // non-nullable
    val owner = belongsTo<FkParentSchema>("owner").field(ownerId).onDelete(OnDelete.SET_NULL)
}

private class FkChildTypeMismatchParent : EntSchema("parents") {
    override fun id() = EntId.uuid()
    val name = string("name")
}

private class FkChildTypeMismatchSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val ownerId = long("owner_id") // Long field but target uses UUID id
    val owner = belongsTo<FkChildTypeMismatchParent>("owner").field(ownerId)
}

// ---------- HasMany / HasOne cardinality test schemas ----------

private class HasManyChildSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val parent = belongsTo<HasManyParentSchema>("parent").unique().inverse(HasManyParentSchema::children)
}

private class HasManyParentSchema : EntSchema("parents") {
    override fun id() = EntId.int()
    val name = string("name")
    val children = hasMany<HasManyChildSchema>("children")
}

private class HasOneChildNonUniqueSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val parent = belongsTo<HasOneParentNonUniqueSchema>("parent").inverse(HasOneParentNonUniqueSchema::child) // missing .unique()
}

private class HasOneParentNonUniqueSchema : EntSchema("parents") {
    override fun id() = EntId.int()
    val name = string("name")
    val child = hasOne<HasOneChildNonUniqueSchema>("child")
}

private class HasOneChildSchema : EntSchema("children") {
    override fun id() = EntId.int()
    val name = string("name")
    val parent = belongsTo<HasOneParentSchema>("parent").unique().inverse(HasOneParentSchema::child)
}

private class HasOneParentSchema : EntSchema("parents") {
    override fun id() = EntId.int()
    val name = string("name")
    val child = hasOne<HasOneChildSchema>("child")
}

// ---------- HasOne eager loading test schemas ----------

private class ProfileSchema : EntSchema("profiles") {
    override fun id() = EntId.int()
    val bio = string("bio")
    val owner = belongsTo<HasOneEagerParentSchema>("owner").unique().inverse(HasOneEagerParentSchema::profile)
}

private class HasOneEagerParentSchema : EntSchema("parents") {
    override fun id() = EntId.int()
    val name = string("name")
    val profile = hasOne<ProfileSchema>("profile")
}

private class ProfileSchema2 : EntSchema("profiles") {
    override fun id() = EntId.int()
    val bio = string("bio")
    val owner = belongsTo<HasOneEdgesParentSchema>("owner").unique().inverse(HasOneEdgesParentSchema::profile)
}

private class HasOneEdgesParentSchema : EntSchema("parents") {
    override fun id() = EntId.int()
    val name = string("name")
    val profile = hasOne<ProfileSchema2>("profile")
}

// ---------- Schemas for field-backed FK + default ----------

private class DefaultedFkParent : EntSchema("defaulted_parents") {
    override fun id() = EntId.long()
    val name = string("name")
}

/**
 * Required field-backed FK whose backing field carries a default.
 * Per RFC: unset → default fires; required-FK can never be assigned null.
 */
private class RequiredFkWithDefaultChild : EntSchema("required_default_children") {
    override fun id() = EntId.int()
    val name = string("name")
    val ownerId = long("owner_id").default(42L)
    val owner = belongsTo<DefaultedFkParent>("owner").field(ownerId)
}

/**
 * Nullable field-backed FK whose backing field carries a default.
 * Per RFC: untouched → default fires; explicit null → suppresses
 * default (explicit-null-wins).
 */
private class NullableFkWithDefaultChild : EntSchema("nullable_default_children") {
    override fun id() = EntId.int()
    val name = string("name")
    val ownerId = long("owner_id").nullable().default(42L)
    val owner = belongsTo<DefaultedFkParent>("owner").field(ownerId).nullable()
}

// ---------- Schema for FK comment propagation ----------

private class CommentedFkParent : EntSchema("commented_parents") {
    override fun id() = EntId.long()
}

/**
 * Backing field carries a `.comment(...)`. The generated FK property
 * KDoc on the entity / Create / Update / Mutation surfaces should
 * pick that up, matching what scalar fields with `.comment(...)` get.
 */
private class CommentedFkChild : EntSchema("commented_children") {
    override fun id() = EntId.int()
    val ownerId = long("owner_id").comment("FK to the owning user")
    val owner = belongsTo<CommentedFkParent>("owner").field(ownerId)
}

/**
 * Implicit FK whose edge carries a `.comment(...)`. Generated FK
 * properties should pick up the edge's comment as KDoc.
 */
private class ImplicitCommentedFkChild : EntSchema("implicit_commented_children") {
    override fun id() = EntId.int()
    val owner = belongsTo<CommentedFkParent>("owner").comment("Owner of this row")
}

// ---------- Schema for sensitive field-backed FK redaction ----------

private class SensitiveFkParent : EntSchema("sensitive_parents") {
    override fun id() = EntId.long()
}

/**
 * Backing field is `.sensitive()`. The generated entity `toString()`
 * must redact the FK as `***` instead of printing the value.
 */
private class SensitiveFkChild : EntSchema("sensitive_children") {
    override fun id() = EntId.int()
    val name = string("name")
    val ownerId = long("owner_id").sensitive()
    val owner = belongsTo<SensitiveFkParent>("owner").field(ownerId)
}

// ---------- Schema for field-backed FK validator propagation ----------

private class ValidatedFkParent : EntSchema("validated_parents") {
    override fun id() = EntId.long()
}

/**
 * Backing field carries a `.positive()` validator. The relationship
 * code path must invoke the same validator on both create and update.
 */
private class ValidatedFkChild : EntSchema("validated_children") {
    override fun id() = EntId.int()
    val ownerId = long("owner_id").positive()
    val owner = belongsTo<ValidatedFkParent>("owner").field(ownerId)
}

// ---------- Schema for immutable field-backed FK tests ----------

private class ImmutableFkParent : EntSchema("immutable_parents") {
    override fun id() = EntId.long()
}

/**
 * Backing field is `.immutable()`, so the relationship is also
 * immutable: writable on create, hidden from update builders and
 * hook-facing update mutation views.
 */
private class ImmutableFkChild : EntSchema("immutable_children") {
    override fun id() = EntId.int()
    val name = string("name")
    val ownerId = long("owner_id").immutable()
    val owner = belongsTo<ImmutableFkParent>("owner").field(ownerId)
}

// ---------- Schemas for field-backed nullability mismatch tests ----------

private class NullabilityMismatchParent : EntSchema("nullability_parents") {
    override fun id() = EntId.long()
}

/**
 * Required relationship backed by a nullable field. Per RFC,
 * codegen should reject this — a required edge can't be backed by a
 * nullable column.
 */
private class RequiredEdgeNullableFieldChild : EntSchema("required_edge_nullable_field") {
    override fun id() = EntId.int()
    val ownerId = long("owner_id").nullable()
    val owner = belongsTo<NullabilityMismatchParent>("owner").field(ownerId)
}

/**
 * Nullable relationship backed by a non-null field. Per RFC,
 * codegen should reject this — a nullable edge can't be backed by a
 * NOT NULL column.
 */
private class NullableEdgeNonNullFieldChild : EntSchema("nullable_edge_nonnull_field") {
    override fun id() = EntId.int()
    val ownerId = long("owner_id")
    val owner = belongsTo<NullabilityMismatchParent>("owner").field(ownerId).nullable()
}

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class EdgeCodegenTest {

    private fun createAllSchemas(): Triple<
        List<EntSchema>,
        Map<EntSchema, String>,
        Map<String, EntSchema>
    > {
        val owner = Owner()
        val pet = Pet()
        val requiredPet = RequiredPet()
        val team = Team()
        val teamMember = TeamMember()
        val person = Person()
        val friendship = Friendship()
        val project = Project()
        val projectAssignment = ProjectAssignment()

        val all: List<EntSchema> = listOf(owner, pet, requiredPet, team, teamMember, person, friendship, project, projectAssignment)
        finalize(*all.toTypedArray())

        val names: Map<EntSchema, String> = mapOf(
            owner to "Owner",
            pet to "Pet",
            requiredPet to "RequiredPet",
            team to "Team",
            teamMember to "TeamMember",
            person to "Person",
            friendship to "Friendship",
            project to "Project",
            projectAssignment to "ProjectAssignment",
        )
        val byName: Map<String, EntSchema> = names.entries.associate { (k, v) -> v to k }
        return Triple(all, names, byName)
    }

    @Test
    fun `entity gets nullable FK property for optional unique edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("val ownerId: Long?")) { "Should add nullable ownerId FK\n$output" }
    }

    @Test
    fun `entity gets non-null FK property for required unique edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()

        assert(output.contains("val ownerId: Long,") || output.contains("val ownerId: Long\n")) {
            "Should add non-null ownerId FK\n$output"
        }
    }

    @Test
    fun `create builder gets FK property without entity setter for unique edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("var ownerId: Long?")) {
            "Should have ownerId: Long? property\n$output"
        }
        assert(!output.contains("var owner: Owner?")) {
            "Must not have owner: Owner? entity setter (removed in to-one FK RFC)\n$output"
        }
        assert(!output.contains("ownerId = value?.id")) {
            "Must not synthesize an owner-setter body that writes ownerId\n$output"
        }
    }

    @Test
    fun `create builder save validates required edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()

        assert(output.contains("\"owner is required\"")) {
            "Should validate required edge in save()\n$output"
        }
    }

    @Test
    fun `update builder gets FK property without entity setter for unique edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = UpdateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("var ownerId: Long?")) {
            "Should have ownerId: Long? property\n$output"
        }
        assert(!output.contains("var owner: Owner?")) {
            "Must not have owner: Owner? entity setter (removed in to-one FK RFC)\n$output"
        }
    }

    @Test
    fun `update builder save lowers edge FK dirty tracking to FieldPatch`() {
        val (_, names, byName) = createAllSchemas()
        val output = UpdateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        // Optional edge FK: Set(this.ownerId) — Set(null) is an explicit clear.
        assert(
            output.contains(
                "ownerId = if (\"ownerId\" in dirtyFields) FieldPatch.Set(this.ownerId) else FieldPatch.Unset",
            ),
        ) {
            "Optional edge FK should lower to FieldPatch.Set(value) / Unset\n$output"
        }
        // Candidate folds effective patch over the loaded entity.
        assert(output.contains("ownerId = effectivePatch.ownerId.orElse(entity.ownerId)")) {
            "Candidate should fold effective patch over entity for edge FKs via orElse(...)\n$output"
        }
    }

    @Test
    fun `update builder edge FK setter tracks dirty state`() {
        val (_, names, byName) = createAllSchemas()
        val output = UpdateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("dirtyFields.add(\"ownerId\")")) {
            "Setting ownerId should add to dirtyFields\n$output"
        }
    }

    @Test
    fun `create builder required FK getter throws on unassigned read`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Per RFC "Resolved FK Getter Behavior": on create builders for
        // required relationships, reading the FK before assignment must
        // throw because there is no valid FK value yet. The non-null
        // public property reads from the private staging field.
        assert(
            output.contains(
                "get() = _ownerIdStaging ?: throw IllegalStateException(\"owner is required\")",
            ),
        ) {
            "Required Create FK getter must throw when staging is null\n$output"
        }
    }

    @Test
    fun `create builder nullable FK getter returns null on unassigned read`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Per RFC: on create builders for nullable relationships, reading
        // the FK before assignment returns `null`. No custom getter is
        // generated; default-null property behavior is correct.
        assert(!output.contains("get() = field ?: throw IllegalStateException(\"owner is required\")")) {
            "Nullable Create FK should not have a throw-on-unassigned getter\n$output"
        }
    }

    @Test
    fun `field-backed FK comment propagates as KDoc on entity Create Update and Mutation`() {
        val parent = CommentedFkParent()
        val child = CommentedFkChild()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "CommentedFkParent", child to "CommentedFkChild")

        val entityOutput = EntityGenerator("com.example.ent").generate("CommentedFkChild", child, names).toString()
        assert(entityOutput.contains("FK to the owning user")) {
            "Entity FK property should pick up the backing field's comment as KDoc\n$entityOutput"
        }

        val createOutput = CreateGenerator("com.example.ent").generate("CommentedFkChild", child, names).toString()
        assert(createOutput.contains("FK to the owning user")) {
            "Create builder FK property should pick up the backing field's comment as KDoc\n$createOutput"
        }

        val updateOutput = UpdateGenerator("com.example.ent").generate("CommentedFkChild", child, names).toString()
        assert(updateOutput.contains("FK to the owning user")) {
            "Update builder FK property should pick up the backing field's comment as KDoc\n$updateOutput"
        }

        val mutationOutput = MutationGenerator("com.example.ent").generate("CommentedFkChild", child, names).toString()
        assert(mutationOutput.contains("FK to the owning user")) {
            "Mutation interface FK property should pick up the backing field's comment as KDoc\n$mutationOutput"
        }
    }

    @Test
    fun `implicit FK comment propagates from edge to KDoc`() {
        val parent = CommentedFkParent()
        val child = ImplicitCommentedFkChild()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "CommentedFkParent", child to "ImplicitCommentedFkChild")

        val entityOutput = EntityGenerator("com.example.ent")
            .generate("ImplicitCommentedFkChild", child, names).toString()
        assert(entityOutput.contains("Owner of this row")) {
            "Implicit FK should pick up the edge's .comment(...) as KDoc\n$entityOutput"
        }
    }

    @Test
    fun `entity toString redacts sensitive field-backed FK values`() {
        val parent = SensitiveFkParent()
        val child = SensitiveFkChild()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "SensitiveFkParent", child to "SensitiveFkChild")
        val output = EntityGenerator("com.example.ent")
            .generate("SensitiveFkChild", child, names).toString()

        // A custom toString must be emitted (since at least one field
        // or FK is sensitive). The FK value must be `***`, not the
        // raw `${ownerId}`.
        assert(output.contains("override fun toString()")) {
            "EntityGenerator should emit a custom toString when any field/FK is sensitive\n$output"
        }
        assert(output.contains("ownerId=***")) {
            "Sensitive field-backed FK should be redacted as `ownerId=***` in toString\n$output"
        }
        assert(!output.contains("ownerId=\${ownerId}") && !output.contains("ownerId=\$ownerId")) {
            "Sensitive FK must not leak via the unredacted interpolation\n$output"
        }
    }

    @Test
    fun `create runs backing-field validators on field-backed FK value`() {
        val parent = ValidatedFkParent()
        val child = ValidatedFkChild()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "ValidatedFkParent", child to "ValidatedFkChild")
        val output = CreateGenerator("com.example.ent")
            .generate("ValidatedFkChild", child, names).toString()
            .replace("\\s+".toRegex(), " ")

        // The `.positive()` check (`if (prop <= 0) throw ...`) should
        // appear in the save body keyed off the FK property name. Phase 12
        // routes the failure through ValidationException so saveOrError
        // returns EntError.ValidationFailed; the backing column name lands
        // in the ValidationDecision.Invalid `field` slot.
        assert(output.contains("if (ownerId <= 0) throw ValidationException(\"ValidatedFkChild\",")) {
            "Create should emit a ValidationException for the .positive() FK validator\n$output"
        }
        assert(output.contains("Invalid(\"value must be positive\", field = \"owner_id\")")) {
            "Validator failure should carry the backing column name in the Invalid field slot\n$output"
        }
    }

    @Test
    fun `update runs backing-field validators on FK patch Set entries`() {
        val parent = ValidatedFkParent()
        val child = ValidatedFkChild()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "ValidatedFkParent", child to "ValidatedFkChild")
        val output = UpdateGenerator("com.example.ent")
            .generate("ValidatedFkChild", child, names).toString()
            .replace("\\s+".toRegex(), " ")

        // The validator must run inside an `if (effectivePatch.ownerId is FieldPatch.Set)`
        // block so Unset entries are not validated.
        assert(output.contains("ownerId_eff = effectivePatch.ownerId")) {
            "Update should bind the FK's effectivePatch entry to a local for validation\n$output"
        }
        assert(output.contains("if (ownerId_v <= 0) throw ValidationException(\"ValidatedFkChild\",")) {
            "Update should emit a ValidationException for the .positive() FK validator\n$output"
        }
        assert(output.contains("Invalid(\"value must be positive\", field = \"owner_id\")")) {
            "Validator failure should carry the backing column name in the Invalid field slot\n$output"
        }
    }

    @Test
    fun `immutable field-backed FK is writable on Create but absent from Update`() {
        val parent = ImmutableFkParent()
        val child = ImmutableFkChild()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "ImmutableFkParent", child to "ImmutableFkChild")

        val createOutput = CreateGenerator("com.example.ent")
            .generate("ImmutableFkChild", child, names).toString()
        // Create still exposes the FK so callers can set it on insert.
        assert(createOutput.contains("override var ownerId: Long\n")) {
            "Create should still expose ownerId as a non-null override\n$createOutput"
        }

        val updateOutput = UpdateGenerator("com.example.ent")
            .generate("ImmutableFkChild", child, names).toString()
        // Update must not expose the FK at all — no setter, no staging,
        // no patch entry, no values-map line.
        assert(!updateOutput.contains("override var ownerId")) {
            "Update must not expose an ownerId setter for an immutable FK\n$updateOutput"
        }
        assert(!updateOutput.contains("_ownerIdStaging")) {
            "Update must not declare a staging field for an immutable FK\n$updateOutput"
        }
        assert(!updateOutput.contains("dirtyFields.add(\"ownerId\")")) {
            "Update must not have a setter that marks an immutable FK dirty\n$updateOutput"
        }
        assert(!updateOutput.contains("unsetOwnerId")) {
            "UpdateMutationView adapter must not emit unsetOwnerId() for an immutable FK\n$updateOutput"
        }
        // Candidate construction still carries the FK value, sourced
        // from `entity` rather than the (absent) effective patch.
        assert(updateOutput.contains("ownerId = entity.ownerId")) {
            "Candidate should pull immutable FK directly from entity.before\n$updateOutput"
        }
    }

    @Test
    fun `immutable field-backed FK omitted from UpdatePatch and UpdateMutationView`() {
        val parent = ImmutableFkParent()
        val child = ImmutableFkChild()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "ImmutableFkParent", child to "ImmutableFkChild")

        val privacyOutput = PrivacyGenerator("com.example.ent")
            .generate("ImmutableFkChild", child, names).toString()
        // UpdatePatch must not carry a slot for the immutable FK.
        assert(!privacyOutput.contains("ownerId: FieldPatch")) {
            "UpdatePatch must not include an FK slot for immutable FKs\n$privacyOutput"
        }

        val mutationOutput = MutationGenerator("com.example.ent")
            .generate("ImmutableFkChild", child, names).toString()
        // Mutation interface must not declare immutable FKs (they're
        // create-only writable; beforeSave hooks can't reach them).
        // CreateMutationView exposes them; UpdateMutationView does not.
        assert(mutationOutput.contains("public interface ImmutableFkChildCreateMutationView")) {
            "Generator should emit CreateMutationView\n$mutationOutput"
        }
        // The unset method should not exist for an immutable FK.
        assert(!mutationOutput.contains("unsetOwnerId")) {
            "UpdateMutationView must not declare unsetOwnerId() for an immutable FK\n$mutationOutput"
        }
    }

    @Test
    fun `codegen rejects required edge backed by nullable field`() {
        val parent = NullabilityMismatchParent()
        val child = RequiredEdgeNullableFieldChild()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "Parent", child to "Child")
        val err = assertFailsWith<IllegalStateException> {
            columnMetadataFor(child, names)
        }
        assertContains(err.message!!, "is required but")
        assertContains(err.message!!, "is nullable")
    }

    @Test
    fun `codegen rejects nullable edge backed by non-null field`() {
        val parent = NullabilityMismatchParent()
        val child = NullableEdgeNonNullFieldChild()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "Parent", child to "Child")
        val err = assertFailsWith<IllegalStateException> {
            columnMetadataFor(child, names)
        }
        // Symmetric to the required+nullable case: a nullable edge
        // must have a nullable backing column (per RFC).
        assertContains(err.message!!, "is nullable but")
        assertContains(err.message!!, "is non-null")
    }

    @Test
    fun `create builder accepts beforeCreate hooks typed against CreateHookContext`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Per RFC: beforeCreate hooks receive a restricted CreateHookContext
        // (mutation view + client), not the concrete Create builder. The
        // save body constructs the context and passes it to each hook so
        // the hook can't reach `save()`, `driver`, hook lists, or the
        // private staging/assigned fields on the concrete builder.
        assert(output.contains("beforeCreateHooks: List<(PetCreateHookContext) -> Unit>")) {
            "beforeCreateHooks list should be typed against PetCreateHookContext\n$output"
        }
        assert(output.contains("val createCtx = PetCreateHookContext(client, this)")) {
            "save() should construct a CreateHookContext\n$output"
        }
        assert(output.contains("for (hook in beforeCreateHooks) hook(createCtx)")) {
            "save() should iterate beforeCreate hooks with the context\n$output"
        }
    }

    @Test
    fun `MutationGenerator emits CreateMutationView extending Mutation`() {
        val (_, names, byName) = createAllSchemas()
        val output = MutationGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("public interface PetCreateMutationView : PetMutation")) {
            "CreateMutationView should extend Mutation\n$output"
        }
    }

    @Test
    fun `create applies default to required field-backed FK when unset`() {
        val parent = DefaultedFkParent()
        val child = RequiredFkWithDefaultChild()
        finalize(parent, child)
        val names = mapOf(parent to "DefaultedFkParent", child to "RequiredFkWithDefaultChild")
        val output = CreateGenerator("com.example.ent")
            .generate("RequiredFkWithDefaultChild", child, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Save body reads staging directly so unset falls back to the
        // default instead of throwing via the public non-null getter.
        assert(output.contains("val ownerId = this._ownerIdStaging ?: 42")) {
            "Required field-backed FK should read staging-or-default in save\n$output"
        }
    }

    @Test
    fun `create applies explicit-null-wins for nullable field-backed FK with default`() {
        val parent = DefaultedFkParent()
        val child = NullableFkWithDefaultChild()
        finalize(parent, child)
        val names = mapOf(parent to "DefaultedFkParent", child to "NullableFkWithDefaultChild")
        val output = CreateGenerator("com.example.ent")
            .generate("NullableFkWithDefaultChild", child, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Generator emits an "assigned" tracking flag and a setter that
        // flips it. Save body uses the flag to distinguish untouched
        // (default fires) from explicit null (default suppressed).
        assert(output.contains("private var _ownerIdAssigned: Boolean = false")) {
            "Nullable field-backed FK with default should track an assigned flag\n$output"
        }
        assert(output.contains("_ownerIdAssigned = true")) {
            "Setter should flip the assigned flag on every write (including null)\n$output"
        }
        assert(output.contains("val ownerId = if (this._ownerIdAssigned) this.ownerId else 42")) {
            "Save body should consult the assigned flag, not value-shape\n$output"
        }
    }

    @Test
    fun `create builder applies required-FK semantics to field-backed FKs`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("TeamMember", byName["TeamMember"]!!, names).toString()

        // TeamMember has `val teamId = int("team_id"); val team =
        // belongsTo<Team>("team").field(teamId)`. Per the RFC, a
        // required field-backed FK must get the same setter/getter
        // behavior as an implicit FK: non-null public type, private
        // nullable staging, throw on unassigned read, requireNotNull
        // at setter entry. It must NOT get scalar-staging semantics.
        assert(output.contains("override var teamId: Int\n")) {
            "Field-backed required FK should be non-null typed on Create builder\n$output"
        }
        assert(output.contains("private var _teamIdStaging: Int?")) {
            "Field-backed required FK should have a private staging field\n$output"
        }
        assert(output.contains("requireNotNull(value) { \"team is required\" }")) {
            "Field-backed required FK should requireNotNull at setter entry\n$output"
        }
        assert(!output.contains("override var teamId: Int? = null\n")) {
            "Field-backed required FK must not fall back to scalar nullable staging\n$output"
        }
    }

    @Test
    fun `update builder applies required-FK semantics to field-backed FKs`() {
        val (_, names, byName) = createAllSchemas()
        val output = UpdateGenerator("com.example.ent")
            .generate("TeamMember", byName["TeamMember"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("override var teamId: Int ")) {
            "Field-backed required FK should be non-null typed on Update builder\n$output"
        }
        assert(output.contains("private var _teamIdStaging: Int?")) {
            "Field-backed required FK should have a private staging field on Update\n$output"
        }
        assert(output.contains("requireNotNull(value) { \"team is required\" }")) {
            "Field-backed required FK should requireNotNull at setter entry on Update\n$output"
        }
    }

    @Test
    fun `create save throws ValidationException for missing required FK`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        // RequiredPet has `val owner = belongsTo<Owner>("owner")` (no
        // .field(...), no default). The save body must read the
        // staging field directly so the missing-input failure is a
        // ValidationException (saveOrError → EntError.ValidationFailed),
        // not the property getter's IllegalStateException.
        assert(
            output.contains(
                "val ownerId = this._ownerIdStaging ?: throw ValidationException(\"RequiredPet\", listOf(ValidationDecision.Invalid(\"owner is required\", field = \"owner_id\")))",
            ),
        ) {
            "Required FK without default should throw ValidationException from save body\n$output"
        }
        // The property getter still throws ISE for hook/property reads
        // (early-read is a usage error, not a save-prep validation).
        assert(output.contains("get() = _ownerIdStaging ?: throw IllegalStateException(\"owner is required\")")) {
            "Required FK getter should still throw IllegalStateException for hook reads\n$output"
        }
    }

    @Test
    fun `create builder required FK property is non-null typed`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()

        // Per RFC "Public Types": required to-one edges must expose
        // non-null FK types on the generated builder. The internal
        // staging field stays nullable.
        assert(output.contains("override var ownerId: Long\n")) {
            "Required Create FK property should be non-null typed (Long, not Long?)\n$output"
        }
        assert(output.contains("private var _ownerIdStaging: Long?")) {
            "Required Create FK should have a private nullable staging field\n$output"
        }
    }

    @Test
    fun `update builder required FK property is non-null typed`() {
        val (_, names, byName) = createAllSchemas()
        val output = UpdateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()

        assert(output.contains("override var ownerId: Long\n")) {
            "Required Update FK property should be non-null typed (Long, not Long?)\n$output"
        }
        assert(output.contains("private var _ownerIdStaging: Long?")) {
            "Required Update FK should have a private nullable staging field\n$output"
        }
    }

    @Test
    fun `create builder required FK setter rejects null at entry`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Per RFC: required FK setters defensively reject Java/platform
        // null calls at setter entry. The post-hook backstop only
        // catches paths that bypass the setter entirely.
        assert(output.contains("@Suppress(\"SENSELESS_COMPARISON\")")) {
            "Required FK setter should suppress SENSELESS_COMPARISON\n$output"
        }
        assert(output.contains("requireNotNull(value) { \"owner is required\" }")) {
            "Required FK setter should requireNotNull at entry\n$output"
        }
    }

    @Test
    fun `update builder required FK setter rejects null at entry`() {
        val (_, names, byName) = createAllSchemas()
        val output = UpdateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Same defensive entry-time check on the update builder so
        // hooks/DSL writes go through the requireNotNull guard.
        assert(output.contains("requireNotNull(value) { \"owner is required\" }")) {
            "Required FK setter should requireNotNull at entry on update builder\n$output"
        }
    }

    @Test
    fun `update builder edge FK getter throws on untouched read`() {
        val (_, names, byName) = createAllSchemas()
        val output = UpdateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Reading an untouched edge FK on the update builder must throw
        // (per RFC) — a default-null getter would conflate Unset and
        // explicit Set(null) for nullable FKs. Hooks should read
        // pending state from `ctx.patch.ownerId` instead.
        assert(
            output.contains(
                "get() { if (\"ownerId\" !in dirtyFields) throw IllegalStateException(\"ownerId is not set in this update; read ctx.patch.ownerId instead\") return field }",
            ),
        ) {
            "Edge FK getter must throw when the property is not in dirtyFields\n$output"
        }
    }

    @Test
    fun `entity emits nullable column ref for optional unique edge FK`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("val ownerId: NullableComparableColumn<Long> = NullableComparableColumn<Long>(\"owner_id\")")) {
            "Should emit NullableComparableColumn<Long> for optional edge FK\n$output"
        }
    }

    @Test
    fun `entity emits non-null column ref for required unique edge FK`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()

        assert(output.contains("val ownerId: ComparableColumn<Long> = ComparableColumn<Long>(\"owner_id\")")) {
            "Should emit non-null ComparableColumn<Long> for required edge FK\n$output"
        }
    }

    // ---------- Foreign key references in generated SCHEMA ----------

    @Test
    fun `generated SCHEMA includes ForeignKeyRef for edge FK columns`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("ForeignKeyRef")) {
            "Should emit ForeignKeyRef for the owner_id FK column\n$output"
        }
        assert(output.contains("table = \"owners\"")) {
            "Should reference the owners table\n$output"
        }
        assert(output.contains("column = \"id\"")) {
            "Should reference the id column\n$output"
        }
    }

    @Test
    fun `non-FK columns do not get ForeignKeyRef`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(!output.contains("ForeignKeyRef")) {
            "Owner has no FK columns -- should not emit ForeignKeyRef\n$output"
        }
    }

    // ---------- EdgeRef emission ----------

    @Test
    fun `entity emits EdgeRef on the companion for to-many edges`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("import entkt.query.EdgeRef")) {
            "Should import EdgeRef\n$output"
        }
        assert(output.contains("val pets: EdgeRef<Pet, PetQuery> = EdgeRef(\"pets\") { PetQuery(NoopDriver) }")) {
            "Should emit EdgeRef for the pets edge wired to NoopDriver\n$output"
        }
    }

    @Test
    fun `entity emits EdgeRef on the companion for from-side unique edges`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("val owner: EdgeRef<Owner, OwnerQuery> = EdgeRef(\"owner\") { OwnerQuery(NoopDriver) }")) {
            "Should emit EdgeRef for the owner edge wired to NoopDriver\n$output"
        }
        // The FK column ref still lives next to it
        assert(output.contains("val ownerId: NullableComparableColumn<Long>")) {
            "FK column ref should coexist with the EdgeRef\n$output"
        }
    }

    // ---------- Traversal methods ----------

    @Test
    fun `query gets traversal method for paired to-many edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("fun queryPets(): PetQuery")) {
            "Should generate traversal queryPets()\n$output"
        }
        assert(output.contains("Predicate.HasEdgeWith(\"owner\", parent)")) {
            "Should reference the inverse edge name in HasEdgeWith\n$output"
        }
        assert(output.contains("Predicate.HasEdge(\"owner\")")) {
            "Should fall back to HasEdge when parent has no wheres\n$output"
        }
    }

    @Test
    fun `query gets traversal method on the from-side too`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("fun queryOwner(): OwnerQuery")) {
            "Should generate traversal queryOwner()\n$output"
        }
        assert(output.contains("Predicate.HasEdgeWith(\"pets\", parent)")) {
            "Should reference Owner's 'pets' edge as the inverse\n$output"
        }
    }

    @Test
    fun `does not emit traversal when the inverse edge cannot be resolved`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(!output.contains("queryRequiredPets")) {
            "Should not emit traversal when there's no matching back-edge\n$output"
        }
    }

    // NOTE: The old test "bad ref value fails at codegen time" has been removed
    // because the typed builder API's .inverse() now prevents bad ref values at
    // compile time. The ref field on BelongsTo edges is set by the framework from
    // the KProperty1 reference, so a bad ref is no longer possible.

    @Test
    fun `ambiguous ref aliases on target fail at codegen time`() {
        val user = AmbigUserSchema()
        val post = AmbigPostSchema()
        finalize(user, post)
        val names = mapOf<EntSchema, String>(user to "User", post to "Post")
        val error = assertFailsWith<IllegalStateException> {
            QueryGenerator("com.example.ent").generate("User", user, names)
        }
        assert(error.message!!.contains("ambiguous")) {
            "Error should mention ambiguity\n${error.message}"
        }
    }

    // ---------- M2M edge codegen ----------

    @Test
    fun `entity emits EdgeRef for M2M edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(output.contains("val members: EdgeRef<Pet, PetQuery> = EdgeRef(\"members\") { PetQuery(NoopDriver) }")) {
            "Should emit EdgeRef for M2M members edge\n$output"
        }
    }

    @Test
    fun `M2M edge does not produce FK on source entity`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(!output.contains("membersId")) {
            "M2M edge should not produce an FK property on the source\n$output"
        }
    }

    @Test
    fun `generated SCHEMA includes junction metadata for M2M edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(output.contains("junctionTable")) {
            "Should include junction metadata in SCHEMA\n$output"
        }
        assert(output.contains("\"team_members\"")) {
            "Junction table should be the TeamMember table name\n$output"
        }
    }

    @Test
    fun `target entity gets reverse M2M edge in SCHEMA`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("\"teams_members\"")) {
            "Pet SCHEMA should include reverse 'teams_members' M2M edge\n$output"
        }
        assert(output.contains("junctionTable = \"team_members\"")) {
            "Reverse edge should carry junction table metadata\n$output"
        }
    }

    @Test
    fun `query gets M2M traversal method`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(output.contains("fun queryMembers(): PetQuery")) {
            "Should generate M2M traversal queryMembers()\n$output"
        }
        assert(output.contains("Predicate.HasEdgeWith(\"teams_members\", parent)")) {
            "Should reference reverse M2M edge name\n$output"
        }
        assert(output.contains("Predicate.HasEdge(\"teams_members\")")) {
            "Should fall back to HasEdge when parent has no wheres\n$output"
        }
    }

    // ---------- Edges inner data class ----------

    @Test
    fun `entity Edges class has nullable entity for to-one edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("data class Edges")) {
            "Should generate inner Edges class\n$output"
        }
        assert(output.contains("val owner: Owner? = null")) {
            "To-one edge should produce nullable entity property\n$output"
        }
    }

    @Test
    fun `entity Edges class has nullable list for to-many edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("val pets: List<Pet>? = null")) {
            "To-many edge should produce nullable list property\n$output"
        }
    }

    @Test
    fun `entity Edges class has nullable list for M2M edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(output.contains("val members: List<Pet>? = null")) {
            "M2M edge should produce nullable list property\n$output"
        }
    }

    // ---------- Eager loading: with{Edge} methods ----------

    @Test
    fun `query generates withPets for to-many edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("fun withPets(")) {
            "Should generate withPets method\n$output"
        }
        assert(output.contains("PetQuery.() -> Unit")) {
            "withPets should accept a PetQuery DSL block\n$output"
        }
        assert(output.contains(": OwnerQuery")) {
            "withPets should return OwnerQuery for chaining\n$output"
        }
    }

    @Test
    fun `query generates withOwner for to-one edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("fun withOwner(")) {
            "Should generate withOwner method\n$output"
        }
        assert(output.contains("OwnerQuery.() -> Unit")) {
            "withOwner should accept an OwnerQuery DSL block\n$output"
        }
    }

    @Test
    fun `query generates withMembers for M2M edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(output.contains("fun withMembers(")) {
            "Should generate withMembers method\n$output"
        }
    }

    @Test
    fun `query generates loadEdges for schemas with edges`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("fun loadEdges(")) {
            "Should generate loadEdges method\n$output"
        }
    }

    @Test
    fun `all() delegates to loadEdges for schemas with edges`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("loadEdges(results, privacy)")) {
            "all() should delegate to loadEdges after privacy check\n$output"
        }
    }

    @Test
    fun `to-many eager loading queries target with IN predicate on FK column`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("Predicate.Leaf(\"owner_id\", Op.IN, sourceIds)")) {
            "Should build IN predicate on the FK column\n$output"
        }
    }

    @Test
    fun `to-one eager loading queries target by id`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("Predicate.Leaf(\"id\", Op.IN, fkValues)")) {
            "Should build IN predicate on target id column\n$output"
        }
    }

    @Test
    fun `M2M eager loading queries junction table then target`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("\"team_members\"")) {
            "Should query junction table\n$output"
        }
        assert(output.contains("Predicate.Leaf(\"team_id\", Op.IN, sourceIds)")) {
            "Should query junction with source FK\n$output"
        }
    }

    // ---------- Self-referential M2M ----------

    @Test
    fun `self-referential M2M resolves distinct source and target FKs`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Person", byName["Person"]!!, names).toString()

        assert(output.contains("junctionSourceColumn = \"person_id\"")) {
            "Source FK should be person_id\n$output"
        }
        assert(output.contains("junctionTargetColumn = \"friend_id\"")) {
            "Target FK should be friend_id (not person_id again)\n$output"
        }
    }

    @Test
    fun `self-referential M2M query uses correct junction FKs`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Person", byName["Person"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("Predicate.Leaf(\"person_id\", Op.IN, sourceIds)")) {
            "Should query junction with source FK person_id\n$output"
        }
    }

    @Test
    fun `self-referential M2M with same source and target edge fails`() {
        val person = SameEdgePersonSchema()
        val junction = SameEdgeJunctionSchema()
        finalize(person, junction)
        val names = mapOf<EntSchema, String>(person to "Person", junction to "Friendship")
        val error = runCatching {
            EntityGenerator("com.example.ent").generate("Person", person, names)
        }.exceptionOrNull()
        assert(error != null) { "Should fail when sourceEdge and targetEdge resolve to same junction edge" }
        assert(error!!.message!!.contains("same junction edge")) {
            "Error should mention same junction edge: ${error.message}"
        }
    }

    // ---------- Per-group limit/offset in eager loading ----------

    @Test
    fun `to-many eager loading applies limit per group not globally`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("subQuery.orderFields, null, null)")) {
            "Batch query should not pass limit/offset to driver\n$output"
        }
        assert(output.contains("perGroupOffset") && output.contains("perGroupLimit")) {
            "Should apply limit/offset per group\n$output"
        }
    }

    @Test
    fun `M2M eager loading applies limit per group not globally`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("subQuery.orderFields, null, null)")) {
            "Target query should not pass limit/offset to driver\n$output"
        }
        assert(output.contains("perGroupOffset") && output.contains("perGroupLimit")) {
            "Should apply limit/offset per group\n$output"
        }
    }

    // ---------- Ambiguous junction disambiguation ----------

    @Test
    fun `through with sourceEdge and targetEdge picks the correct junction FKs`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Project", byName["Project"]!!, names).toString()

        assert(output.contains("junctionSourceColumn = \"project_id\"")) {
            "Source FK should be project_id\n$output"
        }
        assert(output.contains("junctionTargetColumn = \"assignee_id\"")) {
            "Target FK should be assignee_id, not reviewer_id\n$output"
        }
    }

    @Test
    fun `ambiguous junction without hints fails fast`() {
        val (_, names, byName) = createAllSchemas()
        val pet = byName["Pet"]!!
        val projectAssignment = byName["ProjectAssignment"]!!
        val project = byName["Project"]!!
        val edge = Edge(
            name = "assignees",
            target = pet,
            kind = EdgeKind.ManyToMany(Through(projectAssignment)),
        )

        val error = assertFailsWith<IllegalStateException> {
            resolveM2MEdgeJoin(edge, project, names)
        }
        assert(error.message!!.contains("Ambiguous M2M")) {
            "Should mention ambiguous M2M: ${error.message}"
        }
        assert(error.message!!.contains("sourceEdge")) {
            "Should suggest sourceEdge/targetEdge: ${error.message}"
        }
    }

    @Test
    fun `wrong sourceEdge hint fails fast with clear error`() {
        val (_, names, byName) = createAllSchemas()
        val pet = byName["Pet"]!!
        val projectAssignment = byName["ProjectAssignment"]!!
        val project = byName["Project"]!!
        val edge = Edge(
            name = "assignees",
            target = pet,
            kind = EdgeKind.ManyToMany(Through(
                projectAssignment,
                sourceEdge = "assignee",
                targetEdge = "reviewer",
            )),
        )

        val error = assertFailsWith<IllegalStateException> {
            resolveM2MEdgeJoin(edge, project, names)
        }
        assert(error.message!!.contains("sourceEdge hint")) {
            "Should mention sourceEdge hint: ${error.message}"
        }
    }

    @Test
    fun `wrong targetEdge hint fails fast with clear error`() {
        val (_, names, byName) = createAllSchemas()
        val pet = byName["Pet"]!!
        val projectAssignment = byName["ProjectAssignment"]!!
        val project = byName["Project"]!!
        val edge = Edge(
            name = "assignees",
            target = pet,
            kind = EdgeKind.ManyToMany(Through(
                projectAssignment,
                sourceEdge = "project",
                targetEdge = "project",
            )),
        )

        val error = assertFailsWith<IllegalStateException> {
            resolveM2MEdgeJoin(edge, project, names)
        }
        assert(error.message!!.contains("targetEdge hint")) {
            "Should mention targetEdge hint: ${error.message}"
        }
    }

    // ---------- onDelete with explicit .field() ----------

    @Test
    fun `onDelete propagates through explicit field edges`() {
        val parent = FkParentSchema()
        val child = FkChildCascadeSchema()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "Parent", child to "Child")
        val columns = columnMetadataFor(child, names)
        val fkCol = columns.firstOrNull { it.name == "owner_id" }

        assertNotNull(fkCol, "Should find owner_id column")
        val refs = assertNotNull(fkCol.references, "Explicit-field edge should produce FK references")
        assertEquals("parents", refs.first, "Should reference parents table")
        assertEquals(OnDelete.CASCADE, fkCol.onDelete, "Should carry CASCADE from edge")
    }

    @Test
    fun `unique propagates through explicit field edges`() {
        val parent = FkParentSchema()
        val child = FkChildUniqueSchema()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "Parent", child to "Child")
        val columns = columnMetadataFor(child, names)
        val fkCol = columns.firstOrNull { it.name == "owner_id" }

        assertNotNull(fkCol, "Should find owner_id column")
        assertEquals(true, fkCol.unique, "Edge .unique() should propagate to column")
    }

    @Test
    fun `SET_NULL rejected on non-nullable explicit field`() {
        val parent = FkParentSchema()
        val child = FkChildSetNullSchema()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "Parent", child to "Child")
        assertFailsWith<IllegalStateException> {
            columnMetadataFor(child, names)
        }
    }

    // ---------- explicit .field() validation ----------

    // NOTE: The old test "explicit field edge rejected when field does not exist"
    // has been removed because the typed builder API now prevents this at compile
    // time — .field(handle) takes a FieldHandle from the same schema, so referencing
    // a nonexistent field is a compile error.

    @Test
    fun `explicit field edge rejected when field type mismatches target id`() {
        val parent = FkChildTypeMismatchParent()
        val child = FkChildTypeMismatchSchema()
        finalize(parent, child)
        val names = mapOf<EntSchema, String>(parent to "Parent", child to "Child")
        val ex = assertFailsWith<IllegalStateException> {
            columnMetadataFor(child, names)
        }
        assert(ex.message!!.contains("type")) {
            "Error should mention type mismatch\n${ex.message}"
        }
    }

    // ---------- HasOne / HasMany cardinality validation ----------

    @Test
    fun `hasMany rejects inverse belongsTo with unique`() {
        val parent = HasManyParentSchema()
        val child = HasManyChildSchema()
        val ex = assertFailsWith<IllegalStateException> {
            finalize(parent, child)
        }
        assert(ex.message!!.contains("hasMany")) {
            "Error should mention hasMany cardinality mismatch\n${ex.message}"
        }
    }

    @Test
    fun `hasOne edge requires inverse belongsTo to have unique`() {
        val parent = HasOneParentNonUniqueSchema()
        val child = HasOneChildNonUniqueSchema()
        val ex = assertFailsWith<IllegalStateException> {
            finalize(parent, child)
        }
        assert(ex.message!!.contains("unique")) {
            "Error should mention unique requirement\n${ex.message}"
        }
    }

    @Test
    fun `hasOne edge succeeds when inverse belongsTo has unique`() {
        val parent = HasOneParentSchema()
        val child = HasOneChildSchema()
        finalize(parent, child)
        val join = resolveEdgeJoin(parent.edges().first(), parent)
        assertNotNull(join)
        assertEquals("id", join.sourceColumn)
        assertEquals("parent_id", join.targetColumn)
    }

    // ---------- HasOne eager loading ----------

    @Test
    fun `hasOne eager loading queries target by FK not source FK`() {
        val parent = HasOneEagerParentSchema()
        val profile = ProfileSchema()
        finalize(parent, profile)
        val names = mapOf<EntSchema, String>(parent to "Parent", profile to "Profile")
        val output = QueryGenerator("com.example.ent")
            .generate("Parent", parent, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("Predicate.Leaf(\"owner_id\", Op.IN, sourceIds)")) {
            "Should query target by FK column, not source FK\n$output"
        }
        assert(output.contains("groupBy { it[\"owner_id\"] }")) {
            "Should group loaded rows by FK column\n$output"
        }
        assert(output.contains("loadedGroups[entity.id]?.firstOrNull()")) {
            "Should map source.id to grouped target, collapsing to single entity\n$output"
        }
    }

    @Test
    fun `hasOne Edges property is nullable entity not list`() {
        val parent = HasOneEdgesParentSchema()
        val profile = ProfileSchema2()
        finalize(parent, profile)
        val names = mapOf<EntSchema, String>(parent to "Parent", profile to "Profile")
        val output = EntityGenerator("com.example.ent")
            .generate("Parent", parent, names).toString()

        assert(output.contains("val profile: Profile? = null")) {
            "HasOne edge should produce nullable entity property, not a list\n$output"
        }
    }
}
