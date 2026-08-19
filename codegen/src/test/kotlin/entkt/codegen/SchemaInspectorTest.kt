package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntMixin
import entkt.schema.EntSchema
import entkt.schema.FieldType
import entkt.schema.OnDelete
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private enum class InspPriority {
    LOW, HIGH;
    override fun toString() = name.lowercase() + "!!"
}

// ── Test schemas ────────────────────────────────────────────────────

private class InspAuthor : EntSchema("authors", clientName = "inspAuthors") {
    override fun id() = EntId.long()
    val name by string("name")
    val email by string("email").unique()
    val posts by hasMany<InspPost>("posts")
    val byEmail = index("idx_authors_email", email)
}

private class InspPost : EntSchema("posts", clientName = "inspPosts") {
    override fun id() = EntId.long()
    val title by string("title")
    val published by bool("published").default(false)
    val author by belongsTo<InspAuthor>("author").inverse(InspAuthor::posts)
}

private class InspProfile : EntSchema("profiles", clientName = "inspProfiles") {
    override fun id() = EntId.uuid()
    val bio by text("bio").nullable()
    val user by belongsTo<InspProfileUser>("user")
        .inverse(InspProfileUser::profile).unique()
}

private class InspProfileUser : EntSchema("profile_users", clientName = "inspProfileUsers") {
    override fun id() = EntId.uuid()
    val name by string("name")
    val profile by hasOne<InspProfile>("profile")
}

private class InspTag : EntSchema("tags", clientName = "inspTags") {
    override fun id() = EntId.int()
    val label by string("label")
    val articles by manyToMany<InspArticle>("articles")
        .throughEntity<InspArticleTag>(InspArticleTag::tag, InspArticleTag::article)
}

private class InspArticle : EntSchema("articles", clientName = "inspArticles") {
    override fun id() = EntId.int()
    val title by string("title")
}

private class InspArticleTag : EntSchema("article_tags", clientName = "inspArticleTags") {
    override fun id() = EntId.int()
    val articleId by int("article_id")
    val tagId by int("tag_id")
    val article by belongsTo<InspArticle>("article").field(articleId)
    val tag by belongsTo<InspTag>("tag").field(tagId)
}

// Pair-swapped throughLink fixtures: both declared sides are writable.
private class InspLinkPost : EntSchema("link_posts", clientName = "inspLinkPosts") {
    override fun id() = EntId.long()
    val tags by manyToMany<InspLinkTag>("tags")
        .throughLink<InspLinkPostTag>(InspLinkPostTag::post, InspLinkPostTag::tag)
}
private class InspLinkTag : EntSchema("link_tags", clientName = "inspLinkTags") {
    override fun id() = EntId.long()
    val posts by manyToMany<InspLinkPost>("posts")
        .throughLink<InspLinkPostTag>(InspLinkPostTag::tag, InspLinkPostTag::post)
}
private class InspLinkPostTag : EntSchema("link_post_tags", clientName = "inspLinkPostTags") {
    override fun id() = EntId.long()
    val post by belongsTo<InspLinkPost>("post").onDelete(OnDelete.CASCADE)
    val tag by belongsTo<InspLinkTag>("tag").onDelete(OnDelete.CASCADE)
    val byPost = index("idx_link_post_tags_post_tag", post.fk, tag.fk).unique()
    val byTag = index("idx_link_post_tags_tag_post", tag.fk, post.fk)
}

private class InspTimestamps(scope: EntMixin.Scope) : EntMixin(scope) {
    val createdAt by time("created_at").defaultNow().immutable()
    val updatedAt by time("updated_at").defaultNow().updateDefaultNow()
}

private class InspEvent : EntSchema("events", clientName = "inspEvents") {
    override fun id() = EntId.long()
    val timestamps = include(::InspTimestamps)
    val name by string("name")
    val deletedAt by time("deleted_at").nullable()
    val byCreatedAt = index("idx_events_created_at", timestamps.createdAt)
    val byDeletedAt = index("idx_events_deleted_at", deletedAt).where("deleted_at IS NULL")
}

