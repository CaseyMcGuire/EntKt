package entkt.codegen

import entkt.codegen.entity.EntityGenerator
import entkt.codegen.entity.PrivacyGenerator
import entkt.codegen.metadata.columnMetadataFor
import entkt.codegen.metadata.resolveEdgeJoin
import entkt.codegen.metadata.resolveM2MEdgeJoin
import entkt.codegen.mutation.CreateGenerator
import entkt.codegen.mutation.MutationGenerator
import entkt.codegen.mutation.UpdateGenerator
import entkt.codegen.query.QueryGenerator
import entkt.schema.Edge
import entkt.schema.EdgeKind
import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.ManyToManyThrough
import entkt.schema.OnDelete
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class Owner : EntSchema("owners", clientName = "owners") {
    override fun id() = EntId.long()

    val name by string("name")

    val pets by hasMany<Pet>("pets")
}

class Pet : EntSchema("pets", clientName = "pets") {
    override fun id() = EntId.int()
    val name by string("name")

    val owner by belongsTo<Owner>("owner").inverse(Owner::pets).nullable()
}

class RequiredPet : EntSchema("required_pets", clientName = "requiredPets") {
    override fun id() = EntId.int()
    val name by string("name")

    val owner by belongsTo<Owner>("owner")
}

// ---------- M2M test schemas ----------

class Team : EntSchema("teams", clientName = "teams") {
    override fun id() = EntId.int()
    val name by string("name")

    val members by manyToMany<Pet>("members").throughEntity<TeamMember>(TeamMember::team, TeamMember::member)
}

class TeamMember : EntSchema("team_members", clientName = "teamMembers") {
    override fun id() = EntId.int()
    val joinedAt by time("joined_at")
    val teamId by int("team_id")
    val memberId by int("member_id")

    val team by belongsTo<Team>("team").field(teamId)
    val member by belongsTo<Pet>("member").field(memberId)
}

// ---------- Self-referential M2M test schemas ----------

class Person : EntSchema("persons", clientName = "persons") {
    override fun id() = EntId.int()
    val name by string("name")

    val friends by manyToMany<Person>("friends").throughEntity<Friendship>(Friendship::person, Friendship::friend)
}

class Friendship : EntSchema("friendships", clientName = "friendships") {
    override fun id() = EntId.int()
    val createdAt by time("created_at")
    val personId by int("person_id")
    val friendId by int("friend_id")

    val person by belongsTo<Person>("person").field(personId)
    val friend by belongsTo<Person>("friend").field(friendId)
}

// ---------- Ambiguous junction test schemas ----------

class Project : EntSchema("projects", clientName = "projects") {
    override fun id() = EntId.int()
    val name by string("name")

    val assignees by manyToMany<Pet>("assignees").throughEntity<ProjectAssignment>(ProjectAssignment::project, ProjectAssignment::assignee)
}

class ProjectAssignment : EntSchema("project_assignments", clientName = "projectAssignments") {
    override fun id() = EntId.int()
    val assignedAt by time("assigned_at")
    val projectId by int("project_id")
    val assigneeId by int("assignee_id")
    val reviewerId by int("reviewer_id").nullable()

    val project by belongsTo<Project>("project").field(projectId)
    val assignee by belongsTo<Pet>("assignee").field(assigneeId)
    val reviewer by belongsTo<Pet>("reviewer").field(reviewerId)
}

// ---------- Test schemas for "ambiguous ref" test ----------

private class AmbigPostSchema : EntSchema("posts", clientName = "ambigPostSchemas") {
    override fun id() = EntId.int()
    val title by string("title")
    val author by belongsTo<AmbigUserSchema>("author").inverse(AmbigUserSchema::posts)
    val editor by belongsTo<AmbigUserSchema>("editor").inverse(AmbigUserSchema::posts)
}

private class AmbigUserSchema : EntSchema("users", clientName = "ambigUserSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val posts by hasMany<AmbigPostSchema>("posts")
}

// ---------- Test schemas for self-ref M2M "same edge" tests ----------

private class SameEdgeJunctionSchema : EntSchema("friendships", clientName = "sameEdgeJunctionSchemas") {
    override fun id() = EntId.int()
    val personId by int("person_id")
    val friendId by int("friend_id")
    val person by belongsTo<SameEdgePersonSchema>("person").field(personId)
    val friend by belongsTo<SameEdgePersonSchema>("friend").field(friendId)
}

private class SameEdgePersonSchema : EntSchema("persons", clientName = "sameEdgePersonSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val friends by manyToMany<SameEdgePersonSchema>("friends")
        .throughEntity<SameEdgeJunctionSchema>(SameEdgeJunctionSchema::person, SameEdgeJunctionSchema::person)
}

// ---------- Test schemas for onDelete / .field() tests ----------

private class FkParentSchema : EntSchema("parents", clientName = "fkParentSchemas") {
    override fun id() = EntId.long()
    val name by string("name")
}

private class FkChildCascadeSchema : EntSchema("children", clientName = "fkChildCascadeSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val ownerId by long("owner_id")
    val owner by belongsTo<FkParentSchema>("owner").field(ownerId).onDelete(OnDelete.CASCADE)
}

private class FkChildUniqueSchema : EntSchema("children", clientName = "fkChildUniqueSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val ownerId by long("owner_id")
    val owner by belongsTo<FkParentSchema>("owner").unique().field(ownerId)
}

private class FkChildSetNullSchema : EntSchema("children", clientName = "fkChildSetNullSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val ownerId by long("owner_id") // non-nullable
    val owner by belongsTo<FkParentSchema>("owner").field(ownerId).onDelete(OnDelete.SET_NULL)
}

private class FkChildTypeMismatchParent : EntSchema("parents", clientName = "fkChildTypeMismatchParents") {
    override fun id() = EntId.uuid()
    val name by string("name")
}

private class FkChildTypeMismatchSchema : EntSchema("children", clientName = "fkChildTypeMismatchSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val ownerId by long("owner_id") // Long field but target uses UUID id
    val owner by belongsTo<FkChildTypeMismatchParent>("owner").field(ownerId)
}

// ---------- HasMany / HasOne cardinality test schemas ----------

private class HasManyChildSchema : EntSchema("children", clientName = "hasManyChildSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val parent by belongsTo<HasManyParentSchema>("parent").unique().inverse(HasManyParentSchema::children)
}

private class HasManyParentSchema : EntSchema("parents", clientName = "hasManyParentSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val children by hasMany<HasManyChildSchema>("children")
}

private class HasOneChildNonUniqueSchema : EntSchema("children", clientName = "hasOneChildNonUniqueSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val parent by belongsTo<HasOneParentNonUniqueSchema>("parent").inverse(HasOneParentNonUniqueSchema::child) // missing .unique()
}

private class HasOneParentNonUniqueSchema : EntSchema("parents", clientName = "hasOneParentNonUniqueSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val child by hasOne<HasOneChildNonUniqueSchema>("child")
}

private class HasOneChildSchema : EntSchema("children", clientName = "hasOneChildSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val parent by belongsTo<HasOneParentSchema>("parent").unique().inverse(HasOneParentSchema::child)
}

private class HasOneParentSchema : EntSchema("parents", clientName = "hasOneParentSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val child by hasOne<HasOneChildSchema>("child")
}

// ---------- HasOne eager loading test schemas ----------

private class ProfileSchema : EntSchema("profiles", clientName = "profileSchemas") {
    override fun id() = EntId.int()
    val bio by string("bio")
    val owner by belongsTo<HasOneEagerParentSchema>("owner").unique().inverse(HasOneEagerParentSchema::profile)
}

private class HasOneEagerParentSchema : EntSchema("parents", clientName = "hasOneEagerParentSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val profile by hasOne<ProfileSchema>("profile")
}

private class ProfileSchema2 : EntSchema("profiles", clientName = "profileSchema2s") {
    override fun id() = EntId.int()
    val bio by string("bio")
    val owner by belongsTo<HasOneEdgesParentSchema>("owner").unique().inverse(HasOneEdgesParentSchema::profile)
}

private class HasOneEdgesParentSchema : EntSchema("parents", clientName = "hasOneEdgesParentSchemas") {
    override fun id() = EntId.int()
    val name by string("name")
    val profile by hasOne<ProfileSchema2>("profile")
}

// ---------- Schemas for field-backed FK + default ----------

private class DefaultedFkParent : EntSchema("defaulted_parents", clientName = "defaultedFkParents") {
    override fun id() = EntId.long()
    val name by string("name")
}

/**
 * Required field-backed FK whose backing field carries a default.
 * Unset → default fires; required-FK can never be assigned null.
 */
private class RequiredFkWithDefaultChild : EntSchema("required_default_children", clientName = "requiredFkWithDefaultChilds") {
    override fun id() = EntId.int()
    val name by string("name")
    val ownerId by long("owner_id").default(42L)
    val owner by belongsTo<DefaultedFkParent>("owner").field(ownerId)
}

/**
 * Nullable field-backed FK whose backing field carries a default.
 * Untouched → default fires; explicit null → suppresses
 * default (explicit-null-wins).
 */
private class NullableFkWithDefaultChild : EntSchema("nullable_default_children", clientName = "nullableFkWithDefaultChilds") {
    override fun id() = EntId.int()
    val name by string("name")
    val ownerId by long("owner_id").nullable().default(42L)
    val owner by belongsTo<DefaultedFkParent>("owner").field(ownerId).nullable()
}

// ---------- Schema for FK comment propagation ----------

private class CommentedFkParent : EntSchema("commented_parents", clientName = "commentedFkParents") {
    override fun id() = EntId.long()
}

/**
 * Backing field carries a `.comment(...)`. The generated FK property
 * KDoc on the entity / Create / Update / Mutation surfaces should
 * pick that up, matching what scalar fields with `.comment(...)` get.
 */
private class CommentedFkChild : EntSchema("commented_children", clientName = "commentedFkChilds") {
    override fun id() = EntId.int()
    val ownerId by long("owner_id").comment("FK to the owning user")
    val owner by belongsTo<CommentedFkParent>("owner").field(ownerId)
}

