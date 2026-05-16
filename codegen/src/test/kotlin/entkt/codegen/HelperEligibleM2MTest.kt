package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.FieldType
import entkt.schema.OnDelete
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ---------- Helper-eligible throughLink schemas ----------

private class HePost : EntSchema("he_posts") {
    override fun id() = EntId.long()
    val title = string("title")
    val tags = manyToMany<HeTag>("tags")
        .throughLink<HePostTag>(HePostTag::post, HePostTag::tag)
}

private class HeTag : EntSchema("he_tags") {
    override fun id() = EntId.uuid()
    val name = string("name")
}

private class HePostTag : EntSchema("he_post_tags") {
    override fun id() = EntId.long()
    val post = belongsTo<HePost>("post").onDelete(OnDelete.CASCADE)
    val tag = belongsTo<HeTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_he_post_tags_post_tag", post.fk, tag.fk).unique()
}

// ---------- throughEntity schema (must NOT be helper-eligible) ----------

private class HeTeam : EntSchema("he_teams") {
    override fun id() = EntId.long()
    val members = manyToMany<HeMember>("members")
        .throughEntity<HeMembership>(HeMembership::team, HeMembership::member)
}

private class HeMember : EntSchema("he_members") {
    override fun id() = EntId.long()
}

private class HeMembership : EntSchema("he_memberships") {
    override fun id() = EntId.long()
    val joinedAt = time("joined_at")
    val team = belongsTo<HeTeam>("team")
    val member = belongsTo<HeMember>("member")
}

// ---------- Two helper-eligible throughLink edges on one source ----------

private class HeDoc : EntSchema("he_docs") {
    override fun id() = EntId.long()
    val tags = manyToMany<HeLabel>("tags")
        .throughLink<HeDocTag>(HeDocTag::doc, HeDocTag::tag)
    val labels = manyToMany<HeLabel>("labels")
        .throughLink<HeDocLabel>(HeDocLabel::doc, HeDocLabel::label)
}

private class HeLabel : EntSchema("he_labels") {
    override fun id() = EntId.long()
}

private class HeDocTag : EntSchema("he_doc_tags") {
    override fun id() = EntId.long()
    val doc = belongsTo<HeDoc>("doc").onDelete(OnDelete.CASCADE)
    val tag = belongsTo<HeLabel>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_he_doc_tags_doc_tag", doc.fk, tag.fk).unique()
}