// Schema pair for testing edge-driven uniqueness on reused FK fields
private class InspAccountHolder : EntSchema("account_holders", clientName = "inspAccountHolders") {
    override fun id() = EntId.int()
    val account by hasOne<InspAccount>("account")
}

private class InspAccount : EntSchema("accounts", clientName = "inspAccounts") {
    override fun id() = EntId.int()
    val holderId by int("holder_id") // field itself is NOT .unique()
    val holder by belongsTo<InspAccountHolder>("holder")
        .inverse(InspAccountHolder::account).unique().field(holderId)
}

private class InspCascadeParent : EntSchema("cascade_parents", clientName = "inspCascadeParents") {
    override fun id() = EntId.int()
    val children by hasMany<InspCascadeChild>("children")
}

private class InspCascadeChild : EntSchema("cascade_children", clientName = "inspCascadeChilds") {
    override fun id() = EntId.int()
    val parent by belongsTo<InspCascadeParent>("parent")
        .inverse(InspCascadeParent::children)
        .onDelete(OnDelete.CASCADE)
}

// ── Tests ───────────────────────────────────────────────────────────

class SchemaInspectorTest {

    private fun inputs(vararg schemas: EntSchema): List<SchemaInput> =
        schemas.map { SchemaInput(it) }

    // ── validate ────────────────────────────────────────────────────

    @Test
    fun `validate passes for a valid schema graph`() {
        val result = SchemaInspector.validate(inputs(InspAuthor(), InspPost()))
        assertTrue(result.valid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `validate catches duplicate table names`() {
        class DupA : EntSchema("shared", clientName = "dupAs") { override fun id() = EntId.int() }
        class DupB : EntSchema("shared", clientName = "dupBs") { override fun id() = EntId.int() }
        val result = SchemaInspector.validate(listOf(
            SchemaInput(DupA()),
            SchemaInput(DupB()),
        ))
        assertFalse(result.valid)
        assertContains(result.errors.first(), "shared")
    }

    @Test
    fun `validate catches unresolved inverse`() {
        // hasMany with no matching belongsTo on target
        class Orphan : EntSchema("orphans", clientName = "orphans") { override fun id() = EntId.int() }
        class Parent : EntSchema("parents", clientName = "parents") {
            override fun id() = EntId.int()
            val orphans by hasMany<Orphan>("orphans")
        }
        val result = SchemaInspector.validate(listOf(
            SchemaInput(Parent()),
            SchemaInput(Orphan()),
        ))
        assertFalse(result.valid)
        assertContains(result.errors.first(), "inverse")
    }

    @Test
    fun `validate collects multiple errors`() {
        // Two separate schemas each with an unresolved hasMany inverse.
        class TargetA : EntSchema("target_a", clientName = "targetAs") { override fun id() = EntId.int() }
        class TargetB : EntSchema("target_b", clientName = "targetBs") { override fun id() = EntId.int() }
        class BadParent : EntSchema("bad_parents", clientName = "badParents") {
            override fun id() = EntId.int()
            val a by hasMany<TargetA>("a_things")
            val b by hasMany<TargetB>("b_things")
        }
        val result = SchemaInspector.validate(listOf(
            SchemaInput(BadParent()),
            SchemaInput(TargetA()),
            SchemaInput(TargetB()),
        ))
        assertFalse(result.valid)
        assertTrue(result.errors.size >= 2, "expected multiple errors but got: ${result.errors}")
    }

    @Test
    fun `validate rejects table names PostgreSQL would truncate`() {
        // 64+ bytes: the server would silently truncate this to 63 and
        // the migration differ would never find it under its declared
        // name again.
        class LongTable : EntSchema(
            "this_table_name_is_deliberately_far_too_long_to_survive_the_postgres_identifier_limit",
            clientName = "longTables",
        ) { override fun id() = EntId.int() }
        val result = SchemaInspector.validate(listOf(SchemaInput(LongTable())))
        assertFalse(result.valid)
        assertContains(result.errors.first(), "truncates")
        assertContains(result.errors.first(), "table name")
    }

    @Test
    fun `validate rejects column names PostgreSQL would truncate`() {
        class LongColumn : EntSchema("long_columns", clientName = "longColumns") {
            override fun id() = EntId.int()
            val f by string(
                "this_column_name_is_deliberately_far_too_long_to_survive_the_postgres_identifier_limit",
            )
        }
        val result = SchemaInspector.validate(listOf(SchemaInput(LongColumn())))
        assertFalse(result.valid)
        assertContains(result.errors.first(), "truncates")
        assertContains(result.errors.first(), "column name")
    }

    @Test
    fun `explain rejects invalid schema graph`() {
        // hasMany with no matching belongsTo — validate catches this,
        // and explain should refuse to render rather than showing a
        // misleading "resolved" shape.
        class Orphan : EntSchema("orphans", clientName = "orphans") { override fun id() = EntId.int() }
        class Parent : EntSchema("parents", clientName = "parents") {
            override fun id() = EntId.int()
            val orphans by hasMany<Orphan>("orphans")
        }
        val err = assertFailsWith<IllegalStateException> {
            SchemaInspector.explain(listOf(
                SchemaInput(Parent()),
                SchemaInput(Orphan()),
            ))
        }
        assertContains(err.message!!, "Schema validation failed")
        assertContains(err.message!!, "inverse")
    }

    // ── explain: id ─────────────────────────────────────────────────

    @Test
    fun `explain captures id type and strategy`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val author = graph.schemas.first { it.schemaName == "InspAuthor" }
        assertEquals(FieldType.LONG, author.id.type)
        assertEquals("AUTO_LONG", author.id.strategy)
    }

    @Test
    fun `explain captures UUID id strategy`() {
        val graph = SchemaInspector.explain(inputs(InspProfileUser(), InspProfile()))
        val profile = graph.schemas.first { it.schemaName == "InspProfile" }
        assertEquals(FieldType.UUID, profile.id.type)
        assertEquals("CLIENT_UUID", profile.id.strategy)
    }

    // ── explain: fields ─────────────────────────────────────────────

    @Test
    fun `explain lists fields with types and modifiers`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val post = graph.schemas.first { it.schemaName == "InspPost" }

        assertEquals(2, post.fields.size)
        val title = post.fields[0]
        assertEquals("title", title.name)
        assertEquals(FieldType.STRING, title.type)
        assertFalse(title.nullable)

        val published = post.fields[1]
        assertEquals("published", published.name)
        assertEquals(FieldType.BOOL, published.type)
        assertFalse(published.nullable)
        assertEquals("false", published.default)
    }