/**
 * Implicit FK whose edge carries a `.comment(...)`. Generated FK
 * properties should pick up the edge's comment as KDoc.
 */
private class ImplicitCommentedFkChild : EntSchema("implicit_commented_children", clientName = "implicitCommentedFkChilds") {
    override fun id() = EntId.int()
    val owner by belongsTo<CommentedFkParent>("owner").comment("Owner of this row")
}

// ---------- Schema for sensitive field-backed FK redaction ----------

private class SensitiveFkParent : EntSchema("sensitive_parents", clientName = "sensitiveFkParents") {
    override fun id() = EntId.long()
}

/**
 * Backing field is `.sensitive()`. The generated entity `toString()`
 * must redact the FK as `***` instead of printing the value.
 */
private class SensitiveFkChild : EntSchema("sensitive_children", clientName = "sensitiveFkChilds") {
    override fun id() = EntId.int()
    val name by string("name")
    val ownerId by long("owner_id").sensitive()
    val owner by belongsTo<SensitiveFkParent>("owner").field(ownerId)
}

// ---------- Schema for field-backed FK validator propagation ----------

private class ValidatedFkParent : EntSchema("validated_parents", clientName = "validatedFkParents") {
    override fun id() = EntId.long()
}

/**
 * Backing field carries a `.positive()` validator. The relationship
 * code path must invoke the same validator on both create and update.
 */
private class ValidatedFkChild : EntSchema("validated_children", clientName = "validatedFkChilds") {
    override fun id() = EntId.int()
    val ownerId by long("owner_id").positive()
    val owner by belongsTo<ValidatedFkParent>("owner").field(ownerId)
}

// ---------- Schema for immutable field-backed FK tests ----------

private class ImmutableFkParent : EntSchema("immutable_parents", clientName = "immutableFkParents") {
    override fun id() = EntId.long()
}

/**
 * Backing field is `.immutable()`, so the relationship is also
 * immutable: writable on create, hidden from update builders and
 * hook-facing update mutation views.
 */
private class ImmutableFkChild : EntSchema("immutable_children", clientName = "immutableFkChilds") {
    override fun id() = EntId.int()
    val name by string("name")
    val ownerId by long("owner_id").immutable()
    val owner by belongsTo<ImmutableFkParent>("owner").field(ownerId)
}

// ---------- Schemas for field-backed nullability mismatch tests ----------

private class NullabilityMismatchParent : EntSchema("nullability_parents", clientName = "nullabilityMismatchParents") {
    override fun id() = EntId.long()
}

/**
 * Required relationship backed by a nullable field.
 * codegen should reject this — a required edge can't be backed by a
 * nullable column.
 */
private class RequiredEdgeNullableFieldChild : EntSchema("required_edge_nullable_field", clientName = "requiredEdgeNullableFieldChilds") {
    override fun id() = EntId.int()
    val ownerId by long("owner_id").nullable()
    val owner by belongsTo<NullabilityMismatchParent>("owner").field(ownerId)
}

/**
 * Nullable relationship backed by a non-null field.
 * codegen should reject this — a nullable edge can't be backed by a
 * NOT NULL column.
 */
private class NullableEdgeNonNullFieldChild : EntSchema("nullable_edge_nonnull_field", clientName = "nullableEdgeNonNullFieldChilds") {
    override fun id() = EntId.int()
    val ownerId by long("owner_id")
    val owner by belongsTo<NullabilityMismatchParent>("owner").field(ownerId).nullable()
}

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

// ---------- Test schemas for M2M orientation / alias rules ----------

private class M2MUser : EntSchema("m2m_users", clientName = "m2MUsers") {
    override fun id() = EntId.long()
}
private class M2MGroup : EntSchema("m2m_groups", clientName = "m2MGroups") {
    override fun id() = EntId.long()
}
private class M2MMembership : EntSchema("m2m_memberships", clientName = "m2MMemberships") {
    override fun id() = EntId.long()
    val user by belongsTo<M2MUser>("user")
    val group by belongsTo<M2MGroup>("group")
}

// Same-orientation alias: two manyToMany declarations on the same
// schema with identical (junction, sourceEdge, targetEdge) keys.
private class M2MAliasGroup : EntSchema("alias_groups", clientName = "m2MAliasGroups") {
    override fun id() = EntId.long()
    val members by manyToMany<M2MAliasUser>("members")
        .throughEntity<M2MAliasMembership>(M2MAliasMembership::group, M2MAliasMembership::user)
    val users by manyToMany<M2MAliasUser>("users")
        .throughEntity<M2MAliasMembership>(M2MAliasMembership::group, M2MAliasMembership::user)
}
private class M2MAliasUser : EntSchema("alias_users", clientName = "m2MAliasUsers") {
    override fun id() = EntId.long()
}
private class M2MAliasMembership : EntSchema("alias_memberships", clientName = "m2MAliasMemberships") {
    override fun id() = EntId.long()
    val user by belongsTo<M2MAliasUser>("user")
    val group by belongsTo<M2MAliasGroup>("group")
}

// Cross-schema pair-swap with throughEntity is allowed (this is the
// canonical bidirectional traversal pattern).
private class BiUser : EntSchema("bi_users", clientName = "biUsers") {
    override fun id() = EntId.long()
    val groups by manyToMany<BiGroup>("groups")
        .throughEntity<BiMembership>(BiMembership::user, BiMembership::group)
}
private class BiGroup : EntSchema("bi_groups", clientName = "biGroups") {
    override fun id() = EntId.long()
    val users by manyToMany<BiUser>("users")
        .throughEntity<BiMembership>(BiMembership::group, BiMembership::user)
}
private class BiMembership : EntSchema("bi_memberships", clientName = "biMemberships") {
    override fun id() = EntId.long()
    val user by belongsTo<BiUser>("user")
    val group by belongsTo<BiGroup>("group")
}

// Self-referential pair-swap on the same schema is allowed.
private class FollowUser : EntSchema("follow_users", clientName = "followUsers") {
    override fun id() = EntId.long()
    val following by manyToMany<FollowUser>("following")
        .throughEntity<Follow>(Follow::follower, Follow::followed)
    val followers by manyToMany<FollowUser>("followers")
        .throughEntity<Follow>(Follow::followed, Follow::follower)
}
private class Follow : EntSchema("follows", clientName = "follows") {
    override fun id() = EntId.long()
    val follower by belongsTo<FollowUser>("follower")
    val followed by belongsTo<FollowUser>("followed")
}

// Cross-schema pair-swap with throughLink is accepted when the
// junction carries the unique pair index plus a
// leading-column index for each side's source FK.
private class LinkUser : EntSchema("link_users", clientName = "linkUsers") {
    override fun id() = EntId.long()
    val groups by manyToMany<LinkGroup>("groups")
        .throughLink<LinkMembership>(LinkMembership::user, LinkMembership::group)
}
private class LinkGroup : EntSchema("link_groups", clientName = "linkGroups") {
    override fun id() = EntId.long()
    val users by manyToMany<LinkUser>("users")
        .throughLink<LinkMembership>(LinkMembership::group, LinkMembership::user)
}
private class LinkMembership : EntSchema("link_memberships", clientName = "linkMemberships") {
    override fun id() = EntId.long()
    val user by belongsTo<LinkUser>("user").onDelete(OnDelete.CASCADE)
    val group by belongsTo<LinkGroup>("group").onDelete(OnDelete.CASCADE)
    val byUserGroup = index("idx_link_memberships_user_group", user.fk, group.fk).unique()
    val byGroupUser = index("idx_link_memberships_group_user", group.fk, user.fk)
}

// Two same-orientation throughLink aliases over one junction are rejected
// (two declarations must be pair-swapped, not the same direction).
private class SameOrientGroup : EntSchema("same_orient_groups", clientName = "sameOrientGroups") {
    override fun id() = EntId.long()
}
private class SameOrientMembership : EntSchema("same_orient_memberships", clientName = "sameOrientMemberships") {
    override fun id() = EntId.long()
    val user by belongsTo<SameOrientUser>("user").onDelete(OnDelete.CASCADE)
    val group by belongsTo<SameOrientGroup>("group").onDelete(OnDelete.CASCADE)
    val byUserGroup = index("idx_same_orient_user_group", user.fk, group.fk).unique()
}
private class SameOrientUser : EntSchema("same_orient_users", clientName = "sameOrientUsers") {
    override fun id() = EntId.long()
    val groups by manyToMany<SameOrientGroup>("groups")
        .throughLink<SameOrientMembership>(SameOrientMembership::user, SameOrientMembership::group)
    val groupsAlias by manyToMany<SameOrientGroup>("groups_alias")
        .throughLink<SameOrientMembership>(SameOrientMembership::user, SameOrientMembership::group)
}

// Pair-swapped throughLink whose junction is missing the OTHER side's
// leading-column index → rejected by Rule 6b. Only the unique pair index
// (leading with user_id) is present, so the group side has no group_id
// leading index.
private class NoLeadUser : EntSchema("no_lead_users", clientName = "noLeadUsers") {
    override fun id() = EntId.long()
    val groups by manyToMany<NoLeadGroup>("groups")
        .throughLink<NoLeadMembership>(NoLeadMembership::user, NoLeadMembership::group)
}
private class NoLeadGroup : EntSchema("no_lead_groups", clientName = "noLeadGroups") {
    override fun id() = EntId.long()
    val users by manyToMany<NoLeadUser>("users")
        .throughLink<NoLeadMembership>(NoLeadMembership::group, NoLeadMembership::user)
}
private class NoLeadMembership : EntSchema("no_lead_memberships", clientName = "noLeadMemberships") {
    override fun id() = EntId.long()
    val user by belongsTo<NoLeadUser>("user").onDelete(OnDelete.CASCADE)
    val group by belongsTo<NoLeadGroup>("group").onDelete(OnDelete.CASCADE)
    val byUserGroup = index("idx_no_lead_user_group", user.fk, group.fk).unique()
}