private class HeDocLabel : EntSchema("he_doc_labels") {
    override fun id() = EntId.long()
    val doc = belongsTo<HeDoc>("doc").onDelete(OnDelete.CASCADE)
    val label = belongsTo<HeLabel>("label").onDelete(OnDelete.CASCADE)
    val pair = index("idx_he_doc_labels_doc_label", doc.fk, label.fk).unique()
}

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class HelperEligibleM2MTest {

    @Test
    fun `throughLink edge is helper-eligible and resolves junction shape`() {
        val post = HePost()
        val tag = HeTag()
        val pt = HePostTag()
        finalize(post, tag, pt)
        val names = mapOf(post to "HePost", tag to "HeTag", pt to "HePostTag")

        val eligible = helperEligibleM2MEdges(post, names)
        assertEquals(1, eligible.size, "expected one helper-eligible edge")

        val tagsEdge = eligible.single()
        assertEquals("tags", tagsEdge.edgeName)
        assertEquals("tags", tagsEdge.mutatorPropertyName)
        assertEquals("TagsEdgeMutator", tagsEdge.mutatorClassSimpleName)
        assertEquals("he_post_tags", tagsEdge.junctionTable)
        assertEquals("post_id", tagsEdge.junctionSourceColumn)
        assertEquals("tag_id", tagsEdge.junctionTargetColumn)
        assertEquals(FieldType.UUID, tagsEdge.targetIdType)
        // Pin the KotlinPoet TypeName carries the right target id type
        // so downstream codegen can use it directly as the mutator
        // parameter type.
        assertEquals("java.util.UUID", tagsEdge.targetIdTypeName.toString())
    }

    @Test
    fun `throughEntity edge is NOT helper-eligible`() {
        val team = HeTeam()
        val member = HeMember()
        val membership = HeMembership()
        finalize(team, member, membership)
        val names = mapOf(team to "HeTeam", member to "HeMember", membership to "HeMembership")

        val eligible = helperEligibleM2MEdges(team, names)
        assertEquals(emptyList(), eligible, "throughEntity M2M edges must not appear in helper-eligible set")
    }

    @Test
    fun `multiple throughLink edges on one source schema each get their own mutator entry`() {
        val doc = HeDoc()
        val label = HeLabel()
        val docTag = HeDocTag()
        val docLabel = HeDocLabel()
        finalize(doc, label, docTag, docLabel)
        val names = mapOf(
            doc to "HeDoc",
            label to "HeLabel",
            docTag to "HeDocTag",
            docLabel to "HeDocLabel",
        )

        val eligible = helperEligibleM2MEdges(doc, names)
        assertEquals(2, eligible.size, "expected two helper-eligible edges on HeDoc")

        val byName = eligible.associateBy { it.edgeName }
        val tags = assertNotNull(byName["tags"])
        val labels = assertNotNull(byName["labels"])

        // Naming follows the source edge, not the target type — so two
        // edges with the same target type don't collide on the mutator
        // class name (decision C in the RFC #5 plan).
        assertEquals("TagsEdgeMutator", tags.mutatorClassSimpleName)
        assertEquals("LabelsEdgeMutator", labels.mutatorClassSimpleName)
        assertTrue(tags.mutatorClassSimpleName != labels.mutatorClassSimpleName)

        // Each routes to its own junction table.
        assertEquals("he_doc_tags", tags.junctionTable)
        assertEquals("he_doc_labels", labels.junctionTable)
    }

    @Test
    fun `schema with no M2M edges returns empty list`() {
        val target = HeTag()
        finalize(target)
        val names = mapOf<EntSchema, String>(target to "HeTag")
        assertEquals(emptyList(), helperEligibleM2MEdges(target, names))
    }

    @Test
    fun `snake_case edge names become camelCase properties and PascalCase mutator class names`() {
        // Edge names from the schema DSL are typically snake_case
        // (`manyToMany<Tag>("primary_tags")`). The codebase elsewhere
        // applies toCamelCase / toPascalCase before splicing into
        // generated Kotlin identifiers; the mutator should follow the
        // same convention rather than leaking `primary_tags` /
        // `Primary_tagsEdgeMutator`.
        val post = SnakePost()
        val label = SnakeLabel()
        val pl = SnakePostLabel()
        finalize(post, label, pl)
        val names = mapOf<EntSchema, String>(post to "SnakePost", label to "SnakeLabel", pl to "SnakePostLabel")

        val eligible = helperEligibleM2MEdges(post, names).single()
        assertEquals("primary_tags", eligible.edgeName, "edgeName is the raw schema name (for metadata lookup)")
        assertEquals(
            "primaryTags", eligible.mutatorPropertyName,
            "mutatorPropertyName must be camelCase for idiomatic Kotlin DSL",
        )
        assertEquals(
            "PrimaryTagsEdgeMutator", eligible.mutatorClassSimpleName,
            "mutatorClassSimpleName must be PascalCase for idiomatic Kotlin",
        )
    }
}

// snake_case edge name fixture for the case-conversion test above.
private class SnakePost : EntSchema("snake_posts") {
    override fun id() = EntId.long()
    val tags = manyToMany<SnakeLabel>("primary_tags")
        .throughLink<SnakePostLabel>(SnakePostLabel::post, SnakePostLabel::label)
}
private class SnakeLabel : EntSchema("snake_labels") {
    override fun id() = EntId.long()
}
private class SnakePostLabel : EntSchema("snake_post_labels") {
    override fun id() = EntId.long()
    val post = belongsTo<SnakePost>("post").onDelete(OnDelete.CASCADE)
    val label = belongsTo<SnakeLabel>("label").onDelete(OnDelete.CASCADE)
    val pair = index("idx_snake_post_labels_pair", post.fk, label.fk).unique()
}