    @Test
    fun `explain reports unique fields`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val author = graph.schemas.first { it.schemaName == "InspAuthor" }
        val email = author.fields.first { it.name == "email" }
        assertTrue(email.unique)
    }

    @Test
    fun `explain reports nullable fields`() {
        val graph = SchemaInspector.explain(inputs(InspProfileUser(), InspProfile()))
        val profile = graph.schemas.first { it.schemaName == "InspProfile" }
        val bio = profile.fields.first { it.name == "bio" }
        assertTrue(bio.nullable)
    }

    @Test
    fun `explain uses enum constant name for defaults, not toString`() {
        class Task : EntSchema("tasks", clientName = "tasks") {
            override fun id() = EntId.int()
            val priority by enum<InspPriority>("priority").default(InspPriority.LOW)
        }
        val graph = SchemaInspector.explain(listOf(SchemaInput(Task())))
        val field = graph.schemas[0].fields.first { it.name == "priority" }
        assertEquals("LOW", field.default) // not "low!!"
    }

    @Test
    fun `explain carries edge-driven uniqueness onto reused FK field`() {
        // holder_id field is NOT declared .unique(), but the edge adds .unique()
        val graph = SchemaInspector.explain(inputs(InspAccountHolder(), InspAccount()))
        val account = graph.schemas.first { it.schemaName == "InspAccount" }
        val holderId = account.fields.first { it.name == "holder_id" }
        assertTrue(holderId.unique, "edge .unique() should be reflected on the backing field")
    }

    // ── explain: foreign keys ───────────────────────────────────────

    @Test
    fun `explain lists synthesized FK on belongsTo`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val post = graph.schemas.first { it.schemaName == "InspPost" }

        assertEquals(1, post.foreignKeys.size)
        val fk = post.foreignKeys[0]
        assertEquals("author_id", fk.column)
        assertEquals("authors", fk.targetTable)
        assertEquals("id", fk.targetColumn)
        assertFalse(fk.nullable)
        assertEquals("RESTRICT", fk.onDelete)
        assertEquals("author", fk.sourceEdge)
    }

    @Test
    fun `explain lists explicit field FK on belongsTo`() {
        val graph = SchemaInspector.explain(inputs(
            InspTag(), InspArticle(), InspArticleTag(),
        ))
        val junction = graph.schemas.first { it.schemaName == "InspArticleTag" }

        assertEquals(2, junction.foreignKeys.size)
        val articleFk = junction.foreignKeys.first { it.sourceEdge == "article" }
        assertEquals("article_id", articleFk.column)
        assertEquals("articles", articleFk.targetTable)
        assertFalse(articleFk.nullable)
    }

    @Test
    fun `explain shows onDelete CASCADE`() {
        val graph = SchemaInspector.explain(inputs(InspCascadeParent(), InspCascadeChild()))
        val child = graph.schemas.first { it.schemaName == "InspCascadeChild" }
        val fk = child.foreignKeys[0]
        assertEquals("CASCADE", fk.onDelete)
    }

    @Test
    fun `explain shows nullable FK for optional belongsTo`() {
        class Target : EntSchema("targets", clientName = "targets") { override fun id() = EntId.int() }
        class Source : EntSchema("sources", clientName = "sources") {
            override fun id() = EntId.int()
            val target by belongsTo<Target>("target").nullable()
        }
        val graph = SchemaInspector.explain(listOf(
            SchemaInput(Target()),
            SchemaInput(Source()),
        ))
        val source = graph.schemas.first { it.schemaName == "Source" }
        val fk = source.foreignKeys[0]
        assertTrue(fk.nullable)
        assertEquals("SET_NULL", fk.onDelete)
    }

    // ── explain: edges ──────────────────────────────────────────────

    @Test
    fun `explain captures belongsTo edge with FK and inverse`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val post = graph.schemas.first { it.schemaName == "InspPost" }
        val authorEdge = post.edges.first { it.name == "author" }
        assertEquals("belongsTo", authorEdge.kind)
        assertEquals("InspAuthor", authorEdge.targetSchema)
        assertEquals("author_id", authorEdge.fkColumn)
        assertEquals("posts", authorEdge.inverse)
        assertNull(authorEdge.through)
    }

    @Test
    fun `explain captures hasMany edge with inverse`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val author = graph.schemas.first { it.schemaName == "InspAuthor" }
        val postsEdge = author.edges.first { it.name == "posts" }
        assertEquals("hasMany", postsEdge.kind)
        assertEquals("InspPost", postsEdge.targetSchema)
        assertNull(postsEdge.fkColumn)
        assertEquals("author", postsEdge.inverse)
    }

    @Test
    fun `explain captures hasOne edge`() {
        val graph = SchemaInspector.explain(inputs(InspProfileUser(), InspProfile()))
        val user = graph.schemas.first { it.schemaName == "InspProfileUser" }
        val profileEdge = user.edges.first { it.name == "profile" }
        assertEquals("hasOne", profileEdge.kind)
        assertEquals("InspProfile", profileEdge.targetSchema)
    }

    @Test
    fun `explain captures manyToMany edge with through`() {
        val graph = SchemaInspector.explain(inputs(
            InspTag(), InspArticle(), InspArticleTag(),
        ))
        val tag = graph.schemas.first { it.schemaName == "InspTag" }
        val articlesEdge = tag.edges.first { it.name == "articles" }
        assertEquals("manyToMany", articlesEdge.kind)
        assertEquals("InspArticle", articlesEdge.targetSchema)

        val through = articlesEdge.through!!
        assertEquals("article_tags", through.junctionTable)
        assertEquals("tag", through.sourceEdge)
        assertEquals("article", through.targetEdge)
        // throughEntity has no link-table write helpers.
        assertNull(through.writeHelpers)
    }

    @Test
    fun `explain reports the add-remove-set write surface for a writable throughLink side`() {
        val graph = SchemaInspector.explain(inputs(
            InspLinkPost(), InspLinkTag(), InspLinkPostTag(),
        ))
        val post = graph.schemas.first { it.schemaName == "InspLinkPost" }
        val tagsEdge = post.edges.first { it.name == "tags" }
        assertEquals(listOf("add", "remove", "set"), tagsEdge.through!!.writeHelpers)
    }

    @Test
    fun `explain reports the write surface for both pair-swapped throughLink sides`() {
        val graph = SchemaInspector.explain(inputs(
            InspLinkPost(), InspLinkTag(), InspLinkPostTag(),
        ))
        val tag = graph.schemas.first { it.schemaName == "InspLinkTag" }
        val postsEdge = tag.edges.first { it.name == "posts" }
        assertEquals("manyToMany", postsEdge.kind)
        assertEquals(listOf("add", "remove", "set"), postsEdge.through!!.writeHelpers)
    }

    @Test
    fun `explain does not synthesize reverse manyToMany edges on target schema`() {
        // M2M schema modeling (post-revert): no auto-synthesized reverse edges. The
        // explain output reflects only declared edges.
        val graph = SchemaInspector.explain(inputs(
            InspTag(), InspArticle(), InspArticleTag(),
        ))
        val article = graph.schemas.first { it.schemaName == "InspArticle" }
        assertNull(article.edges.firstOrNull { it.name == "tags_articles" })
    }

    // ── explain: indexes ────────────────────────────────────────────

    @Test
    fun `explain lists explicit and synthesized indexes`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val author = graph.schemas.first { it.schemaName == "InspAuthor" }

        assertEquals(2, author.indexes.size)

        val explicit = author.indexes[0]
        assertEquals("idx_authors_email", explicit.name)
        assertEquals(listOf("email"), explicit.columns)
        assertFalse(explicit.unique)
        assertNull(explicit.where)

        // Synthesized unique index from email.unique()
        val synthesized = author.indexes[1]
        assertEquals("idx_authors_email_unique", synthesized.name)
        assertEquals(listOf("email"), synthesized.columns)
        assertTrue(synthesized.unique)
    }

    @Test
    fun `explain includes synthesized unique index for unique edge FK`() {
        val graph = SchemaInspector.explain(inputs(InspProfileUser(), InspProfile()))
        val profile = graph.schemas.first { it.schemaName == "InspProfile" }

        val synthIdx = profile.indexes.first { it.name == "idx_profiles_user_id_unique" }
        assertEquals(listOf("user_id"), synthIdx.columns)
        assertTrue(synthIdx.unique)
    }

    @Test
    fun `explain shows partial index with WHERE clause`() {
        val graph = SchemaInspector.explain(inputs(InspEvent()))
        val event = graph.schemas.first { it.schemaName == "InspEvent" }

        val partialIdx = event.indexes.first { it.name == "idx_events_deleted_at" }
        assertEquals(listOf("deleted_at"), partialIdx.columns)
        assertEquals("deleted_at IS NULL", partialIdx.where)
    }

    // ── explain: mixins ─────────────────────────────────────────────

    @Test
    fun `explain includes mixin fields`() {
        val graph = SchemaInspector.explain(inputs(InspEvent()))
        val event = graph.schemas.first { it.schemaName == "InspEvent" }

        val fieldNames = event.fields.map { it.name }
        assertContains(fieldNames, "created_at")
        assertContains(fieldNames, "updated_at")
        assertContains(fieldNames, "name")
        assertContains(fieldNames, "deleted_at")

        val createdAt = event.fields.first { it.name == "created_at" }
        assertTrue(createdAt.immutable)
        assertEquals("now", createdAt.default)
    }

    @Test
    fun `explain indexes can reference mixin fields`() {
        val graph = SchemaInspector.explain(inputs(InspEvent()))
        val event = graph.schemas.first { it.schemaName == "InspEvent" }

        val idx = event.indexes.first { it.name == "idx_events_created_at" }
        assertEquals(listOf("created_at"), idx.columns)
    }

    // ── explain: schema ordering ────────────────────────────────────

    @Test
    fun `explain preserves input order`() {
        val graph = SchemaInspector.explain(inputs(InspPost(), InspAuthor()))
        assertEquals("InspPost", graph.schemas[0].schemaName)
        assertEquals("InspAuthor", graph.schemas[1].schemaName)
    }

    // ── renderText ──────────────────────────────────────────────────

    @Test
    fun `renderText produces expected format`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val text = SchemaInspector.renderText(graph)

        // Header row
        assertContains(text, "Schema: InspAuthor")
        assertContains(text, "Table: authors")
        assertContains(text, "Id: LONG (AUTO_LONG)")

        // Fields table with | delimiters and --- separators
        assertContains(text, "Fields:")
        assertContains(text, "| Name")
        assertContains(text, "| name")
        assertContains(text, "STRING")
        assertContains(text, "NOT NULL")
        assertContains(text, "|---")

        // Edges table
        assertContains(text, "Edges:")
        assertContains(text, "| hasMany")
        assertContains(text, "| InspPost")

        // Foreign Keys table on Post
        assertContains(text, "Schema: InspPost")
        assertContains(text, "Foreign Keys:")
        assertContains(text, "| author_id")
        assertContains(text, "authors.id")
        assertContains(text, "RESTRICT")
        assertContains(text, "| belongsTo")
        assertContains(text, "fk=author_id")

        // Indexes table
        assertContains(text, "Indexes:")
        assertContains(text, "| idx_authors_email")
    }

    @Test
    fun `renderText omits empty sections`() {
        class Bare : EntSchema("bare", clientName = "bares") { override fun id() = EntId.int() }
        val graph = SchemaInspector.explain(listOf(SchemaInput(Bare())))
        val text = SchemaInspector.renderText(graph)

        assertContains(text, "Schema: Bare")
        assertContains(text, "Id: INT (AUTO_INT)")
        assertFalse(text.contains("Fields:"))
        assertFalse(text.contains("Foreign Keys:"))
        assertFalse(text.contains("Edges:"))
        assertFalse(text.contains("Indexes:"))
    }

    @Test
    fun `renderText includes throughLink write helpers on both declared sides`() {
        val graph = SchemaInspector.explain(inputs(
            InspLinkPost(), InspLinkTag(), InspLinkPostTag(),
        ))
        val text = SchemaInspector.renderText(graph)

        assertContains(text, "through=link_post_tags(post, tag), helpers=[add, remove, set]")
        assertContains(text, "through=link_post_tags(tag, post), helpers=[add, remove, set]")
    }

    // ── renderJson ──────────────────────────────────────────────────

    @Test
    fun `renderJson produces valid structure`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val json = SchemaInspector.renderJson(graph)

        assertContains(json, "\"schemas\":")
        assertContains(json, "\"schemaName\": \"InspAuthor\"")
        assertContains(json, "\"tableName\": \"authors\"")
        assertContains(json, "\"strategy\": \"AUTO_LONG\"")
        assertContains(json, "\"fields\":")
        assertContains(json, "\"name\": \"email\"")
        assertContains(json, "\"unique\": true")
    }

    @Test
    fun `renderJson includes foreign keys and edges`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val json = SchemaInspector.renderJson(graph)

        // FK on Post
        assertContains(json, "\"column\": \"author_id\"")
        assertContains(json, "\"targetTable\": \"authors\"")
        assertContains(json, "\"onDelete\": \"RESTRICT\"")

        // Edge
        assertContains(json, "\"kind\": \"belongsTo\"")
        assertContains(json, "\"fkColumn\": \"author_id\"")
        assertContains(json, "\"inverse\": \"posts\"")
    }

    @Test
    fun `renderJson includes manyToMany through`() {
        val graph = SchemaInspector.explain(inputs(
            InspTag(), InspArticle(), InspArticleTag(),
        ))
        val json = SchemaInspector.renderJson(graph)

        assertContains(json, "\"kind\": \"manyToMany\"")
        assertContains(json, "\"junctionTable\": \"article_tags\"")
        assertContains(json, "\"sourceEdge\": \"tag\"")
        assertContains(json, "\"targetEdge\": \"article\"")
    }

    @Test
    fun `renderJson includes throughLink write helpers`() {
        val graph = SchemaInspector.explain(inputs(
            InspLinkPost(), InspLinkTag(), InspLinkPostTag(),
        ))
        val json = SchemaInspector.renderJson(graph)

        assertContains(json, "\"writeHelpers\": [\"add\", \"remove\", \"set\"]")
    }

    @Test
    fun `renderJson empty schema has empty arrays`() {
        class Bare : EntSchema("bare", clientName = "bares") { override fun id() = EntId.int() }
        val graph = SchemaInspector.explain(listOf(SchemaInput(Bare())))
        val json = SchemaInspector.renderJson(graph)

        assertContains(json, "\"fields\": [")
        assertContains(json, "\"foreignKeys\": [")
        assertContains(json, "\"edges\": [")
        assertContains(json, "\"indexes\": [")
    }

    // ── Filter ─────────────────────────────────────────────────────

    @Test
    fun `filter by schema name`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val filtered = SchemaInspector.filter(graph, "InspAuthor")
        assertEquals(1, filtered.schemas.size)
        assertEquals("InspAuthor", filtered.schemas[0].schemaName)
    }

    @Test
    fun `filter by table name`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val filtered = SchemaInspector.filter(graph, "posts")
        assertEquals(1, filtered.schemas.size)
        assertEquals("InspPost", filtered.schemas[0].schemaName)
    }

    @Test
    fun `filter is case-insensitive`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val filtered = SchemaInspector.filter(graph, "author")
        assertEquals(1, filtered.schemas.size)
        assertEquals("InspAuthor", filtered.schemas[0].schemaName)
    }

    @Test
    fun `filter matches substring`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val filtered = SchemaInspector.filter(graph, "ost")
        assertEquals(1, filtered.schemas.size)
        assertEquals("InspPost", filtered.schemas[0].schemaName)
    }

    @Test
    fun `filter null or blank returns full graph`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        assertEquals(2, SchemaInspector.filter(graph, null).schemas.size)
        assertEquals(2, SchemaInspector.filter(graph, "").schemas.size)
        assertEquals(2, SchemaInspector.filter(graph, "  ").schemas.size)
    }

    @Test
    fun `filter no match returns empty graph`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val filtered = SchemaInspector.filter(graph, "nonexistent")
        assertTrue(filtered.schemas.isEmpty())
    }

    @Test
    fun `explain distinguishes declaration, client, and storage identities`() {
        // Every name diverges, so any surface that collapsed two of the
        // three identities would show the wrong string here.
        val graph = SchemaInspector.explain(inputs(DivergentUser(), DivergentDoc()))
        val doc = graph.schemas.first { it.schemaName == "DivergentDoc" }

        assertEquals("DivergentDoc", doc.schemaName)
        assertEquals("stories", doc.clientName)
        assertEquals("legacy_doc_tbl", doc.tableName)

        val title = doc.fields.first { it.apiName == "publicTitle" }
        assertEquals("legacy_title_txt", title.name)

        val edge = doc.edges.first { it.apiName == "writer" }
        assertEquals("legacy_author", edge.name)

        val fk = doc.foreignKeys.first { it.sourceEdgeApiName == "writer" }
        assertEquals("legacy_author", fk.sourceEdge)
        assertEquals("writerId", fk.propertyName)
        assertEquals("legacy_author_id", fk.column)

        // Both identities reach the rendered output.
        val text = SchemaInspector.renderText(graph)
        assertContains(text, "Client: stories")
        assertContains(text, "publicTitle")
        assertContains(text, "legacy_title_txt")

        val json = SchemaInspector.renderJson(graph)
        assertContains(json, "\"clientName\": \"stories\"")
        assertContains(json, "\"apiName\": \"publicTitle\"")
        assertContains(json, "\"name\": \"legacy_title_txt\"")
    }
}

private class DivergentUser : EntSchema("legacy_user_tbl", clientName = "people") {
    override fun id() = EntId.long()
    val displayName by string("legacy_name_txt")
}

private class DivergentDoc : EntSchema("legacy_doc_tbl", clientName = "stories") {
    override fun id() = EntId.long()
    val publicTitle by string("legacy_title_txt")
    val writer by belongsTo<DivergentUser>("legacy_author")
}