// Mixed mode (one side throughLink, the other throughEntity) over the
// same canonical identity is rejected.
private class MixedLinkUser : EntSchema("mixed_users", clientName = "mixedLinkUsers") {
    override fun id() = EntId.long()
    val groups by manyToMany<MixedLinkGroup>("groups")
        .throughLink<MixedLinkMembership>(MixedLinkMembership::user, MixedLinkMembership::group)
}
private class MixedLinkGroup : EntSchema("mixed_groups", clientName = "mixedLinkGroups") {
    override fun id() = EntId.long()
    val users by manyToMany<MixedLinkUser>("users")
        .throughEntity<MixedLinkMembership>(MixedLinkMembership::group, MixedLinkMembership::user)
}
private class MixedLinkMembership : EntSchema("mixed_memberships", clientName = "mixedLinkMemberships") {
    override fun id() = EntId.long()
    val user by belongsTo<MixedLinkUser>("user")
    val group by belongsTo<MixedLinkGroup>("group")
}

// Multi-relationship junction (distinct canonical identities) is
// allowed even though both share the junction class.
private class MultiRelProject : EntSchema("multi_rel_projects", clientName = "multiRelProjects") {
    override fun id() = EntId.long()
    val assignees by manyToMany<MultiRelPet>("assignees")
        .throughEntity<MultiRelAssignment>(MultiRelAssignment::project, MultiRelAssignment::assignee)
    val reviewers by manyToMany<MultiRelPet>("reviewers")
        .throughEntity<MultiRelAssignment>(MultiRelAssignment::project, MultiRelAssignment::reviewer)
}
private class MultiRelPet : EntSchema("multi_rel_pets", clientName = "multiRelPets") {
    override fun id() = EntId.long()
}
private class MultiRelAssignment : EntSchema("multi_rel_assignments", clientName = "multiRelAssignments") {
    override fun id() = EntId.long()
    val project by belongsTo<MultiRelProject>("project")
    val assignee by belongsTo<MultiRelPet>("assignee")
    val reviewer by belongsTo<MultiRelPet>("reviewer")
}

// ---------- Test schemas for throughLink junction-shape rules ----------

