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

private class InspAuthor : EntSchema("authors") {
    override fun id() = EntId.long()
    val name = string("name")
    val email = string("email").unique()
    val posts = hasMany<InspPost>("posts")
    val byEmail = index("idx_authors_email", email)
}

private class InspPost : EntSchema("posts") {
    override fun id() = EntId.long()
    val title = string("title")
    val published = bool("published").default(false)
    val author = belongsTo<InspAuthor>("author").inverse(InspAuthor::posts).required()
}

private class InspProfile : EntSchema("profiles") {
    override fun id() = EntId.uuid()
    val bio = text("bio").optional()
    val user = belongsTo<InspProfileUser>("user")
        .inverse(InspProfileUser::profile).required().unique()
}

private class InspProfileUser : EntSchema("profile_users") {
    override fun id() = EntId.uuid()
    val name = string("name")
    val profile = hasOne<InspProfile>("profile")
}

private class InspTag : EntSchema("tags") {
    override fun id() = EntId.int()
    val label = string("label")
    val articles = manyToMany<InspArticle>("articles")
        .through<InspArticleTag>(InspArticleTag::tag, InspArticleTag::article)
}

private class InspArticle : EntSchema("articles") {
    override fun id() = EntId.int()
    val title = string("title")
}

private class InspArticleTag : EntSchema("article_tags") {
    override fun id() = EntId.int()
    val articleId = int("article_id")
    val tagId = int("tag_id")
    val article = belongsTo<InspArticle>("article").required().field(articleId)
    val tag = belongsTo<InspTag>("tag").required().field(tagId)
}

private class InspTimestamps(scope: EntMixin.Scope) : EntMixin(scope) {
    val createdAt = time("created_at").defaultNow().immutable()
    val updatedAt = time("updated_at").defaultNow().updateDefaultNow()
}

private class InspEvent : EntSchema("events") {
    override fun id() = EntId.long()
    val timestamps = include(::InspTimestamps)
    val name = string("name")
    val deletedAt = time("deleted_at").nullable()
    val byCreatedAt = index("idx_events_created_at", timestamps.createdAt)
    val byDeletedAt = index("idx_events_deleted_at", deletedAt).where("deleted_at IS NULL")
}

// Schema pair for testing edge-driven uniqueness on reused FK fields
private class InspAccountHolder : EntSchema("account_holders") {
    override fun id() = EntId.int()
    val account = hasOne<InspAccount>("account")
}

private class InspAccount : EntSchema("accounts") {
    override fun id() = EntId.int()
    val holderId = int("holder_id") // field itself is NOT .unique()
    val holder = belongsTo<InspAccountHolder>("holder")
        .inverse(InspAccountHolder::account).required().unique().field(holderId)
}

// Schemas for reverse M2M edge name collision test.
// Owner.items is manyToMany → reverse entry on InspColItem will be "col_owners_items".
// InspColItem declares a belongsTo edge with that exact name to trigger the collision.
private class InspColItem : EntSchema("col_items") {
    override fun id() = EntId.int()
    val col_owners_items = belongsTo<InspColItem>("col_owners_items")
}

private class InspColJunction : EntSchema("col_owner_items") {
    override fun id() = EntId.int()
    val ownerId = int("owner_id")
    val itemId = int("item_id")
    val owner = belongsTo<InspColOwner>("owner").required().field(ownerId)
    val item = belongsTo<InspColItem>("item").required().field(itemId)
}

private class InspColOwner : EntSchema("col_owners") {
    override fun id() = EntId.int()
    val items = manyToMany<InspColItem>("items")
        .through<InspColJunction>(InspColJunction::owner, InspColJunction::item)
}

// Schemas for duplicate reverse M2M name test.
// Table "dup_foo_bar" with edge "baz" and table "dup_foo" with edge "bar_baz"
// both produce reverse name "dup_foo_bar_baz" on the shared target.
private class InspDupM2MTarget : EntSchema("dup_target") {
    override fun id() = EntId.int()
}

