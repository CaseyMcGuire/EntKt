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

    val members = manyToMany<Pet>("members").throughEntity<TeamMember>(TeamMember::team, TeamMember::member)
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

    val friends = manyToMany<Person>("friends").throughEntity<Friendship>(Friendship::person, Friendship::friend)
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

    val assignees = manyToMany<Pet>("assignees").throughEntity<ProjectAssignment>(ProjectAssignment::project, ProjectAssignment::assignee)
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
        .throughEntity<SameEdgeJunctionSchema>(SameEdgeJunctionSchema::person, SameEdgeJunctionSchema::person)
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
 * Unset → default fires; required-FK can never be assigned null.
 */
private class RequiredFkWithDefaultChild : EntSchema("required_default_children") {
    override fun id() = EntId.int()
    val name = string("name")
    val ownerId = long("owner_id").default(42L)
    val owner = belongsTo<DefaultedFkParent>("owner").field(ownerId)
}

/**
 * Nullable field-backed FK whose backing field carries a default.
 * Untouched → default fires; explicit null → suppresses
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
 * Required relationship backed by a nullable field.
 * codegen should reject this — a required edge can't be backed by a
 * nullable column.
 */
private class RequiredEdgeNullableFieldChild : EntSchema("required_edge_nullable_field") {
    override fun id() = EntId.int()
    val ownerId = long("owner_id").nullable()
    val owner = belongsTo<NullabilityMismatchParent>("owner").field(ownerId)
}