private class LinkPost : EntSchema("link_posts", clientName = "linkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<LinkTag>("tags")
        .throughLink<LinkPostTag>(LinkPostTag::post, LinkPostTag::tag)
}
private class LinkTag : EntSchema("link_tags", clientName = "linkTags") {
    override fun id() = EntId.long()
}
// Helper-eligible junction: id + 2 FK columns, both non-null, CASCADE,
// non-partial unique index on (post_id, tag_id). Used as the baseline
// for "all rules pass" tests.
private class LinkPostTag : EntSchema("link_post_tags", clientName = "linkPostTags") {
    override fun id() = EntId.long()
    val post by belongsTo<LinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag by belongsTo<LinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction with a payload column (violates rule 1).
private class PayloadLinkPost : EntSchema("payload_link_posts", clientName = "payloadLinkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<PayloadLinkTag>("tags")
        .throughLink<PayloadLinkPostTag>(PayloadLinkPostTag::post, PayloadLinkPostTag::tag)
}
private class PayloadLinkTag : EntSchema("payload_link_tags", clientName = "payloadLinkTags") {
    override fun id() = EntId.long()
}
private class PayloadLinkPostTag : EntSchema("payload_link_post_tags", clientName = "payloadLinkPostTags") {
    override fun id() = EntId.long()
    val nickname by string("nickname")
    val post by belongsTo<PayloadLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag by belongsTo<PayloadLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_payload_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction with a nullable belongsTo (violates rule 2).
private class NullableLinkPost : EntSchema("nullable_link_posts", clientName = "nullableLinkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<NullableLinkTag>("tags")
        .throughLink<NullableLinkPostTag>(NullableLinkPostTag::post, NullableLinkPostTag::tag)
}
private class NullableLinkTag : EntSchema("nullable_link_tags", clientName = "nullableLinkTags") {
    override fun id() = EntId.long()
}
private class NullableLinkPostTag : EntSchema("nullable_link_post_tags", clientName = "nullableLinkPostTags") {
    override fun id() = EntId.long()
    val post by belongsTo<NullableLinkPost>("post").onDelete(OnDelete.CASCADE).nullable()
    val tag by belongsTo<NullableLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_nullable_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction missing OnDelete.CASCADE (violates rule 4).
private class NoCascadeLinkPost : EntSchema("no_cascade_link_posts", clientName = "noCascadeLinkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<NoCascadeLinkTag>("tags")
        .throughLink<NoCascadeLinkPostTag>(NoCascadeLinkPostTag::post, NoCascadeLinkPostTag::tag)
}
private class NoCascadeLinkTag : EntSchema("no_cascade_link_tags", clientName = "noCascadeLinkTags") {
    override fun id() = EntId.long()
}
private class NoCascadeLinkPostTag : EntSchema("no_cascade_link_post_tags", clientName = "noCascadeLinkPostTags") {
    override fun id() = EntId.long()
    val post by belongsTo<NoCascadeLinkPost>("post").onDelete(OnDelete.RESTRICT)
    val tag by belongsTo<NoCascadeLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_no_cascade_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction without the required unique composite index (violates rule 6).
private class NoIndexLinkPost : EntSchema("no_index_link_posts", clientName = "noIndexLinkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<NoIndexLinkTag>("tags")
        .throughLink<NoIndexLinkPostTag>(NoIndexLinkPostTag::post, NoIndexLinkPostTag::tag)
}
private class NoIndexLinkTag : EntSchema("no_index_link_tags", clientName = "noIndexLinkTags") {
    override fun id() = EntId.long()
}
private class NoIndexLinkPostTag : EntSchema("no_index_link_post_tags", clientName = "noIndexLinkPostTags") {
    override fun id() = EntId.long()
    val post by belongsTo<NoIndexLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag by belongsTo<NoIndexLinkTag>("tag").onDelete(OnDelete.CASCADE)
}

// Junction with a partial unique index (violates rule 6).
private class PartialIdxLinkPost : EntSchema("partial_idx_link_posts", clientName = "partialIdxLinkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<PartialIdxLinkTag>("tags")
        .throughLink<PartialIdxLinkPostTag>(PartialIdxLinkPostTag::post, PartialIdxLinkPostTag::tag)
}
private class PartialIdxLinkTag : EntSchema("partial_idx_link_tags", clientName = "partialIdxLinkTags") {
    override fun id() = EntId.long()
}
private class PartialIdxLinkPostTag : EntSchema("partial_idx_link_post_tags", clientName = "partialIdxLinkPostTags") {
    override fun id() = EntId.long()
    val post by belongsTo<PartialIdxLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag by belongsTo<PartialIdxLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_partial_link_post_tags_post_tag", post.fk, tag.fk).unique().where("post_id > 0")
}

// Junction with the unique index in the wrong order (violates rule 6).
private class ReverseOrderIdxLinkPost : EntSchema("rev_idx_link_posts", clientName = "reverseOrderIdxLinkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<ReverseOrderIdxLinkTag>("tags")
        .throughLink<ReverseOrderIdxLinkPostTag>(ReverseOrderIdxLinkPostTag::post, ReverseOrderIdxLinkPostTag::tag)
}
private class ReverseOrderIdxLinkTag : EntSchema("rev_idx_link_tags", clientName = "reverseOrderIdxLinkTags") {
    override fun id() = EntId.long()
}
private class ReverseOrderIdxLinkPostTag : EntSchema("rev_idx_link_post_tags", clientName = "reverseOrderIdxLinkPostTags") {
    override fun id() = EntId.long()
    val post by belongsTo<ReverseOrderIdxLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag by belongsTo<ReverseOrderIdxLinkTag>("tag").onDelete(OnDelete.CASCADE)
    // (tag_id, post_id) instead of (post_id, tag_id).
    val pair = index("idx_rev_link_post_tags_tag_post", tag.fk, post.fk).unique()
}

// Junction with a field-backed FK that carries a validator (violates rule 3).
private class ValidatorBackingLinkPost : EntSchema("val_link_posts", clientName = "validatorBackingLinkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<ValidatorBackingLinkTag>("tags")
        .throughLink<ValidatorBackingLinkPostTag>(ValidatorBackingLinkPostTag::post, ValidatorBackingLinkPostTag::tag)
}
private class ValidatorBackingLinkTag : EntSchema("val_link_tags", clientName = "validatorBackingLinkTags") {
    override fun id() = EntId.long()
}
private class ValidatorBackingLinkPostTag : EntSchema("val_link_post_tags", clientName = "validatorBackingLinkPostTags") {
    override fun id() = EntId.long()
    val postIdCol by long("post_id_col").positive()
    val tagIdCol by long("tag_id_col")
    val post by belongsTo<ValidatorBackingLinkPost>("post").field(postIdCol).onDelete(OnDelete.CASCADE)
    val tag by belongsTo<ValidatorBackingLinkTag>("tag").field(tagIdCol).onDelete(OnDelete.CASCADE)
    val pair = index("idx_val_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction with a third belongsTo beyond sourceEdge/targetEdge
// (violates rule 1a — adds an FK column the helpers would not populate).
// Mirrors a multi-tenant link table where the schema author tried to
// attach a `tenant` edge to the junction.
private class TenantLink : EntSchema("tenant_link_orgs", clientName = "tenantLinks") {
    override fun id() = EntId.long()
}
private class TenantedLinkPost : EntSchema("tenanted_link_posts", clientName = "tenantedLinkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<TenantedLinkTag>("tags")
        .throughLink<TenantedLinkPostTag>(TenantedLinkPostTag::post, TenantedLinkPostTag::tag)
}
private class TenantedLinkTag : EntSchema("tenanted_link_tags", clientName = "tenantedLinkTags") {
    override fun id() = EntId.long()
}
private class TenantedLinkPostTag : EntSchema("tenanted_link_post_tags", clientName = "tenantedLinkPostTags") {
    override fun id() = EntId.long()
    val post by belongsTo<TenantedLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag by belongsTo<TenantedLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val tenant by belongsTo<TenantLink>("tenant").onDelete(OnDelete.CASCADE)
    val pair = index("idx_tenanted_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Same shape but with `.field(handle)` on the extra belongsTo, to prove
// the check catches the field-backed case where `scalarFields()`
// silently filters out the third FK's backing column.
private class FieldBackedTenantedLinkPost : EntSchema("fb_tenanted_link_posts", clientName = "fieldBackedTenantedLinkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<FieldBackedTenantedLinkTag>("tags")
        .throughLink<FieldBackedTenantedLinkPostTag>(
            FieldBackedTenantedLinkPostTag::post, FieldBackedTenantedLinkPostTag::tag,
        )
}
private class FieldBackedTenantedLinkTag : EntSchema("fb_tenanted_link_tags", clientName = "fieldBackedTenantedLinkTags") {
    override fun id() = EntId.long()
}
private class FieldBackedTenantedLinkPostTag : EntSchema("fb_tenanted_link_post_tags", clientName = "fieldBackedTenantedLinkPostTags") {
    override fun id() = EntId.long()
    val postIdCol by long("post_id_col")
    val tagIdCol by long("tag_id_col")
    val tenantIdCol by long("tenant_id_col")
    val post by belongsTo<FieldBackedTenantedLinkPost>("post")
        .field(postIdCol).onDelete(OnDelete.CASCADE)
    val tag by belongsTo<FieldBackedTenantedLinkTag>("tag")
        .field(tagIdCol).onDelete(OnDelete.CASCADE)
    val tenant by belongsTo<TenantLink>("tenant")
        .field(tenantIdCol).onDelete(OnDelete.CASCADE)
    val pair = index("idx_fb_tenanted_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction whose source belongsTo is `.unique()` (violates rule 4a).
private class UniqueEdgeLinkPost : EntSchema("uniq_edge_link_posts", clientName = "uniqueEdgeLinkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<UniqueEdgeLinkTag>("tags")
        .throughLink<UniqueEdgeLinkPostTag>(UniqueEdgeLinkPostTag::post, UniqueEdgeLinkPostTag::tag)
}
private class UniqueEdgeLinkTag : EntSchema("uniq_edge_link_tags", clientName = "uniqueEdgeLinkTags") {
    override fun id() = EntId.long()
}
private class UniqueEdgeLinkPostTag : EntSchema("uniq_edge_link_post_tags", clientName = "uniqueEdgeLinkPostTags") {
    override fun id() = EntId.long()
    val post by belongsTo<UniqueEdgeLinkPost>("post").unique().onDelete(OnDelete.CASCADE)
    val tag by belongsTo<UniqueEdgeLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_uniq_edge_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction with an extra unique single-column index on a junction FK
// (violates rule 6a). The required composite is also present so we
// exercise the new rule, not Rule 6's missing-composite path.
private class UniqueSingleIdxLinkPost : EntSchema("uniq_single_idx_link_posts", clientName = "uniqueSingleIdxLinkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<UniqueSingleIdxLinkTag>("tags")
        .throughLink<UniqueSingleIdxLinkPostTag>(UniqueSingleIdxLinkPostTag::post, UniqueSingleIdxLinkPostTag::tag)
}
private class UniqueSingleIdxLinkTag : EntSchema("uniq_single_idx_link_tags", clientName = "uniqueSingleIdxLinkTags") {
    override fun id() = EntId.long()
}
private class UniqueSingleIdxLinkPostTag : EntSchema("uniq_single_idx_link_post_tags", clientName = "uniqueSingleIdxLinkPostTags") {
    override fun id() = EntId.long()
    val post by belongsTo<UniqueSingleIdxLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag by belongsTo<UniqueSingleIdxLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_uniq_single_idx_link_post_tags_post_tag", post.fk, tag.fk).unique()
    val byPost = index("idx_uniq_single_idx_link_post_tags_post", post.fk).unique()
}

// Junction with EXPLICIT id strategy (violates rule 5).
private class ExplicitIdLinkPost : EntSchema("explicit_id_link_posts", clientName = "explicitIdLinkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<ExplicitIdLinkTag>("tags")
        .throughLink<ExplicitIdLinkPostTag>(ExplicitIdLinkPostTag::post, ExplicitIdLinkPostTag::tag)
}
private class ExplicitIdLinkTag : EntSchema("explicit_id_link_tags", clientName = "explicitIdLinkTags") {
    override fun id() = EntId.long()
}
private class ExplicitIdLinkPostTag : EntSchema("explicit_id_link_post_tags", clientName = "explicitIdLinkPostTags") {
    override fun id() = EntId.string() // explicit caller-provided id
    val post by belongsTo<ExplicitIdLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag by belongsTo<ExplicitIdLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_explicit_id_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

class EdgeCodegenTest {

    private fun createResolution(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String>,
    ): String = CreateGenerator("com.example.ent")
        .buildResolveFunction(schemaName, schema, schemaNames)
        .toString()

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
    fun `create draft gets FK property without entity setter for unique edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("var ownerId: Long?")) {
            "Should have ownerId: Long? property\n$output"
        }
        assert(!output.contains("var owner: Owner?")) {
            "Must not have owner: Owner? entity setter (removed in to-one FK behavior)\n$output"
        }
        assert(!output.contains("ownerId = value?.id")) {
            "Must not synthesize an owner-setter body that writes ownerId\n$output"
        }
    }

    @Test
    fun `create resolution validates required edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = createResolution("RequiredPet", byName["RequiredPet"]!!, names)

        assert(output.contains("\"ownerId is required\"")) {
            "Should validate the required edge while resolving the draft\n$output"
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
            "Must not have owner: Owner? entity setter (removed in to-one FK behavior)\n$output"
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
    fun `create draft required FK getter returns null until assigned`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("var ownerId: Long? = null")) {
            "Required create-draft FKs remain nullable while the draft is incomplete\n$output"
        }
        assert(!output.contains("throw IllegalStateException")) { output }
    }

    @Test
    fun `create draft nullable FK getter returns null on unassigned read`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        // On create drafts for nullable relationships, reading
        // the FK before assignment returns `null`. No custom getter is
        // generated; default-null property behavior is correct.
        assert(!output.contains("get() = field ?: throw IllegalStateException(\"ownerId is required\")")) {
            "Nullable Create FK should not have a throw-on-unassigned getter\n$output"
        }
    }

    @Test
    fun `every generated FK property carries the baseline relationship-write KDoc`() {
        //  "Generated resolved FK properties must include KDoc
        // explaining the relationship-write semantics. FK properties
        // write only target ids, do not load the target row, and do not
        // evaluate target LOAD privacy."
        val (_, names, byName) = createAllSchemas()
        val baseline = "id-only surface: target rows are not auto-loaded"

        val entityOutput = EntityGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()
        assert(entityOutput.contains(baseline)) {
            "Entity FK property should carry the baseline KDoc\n$entityOutput"
        }

        val createOutput = CreateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()
        assert(createOutput.contains(baseline)) {
            "Create builder FK property should carry the baseline KDoc\n$createOutput"
        }

        val updateOutput = UpdateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()
        assert(updateOutput.contains(baseline)) {
            "Update builder FK property should carry the baseline KDoc\n$updateOutput"
        }

        val mutationOutput = MutationGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()
        assert(mutationOutput.contains(baseline)) {
            "Mutation interface FK property should carry the baseline KDoc\n$mutationOutput"
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
        val output = createResolution("ValidatedFkChild", child, names)
            .replace("\\s+".toRegex(), " ")

        // The `.positive()` check should appear in create preparation keyed
        // off the FK property name. The shared evaluator turns the invalid
        // preparation into the typed mutation failure.
        assert(
            output.contains(
                "if (_entktValueOwnerId <= 0) return entkt.runtime.mutation.CreatePreparation.Invalid",
            ),
        ) {
            "Create should return a validation Failed for the .positive() FK validator\n$output"
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

        // The validator must run inside an `if (ownerId_eff is FieldPatch.Set)`
        // block so Unset entries are not validated.
        assert(output.contains("ownerId_eff = effectivePatch.ownerId")) {
            "Update should bind the FK's effectivePatch entry to a local for validation\n$output"
        }
        assert(
            output.contains(
                "if (ownerId_eff is FieldPatch.Set) { val ownerId_v = ownerId_eff.value " +
                    "if (ownerId_v <= 0) return _validationFailed(listOf(ValidationViolation(\"value must be positive\", field = \"ownerId\"))) }",
            ),
        ) {
            "Update should return a validation Failed for Set entries failing the .positive() FK validator\n$output"
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
        assert(createOutput.contains("var ownerId: Long? = null")) {
            "Create should still expose the immutable FK on its draft\n$createOutput"
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
        // must have a nullable backing column (by contract).
        assertContains(err.message!!, "is nullable but")
        assertContains(err.message!!, "is non-null")
    }

    @Test
    fun `create specification adapts drafts to beforeCreate hook contexts`() {
        val (_, names, byName) = createAllSchemas()
        val output = entkt.codegen.client.RepoGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        // create-hook adapter: the CreateHookContext now wraps the private
        // mutation-view adapter, not the concrete draft. This matches the runtime-enforced contract
        // the update path has had since transaction locking and link-table M2M helpers — a hook
        // attempting `ctx.mutation as PetCreateDraft` throws.
        assert(output.contains("private fun beforeCreateHookValue(draft: PetCreateDraft): PetCreateHookContext")) {
            "the repo should construct the typed hook context for its create specification\n$output"
        }
        assert(output.contains("private val createSpec: CreateMutationSpec<PetCreateDraft, PetMutation, PetCreateHookContext,")) {
            "the repo should pass a distinct immutable specification to MutationEvaluator\n$output"
        }
        assert(!output.contains(": PetReadSurface, CreateMutationSpec"))
        assert(!output.contains("CreateMutationDelegate")) {
            "lifecycle execution should use the runtime specification\n$output"
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
        val output = createResolution("RequiredFkWithDefaultChild", child, names)
            .replace("\\s+".toRegex(), " ")

        // Save body reads staging directly so unset falls back to the
        // default instead of throwing via the public non-null getter.
        assert(output.contains("if (isSet(com.example.ent.RequiredFkWithDefaultChild.ownerId)) this.ownerId")) {
            "Required field-backed FK should distinguish explicit assignment from omission\n$output"
        }
        assert(output.contains("else 42")) { output }
    }

    @Test
    fun `create applies explicit-null-wins for nullable field-backed FK with default`() {
        val parent = DefaultedFkParent()
        val child = NullableFkWithDefaultChild()
        finalize(parent, child)
        val names = mapOf(parent to "DefaultedFkParent", child to "NullableFkWithDefaultChild")
        val draftOutput = CreateGenerator("com.example.ent")
            .generate("NullableFkWithDefaultChild", child, names).toString()
            .replace("\\s+".toRegex(), " ")
        val output = createResolution("NullableFkWithDefaultChild", child, names)
            .replace("\\s+".toRegex(), " ")

        // The draft records assignment through the canonical column token.
        // Resolution uses that state to distinguish untouched (default fires)
        // from explicit null (default suppressed).
        assert(draftOutput.contains("assignedFields.mark(NullableFkWithDefaultChild.ownerId)")) {
            "The canonical column token should record every write, including null\n$draftOutput"
        }
        assert(output.contains("if (isSet(com.example.ent.NullableFkWithDefaultChild.ownerId)) this.ownerId else 42")) {
            "Resolution should consult assignment state, not value shape\n$output"
        }
    }

    @Test
    fun `create draft applies required-FK semantics to field-backed FKs`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("TeamMember", byName["TeamMember"]!!, names).toString()

        // TeamMember has `val teamId = int("team_id"); val team =
        // belongsTo<Team>("team").field(teamId)`. A
        assert(output.contains("var teamId: Int? = null")) {
            "Required field-backed FKs should use the same incomplete draft shape\n$output"
        }
        assert(output.contains("assignedFields.mark(TeamMember.teamId)")) { output }
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
        assert(output.contains("requireNotNull(value) { \"teamId is required\" }")) {
            "Field-backed required FK should requireNotNull at setter entry on Update\n$output"
        }
    }

    @Test
    fun `create save returns validation Failed for missing required FK`() {
        val (_, names, byName) = createAllSchemas()
        val output = createResolution("RequiredPet", byName["RequiredPet"]!!, names)
            .replace("\\s+".toRegex(), " ")

        // RequiredPet has `val owner = belongsTo<Owner>("owner")` (no
        // .field(...), no default). The save body must read the
        // staging field directly so the missing-input failure is a
        // typed invalid preparation, not the property getter's
        // IllegalStateException. The runtime evaluator maps it to the
        // mutation result.
        assert(
            output.contains(
                "val _entktValueOwnerId = this.ownerId ?: return entkt.runtime.mutation.CreatePreparation.Invalid",
            ),
        ) {
            "Required FK without default should return a validation Failed from save body\n$output"
        }
    }

    @Test
    fun `create draft required FK property is nullable while incomplete`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()

        assert(output.contains("var ownerId: Long? = null")) {
            "Required create-draft FK should be nullable until resolution\n$output"
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
    fun `create draft required FK setter records explicit null`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("assignedFields.mark(RequiredPet.ownerId)")) {
            "Required FK assignment should preserve explicit-null state for resolution\n$output"
        }
        assert(!output.contains("requireNotNull(value)")) { output }
    }

    @Test
    fun `update builder required FK setter rejects null at entry`() {
        val (_, names, byName) = createAllSchemas()
        val output = UpdateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        // Same defensive entry-time check on the update builder so
        // hooks/DSL writes go through the requireNotNull guard.
        assert(output.contains("requireNotNull(value) { \"ownerId is required\" }")) {
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
        // (by contract) — a default-null getter would conflate Unset and
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

        assert(output.contains("val ownerId: NullableIntegralColumn<Pet, Long>")) {
            "Should declare ownerId as NullableIntegralColumn<Pet, Long>\n$output"
        }
        assert(output.contains("NullableIntegralColumn<Pet, Long>(\"owner_id\")")) {
            "Should emit NullableIntegralColumn<Pet, Long>(\"owner_id\") initializer\n$output"
        }
    }

    @Test
    fun `entity emits non-null column ref for required unique edge FK`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()

        assert(output.contains("val ownerId: IntegralColumn<RequiredPet, Long>")) {
            "Should declare ownerId as IntegralColumn<RequiredPet, Long>\n$output"
        }
        assert(output.contains("IntegralColumn<RequiredPet, Long>(\"owner_id\")")) {
            "Should emit IntegralColumn<RequiredPet, Long>(\"owner_id\") initializer\n$output"
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
        assert(output.contains("val pets: EdgeRef<Owner, Pet, PetQuery> = EdgeRef(\"pets\") { PetQuery(NoopDriver) }")) {
            "Should emit EdgeRef<Owner, Pet, PetQuery> for the pets edge\n$output"
        }
    }

    @Test
    fun `entity emits EdgeRef on the companion for from-side unique edges`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("val owner: EdgeRef<Pet, Owner, OwnerQuery> = EdgeRef(\"owner\") { OwnerQuery(NoopDriver) }")) {
            "Should emit EdgeRef<Pet, Owner, OwnerQuery> for the owner edge\n$output"
        }
        // The FK column ref still lives next to it
        assert(output.contains("val ownerId: NullableIntegralColumn<Pet, Long>")) {
            "FK column ref should coexist with the EdgeRef\n$output"
        }
    }

    // ---------- Traversal methods ----------

    @Test
    fun `query gets traversal method for paired to-many edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        // Traversal methods take the same defaulted receiver block as
        // repository / index `query { ... }` helpers.
        assert(output.contains("fun queryPets(block: PetQuery.() -> Unit = {}): PetQuery")) {
            "Should generate traversal queryPets(block: PetQuery.() -> Unit = {})\n$output"
        }
        assert(output.contains("val source = captureEntityQuery()")) {
            "Traversal should capture the complete immutable source query\n$output"
        }
        assert(
            output.contains(
                "target.setEntityQuerySource(QuerySource.Traversal(source, GeneratedPetsEdgeMapping))",
            ),
        ) {
            "Traversal should pass its typed edge and source tree to runtime\n$output"
        }
        assert(!output.contains("TraversalSourceShape(")) {
            "runtime preparation should lower the recursive source shape\n$output"
        }
    }

    @Test
    fun `query gets traversal method on the from-side too`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("fun queryOwner(block: OwnerQuery.() -> Unit = {}): OwnerQuery")) {
            "Should generate traversal queryOwner(block: OwnerQuery.() -> Unit = {})\n$output"
        }
        assert(
            output.contains(
                "target.setEntityQuerySource(QuerySource.Traversal(source, GeneratedOwnerEdgeMapping))",
            ),
        ) {
            "from-side traversal should pass its typed recursive source to runtime\n$output"
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

        assert(output.contains("val members: EdgeRef<Team, Pet, PetQuery> = EdgeRef(\"members\") { PetQuery(NoopDriver) }")) {
            "Should emit EdgeRef<Team, Pet, PetQuery> for M2M members edge\n$output"
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
    fun `M2M target schema does not synthesize reverse edge metadata`() {
        // M2M schema modeling (post-revert): no auto-synthesized reverse-edge entries
        // on the target's SCHEMA.edges. Bidirectional traversal needs an
        // explicit declaration on the opposite schema.
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(!output.contains("\"teams_members\"")) {
            "Pet SCHEMA should not contain a synthesized reverse 'teams_members' entry\n$output"
        }
    }

    @Test
    fun `query M2M traversal lowers to HasM2MEdgeFromShape against source schema`() {
        // queryMembers() on TeamQuery walks back through the junction
        // using the source-side forward-edge metadata, via a
        // HasM2MEdgeFromShape predicate embedding the source shape
        // and naming the forward edge ("members"). No reverse-edge
        // name on Pet's SCHEMA is referenced.
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(output.contains("fun queryMembers(block: PetQuery.() -> Unit = {}): PetQuery")) {
            "Should generate M2M traversal queryMembers(block: PetQuery.() -> Unit = {})\n$output"
        }
        assert(
            output.contains(
                "target.setEntityQuerySource(QuerySource.Traversal(source, GeneratedMembersEdgeMapping))",
            ),
        ) {
            "M2M traversal should preserve its typed source relationship for runtime lowering\n$output"
        }
        assert(!output.contains("HasM2MEdgeFromShape")) {
            "generated traversal methods should not own M2M lowering\n$output"
        }
    }

    // ---------- Edges inner data class ----------

    @Test
    fun `entity Edges class has EdgeState nullable entity for to-one edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()

        assert(output.contains("data class Edges")) {
            "Should generate inner Edges class\n$output"
        }
        assert(output.contains("val owner: EdgeState<Owner?> = EdgeState.Unloaded")) {
            "To-one edge should produce an EdgeState property over a nullable target\n$output"
        }
    }

    @Test
    fun `entity Edges class has EdgeState list for to-many edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("val pets: EdgeState<List<Pet>> = EdgeState.Unloaded")) {
            "To-many edge should produce an EdgeState list property\n$output"
        }
    }

    @Test
    fun `entity Edges class has EdgeState list for M2M edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString()

        assert(output.contains("val members: EdgeState<List<Pet>> = EdgeState.Unloaded")) {
            "M2M edge should produce an EdgeState list property\n$output"
        }
    }

    @Test
    fun `entity Edges class is EdgeState of nullable target even for a required FK belongsTo`() {
        val (_, names, byName) = createAllSchemas()
        val output = EntityGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()

        // A required FK guarantees the row names a target, not that the
        // eager subquery returns it — the Loaded value stays nullable.
        assert(output.contains("val owner: EdgeState<Owner?> = EdgeState.Unloaded")) {
            "Required-FK belongsTo should still produce EdgeState<Owner?>\n$output"
        }
    }

    // ---------- Edge loading: load{Edge} methods ----------

    @Test
    fun `query generates loadPets returning the EdgeLoad handle for to-many edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString().replace("\\s+".toRegex(), " ")

        // load{Edge} returns an EdgeLoad<ParentQuery> handle whose
        // filterVisible() flips the per-edge filter flag and hands the
        // parent query back for chaining. Selecting an edge twice is
        // rejected up front, so the handle always governs the edge's
        // only configuration — no stale-handle state exists. The slot
        // is reserved before the block runs (re-entrant calls hit the
        // duplicate guard, never last-write-wins) and rolled back if
        // the block fails. Executions consume an immutable capture, so
        // later builder changes naturally affect only later calls.
        assert(
            output.contains(
                "public fun loadPets(block: PetQuery.() -> Unit = {}): EdgeLoad<OwnerQuery> " +
                    "{ if (eagerPets != null) { throw EntQueryConfigurationException( \"Owner\", " +
                    "\"Owner.pets is already selected on this OwnerQuery: loadPets() may be called " +
                    "at most once per query; compose all configuration for the edge in a single " +
                    "loadPets block\", ) } " +
                    "val configured = PetQuery(driver, client) " +
                    "eagerPets = configured " +
                    "try { configured.apply(block) } catch (e: Throwable) { eagerPets = null throw e } " +
                    "return object : EdgeLoad<OwnerQuery> { override fun filterVisible(): OwnerQuery " +
                    "{ eagerPetsFilterVisible = true return this@OwnerQuery } } }",
            ),
        ) {
            "loadPets should reject duplicate selection with rollback and return an EdgeLoad<OwnerQuery> handle\n$output"
        }
        // Java callers get a real zero-arg overload rather than
        // Kotlin's default-argument marker.
        assert(output.contains("@JvmOverloads public fun loadPets(")) {
            "loadPets should carry @JvmOverloads for the zero-block Java overload\n$output"
        }
    }

    @Test
    fun `query generates loadOwner for to-one edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("fun loadOwner(block: OwnerQuery.() -> Unit = {}): EdgeLoad<PetQuery>")) {
            "loadOwner should accept an OwnerQuery DSL block and return EdgeLoad<PetQuery>\n$output"
        }
    }

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `to-one eager resolution omits the redundant safe-call for a required FK`() {
        val (_, names, byName) = createAllSchemas()

        // Pet.owner is nullable → ownerId is Long?, so the safe-call is needed.
        val petOut = QueryGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()
        assert(petOut.contains("entity.ownerId?.let { targetMap[it] }")) {
            "Nullable FK should keep the safe-call\n$petOut"
        }

        // RequiredPet.owner is required → ownerId is Long (non-null), so the
        // safe-call would be a redundant-warning; resolve via a plain lookup.
        val reqOut = QueryGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()
        assert(reqOut.contains("targetMap[entity.ownerId]")) {
            "Required FK should use a plain map lookup (no redundant ?.)\n$reqOut"
        }
        assert(!reqOut.contains("entity.ownerId?.let")) {
            "Required FK should not emit a redundant safe-call\n$reqOut"
        }
    }

    @Test
    fun `query generates loadMembers for M2M edge`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("fun loadMembers(block: PetQuery.() -> Unit = {}): EdgeLoad<TeamQuery>")) {
            "loadMembers should return the EdgeLoad<TeamQuery> handle\n$output"
        }
    }

    @Test
    fun `generated query emits no public with-prefixed edge methods`() {
        val (_, names, byName) = createAllSchemas()
        for (schemaName in listOf("Owner", "Pet", "Team")) {
            val output = QueryGenerator("com.example.ent")
                .generate(schemaName, byName[schemaName]!!, names).toString()
            // The atomic cutover leaves no with{Name} spelling behind:
            // the declaration-derived family is load{Name} only.
            assert(!Regex("fun with[A-Z]").containsMatchIn(output)) {
                "$schemaName query should not emit any with{Name} method\n$output"
            }
            assert(!output.contains("EagerLoad")) {
                "$schemaName query should reference EdgeLoad, not EagerLoad\n$output"
            }
        }
    }

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `query generates loadEdges for schemas with edges`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("fun loadEdges(")) {
            "Should generate loadEdges method\n$output"
        }
    }

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `selected edge adapter delegates graph completion to loadEdges`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString()