private class InspDupM2MJunctionA : EntSchema("dup_junc_a") {
    override fun id() = EntId.int()
    val srcId = int("src_id")
    val tgtId = int("tgt_id")
    val src = belongsTo<InspDupM2MSourceA>("src").required().field(srcId)
    val tgt = belongsTo<InspDupM2MTarget>("tgt").required().field(tgtId)
}

private class InspDupM2MJunctionB : EntSchema("dup_junc_b") {
    override fun id() = EntId.int()
    val srcId = int("src_id")
    val tgtId = int("tgt_id")
    val src = belongsTo<InspDupM2MSourceB>("src").required().field(srcId)
    val tgt = belongsTo<InspDupM2MTarget>("tgt").required().field(tgtId)
}

// table "dup_foo_bar", edge "baz" → reverse name "dup_foo_bar_baz"
private class InspDupM2MSourceA : EntSchema("dup_foo_bar") {
    override fun id() = EntId.int()
    val baz = manyToMany<InspDupM2MTarget>("baz")
        .through<InspDupM2MJunctionA>(InspDupM2MJunctionA::src, InspDupM2MJunctionA::tgt)
}

// table "dup_foo", edge "bar_baz" → reverse name "dup_foo_bar_baz"
private class InspDupM2MSourceB : EntSchema("dup_foo") {
    override fun id() = EntId.int()
    val bar_baz = manyToMany<InspDupM2MTarget>("bar_baz")
        .through<InspDupM2MJunctionB>(InspDupM2MJunctionB::src, InspDupM2MJunctionB::tgt)
}

private class InspCascadeParent : EntSchema("cascade_parents") {
    override fun id() = EntId.int()
    val children = hasMany<InspCascadeChild>("children")
}

private class InspCascadeChild : EntSchema("cascade_children") {
    override fun id() = EntId.int()
    val parent = belongsTo<InspCascadeParent>("parent")
        .inverse(InspCascadeParent::children)
        .required()
        .onDelete(OnDelete.CASCADE)
}

// ── Tests ───────────────────────────────────────────────────────────

class SchemaInspectorTest {

    private fun inputs(vararg schemas: EntSchema): List<SchemaInput> =
        schemas.map { SchemaInput(it::class.simpleName!!.removePrefix("Insp"), it) }

    // ── validate ────────────────────────────────────────────────────