/**
 * Nullable relationship backed by a non-null field.
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

// ---------- Test schemas for M2M orientation / alias rules ----------

private class M2MUser : EntSchema("m2m_users") {
    override fun id() = EntId.long()
}
private class M2MGroup : EntSchema("m2m_groups") {
    override fun id() = EntId.long()
}
private class M2MMembership : EntSchema("m2m_memberships") {
    override fun id() = EntId.long()
    val user = belongsTo<M2MUser>("user")
    val group = belongsTo<M2MGroup>("group")
}

// Same-orientation alias: two manyToMany declarations on the same
// schema with identical (junction, sourceEdge, targetEdge) keys.
private class M2MAliasGroup : EntSchema("alias_groups") {
    override fun id() = EntId.long()
    val members = manyToMany<M2MAliasUser>("members")
        .throughEntity<M2MAliasMembership>(M2MAliasMembership::group, M2MAliasMembership::user)
    val users = manyToMany<M2MAliasUser>("users")
        .throughEntity<M2MAliasMembership>(M2MAliasMembership::group, M2MAliasMembership::user)
}
private class M2MAliasUser : EntSchema("alias_users") {
    override fun id() = EntId.long()
}
private class M2MAliasMembership : EntSchema("alias_memberships") {
    override fun id() = EntId.long()
    val user = belongsTo<M2MAliasUser>("user")
    val group = belongsTo<M2MAliasGroup>("group")
}

// Cross-schema pair-swap with throughEntity is allowed (this is the
// canonical bidirectional traversal pattern).
private class BiUser : EntSchema("bi_users") {
    override fun id() = EntId.long()
    val groups = manyToMany<BiGroup>("groups")
        .throughEntity<BiMembership>(BiMembership::user, BiMembership::group)
}
private class BiGroup : EntSchema("bi_groups") {
    override fun id() = EntId.long()
    val users = manyToMany<BiUser>("users")
        .throughEntity<BiMembership>(BiMembership::group, BiMembership::user)
}
private class BiMembership : EntSchema("bi_memberships") {
    override fun id() = EntId.long()
    val user = belongsTo<BiUser>("user")
    val group = belongsTo<BiGroup>("group")
}

// Self-referential pair-swap on the same schema is allowed.
private class FollowUser : EntSchema("follow_users") {
    override fun id() = EntId.long()
    val following = manyToMany<FollowUser>("following")
        .throughEntity<Follow>(Follow::follower, Follow::followed)
    val followers = manyToMany<FollowUser>("followers")
        .throughEntity<Follow>(Follow::followed, Follow::follower)
}
private class Follow : EntSchema("follows") {
    override fun id() = EntId.long()
    val follower = belongsTo<FollowUser>("follower")
    val followed = belongsTo<FollowUser>("followed")
}

// Cross-schema pair-swap with throughLink is accepted when the
// junction carries the unique pair index plus a
// leading-column index for each side's source FK.
private class LinkUser : EntSchema("link_users") {
    override fun id() = EntId.long()
    val groups = manyToMany<LinkGroup>("groups")
        .throughLink<LinkMembership>(LinkMembership::user, LinkMembership::group)
}
private class LinkGroup : EntSchema("link_groups") {
    override fun id() = EntId.long()
    val users = manyToMany<LinkUser>("users")
        .throughLink<LinkMembership>(LinkMembership::group, LinkMembership::user)
}
private class LinkMembership : EntSchema("link_memberships") {
    override fun id() = EntId.long()
    val user = belongsTo<LinkUser>("user").onDelete(OnDelete.CASCADE)
    val group = belongsTo<LinkGroup>("group").onDelete(OnDelete.CASCADE)
    val byUserGroup = index("idx_link_memberships_user_group", user.fk, group.fk).unique()
    val byGroupUser = index("idx_link_memberships_group_user", group.fk, user.fk)
}

// Two same-orientation throughLink aliases over one junction are rejected
// (two declarations must be pair-swapped, not the same direction).
private class SameOrientGroup : EntSchema("same_orient_groups") {
    override fun id() = EntId.long()
}
private class SameOrientMembership : EntSchema("same_orient_memberships") {
    override fun id() = EntId.long()
    val user = belongsTo<SameOrientUser>("user").onDelete(OnDelete.CASCADE)
    val group = belongsTo<SameOrientGroup>("group").onDelete(OnDelete.CASCADE)
    val byUserGroup = index("idx_same_orient_user_group", user.fk, group.fk).unique()
}
private class SameOrientUser : EntSchema("same_orient_users") {
    override fun id() = EntId.long()
    val groups = manyToMany<SameOrientGroup>("groups")
        .throughLink<SameOrientMembership>(SameOrientMembership::user, SameOrientMembership::group)
    val groupsAlias = manyToMany<SameOrientGroup>("groups_alias")
        .throughLink<SameOrientMembership>(SameOrientMembership::user, SameOrientMembership::group)
}

// Pair-swapped throughLink whose junction is missing the OTHER side's
// leading-column index → rejected by Rule 6b. Only the unique pair index
// (leading with user_id) is present, so the group side has no group_id
// leading index.
private class NoLeadUser : EntSchema("no_lead_users") {
    override fun id() = EntId.long()
    val groups = manyToMany<NoLeadGroup>("groups")
        .throughLink<NoLeadMembership>(NoLeadMembership::user, NoLeadMembership::group)
}
private class NoLeadGroup : EntSchema("no_lead_groups") {
    override fun id() = EntId.long()
    val users = manyToMany<NoLeadUser>("users")
        .throughLink<NoLeadMembership>(NoLeadMembership::group, NoLeadMembership::user)
}
private class NoLeadMembership : EntSchema("no_lead_memberships") {
    override fun id() = EntId.long()
    val user = belongsTo<NoLeadUser>("user").onDelete(OnDelete.CASCADE)
    val group = belongsTo<NoLeadGroup>("group").onDelete(OnDelete.CASCADE)
    val byUserGroup = index("idx_no_lead_user_group", user.fk, group.fk).unique()
}

// A `.readOnly()` second side STILL reads by its source FK, so it still needs
// its own leading-column index — Rule 6b does not relax for readOnly. Here the
// readOnly `users` side (source FK group_id) has no group_id-leading index, so
// the junction is rejected even though that side is read-only.
private class RoNoLeadUser : EntSchema("ro_no_lead_users") {
    override fun id() = EntId.long()
    val groups = manyToMany<RoNoLeadGroup>("groups")
        .throughLink<RoNoLeadMembership>(RoNoLeadMembership::user, RoNoLeadMembership::group)
}
private class RoNoLeadGroup : EntSchema("ro_no_lead_groups") {
    override fun id() = EntId.long()
    val users = manyToMany<RoNoLeadUser>("users")
        .throughLink<RoNoLeadMembership>(RoNoLeadMembership::group, RoNoLeadMembership::user)
        .readOnly()
}
private class RoNoLeadMembership : EntSchema("ro_no_lead_memberships") {
    override fun id() = EntId.long()
    val user = belongsTo<RoNoLeadUser>("user").onDelete(OnDelete.CASCADE)
    val group = belongsTo<RoNoLeadGroup>("group").onDelete(OnDelete.CASCADE)
    // Only a user_id-leading unique pair index — nothing leads with group_id.
    val byUserGroup = index("idx_ro_no_lead_user_group", user.fk, group.fk).unique()
}

// Same shape but the readOnly `users` side HAS its group_id-leading index, so
// the two-sided declaration (writable + readOnly) finalizes cleanly.
private class RoLeadUser : EntSchema("ro_lead_users") {
    override fun id() = EntId.long()
    val groups = manyToMany<RoLeadGroup>("groups")
        .throughLink<RoLeadMembership>(RoLeadMembership::user, RoLeadMembership::group)
}
private class RoLeadGroup : EntSchema("ro_lead_groups") {
    override fun id() = EntId.long()
    val users = manyToMany<RoLeadUser>("users")
        .throughLink<RoLeadMembership>(RoLeadMembership::group, RoLeadMembership::user)
        .readOnly()
}
private class RoLeadMembership : EntSchema("ro_lead_memberships") {
    override fun id() = EntId.long()
    val user = belongsTo<RoLeadUser>("user").onDelete(OnDelete.CASCADE)
    val group = belongsTo<RoLeadGroup>("group").onDelete(OnDelete.CASCADE)
    val byUserGroup = index("idx_ro_lead_user_group", user.fk, group.fk).unique()
    val byGroupUser = index("idx_ro_lead_group_user", group.fk, user.fk)
}

// Mixed mode (one side throughLink, the other throughEntity) over the
// same canonical identity is rejected.
private class MixedLinkUser : EntSchema("mixed_users") {
    override fun id() = EntId.long()
    val groups = manyToMany<MixedLinkGroup>("groups")
        .throughLink<MixedLinkMembership>(MixedLinkMembership::user, MixedLinkMembership::group)
}
private class MixedLinkGroup : EntSchema("mixed_groups") {
    override fun id() = EntId.long()
    val users = manyToMany<MixedLinkUser>("users")
        .throughEntity<MixedLinkMembership>(MixedLinkMembership::group, MixedLinkMembership::user)
}
private class MixedLinkMembership : EntSchema("mixed_memberships") {
    override fun id() = EntId.long()
    val user = belongsTo<MixedLinkUser>("user")
    val group = belongsTo<MixedLinkGroup>("group")
}

// Multi-relationship junction (distinct canonical identities) is
// allowed even though both share the junction class.
private class MultiRelProject : EntSchema("multi_rel_projects") {
    override fun id() = EntId.long()
    val assignees = manyToMany<MultiRelPet>("assignees")
        .throughEntity<MultiRelAssignment>(MultiRelAssignment::project, MultiRelAssignment::assignee)
    val reviewers = manyToMany<MultiRelPet>("reviewers")
        .throughEntity<MultiRelAssignment>(MultiRelAssignment::project, MultiRelAssignment::reviewer)
}
private class MultiRelPet : EntSchema("multi_rel_pets") {
    override fun id() = EntId.long()
}
private class MultiRelAssignment : EntSchema("multi_rel_assignments") {
    override fun id() = EntId.long()
    val project = belongsTo<MultiRelProject>("project")
    val assignee = belongsTo<MultiRelPet>("assignee")
    val reviewer = belongsTo<MultiRelPet>("reviewer")
}

// ---------- Test schemas for throughLink junction-shape rules ----------

private class LinkPost : EntSchema("link_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<LinkTag>("tags")
        .throughLink<LinkPostTag>(LinkPostTag::post, LinkPostTag::tag)
}
private class LinkTag : EntSchema("link_tags") {
    override fun id() = EntId.long()
}
// Helper-eligible junction: id + 2 FK columns, both non-null, CASCADE,
// non-partial unique index on (post_id, tag_id). Used as the baseline
// for "all rules pass" tests.
private class LinkPostTag : EntSchema("link_post_tags") {
    override fun id() = EntId.long()
    val post = belongsTo<LinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag = belongsTo<LinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction with a payload column (violates rule 1).
private class PayloadLinkPost : EntSchema("payload_link_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<PayloadLinkTag>("tags")
        .throughLink<PayloadLinkPostTag>(PayloadLinkPostTag::post, PayloadLinkPostTag::tag)
}
private class PayloadLinkTag : EntSchema("payload_link_tags") {
    override fun id() = EntId.long()
}
private class PayloadLinkPostTag : EntSchema("payload_link_post_tags") {
    override fun id() = EntId.long()
    val nickname = string("nickname")
    val post = belongsTo<PayloadLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag = belongsTo<PayloadLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_payload_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction with a nullable belongsTo (violates rule 2).
private class NullableLinkPost : EntSchema("nullable_link_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<NullableLinkTag>("tags")
        .throughLink<NullableLinkPostTag>(NullableLinkPostTag::post, NullableLinkPostTag::tag)
}
private class NullableLinkTag : EntSchema("nullable_link_tags") {
    override fun id() = EntId.long()
}
private class NullableLinkPostTag : EntSchema("nullable_link_post_tags") {
    override fun id() = EntId.long()
    val post = belongsTo<NullableLinkPost>("post").onDelete(OnDelete.CASCADE).nullable()
    val tag = belongsTo<NullableLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_nullable_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction missing OnDelete.CASCADE (violates rule 4).
private class NoCascadeLinkPost : EntSchema("no_cascade_link_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<NoCascadeLinkTag>("tags")
        .throughLink<NoCascadeLinkPostTag>(NoCascadeLinkPostTag::post, NoCascadeLinkPostTag::tag)
}
private class NoCascadeLinkTag : EntSchema("no_cascade_link_tags") {
    override fun id() = EntId.long()
}
private class NoCascadeLinkPostTag : EntSchema("no_cascade_link_post_tags") {
    override fun id() = EntId.long()
    val post = belongsTo<NoCascadeLinkPost>("post").onDelete(OnDelete.RESTRICT)
    val tag = belongsTo<NoCascadeLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_no_cascade_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction without the required unique composite index (violates rule 6).
private class NoIndexLinkPost : EntSchema("no_index_link_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<NoIndexLinkTag>("tags")
        .throughLink<NoIndexLinkPostTag>(NoIndexLinkPostTag::post, NoIndexLinkPostTag::tag)
}
private class NoIndexLinkTag : EntSchema("no_index_link_tags") {
    override fun id() = EntId.long()
}
private class NoIndexLinkPostTag : EntSchema("no_index_link_post_tags") {
    override fun id() = EntId.long()
    val post = belongsTo<NoIndexLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag = belongsTo<NoIndexLinkTag>("tag").onDelete(OnDelete.CASCADE)
}

// Junction with a partial unique index (violates rule 6).
private class PartialIdxLinkPost : EntSchema("partial_idx_link_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<PartialIdxLinkTag>("tags")
        .throughLink<PartialIdxLinkPostTag>(PartialIdxLinkPostTag::post, PartialIdxLinkPostTag::tag)
}
private class PartialIdxLinkTag : EntSchema("partial_idx_link_tags") {
    override fun id() = EntId.long()
}
private class PartialIdxLinkPostTag : EntSchema("partial_idx_link_post_tags") {
    override fun id() = EntId.long()
    val post = belongsTo<PartialIdxLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag = belongsTo<PartialIdxLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_partial_link_post_tags_post_tag", post.fk, tag.fk).unique().where("post_id > 0")
}

// Junction with the unique index in the wrong order (violates rule 6).
private class ReverseOrderIdxLinkPost : EntSchema("rev_idx_link_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<ReverseOrderIdxLinkTag>("tags")
        .throughLink<ReverseOrderIdxLinkPostTag>(ReverseOrderIdxLinkPostTag::post, ReverseOrderIdxLinkPostTag::tag)
}
private class ReverseOrderIdxLinkTag : EntSchema("rev_idx_link_tags") {
    override fun id() = EntId.long()
}
private class ReverseOrderIdxLinkPostTag : EntSchema("rev_idx_link_post_tags") {
    override fun id() = EntId.long()
    val post = belongsTo<ReverseOrderIdxLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag = belongsTo<ReverseOrderIdxLinkTag>("tag").onDelete(OnDelete.CASCADE)
    // (tag_id, post_id) instead of (post_id, tag_id).
    val pair = index("idx_rev_link_post_tags_tag_post", tag.fk, post.fk).unique()
}

// Junction with a field-backed FK that carries a validator (violates rule 3).
private class ValidatorBackingLinkPost : EntSchema("val_link_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<ValidatorBackingLinkTag>("tags")
        .throughLink<ValidatorBackingLinkPostTag>(ValidatorBackingLinkPostTag::post, ValidatorBackingLinkPostTag::tag)
}
private class ValidatorBackingLinkTag : EntSchema("val_link_tags") {
    override fun id() = EntId.long()
}
private class ValidatorBackingLinkPostTag : EntSchema("val_link_post_tags") {
    override fun id() = EntId.long()
    val postIdCol = long("post_id_col").positive()
    val tagIdCol = long("tag_id_col")
    val post = belongsTo<ValidatorBackingLinkPost>("post").field(postIdCol).onDelete(OnDelete.CASCADE)
    val tag = belongsTo<ValidatorBackingLinkTag>("tag").field(tagIdCol).onDelete(OnDelete.CASCADE)
    val pair = index("idx_val_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction with a third belongsTo beyond sourceEdge/targetEdge
// (violates rule 1a — adds an FK column the helpers would not populate).
// Mirrors a multi-tenant link table where the schema author tried to
// attach a `tenant` edge to the junction.
private class TenantLink : EntSchema("tenant_link_orgs") {
    override fun id() = EntId.long()
}
private class TenantedLinkPost : EntSchema("tenanted_link_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<TenantedLinkTag>("tags")
        .throughLink<TenantedLinkPostTag>(TenantedLinkPostTag::post, TenantedLinkPostTag::tag)
}
private class TenantedLinkTag : EntSchema("tenanted_link_tags") {
    override fun id() = EntId.long()
}
private class TenantedLinkPostTag : EntSchema("tenanted_link_post_tags") {
    override fun id() = EntId.long()
    val post = belongsTo<TenantedLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag = belongsTo<TenantedLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val tenant = belongsTo<TenantLink>("tenant").onDelete(OnDelete.CASCADE)
    val pair = index("idx_tenanted_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Same shape but with `.field(handle)` on the extra belongsTo, to prove
// the check catches the field-backed case where `scalarFields()`
// silently filters out the third FK's backing column.
private class FieldBackedTenantedLinkPost : EntSchema("fb_tenanted_link_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<FieldBackedTenantedLinkTag>("tags")
        .throughLink<FieldBackedTenantedLinkPostTag>(
            FieldBackedTenantedLinkPostTag::post, FieldBackedTenantedLinkPostTag::tag,
        )
}
private class FieldBackedTenantedLinkTag : EntSchema("fb_tenanted_link_tags") {
    override fun id() = EntId.long()
}
private class FieldBackedTenantedLinkPostTag : EntSchema("fb_tenanted_link_post_tags") {
    override fun id() = EntId.long()
    val postIdCol = long("post_id_col")
    val tagIdCol = long("tag_id_col")
    val tenantIdCol = long("tenant_id_col")
    val post = belongsTo<FieldBackedTenantedLinkPost>("post")
        .field(postIdCol).onDelete(OnDelete.CASCADE)
    val tag = belongsTo<FieldBackedTenantedLinkTag>("tag")
        .field(tagIdCol).onDelete(OnDelete.CASCADE)
    val tenant = belongsTo<TenantLink>("tenant")
        .field(tenantIdCol).onDelete(OnDelete.CASCADE)
    val pair = index("idx_fb_tenanted_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction whose source belongsTo is `.unique()` (violates rule 4a).
private class UniqueEdgeLinkPost : EntSchema("uniq_edge_link_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<UniqueEdgeLinkTag>("tags")
        .throughLink<UniqueEdgeLinkPostTag>(UniqueEdgeLinkPostTag::post, UniqueEdgeLinkPostTag::tag)
}
private class UniqueEdgeLinkTag : EntSchema("uniq_edge_link_tags") {
    override fun id() = EntId.long()
}
private class UniqueEdgeLinkPostTag : EntSchema("uniq_edge_link_post_tags") {
    override fun id() = EntId.long()
    val post = belongsTo<UniqueEdgeLinkPost>("post").unique().onDelete(OnDelete.CASCADE)
    val tag = belongsTo<UniqueEdgeLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_uniq_edge_link_post_tags_post_tag", post.fk, tag.fk).unique()
}

// Junction with an extra unique single-column index on a junction FK
// (violates rule 6a). The required composite is also present so we
// exercise the new rule, not Rule 6's missing-composite path.
private class UniqueSingleIdxLinkPost : EntSchema("uniq_single_idx_link_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<UniqueSingleIdxLinkTag>("tags")
        .throughLink<UniqueSingleIdxLinkPostTag>(UniqueSingleIdxLinkPostTag::post, UniqueSingleIdxLinkPostTag::tag)
}
private class UniqueSingleIdxLinkTag : EntSchema("uniq_single_idx_link_tags") {
    override fun id() = EntId.long()
}
private class UniqueSingleIdxLinkPostTag : EntSchema("uniq_single_idx_link_post_tags") {
    override fun id() = EntId.long()
    val post = belongsTo<UniqueSingleIdxLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag = belongsTo<UniqueSingleIdxLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_uniq_single_idx_link_post_tags_post_tag", post.fk, tag.fk).unique()
    val byPost = index("idx_uniq_single_idx_link_post_tags_post", post.fk).unique()
}

// Junction with EXPLICIT id strategy (violates rule 5).
private class ExplicitIdLinkPost : EntSchema("explicit_id_link_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<ExplicitIdLinkTag>("tags")
        .throughLink<ExplicitIdLinkPostTag>(ExplicitIdLinkPostTag::post, ExplicitIdLinkPostTag::tag)
}
private class ExplicitIdLinkTag : EntSchema("explicit_id_link_tags") {
    override fun id() = EntId.long()
}
private class ExplicitIdLinkPostTag : EntSchema("explicit_id_link_post_tags") {
    override fun id() = EntId.string() // explicit caller-provided id
    val post = belongsTo<ExplicitIdLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag = belongsTo<ExplicitIdLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_explicit_id_link_post_tags_post_tag", post.fk, tag.fk).unique()
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
            "Must not have owner: Owner? entity setter (removed in to-one FK behavior)\n$output"
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
    fun `create builder required FK getter throws on unassigned read`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("RequiredPet", byName["RequiredPet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        // On create builders for
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

        // On create builders for nullable relationships, reading
        // the FK before assignment returns `null`. No custom getter is
        // generated; default-null property behavior is correct.
        assert(!output.contains("get() = field ?: throw IllegalStateException(\"owner is required\")")) {
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
        val output = CreateGenerator("com.example.ent")
            .generate("ValidatedFkChild", child, names).toString()
            .replace("\\s+".toRegex(), " ")

        // The `.positive()` check (`if (prop <= 0) throw ...`) should
        // appear in the save body keyed off the FK property name.2
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
        // must have a nullable backing column (by contract).
        assertContains(err.message!!, "is nullable but")
        assertContains(err.message!!, "is non-null")
    }

    @Test
    fun `create builder accepts beforeCreate hooks typed against CreateHookContext`() {
        val (_, names, byName) = createAllSchemas()
        val output = CreateGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString()
            .replace("\\s+".toRegex(), " ")

        // beforeCreate hooks receive a restricted CreateHookContext
        // (mutation view + client), not the concrete Create builder. The
        // save body constructs the context and passes it to each hook so
        // the hook can't reach `save()`, `driver`, hook lists, or the
        // private staging/assigned fields on the concrete builder.
        assert(output.contains("beforeCreateHooks: List<(PetCreateHookContext) -> Unit>")) {
            "beforeCreateHooks list should be typed against PetCreateHookContext\n$output"
        }
        // create-hook adapter: the CreateHookContext now wraps the private
        // `_createMutationView` adapter, not the concrete builder
        // (`this`). This matches the runtime-enforced contract
        // the update path has had since transaction locking and link-table M2M helpers — a hook
        // attempting `ctx.mutation as PetCreate` throws.
        assert(output.contains("val createCtx = PetCreateHookContext(client, _createMutationView)")) {
            "save() should construct a CreateHookContext wrapping _createMutationView\n$output"
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
        // belongsTo<Team>("team").field(teamId)`. A
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

        // Required to-one edges must expose
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

        // Required FK setters defensively reject Java/platform
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
        // Traversal generates a shaped bridge typed
        // HasEdgeFromShape<TargetEntity, SourceEntity>.
        // Owner.queryPets → Pet candidates filtered by inverse "owner"
        // edge pointing back to Owner; so HasEdgeFromShape<Pet, Owner>.
        assert(output.contains("Predicate.HasEdgeFromShape<Pet, Owner>(")) {
            "Should construct HasEdgeFromShape<Pet, Owner> naming the inverse edge\n$output"
        }
        // The embedded shape carries the post-interceptor source
        // query as written: predicates, order, limit, offset, flags.
        // Owner owns no FK, so the source subquery selects owners.id.
        assert(output.contains("selectedColumn = \"id\"")) {
            "Shape should select the source id column for a to-many traversal\n$output"
        }
        for (field in listOf(
            "table = sourceSpec.table",
            "predicates = sourceSpec.predicates",
            "orderBy = sourceSpec.orderBy",
            "limit = sourceSpec.limit",
            "offset = sourceSpec.offset",
            "flags = sourceSpec.flags",
        )) {
            assert(output.contains(field)) {
                "TraversalSourceShape should embed the post-interceptor $field\n$output"
            }
        }
        // The predicate-only fold is gone: no HasEdgeWith bridge and
        // no HasEdge fallback in the traversal lambda. (The walker's
        // HasEdgeWith rewrites are typed <Owner, Pet> here, so this
        // substring is traversal-specific.)
        assert(!output.contains("Predicate.HasEdgeWith<Pet, Owner>(")) {
            "Traversal should no longer fold predicates into HasEdgeWith\n$output"
        }
        assert(!output.contains("Predicate.HasEdge<Pet>(\"owner\")")) {
            "Traversal should no longer fall back to a bare HasEdge bridge\n$output"
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
        // Pet.queryOwner → Owner candidates filtered by inverse "pets"
        // edge on Owner pointing to Pet; HasEdgeFromShape<Owner, Pet>.
        assert(output.contains("Predicate.HasEdgeFromShape<Owner, Pet>(")) {
            "Should construct HasEdgeFromShape<Owner, Pet> naming Owner's 'pets' edge\n$output"
        }
        // Child-to-parent traversal: Pet owns the FK, so the source
        // subquery selects pets.owner_id.
        assert(output.contains("selectedColumn = \"owner_id\"")) {
            "Shape should select the source FK column for a child-to-parent traversal\n$output"
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
        // M2M traversal: bridge is HasM2MEdgeFromShape<TargetEntity, SourceEntity>.
        // Team.queryMembers → Pet candidates filtered through junction
        // by Team's "members" forward edge: HasM2MEdgeFromShape<Pet, Team>.
        assert(output.contains("Predicate.HasM2MEdgeFromShape<Pet, Team>(")) {
            "Should lower to HasM2MEdgeFromShape<Pet, Team> against the source schema\n$output"
        }
        // The junction references the source's id column.
        assert(output.contains("selectedColumn = \"id\"")) {
            "M2M shape should select the junction-referenced source id column\n$output"
        }
        assert(!output.contains("Predicate.HasM2MEdgeFrom<Pet, Team>(")) {
            "Traversal should no longer construct the predicate-only HasM2MEdgeFrom bridge\n$output"
        }
        assert(!output.contains("Predicate.HasEdgeWith<Pet, Team>(\"teams_members\"")) {
            "Should not reference the synthesized reverse-edge name\n$output"
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

        // Owner eager-loads pets via the IN predicate on target (Pet)
        // FK column. Eager-load Leaf is target-scoped: Predicate.Leaf<Pet>.
        assert(output.contains("Predicate.Leaf<Pet>(\"owner_id\", Op.IN, sourceIds)")) {
            "Should build IN predicate on the FK column scoped to target\n$output"
        }
    }

    @Test
    fun `to-one eager loading queries target by id`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Pet", byName["Pet"]!!, names).toString().replace("\\s+".toRegex(), " ")

        // Pet eager-loads owner (Owner) by id: Predicate.Leaf<Owner>.
        assert(output.contains("Predicate.Leaf<Owner>(\"id\", Op.IN, fkValues)")) {
            "Should build IN predicate on target id column scoped to target\n$output"
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
        // Junction-table query has no entity scope; Predicate.Leaf<Any>.
        assert(output.contains("Predicate.Leaf<Any>(\"team_id\", Op.IN, sourceIds)")) {
            "Should query junction with source FK as erased Predicate.Leaf<Any>\n$output"
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

        // Junction-table query, erased scope.
        assert(output.contains("Predicate.Leaf<Any>(\"person_id\", Op.IN, sourceIds)")) {
            "Should query junction with source FK person_id as erased Predicate.Leaf<Any>\n$output"
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
                SchemaInput("AliasGroup", M2MAliasGroup()),
                SchemaInput("AliasUser", M2MAliasUser()),
                SchemaInput("AliasMembership", M2MAliasMembership()),
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
            SchemaInput("BiUser", BiUser()),
            SchemaInput("BiGroup", BiGroup()),
            SchemaInput("BiMembership", BiMembership()),
        ))
    }

    @Test
    fun `self-referential pair-swapped throughEntity is allowed`() {
        // FollowUser.following uses (follower, followed); FollowUser.followers
        // uses (followed, follower) — pair-swapped, same canonical identity.
        EntGenerator("com.example.ent").generate(listOf(
            SchemaInput("FollowUser", FollowUser()),
            SchemaInput("Follow", Follow()),
        ))
    }

    @Test
    fun `cross-schema pair-swapped throughLink is accepted with indexes`() {
        // Pair-swapped throughLink declarations are the two endpoints
        // of one symmetric link table. With the unique pair index + a
        // leading-column index per side, this finalizes and generates.
        EntGenerator("com.example.ent").generate(listOf(
            SchemaInput("LinkUser", LinkUser()),
            SchemaInput("LinkGroup", LinkGroup()),
            SchemaInput("LinkMembership", LinkMembership()),
        ))
    }

    @Test
    fun `same-orientation throughLink aliases are rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput("SameOrientUser", SameOrientUser()),
                SchemaInput("SameOrientGroup", SameOrientGroup()),
                SchemaInput("SameOrientMembership", SameOrientMembership()),
            ))
        }
        assertContains(err.message!!, "Same-orientation alias")
    }

    @Test
    fun `pair-swapped throughLink missing the other-side leading index is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput("NoLeadUser", NoLeadUser()),
                SchemaInput("NoLeadGroup", NoLeadGroup()),
                SchemaInput("NoLeadMembership", NoLeadMembership()),
            ))
        }
        assertContains(err.message!!, "no non-partial index leading with")
    }

    @Test
    fun `pair-swapped readOnly side missing its leading index is rejected`() {
        // Every two-sided declaration — writable OR .readOnly() — needs
        // its source-FK leading index, because a readOnly side still reads by
        // source. The readOnly `users` side here lacks a group_id-leading index.
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput("RoNoLeadUser", RoNoLeadUser()),
                SchemaInput("RoNoLeadGroup", RoNoLeadGroup()),
                SchemaInput("RoNoLeadMembership", RoNoLeadMembership()),
            ))
        }
        assertContains(err.message!!, "no non-partial index leading with")
        assertContains(err.message!!, "group_id")
    }

    @Test
    fun `pair-swapped writable plus readOnly side with both leading indexes is accepted`() {
        // Positive: the readOnly side has its group_id-leading index, so the
        // two-sided (writable + readOnly) declaration finalizes cleanly.
        EntGenerator("com.example.ent").generate(listOf(
            SchemaInput("RoLeadUser", RoLeadUser()),
            SchemaInput("RoLeadGroup", RoLeadGroup()),
            SchemaInput("RoLeadMembership", RoLeadMembership()),
        ))
    }

    @Test
    fun `mixed throughLink and throughEntity on same canonical identity is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput("MixedLinkUser", MixedLinkUser()),
                SchemaInput("MixedLinkGroup", MixedLinkGroup()),
                SchemaInput("MixedLinkMembership", MixedLinkMembership()),
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
            SchemaInput("MultiRelProject", MultiRelProject()),
            SchemaInput("MultiRelPet", MultiRelPet()),
            SchemaInput("MultiRelAssignment", MultiRelAssignment()),
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
            SchemaInput("LinkPost", LinkPost()),
            SchemaInput("LinkTag", LinkTag()),
            SchemaInput("LinkPostTag", LinkPostTag()),
        ))
    }

    @Test
    fun `throughLink junction with payload column is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput("PayloadLinkPost", PayloadLinkPost()),
                SchemaInput("PayloadLinkTag", PayloadLinkTag()),
                SchemaInput("PayloadLinkPostTag", PayloadLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "payload field")
        assertContains(err.message!!, "'nickname'")
    }

    @Test
    fun `throughLink junction with nullable belongsTo is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput("NullableLinkPost", NullableLinkPost()),
                SchemaInput("NullableLinkTag", NullableLinkTag()),
                SchemaInput("NullableLinkPostTag", NullableLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "is nullable")
        assertContains(err.message!!, "throughLink requires both junction belongsTo edges to be non-null")
    }

    @Test
    fun `throughLink junction without explicit OnDelete CASCADE is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput("NoCascadeLinkPost", NoCascadeLinkPost()),
                SchemaInput("NoCascadeLinkTag", NoCascadeLinkTag()),
                SchemaInput("NoCascadeLinkPostTag", NoCascadeLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "onDelete=")
        assertContains(err.message!!, "OnDelete.CASCADE")
    }

    @Test
    fun `throughLink junction without unique composite index is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput("NoIndexLinkPost", NoIndexLinkPost()),
                SchemaInput("NoIndexLinkTag", NoIndexLinkTag()),
                SchemaInput("NoIndexLinkPostTag", NoIndexLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "missing a non-partial unique composite index")
        assertContains(err.message!!, "(post_id, tag_id)")
    }

    @Test
    fun `throughLink junction with partial unique index is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput("PartialIdxLinkPost", PartialIdxLinkPost()),
                SchemaInput("PartialIdxLinkTag", PartialIdxLinkTag()),
                SchemaInput("PartialIdxLinkPostTag", PartialIdxLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "missing a non-partial unique composite index")
    }

    @Test
    fun `throughLink junction accepts a reverse-order unique pair index`() {
        // The unique pair index matches unordered, so (tag_id, post_id)
        // is accepted for a lone declaration just like (post_id, tag_id).
        EntGenerator("com.example.ent").generate(listOf(
            SchemaInput("ReverseOrderIdxLinkPost", ReverseOrderIdxLinkPost()),
            SchemaInput("ReverseOrderIdxLinkTag", ReverseOrderIdxLinkTag()),
            SchemaInput("ReverseOrderIdxLinkPostTag", ReverseOrderIdxLinkPostTag()),
        ))
    }

    @Test
    fun `throughLink junction with validator on FK backing field is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput("ValidatorBackingLinkPost", ValidatorBackingLinkPost()),
                SchemaInput("ValidatorBackingLinkTag", ValidatorBackingLinkTag()),
                SchemaInput("ValidatorBackingLinkPostTag", ValidatorBackingLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "validator")
    }

    @Test
    fun `throughLink junction with extra synthesized-FK belongsTo is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput("TenantLink", TenantLink()),
                SchemaInput("TenantedLinkPost", TenantedLinkPost()),
                SchemaInput("TenantedLinkTag", TenantedLinkTag()),
                SchemaInput("TenantedLinkPostTag", TenantedLinkPostTag()),
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
                SchemaInput("TenantLink", TenantLink()),
                SchemaInput("FieldBackedTenantedLinkPost", FieldBackedTenantedLinkPost()),
                SchemaInput("FieldBackedTenantedLinkTag", FieldBackedTenantedLinkTag()),
                SchemaInput("FieldBackedTenantedLinkPostTag", FieldBackedTenantedLinkPostTag()),
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
                SchemaInput("UniqueEdgeLinkPost", UniqueEdgeLinkPost()),
                SchemaInput("UniqueEdgeLinkTag", UniqueEdgeLinkTag()),
                SchemaInput("UniqueEdgeLinkPostTag", UniqueEdgeLinkPostTag()),
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
                SchemaInput("UniqueSingleIdxLinkPost", UniqueSingleIdxLinkPost()),
                SchemaInput("UniqueSingleIdxLinkTag", UniqueSingleIdxLinkTag()),
                SchemaInput("UniqueSingleIdxLinkPostTag", UniqueSingleIdxLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "unique single-column index")
        assertContains(err.message!!, "post_id")
    }

    @Test
    fun `throughLink junction with EXPLICIT id strategy is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            EntGenerator("com.example.ent").generate(listOf(
                SchemaInput("ExplicitIdLinkPost", ExplicitIdLinkPost()),
                SchemaInput("ExplicitIdLinkTag", ExplicitIdLinkTag()),
                SchemaInput("ExplicitIdLinkPostTag", ExplicitIdLinkPostTag()),
            ))
        }
        assertContains(err.message!!, "EXPLICIT")
    }

    // ---------- Per-group limit/offset in eager loading ----------

    @Test
    fun `to-many eager loading applies limit per group not globally`() {
        val (_, names, byName) = createAllSchemas()
        val output = QueryGenerator("com.example.ent")
            .generate("Owner", byName["Owner"]!!, names).toString().replace("\\s+".toRegex(), " ")

        // Batch fetch passes null,null limit/offset to the driver
        // (per-group pagination is applied below in Kotlin). The
        // orderBy comes from spec.orderBy after EAGER_LOAD
        // interceptors have run.
        assert(output.contains("subSpec.orderBy, null, null)")) {
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

        assert(output.contains("subSpec.orderBy, null, null)")) {
            "Target query should not pass limit/offset to driver\n$output"
        }
        assert(output.contains("perGroupOffset") && output.contains("perGroupLimit")) {
            "Should apply limit/offset per group\n$output"
        }
    }

    @Test
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

    @Test
    fun `M2M eager loading groups by iterating ordered target rows, not junction rows`() {
        // Iterating junctionRows here would group in driver-default
        // junction order — which is unrelated to `subQuery.orderFields`
        // — so a later `drop(offset).take(limit)` per group would pick
        // the wrong subset for `withTags { orderBy(...); limit(...) }`.
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

    @Test
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
