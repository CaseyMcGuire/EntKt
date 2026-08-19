package entkt.codegen

import entkt.codegen.metadata.helperEligibleM2MEdges
import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.FieldType
import entkt.schema.OnDelete
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ---------- Helper-eligible throughLink schemas ----------

private class HePost : EntSchema("he_posts", clientName = "hePosts") {
    override fun id() = EntId.long()
    val title by string("title")
    val tags by manyToMany<HeTag>("tags")
        .throughLink<HePostTag>(HePostTag::post, HePostTag::tag)
}

private class HeTag : EntSchema("he_tags", clientName = "heTags") {
    override fun id() = EntId.uuid()
    val name by string("name")
}

private class HePostTag : EntSchema("he_post_tags", clientName = "hePostTags") {
    override fun id() = EntId.long()
    val post by belongsTo<HePost>("post").onDelete(OnDelete.CASCADE)
    val tag by belongsTo<HeTag>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_he_post_tags_post_tag", post.fk, tag.fk).unique()
}

// ---------- throughEntity schema (must NOT be helper-eligible) ----------

private class HeTeam : EntSchema("he_teams", clientName = "heTeams") {
    override fun id() = EntId.long()
    val members by manyToMany<HeMember>("members")
        .throughEntity<HeMembership>(HeMembership::team, HeMembership::member)
}

private class HeMember : EntSchema("he_members", clientName = "heMembers") {
    override fun id() = EntId.long()
}

private class HeMembership : EntSchema("he_memberships", clientName = "heMemberships") {
    override fun id() = EntId.long()
    val joinedAt by time("joined_at")
    val team by belongsTo<HeTeam>("team")
    val member by belongsTo<HeMember>("member")
}

// ---------- Two helper-eligible throughLink edges on one source ----------

private class HeDoc : EntSchema("he_docs", clientName = "heDocs") {
    override fun id() = EntId.long()
    val tags by manyToMany<HeLabel>("tags")
        .throughLink<HeDocTag>(HeDocTag::doc, HeDocTag::tag)
    val labels by manyToMany<HeLabel>("labels")
        .throughLink<HeDocLabel>(HeDocLabel::doc, HeDocLabel::label)
}

private class HeLabel : EntSchema("he_labels", clientName = "heLabels") {
    override fun id() = EntId.long()
}

private class HeDocTag : EntSchema("he_doc_tags", clientName = "heDocTags") {
    override fun id() = EntId.long()
    val doc by belongsTo<HeDoc>("doc").onDelete(OnDelete.CASCADE)
    val tag by belongsTo<HeLabel>("tag").onDelete(OnDelete.CASCADE)
    val pair = index("idx_he_doc_tags_doc_tag", doc.fk, tag.fk).unique()
}

private class HeDocLabel : EntSchema("he_doc_labels", clientName = "heDocLabels") {
    override fun id() = EntId.long()
    val doc by belongsTo<HeDoc>("doc").onDelete(OnDelete.CASCADE)
    val label by belongsTo<HeLabel>("label").onDelete(OnDelete.CASCADE)
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

        // Naming follows the source edge, not the target type, so two
        // edges with the same target type don't collide on the mutator
        // class name.
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
    fun `M2M mutator names come from the edge declaration, not its storage name`() {
        // The storage name is snake_case (`manyToMany<Tag>("primary_tags")`)
        // and the declaration is `tags`. The generated mutator follows the
        // declaration; the storage name stays the metadata lookup key.
        // Nothing converts `primary_tags` into a Kotlin identifier.
        val post = SnakePost()
        val label = SnakeLabel()
        val pl = SnakePostLabel()
        finalize(post, label, pl)
        val names = mapOf<EntSchema, String>(post to "SnakePost", label to "SnakeLabel", pl to "SnakePostLabel")

        val eligible = helperEligibleM2MEdges(post, names).single()
        assertEquals("primary_tags", eligible.edgeName, "edgeName is the storage name (for metadata lookup)")
        assertEquals(
            "tags", eligible.mutatorPropertyName,
            "mutatorPropertyName is the edge's Kotlin declaration name",
        )
        assertEquals(
            "TagsEdgeMutator", eligible.mutatorClassSimpleName,
            "mutator class is the declaration name's generated stem",
        )
    }
}

// snake_case edge name fixture for the case-conversion test above.
private class SnakePost : EntSchema("snake_posts", clientName = "snakePosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<SnakeLabel>("primary_tags")
        .throughLink<SnakePostLabel>(SnakePostLabel::post, SnakePostLabel::label)
}
private class SnakeLabel : EntSchema("snake_labels", clientName = "snakeLabels") {
    override fun id() = EntId.long()
}
private class SnakePostLabel : EntSchema("snake_post_labels", clientName = "snakePostLabels") {
    override fun id() = EntId.long()
    val post by belongsTo<SnakePost>("post").onDelete(OnDelete.CASCADE)
    val label by belongsTo<SnakeLabel>("label").onDelete(OnDelete.CASCADE)
    val pair = index("idx_snake_post_labels_pair", post.fk, label.fk).unique()
}