    @Test
    fun `validate passes for a valid schema graph`() {
        val result = SchemaInspector.validate(inputs(InspAuthor(), InspPost()))
        assertTrue(result.valid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `validate catches duplicate table names`() {
        class DupA : EntSchema("shared") { override fun id() = EntId.int() }
        class DupB : EntSchema("shared") { override fun id() = EntId.int() }
        val result = SchemaInspector.validate(listOf(
            SchemaInput("DupA", DupA()),
            SchemaInput("DupB", DupB()),
        ))
        assertFalse(result.valid)
        assertContains(result.errors.first(), "shared")
    }

    @Test
    fun `validate catches reverse M2M edge name collision`() {
        // InspColOwner.items is M2M → reverse entry on InspColItem is "col_owners_items".
        // InspColItem declares a forward edge with that exact name.
        val result = SchemaInspector.validate(listOf(
            SchemaInput("ColOwner", InspColOwner()),
            SchemaInput("ColItem", InspColItem()),
            SchemaInput("ColJunction", InspColJunction()),
        ))
        assertFalse(result.valid)
        assertContains(result.errors.first(), "reverse M2M edge")
        assertContains(result.errors.first(), "col_owners_items")
    }

    @Test
    fun `validate catches duplicate reverse M2M edge names`() {
        // Two sources both produce reverse name "dup_foo_bar_baz" on the same target.
        val result = SchemaInspector.validate(listOf(
            SchemaInput("DupTarget", InspDupM2MTarget()),
            SchemaInput("DupSourceA", InspDupM2MSourceA()),
            SchemaInput("DupSourceB", InspDupM2MSourceB()),
            SchemaInput("DupJuncA", InspDupM2MJunctionA()),
            SchemaInput("DupJuncB", InspDupM2MJunctionB()),
        ))
        assertFalse(result.valid)
        assertContains(result.errors.first(), "duplicate reverse M2M edge")
        assertContains(result.errors.first(), "dup_foo_bar_baz")
    }

    @Test
    fun `validate catches unresolved inverse`() {
        // hasMany with no matching belongsTo on target
        class Orphan : EntSchema("orphans") { override fun id() = EntId.int() }
        class Parent : EntSchema("parents") {
            override fun id() = EntId.int()
            val orphans = hasMany<Orphan>("orphans")
        }
        val result = SchemaInspector.validate(listOf(
            SchemaInput("Parent", Parent()),
            SchemaInput("Orphan", Orphan()),
        ))
        assertFalse(result.valid)
        assertContains(result.errors.first(), "inverse")
    }

    @Test
    fun `validate collects multiple errors`() {
        // Two separate schemas each with an unresolved hasMany inverse.
        class TargetA : EntSchema("target_a") { override fun id() = EntId.int() }
        class TargetB : EntSchema("target_b") { override fun id() = EntId.int() }
        class BadParent : EntSchema("bad_parents") {
            override fun id() = EntId.int()
            val a = hasMany<TargetA>("a_things")
            val b = hasMany<TargetB>("b_things")
        }
        val result = SchemaInspector.validate(listOf(
            SchemaInput("BadParent", BadParent()),
            SchemaInput("TargetA", TargetA()),
            SchemaInput("TargetB", TargetB()),
        ))
        assertFalse(result.valid)
        assertTrue(result.errors.size >= 2, "expected multiple errors but got: ${result.errors}")
    }

    @Test
    fun `explain rejects invalid schema graph`() {
        // hasMany with no matching belongsTo — validate catches this,
        // and explain should refuse to render rather than showing a
        // misleading "resolved" shape.
        class Orphan : EntSchema("orphans") { override fun id() = EntId.int() }
        class Parent : EntSchema("parents") {
            override fun id() = EntId.int()
            val orphans = hasMany<Orphan>("orphans")
        }
        val err = assertFailsWith<IllegalStateException> {
            SchemaInspector.explain(listOf(
                SchemaInput("Parent", Parent()),
                SchemaInput("Orphan", Orphan()),
            ))
        }
        assertContains(err.message!!, "Schema validation failed")
        assertContains(err.message!!, "inverse")
    }

    // ── explain: id ─────────────────────────────────────────────────

    @Test
    fun `explain captures id type and strategy`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val author = graph.schemas.first { it.schemaName == "Author" }
        assertEquals(FieldType.LONG, author.id.type)
        assertEquals("AUTO_LONG", author.id.strategy)
    }

    @Test
    fun `explain captures UUID id strategy`() {
        val graph = SchemaInspector.explain(inputs(InspProfileUser(), InspProfile()))
        val profile = graph.schemas.first { it.schemaName == "Profile" }
        assertEquals(FieldType.UUID, profile.id.type)
        assertEquals("CLIENT_UUID", profile.id.strategy)
    }

    // ── explain: fields ─────────────────────────────────────────────

    @Test
    fun `explain lists fields with types and modifiers`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val post = graph.schemas.first { it.schemaName == "Post" }

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
        val author = graph.schemas.first { it.schemaName == "Author" }
        val email = author.fields.first { it.name == "email" }
        assertTrue(email.unique)
    }

    @Test
    fun `explain reports nullable fields`() {
        val graph = SchemaInspector.explain(inputs(InspProfileUser(), InspProfile()))
        val profile = graph.schemas.first { it.schemaName == "Profile" }
        val bio = profile.fields.first { it.name == "bio" }
        assertTrue(bio.nullable)
    }

    @Test
    fun `explain uses enum constant name for defaults, not toString`() {
        class Task : EntSchema("tasks") {
            override fun id() = EntId.int()
            val priority = enum<InspPriority>("priority").default(InspPriority.LOW)
        }
        val graph = SchemaInspector.explain(listOf(SchemaInput("Task", Task())))
        val field = graph.schemas[0].fields.first { it.name == "priority" }
        assertEquals("LOW", field.default) // not "low!!"
    }