        assert(output.contains("query.loadEdges(entities, privacyContext)")) {
            "the selected-edge adapter should delegate graph completion after root privacy\n$output"
        }
    }

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `to-many eager loading queries target with IN predicate on FK column`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString().replace("\\s+".toRegex(), " ")

        // Owner eager-loads pets via the IN predicate on target (Pet)
        // FK column. Eager-load Leaf is target-scoped: Predicate.Leaf<Pet>.
        assert(output.contains("Predicate.Leaf<Pet>(\"owner_id\", Op.IN, sourceIds)")) {
            "Should build IN predicate on the FK column scoped to target\n$output"
        }
    }

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `to-one eager loading queries target by id`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString().replace("\\s+".toRegex(), " ")

        // Pet eager-loads owner (Owner) by id: Predicate.Leaf<Owner>.
        assert(output.contains("Predicate.Leaf<Owner>(\"id\", Op.IN, fkValues)")) {
            "Should build IN predicate on target id column scoped to target\n$output"
        }
    }

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `M2M eager loading queries junction table then target`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("\"team_members\"")) {
            "Should query junction table\n$output"
        }
        // The junction discovery pass runs the JUNCTION entity's read
        // interceptors with EAGER_JUNCTION and its structural
        // source-FK IN, typed to the junction entity.
        assert(output.contains("Predicate.Leaf<TeamMember>(\"team_id\", Op.IN, sourceIds)")) {
            "Should predicate the junction pass on the source FK, typed to the junction entity\n$output"
        }
        assert(output.contains("runReadInterceptors(ReadOperation.EAGER_JUNCTION")) {
            "Should run the junction entity's interceptors before the junction read\n$output"
        }
    }

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `every eager assignment path wraps its result in EdgeState Loaded`() {
        val (_, names, byName) = createAllSchemas()

        // to-many (Owner.pets): empty groups collapse to
        // Loaded(emptyList()), never Unloaded.
        val ownerOut = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString().replace("\\s+".toRegex(), " ")
        assert(ownerOut.contains("EdgeState.Loaded(loadedGroups[entity.id] ?: emptyList())")) {
            "to-many eager assignment should wrap in EdgeState.Loaded\n$ownerOut"
        }

        // belongsTo with a nullable FK (Pet.owner): safe-call lookup inside Loaded.
        val petOut = QueryGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString().replace("\\s+".toRegex(), " ")
        assert(petOut.contains("EdgeState.Loaded(entity.ownerId?.let { targetMap[it] })")) {
            "nullable-FK belongsTo eager assignment should wrap in EdgeState.Loaded\n$petOut"
        }

        // belongsTo with a required FK (RequiredPet.owner): plain lookup
        // inside Loaded — still nullable, the target can be filtered out.
        val reqOut = QueryGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString().replace("\\s+".toRegex(), " ")
        assert(reqOut.contains("EdgeState.Loaded(targetMap[entity.ownerId])")) {
            "required-FK belongsTo eager assignment should wrap in EdgeState.Loaded\n$reqOut"
        }

        // M2M (Team.members): same grouped shape as to-many.
        // (The hasOne path is pinned in `hasOne eager loading queries
        // target by FK not source FK`.)
        val teamOut = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString().replace("\\s+".toRegex(), " ")
        assert(teamOut.contains("EdgeState.Loaded(loadedGroups[entity.id] ?: emptyList())")) {
            "M2M eager assignment should wrap in EdgeState.Loaded\n$teamOut"
        }
    }

    @Test
    fun `load-edge builder configuration stays a private nullable query field`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString().replace("\\s+".toRegex(), " ")

        // EdgeState wraps returned entity edges only — the builder's
        // load{Edge} bookkeeping keeps its private nullable field.
        assert(output.contains("private var eagerPets: PetQuery? = null")) {
            "load{Edge} config should stay a private nullable builder field\n$output"
        }
        // The EdgeLoad handle's filterVisible() state is a private
        // per-edge flag alongside it.
        assert(output.contains("private var eagerPetsFilterVisible: Boolean = false")) {
            "load{Edge} filterVisible state should be a private per-edge flag\n$output"
        }
    }

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `eager privacy batches ordered deduped targets and filters by target id`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString().replace("\\s+".toRegex(), " ")

        // The per-parent window is collapsed to target IDs, then the
        // original target-query order is retained while duplicate IDs
        // are removed. One positional LOAD batch covers that complete
        // edge block.
        assert(
            output.contains(
                "val inWindowTargetIds = loadedGroups.values.flatten().mapTo(mutableSetOf()) { it.id } " +
                    "val privacyTargets = decodedTargets.map { it.second }.filter { it.id in inWindowTargetIds }.distinctBy { it.id } " +
                    "val privacyDenials = eagerClient.pets.loadDenials(eagerPrivacyContext, privacyTargets)",
            ),
        ) {
            "Eager LOAD privacy should batch the in-window targets in ordered, ID-deduplicated form\n$output"
        }
        val batchCalls = Regex(
            Regex.escape("eagerClient.pets.loadDenials(eagerPrivacyContext, privacyTargets)"),
        ).findAll(output).count()
        assert(batchCalls == 1) {
            "The emitted pets edge block should make exactly one plural LOAD call; found $batchCalls\n$output"
        }

        // filterVisible derives an ID set from the positional result and
        // applies it to every parent group. Entity equality is not used:
        // distinct instances representing the same target stay aligned.
        assert(
            output.contains(
                "if (eagerPetsFilterVisible) { val visibleTargetIds = privacyTargets.zip(privacyDenials) " +
                    ".filter { (_, denial) -> denial == null } " +
                    ".mapTo(mutableSetOf()) { (entity, _) -> entity.id } " +
                    "loadedGroups = loadedGroups.mapValues { (_, list) -> list.filter { it.id in visibleTargetIds } } }",
            ),
        ) {
            "filterVisible eager loading should retain targets by ID from the positional privacy result\n$output"
        }

        // Strict mode reports the first denied target in batch order and
        // never evaluates targets again merely to choose a denial.
        assert(output.contains("else { val denial = privacyDenials.firstOrNull { it != null } if (denial != null) {")) {
            "Strict eager loading should select the first non-null positional denial\n$output"
        }
        assert(
            output.contains(
                "throw EntPrivacyDeniedException(LoadDenialOrigin.SelectedEdgePath(eagerDenialPath.map { " +
                    "SelectedEdgeStep(it.source.simpleName!!, it.edgeName, it.target.simpleName!!) }), listOf(denial))",
            ),
        ) {
            "Strict eager denial should use a SelectedEdgePath origin\n$output"
        }
        assert(!output.contains("eagerClient.pets.loadDenialOrNull")) {
            "Eager privacy must not regress to per-target singleton calls\n$output"
        }
    }

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `every eager edge shape emits exactly one plural LOAD call`() {
        val (_, names, byName) = createAllSchemas()
        val generator = QueryGenerator("com.example.ent")
        val outputs = listOf(
            "hasMany" to generator.generate("Owner", byName["Owner"]!!, names).toString(),
            "belongsTo" to generator.generate("Pet", byName["Pet"]!!, names).toString(),
            "manyToMany" to generator.generate("Team", byName["Team"]!!, names).toString(),
        ) + run {
            val parent = HasOneEagerParentSchema()
            val profile = ProfileSchema()
            finalize(parent, profile)
            val hasOneNames = mapOf<EntSchema, String>(parent to "Parent", profile to "Profile")
            listOf("hasOne" to generator.generate("Parent", parent, hasOneNames).toString())
        }

        // KotlinPoet wraps long statements, so the argument list may span
        // lines depending on how long the target's clientName is. Match
        // across whitespace — wrapping is formatting, not semantics.
        val eagerBatchCall = Regex("eagerClient\\.\\w+\\.loadDenials\\(eagerPrivacyContext,\\s*privacyTargets\\)")
        val eagerSingletonCall = Regex("eagerClient\\.\\w+\\.loadDenialOrNull\\(")
        for ((shape, source) in outputs) {
            val batchCalls = eagerBatchCall.findAll(source).count()
            assert(batchCalls == 1) {
                "$shape should emit exactly one plural LOAD call for its one eager edge block; found $batchCalls\n$source"
            }
            assert(!eagerSingletonCall.containsMatchIn(source)) {
                "$shape eager privacy should not emit a per-target singleton LOAD call\n$source"
            }
        }

        val manyToManySource = outputs.single { it.first == "manyToMany" }.second
            .replace("\\s+".toRegex(), " ")
        assert(
            manyToManySource.contains(
                "val privacyTargets = orderedTargets.filter { it.id in inWindowTargetIds }.distinctBy { it.id }",
            ),
        ) {
            "M2M eager privacy should preserve target-query order while deduplicating shared target IDs\n$manyToManySource"
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

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `self-referential M2M query uses correct junction FKs`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Person", byName["Person"]!!, names).toString().replace("\\s+".toRegex(), " ")

        // Junction discovery pass, typed to the junction entity.
        assert(output.contains("Predicate.Leaf<Friendship>(\"person_id\", Op.IN, sourceIds)")) {
            "Should predicate the junction pass on source FK person_id, typed to the junction entity\n$output"
        }
    }

    @Test
    fun `self-referential M2M with same source and target edge fails at finalize`() {
        val person = SameEdgePersonSchema()
        val junction = SameEdgeJunctionSchema()
        val error = runCatching { finalize(person, junction) }.exceptionOrNull()
        assert(error != null) { "Should fail at finalize when sourceEdge and targetEdge are the same junction property" }
        assert(error!!.message!!.contains("sourceEdge and targetEdge are the same junction property")) {
            "Error should call out same junction property: ${error.message}"
        }
    }

    // ---------- M2M orientation / alias rules ----------

    @Test
    fun `same-orientation alias throughEntity declarations on one schema are rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(M2MAliasGroup()),
                SchemaInput(M2MAliasUser()),
                SchemaInput(M2MAliasMembership()),
            ))
        }
        assertContains(err.message!!, "Same-orientation alias")
        assertContains(err.message!!, "AliasGroup.members")
        assertContains(err.message!!, "AliasGroup.users")
    }

    @Test
    fun `cross-schema pair-swapped throughEntity is allowed`() {
        // BiUser declares (user, group); BiGroup declares (group, user) — same
        // canonical identity, pair-swapped orientations. Both should be accepted.
        EntGenerator("com.example.ent").generate(listOf(
            SchemaInput(BiUser()),
            SchemaInput(BiGroup()),
            SchemaInput(BiMembership()),
        ))
    }

    @Test
    fun `self-referential pair-swapped throughEntity is allowed`() {
        // FollowUser.following uses (follower, followed); FollowUser.followers
        // uses (followed, follower) — pair-swapped, same canonical identity.
        EntGenerator("com.example.ent").generate(listOf(
            SchemaInput(FollowUser()),
            SchemaInput(Follow()),
        ))
    }

    @Test
    fun `cross-schema pair-swapped throughLink is accepted with indexes`() {
        // Pair-swapped throughLink declarations are the two endpoints
        // of one symmetric link table. With the unique pair index + a
        // leading-column index per side, this finalizes and generates.
        EntGenerator("com.example.ent").generate(listOf(
            SchemaInput(LinkUser()),
            SchemaInput(LinkGroup()),
            SchemaInput(LinkMembership()),
        ))
    }

    @Test
    fun `same-orientation throughLink aliases are rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(SameOrientUser()),
                SchemaInput(SameOrientGroup()),
                SchemaInput(SameOrientMembership()),
            ))
        }
        assertContains(err.message!!, "Same-orientation alias")
    }

    @Test
    fun `pair-swapped throughLink missing the other-side leading index is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(NoLeadUser()),
                SchemaInput(NoLeadGroup()),
                SchemaInput(NoLeadMembership()),
            ))
        }
        assertContains(err.message!!, "no non-partial index leading with")
    }

    @Test
    fun `mixed throughLink and throughEntity on same canonical identity is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(MixedLinkUser()),
                SchemaInput(MixedLinkGroup()),
                SchemaInput(MixedLinkMembership()),
            ))
        }
        assertContains(err.message!!, "Mixed M2M write models")
        assertContains(err.message!!, "throughLink")
        assertContains(err.message!!, "throughEntity")
    }

    @Test
    fun `multi-relationship junction with distinct canonical identities is allowed`() {
        // MultiRelProject declares two manyToMany via MultiRelAssignment but
        // with distinct edge pairs ({project, assignee} vs {project, reviewer}).
        EntGenerator("com.example.ent").generate(listOf(
            SchemaInput(MultiRelProject()),
            SchemaInput(MultiRelPet()),
            SchemaInput(MultiRelAssignment()),
        ))
    }

    // ---------- M2M target schema gets no reverse-edge metadata ----------

    @Test
    fun `bidirectional throughEntity emits no reverse-edge metadata on either side`() {
        // BiUser declares (user, group); BiGroup declares (group, user) — same
        // canonical identity, pair-swapped orientations. Each side carries
        // its own forward edge in SCHEMA.edges; nothing extra is synthesized
        // on the opposite side — so neither schema picks up the
        // `${otherTable}_${otherEdge}` shape that older codegen used to
        // inject as a reverse entry.
        val biUser = BiUser()
        val biGroup = BiGroup()
        val biMembership = BiMembership()
        val names = mapOf<EntSchema, String>(
            biUser to "BiUser", biGroup to "BiGroup", biMembership to "BiMembership",
        )
        finalize(biUser, biGroup, biMembership)

        val userOutput = EntityGenerator("com.example.ent")
            .generate("BiUser", biUser, names).toString()
        val groupOutput = EntityGenerator("com.example.ent")
            .generate("BiGroup", biGroup, names).toString()

        assert(!userOutput.contains("\"bi_groups_users\"")) {
            "BiUser SCHEMA should not contain a reverse 'bi_groups_users' entry\n$userOutput"
        }
        assert(!groupOutput.contains("\"bi_users_groups\"")) {
            "BiGroup SCHEMA should not contain a reverse 'bi_users_groups' entry\n$groupOutput"
        }
    }

    @Test
    fun `self-referential throughEntity emits only the declared forward edges`() {
        // FollowUser declares 'following' and 'followers' over Follow with
        // pair-swapped orientations. Both declared edges appear in
        // SCHEMA.edges; no `follow_users_*` reverse name shows up.
        val user = FollowUser()
        val follow = Follow()
        val names = mapOf<EntSchema, String>(user to "FollowUser", follow to "Follow")
        finalize(user, follow)

        val output = EntityGenerator("com.example.ent")
            .generate("FollowUser", user, names).toString()

        assert(!output.contains("\"follow_users_following\"")) {
            "FollowUser SCHEMA should not contain a 'follow_users_following' reverse entry\n$output"
        }
        assert(!output.contains("\"follow_users_followers\"")) {
            "FollowUser SCHEMA should not contain a 'follow_users_followers' reverse entry\n$output"
        }
        assert(output.contains("\"following\"")) {
            "Declared forward 'following' edge should be present\n$output"
        }
        assert(output.contains("\"followers\"")) {
            "Declared forward 'followers' edge should be present\n$output"
        }
    }



    // ---------- throughLink junction-shape rules ----------

    @Test
    fun `helper-eligible throughLink junction passes validation`() {
        EntGenerator("com.example.ent").generate(listOf(
            SchemaInput(LinkPost()),
            SchemaInput(LinkTag()),
            SchemaInput(LinkPostTag()),
        ))
    }

    @Test
    fun `throughLink junction with payload column is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(PayloadLinkPost()),
                SchemaInput(PayloadLinkTag()),
                SchemaInput(PayloadLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "payload field")
        assertContains(err.message!!, "'nickname'")
    }

    @Test
    fun `throughLink junction with nullable belongsTo is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(NullableLinkPost()),
                SchemaInput(NullableLinkTag()),
                SchemaInput(NullableLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "is nullable")
        assertContains(err.message!!, "throughLink requires both junction belongsTo edges to be non-null")
    }

    @Test
    fun `throughLink junction without explicit OnDelete CASCADE is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(NoCascadeLinkPost()),
                SchemaInput(NoCascadeLinkTag()),
                SchemaInput(NoCascadeLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "onDelete=")
        assertContains(err.message!!, "OnDelete.CASCADE")
    }

    @Test
    fun `throughLink junction without unique composite index is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(NoIndexLinkPost()),
                SchemaInput(NoIndexLinkTag()),
                SchemaInput(NoIndexLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "missing a non-partial unique composite index")
        assertContains(err.message!!, "(post_id, tag_id)")
    }

    @Test
    fun `throughLink junction with partial unique index is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(PartialIdxLinkPost()),
                SchemaInput(PartialIdxLinkTag()),
                SchemaInput(PartialIdxLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "missing a non-partial unique composite index")
    }

    @Test
    fun `throughLink junction accepts a reverse-order unique pair index`() {
        // The unique pair index matches unordered, so (tag_id, post_id)
        // is accepted for a lone declaration just like (post_id, tag_id).
        EntGenerator("com.example.ent").generate(listOf(
            SchemaInput(ReverseOrderIdxLinkPost()),
            SchemaInput(ReverseOrderIdxLinkTag()),
            SchemaInput(ReverseOrderIdxLinkPostTag()),
        ))
    }

    @Test
    fun `throughLink junction with validator on FK backing field is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(ValidatorBackingLinkPost()),
                SchemaInput(ValidatorBackingLinkTag()),
                SchemaInput(ValidatorBackingLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "validator")
    }

    @Test
    fun `throughLink junction with extra synthesized-FK belongsTo is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(TenantLink()),
                SchemaInput(TenantedLinkPost()),
                SchemaInput(TenantedLinkTag()),
                SchemaInput(TenantedLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "extra belongsTo edge")
        assertContains(err.message!!, "'tenant'")
        assertContains(err.message!!, "tenant_id")
    }

    @Test
    fun `throughLink junction with extra field-backed belongsTo is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(TenantLink()),
                SchemaInput(FieldBackedTenantedLinkPost()),
                SchemaInput(FieldBackedTenantedLinkTag()),
                SchemaInput(FieldBackedTenantedLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "extra belongsTo edge")
        assertContains(err.message!!, "'tenant'")
        assertContains(err.message!!, "tenant_id_col")
    }

    @Test
    fun `throughLink junction with unique() belongsTo is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(UniqueEdgeLinkPost()),
                SchemaInput(UniqueEdgeLinkTag()),
                SchemaInput(UniqueEdgeLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "is `.unique()`")
        assertContains(err.message!!, "1:1")
        assertContains(err.message!!, "'post'")
    }

    @Test
    fun `throughLink junction with extra unique single-column FK index is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(UniqueSingleIdxLinkPost()),
                SchemaInput(UniqueSingleIdxLinkTag()),
                SchemaInput(UniqueSingleIdxLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "unique single-column index")
        assertContains(err.message!!, "post_id")
    }

    @Test
    fun `throughLink junction with EXPLICIT id strategy is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput(ExplicitIdLinkPost()),
                SchemaInput(ExplicitIdLinkTag()),
                SchemaInput(ExplicitIdLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "EXPLICIT")
    }

    // ---------- Per-group limit/offset in eager loading ----------

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `to-many eager loading applies limit per group not globally`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString().replace("\\s+".toRegex(), " ")

        // The batch fetch routes through the runtime-owned direct
        // to-many executor: the statement carries no LIMIT/OFFSET —
        // a native driver applies each parent's window in storage,
        // and the emulated fallback fetches everything and windows
        // per group in Kotlin below.
        assert(output.contains("val related = executeDirectToMany(")) {
            "to-many fetch should route through the runtime direct to-many executor\n$output"
        }
        assert(output.contains("pairs.drop(perGroupOffset).take(perGroupLimit)")) {
            "Should apply limit/offset per group on the emulated path\n$output"
        }
        // Under STORAGE_NATIVE the rows are already windowed — the
        // Kotlin drop/take must not run a second time.
        assert(output.contains("val windowInStorage = related.strategy == EagerWindowStrategy.STORAGE_NATIVE")) {
            "Should skip the Kotlin window when storage applied it\n$output"
        }
    }

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `M2M eager loading applies limit per group not globally`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("subSpec.orderBy, null, null)")) {
            "Target query should not pass limit/offset to driver\n$output"
        }
        assert(output.contains("perGroupOffset") && output.contains("perGroupLimit")) {
            "Should apply limit/offset per group\n$output"
        }
    }

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `M2M eager loading dedups duplicate (source, target) junction rows`() {
        // throughEntity junctions can legitimately carry duplicate
        // (source_id, target_id) pairs (the row carries distinct
        // payload — there's no required pair-uniqueness index for
        // throughEntity, only for throughLink). Without dedup, the
        // eager-load helper would append the same target twice to one
        // source's group, while the EXISTS-based queryX traversal
        // returns each target once — and per-group drop/take would
        // slice from a duplicated list.
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString().replace("\\s+".toRegex(), " ")

        // Membership lookup uses Set (LinkedHashSet via mutableSetOf),
        // not List. Duplicate junction rows collapse to one membership.
        assert(output.contains("MutableSet<Any?>")) {
            "Membership lookup must use a Set so duplicate (source, target) junction rows dedup\n$output"
        }
        assert(output.contains("mutableSetOf()")) {
            "Should initialize per-target-id source bucket with mutableSetOf()\n$output"
        }
        // Negative: the old List form would not dedup.
        assert(!output.contains("MutableList<Any?>")) {
            "Should not use MutableList for the membership lookup (dedup needs Set)\n$output"
        }
    }

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `M2M eager loading groups by iterating ordered target rows, not junction rows`() {
        // Iterating junctionRows here would group in driver-default
        // junction order — which is unrelated to `subQuery.orderFields`
        // — so a later `drop(offset).take(limit)` per group would pick
        // the wrong subset for `loadTags { orderBy(...); limit(...) }`.
        // The fix builds a target→sources membership lookup from the
        // junction rows, then iterates targetRows (already ordered by
        // `subQuery.orderFields`) and appends each target to its
        // source groups.
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Team", byName["Team"]!!, names).toString().replace("\\s+".toRegex(), " ")

        // The loop that populates the grouped map must iterate
        // targetRows, not junctionRows.
        assert(output.contains("for (row in targetRows)")) {
            "M2M eager grouping must iterate ordered targetRows\n$output"
        }
        // Membership lookup: target id → list of sources.
        assert(output.contains("sourcesByTargetId")) {
            "Should build a target→sources membership lookup\n$output"
        }
        // Negative: the old "iterate junctionRows + targetById lookup"
        // shape would lose ordering.
        assert(!output.contains("for (jr in junctionRows) { val target = targetById[")) {
            "Should not iterate junctionRows when building groups\n$output"
        }
        assert(!output.contains("val targetById = targetRows.map")) {
            "Should not pre-build targetById; iterating targetRows directly preserves order\n$output"
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
    fun `wrong sourceEdge ref fails fast with clear error`() {
        val (_, names, byName) = createAllSchemas()
        val pet = byName["Pet"]!!
        val projectAssignment = byName["ProjectAssignment"]!!
        val project = byName["Project"]!!
        val edge = Edge(
            name = "assignees",
            target = pet,
            kind = EdgeKind.ManyToMany(ManyToManyThrough.ThroughEntity(
                junction = projectAssignment,
                sourceEdge = "assignee",
                targetEdge = "reviewer",
            )),
        )

        val error = assertFailsWith<IllegalStateException> {
            resolveM2MEdgeJoin(edge, project, names)
        }
        assert(error.message!!.contains("M2M sourceEdge")) {
            "Should mention M2M sourceEdge: ${error.message}"
        }
    }

    @Test
    fun `wrong targetEdge ref fails fast with clear error`() {
        val (_, names, byName) = createAllSchemas()
        val pet = byName["Pet"]!!
        val projectAssignment = byName["ProjectAssignment"]!!
        val project = byName["Project"]!!
        val edge = Edge(
            name = "assignees",
            target = pet,
            kind = EdgeKind.ManyToMany(ManyToManyThrough.ThroughEntity(
                junction = projectAssignment,
                sourceEdge = "project",
                targetEdge = "project",
            )),
        )

        val error = assertFailsWith<IllegalStateException> {
            resolveM2MEdgeJoin(edge, project, names)
        }
        assert(error.message!!.contains("M2M targetEdge")) {
            "Should mention M2M targetEdge: ${error.message}"
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

    @Suppress("unused") // Runtime execution coverage lives in runtime and integration tests.
    fun `hasOne eager loading queries target by FK not source FK`() {
        val parent = HasOneEagerParentSchema()
        val profile = ProfileSchema()
        finalize(parent, profile)
        val names = mapOf<EntSchema, String>(parent to "Parent", profile to "Profile")
        val output = QueryGenerator("com.example.ent")
            .generate("Parent", parent, names).toString().replace("\\s+".toRegex(), " ")

        // Parent eager-loads Profile target via FK on target side.
        assert(output.contains("Predicate.Leaf<Profile>(\"owner_id\", Op.IN, sourceIds)")) {
            "Should query target by FK column (target-scoped Predicate.Leaf<Profile>), not source FK\n$output"
        }
        assert(output.contains("groupBy { (row, _) -> row[\"owner_id\"] }")) {
            "Should group row-order-decoded targets by FK column\n$output"
        }
        assert(output.contains("EdgeState.Loaded(loadedGroups[entity.id]?.firstOrNull())")) {
            "Should map source.id to grouped target, collapsing to a single Loaded entity\n$output"
        }
    }

    @Test
    fun `hasOne Edges property is EdgeState of nullable entity not list`() {
        val parent = HasOneEdgesParentSchema()
        val profile = ProfileSchema2()
        finalize(parent, profile)
        val names = mapOf<EntSchema, String>(parent to "Parent", profile to "Profile")
        val output = EntityGenerator("com.example.ent")
            .generate("Parent", parent, names).toString()

        assert(output.contains("val profile: EdgeState<Profile?> = EdgeState.Unloaded")) {
            "HasOne edge should produce an EdgeState property over a nullable entity, not a list\n$output"
        }
    }
}