    @Test
    fun `explain carries edge-driven uniqueness onto reused FK field`() {
        // holder_id field is NOT declared .unique(), but the edge adds .unique()
        val graph = SchemaInspector.explain(inputs(InspAccountHolder(), InspAccount()))
        val account = graph.schemas.first { it.schemaName == "Account" }
        val holderId = account.fields.first { it.name == "holder_id" }
        assertTrue(holderId.unique, "edge .unique() should be reflected on the backing field")
    }

    // ── explain: foreign keys ───────────────────────────────────────

    @Test
    fun `explain lists synthesized FK on belongsTo`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val post = graph.schemas.first { it.schemaName == "Post" }

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
        val junction = graph.schemas.first { it.schemaName == "ArticleTag" }

        assertEquals(2, junction.foreignKeys.size)
        val articleFk = junction.foreignKeys.first { it.sourceEdge == "article" }
        assertEquals("article_id", articleFk.column)
        assertEquals("articles", articleFk.targetTable)
        assertFalse(articleFk.nullable)
    }

    @Test
    fun `explain shows onDelete CASCADE`() {
        val graph = SchemaInspector.explain(inputs(InspCascadeParent(), InspCascadeChild()))
        val child = graph.schemas.first { it.schemaName == "CascadeChild" }
        val fk = child.foreignKeys[0]
        assertEquals("CASCADE", fk.onDelete)
    }

    @Test
    fun `explain shows nullable FK for optional belongsTo`() {
        class Target : EntSchema("targets") { override fun id() = EntId.int() }
        class Source : EntSchema("sources") {
            override fun id() = EntId.int()
            val target = belongsTo<Target>("target") // not required → nullable FK
        }
        val graph = SchemaInspector.explain(listOf(
            SchemaInput("Target", Target()),
            SchemaInput("Source", Source()),
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
        val post = graph.schemas.first { it.schemaName == "Post" }
        val authorEdge = post.edges.first { it.name == "author" }
        assertEquals("belongsTo", authorEdge.kind)
        assertEquals("Author", authorEdge.targetSchema)
        assertEquals("author_id", authorEdge.fkColumn)
        assertEquals("posts", authorEdge.inverse)
        assertNull(authorEdge.through)
    }

    @Test
    fun `explain captures hasMany edge with inverse`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val author = graph.schemas.first { it.schemaName == "Author" }
        val postsEdge = author.edges.first { it.name == "posts" }
        assertEquals("hasMany", postsEdge.kind)
        assertEquals("Post", postsEdge.targetSchema)
        assertNull(postsEdge.fkColumn)
        assertEquals("author", postsEdge.inverse)
    }

    @Test
    fun `explain captures hasOne edge`() {
        val graph = SchemaInspector.explain(inputs(InspProfileUser(), InspProfile()))
        val user = graph.schemas.first { it.schemaName == "ProfileUser" }
        val profileEdge = user.edges.first { it.name == "profile" }
        assertEquals("hasOne", profileEdge.kind)
        assertEquals("Profile", profileEdge.targetSchema)
    }

    @Test
    fun `explain captures manyToMany edge with through`() {
        val graph = SchemaInspector.explain(inputs(
            InspTag(), InspArticle(), InspArticleTag(),
        ))
        val tag = graph.schemas.first { it.schemaName == "Tag" }
        val articlesEdge = tag.edges.first { it.name == "articles" }
        assertEquals("manyToMany", articlesEdge.kind)
        assertEquals("Article", articlesEdge.targetSchema)

        val through = articlesEdge.through!!
        assertEquals("article_tags", through.junctionTable)
        assertEquals("tag", through.sourceEdge)
        assertEquals("article", through.targetEdge)
    }

    @Test
    fun `explain includes reverse manyToMany edges on target schema`() {
        val graph = SchemaInspector.explain(inputs(
            InspTag(), InspArticle(), InspArticleTag(),
        ))
        // Tag declares manyToMany("articles", Article) → Article should get
        // a synthesized reverse edge "tags_articles"
        val article = graph.schemas.first { it.schemaName == "Article" }
        val reverseEdge = article.edges.firstOrNull { it.name == "tags_articles" }
        assertNotNull(reverseEdge)
        assertEquals("manyToMany", reverseEdge.kind)
        assertEquals("Tag", reverseEdge.targetSchema)
        assertNotNull(reverseEdge.through)
        assertEquals("article_tags", reverseEdge.through!!.junctionTable)
    }

    // ── explain: indexes ────────────────────────────────────────────

    @Test
    fun `explain lists explicit and synthesized indexes`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val author = graph.schemas.first { it.schemaName == "Author" }

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
        val profile = graph.schemas.first { it.schemaName == "Profile" }

        val synthIdx = profile.indexes.first { it.name == "idx_profiles_user_id_unique" }
        assertEquals(listOf("user_id"), synthIdx.columns)
        assertTrue(synthIdx.unique)
    }

    @Test
    fun `explain shows partial index with WHERE clause`() {
        val graph = SchemaInspector.explain(inputs(InspEvent()))
        val event = graph.schemas.first { it.schemaName == "Event" }

        val partialIdx = event.indexes.first { it.name == "idx_events_deleted_at" }
        assertEquals(listOf("deleted_at"), partialIdx.columns)
        assertEquals("deleted_at IS NULL", partialIdx.where)
    }

    // ── explain: mixins ─────────────────────────────────────────────

    @Test
    fun `explain includes mixin fields`() {
        val graph = SchemaInspector.explain(inputs(InspEvent()))
        val event = graph.schemas.first { it.schemaName == "Event" }

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
        val event = graph.schemas.first { it.schemaName == "Event" }

        val idx = event.indexes.first { it.name == "idx_events_created_at" }
        assertEquals(listOf("created_at"), idx.columns)
    }

    // ── explain: schema ordering ────────────────────────────────────

    @Test
    fun `explain preserves input order`() {
        val graph = SchemaInspector.explain(inputs(InspPost(), InspAuthor()))
        assertEquals("Post", graph.schemas[0].schemaName)
        assertEquals("Author", graph.schemas[1].schemaName)
    }

    // ── renderText ──────────────────────────────────────────────────

    @Test
    fun `renderText produces expected format`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val text = SchemaInspector.renderText(graph)

        // Header row
        assertContains(text, "Schema: Author")
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
        assertContains(text, "| Post")

        // Foreign Keys table on Post
        assertContains(text, "Schema: Post")
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
        class Bare : EntSchema("bare") { override fun id() = EntId.int() }
        val graph = SchemaInspector.explain(listOf(SchemaInput("Bare", Bare())))
        val text = SchemaInspector.renderText(graph)

        assertContains(text, "Schema: Bare")
        assertContains(text, "Id: INT (AUTO_INT)")
        assertFalse(text.contains("Fields:"))
        assertFalse(text.contains("Foreign Keys:"))
        assertFalse(text.contains("Edges:"))
        assertFalse(text.contains("Indexes:"))
    }

    // ── renderJson ──────────────────────────────────────────────────

    @Test
    fun `renderJson produces valid structure`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val json = SchemaInspector.renderJson(graph)

        assertContains(json, "\"schemas\":")
        assertContains(json, "\"schemaName\": \"Author\"")
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
    fun `renderJson empty schema has empty arrays`() {
        class Bare : EntSchema("bare") { override fun id() = EntId.int() }
        val graph = SchemaInspector.explain(listOf(SchemaInput("Bare", Bare())))
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
        val filtered = SchemaInspector.filter(graph, "Author")
        assertEquals(1, filtered.schemas.size)
        assertEquals("Author", filtered.schemas[0].schemaName)
    }

    @Test
    fun `filter by table name`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val filtered = SchemaInspector.filter(graph, "posts")
        assertEquals(1, filtered.schemas.size)
        assertEquals("Post", filtered.schemas[0].schemaName)
    }

    @Test
    fun `filter is case-insensitive`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val filtered = SchemaInspector.filter(graph, "author")
        assertEquals(1, filtered.schemas.size)
        assertEquals("Author", filtered.schemas[0].schemaName)
    }

    @Test
    fun `filter matches substring`() {
        val graph = SchemaInspector.explain(inputs(InspAuthor(), InspPost()))
        val filtered = SchemaInspector.filter(graph, "ost")
        assertEquals(1, filtered.schemas.size)
        assertEquals("Post", filtered.schemas[0].schemaName)
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
}
