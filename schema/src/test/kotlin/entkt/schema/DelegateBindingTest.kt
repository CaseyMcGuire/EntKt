package entkt.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertContains
import kotlin.test.assertSame

/**
 * Delegate binding: `val x by string("col")` names the generated API,
 * independently of the storage string.
 *
 * See `docs/implemented-features/schema/schema-declaration-api-names.md`.
 */
class DelegateBindingTest {

    private fun finalized(vararg schemas: EntSchema): EntSchema {
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        return schemas.first()
    }

    private fun fieldByColumn(schema: EntSchema, column: String): Field =
        schema.fields().single { it.name == column }

    private fun edgeByStorage(schema: EntSchema, storage: String): Edge =
        schema.edges().single { it.name == storage }

    // ── Binding happens at construction ────────────────────────────

    @Test
    fun `delegated field binds its declaration name during construction`() {
        // No finalize() call — the point is that `by` binds while the
        // property is constructed, not in a later reflective pass.
        class Article : EntSchema("articles", clientName = "articles") {
            override fun id() = EntId.long()
            val publicTitle by string("legacy_title_txt")
        }
        val schema = Article()
        assertEquals("publicTitle", fieldByColumn(schema, "legacy_title_txt").declarationName)
    }

    @Test
    fun `declaration name and storage name are independent`() {
        class Article : EntSchema("articles", clientName = "articles") {
            override fun id() = EntId.long()
            val publicTitle by string("legacy_title_txt")
            val publishedAt by time("published_timestamp")
        }
        val schema = Article()
        val title = fieldByColumn(schema, "legacy_title_txt")
        assertEquals("publicTitle", title.declarationName)
        assertEquals("legacy_title_txt", title.name, "storage name must be untouched")

        val published = fieldByColumn(schema, "published_timestamp")
        assertEquals("publishedAt", published.declarationName)
        assertEquals("published_timestamp", published.name)
    }

    @Test
    fun `every edge kind binds its declaration name`() {
        val article = Article2()
        val profile = Profile2()
        val junction = Junction2()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(
            Article2::class to article,
            Profile2::class to profile,
            Junction2::class to junction,
        )
        registry.values.forEach { it.finalize(registry) }

        assertEquals("writer", edgeByStorage(article, "primary_author").declarationName)
        assertEquals("relatedStories", edgeByStorage(article, "related_stories").declarationName)
        assertEquals("mainProfile", edgeByStorage(article, "main_profile").declarationName)
        assertEquals("labels", edgeByStorage(article, "person_links").declarationName)

        // Storage names survive unchanged for joins and migrations.
        assertEquals(
            setOf("primary_author", "related_stories", "main_profile", "person_links"),
            article.edges().map { it.name }.toSet(),
        )
    }

    @Test
    fun `mixin fields bind inside the mixin without a host-property prefix`() {
        class Article : EntSchema("articles", clientName = "articles") {
            override fun id() = EntId.long()
            val timestamps = include(::BindingTimestamps)
            val title by string("title")
        }
        val schema = Article()
        assertEquals("createdAt", fieldByColumn(schema, "created_at").declarationName)
        assertEquals("updatedAt", fieldByColumn(schema, "updated_at").declarationName)
        assertEquals("title", fieldByColumn(schema, "title").declarationName)
    }

    // ── Static types survive the delegate ──────────────────────────

    @Test
    fun `delegate access preserves each builder's concrete static type`() {
        class Target : EntSchema("targets", clientName = "targets") {
            override fun id() = EntId.long()
        }
        class Post : EntSchema("posts", clientName = "posts") {
            override fun id() = EntId.long()
            val title by string("title").maxLength(64)
            val authorId by long("author_id")
            val writer by belongsTo<Target>("writer").field(authorId).nullable()
            val byAuthor = index("idx_post_author", authorId)
            val byFk = index("idx_post_fk", writer.fk)
        }
        val schema = Post()
        // These assignments are the assertion: they only compile if the
        // delegated properties kept their concrete builder types.
        val title: StringFieldBuilder = schema.title
        val authorId: LongFieldBuilder = schema.authorId
        val writer: BelongsToBuilder<Target> = schema.writer
        assertEquals("title", title.fieldName)
        assertEquals("author_id", authorId.fieldName)
        assertEquals("writer", writer.edgeName)
        // Reading the property twice yields the same builder, not a copy.
        assertSame(schema.title, schema.title)
    }

    // ── Rejections ─────────────────────────────────────────────────

    @Test
    fun `binding one builder to two properties is rejected at the second binding`() {
        val err = assertFailsWith<IllegalStateException> {
            object : EntSchema("dupes", clientName = "dupes") {
                override fun id() = EntId.long()
                private val shared = string("shared_col")
                val title by shared
                val headline by shared
            }
        }
        // The diagnostic must name both properties so the author can
        // tell which declaration to keep.
        assertContains(err.message!!, "'title'")
        assertContains(err.message!!, "'headline'")
        assertContains(err.message!!, "shared_col")
    }

    @Test
    fun `non-public delegated declarations are rejected`() {
        for (build in listOf<() -> EntSchema>(
            {
                object : EntSchema("vis_priv", clientName = "visPriv") {
                    override fun id() = EntId.long()
                    private val hidden by string("hidden_col")
                    init { hidden.fieldName }
                }
            },
            {
                object : EntSchema("vis_int", clientName = "visInt") {
                    override fun id() = EntId.long()
                    internal val hidden by string("hidden_col")
                }
            },
            {
                object : EntSchema("vis_prot", clientName = "visProt") {
                    override fun id() = EntId.long()
                    protected val hidden by string("hidden_col")
                    init { hidden.fieldName }
                }
            },
        )) {
            val err = assertFailsWith<IllegalStateException> { build() }
            assertContains(err.message!!, "must come from a public `val`")
            assertContains(err.message!!, "hidden_col")
        }
    }

    @Test
    fun `a declaration name that is not a lower-camel identifier is rejected`() {
        // Backticks make these legal Kotlin property names, but codegen
        // emits declaration names raw, so they cannot become generated
        // members.
        val upperLeading = assertFailsWith<IllegalArgumentException> {
            object : EntSchema("bad_decl", clientName = "badDecl") {
                override fun id() = EntId.long()
                @Suppress("PropertyName")
                val Title by string("cls")
            }
        }
        assertContains(upperLeading.message!!, "not a valid generated API name")
        assertContains(upperLeading.message!!, "'Title'")

        val spaced = assertFailsWith<IllegalArgumentException> {
            object : EntSchema("bad_decl2", clientName = "badDecl2") {
                override fun id() = EntId.long()
                val `my name` by string("cls")
            }
        }
        assertContains(spaced.message!!, "not a valid generated API name")
    }

    @Test
    fun `a declaration name that is a Kotlin hard keyword is rejected`() {
        // `class` is shaped like a lower-camel identifier, so it passes
        // the regex and is caught by the keyword check instead.
        val err = assertFailsWith<IllegalArgumentException> {
            object : EntSchema("kw_decl", clientName = "kwDecl") {
                override fun id() = EntId.long()
                val `class` by string("cls")
            }
        }
        assertContains(err.message!!, "Kotlin reserved keyword")
        assertContains(err.message!!, "'class'")
    }

    @Test
    fun `a wrapper-delegated declaration inside a mixin is rejected`() {
        // `by lazy` never runs the builder, so nothing registers and
        // finalization has nothing to notice. The mixin is checked while
        // its instance is still in hand, which is the only point where
        // the dropped column is observable.
        val err = assertFailsWith<IllegalStateException> {
            object : EntSchema("lazy_host", clientName = "lazyHosts") {
                override fun id() = EntId.long()
                val bad = include(::LazyMixin)
            }
        }
        assertContains(err.message!!, "LazyMixin")
        assertContains(err.message!!, "'lazyField'")
        assertContains(err.message!!, "by lazy")
    }

    @Test
    fun `an unbound '=' declaration inside a mixin is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            object : EntSchema("eq_host", clientName = "eqHosts") {
                override fun id() = EntId.long()
                val bad = include(::AssignMixin)
            }
        }
        assertContains(err.message!!, "AssignMixin")
        assertContains(err.message!!, "'plain'")
    }

    @Test
    fun `a declaration inherited from a base mixin is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            object : EntSchema("inherit_host", clientName = "inheritHosts") {
                override fun id() = EntId.long()
                val bad = include(::DerivedMixin)
            }
        }
        assertContains(err.message!!, "not declared on 'DerivedMixin'")
    }

    @Test
    fun `nested mixin inclusion validates each mixin against its own class`() {
        // The outer mixin's check must not see the inner mixin's fields
        // as unbound strays, and vice versa.
        class Host : EntSchema("nested_host", clientName = "nestedHosts") {
            override fun id() = EntId.long()
            val outer = include(::OuterMixin)
        }
        val schema = Host()
        assertEquals("createdAt", fieldByColumn(schema, "created_at").declarationName)
        assertEquals("updatedAt", fieldByColumn(schema, "updated_at").declarationName)
        assertEquals("note", fieldByColumn(schema, "note").declarationName)
    }

    @Test
    fun `a mixin's declaration does not vouch for a same-named host property`() {
        // The mixin contributes `title` into the host's namespace. That
        // must not make the host's own unbound `val title` look
        // accounted for — otherwise the host's column vanishes and the
        // generated `title` silently resolves to the mixin's column.
        val err = assertFailsWith<IllegalStateException> {
            VouchHost().finalize(mapOf(VouchHost::class to VouchHost() as EntSchema))
        }
        assertContains(err.message!!, "'title'")
        assertContains(err.message!!, "never bound")
    }

    @Test
    fun `an unbound declaration on a schema superclass is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            LazyBaseHost().finalize(mapOf(LazyBaseHost::class to LazyBaseHost() as EntSchema))
        }
        assertContains(err.message!!, "'ghost'")
        assertContains(err.message!!, "never bound")
    }

    @Test
    fun `an IndexableColumn-typed wrapper delegate is rejected`() {
        // The declared type is IndexableColumn, a supertype of
        // FieldHandle. Excluding it wholesale would let this drop the
        // column silently — and a dropped column means a missing
        // migration, not just a missing API.
        val err = assertFailsWith<IllegalStateException> {
            object : EntSchema("idxcol_host", clientName = "idxcolHosts") {
                override fun id() = EntId.long()
                @Suppress("unused")
                val hidden: IndexableColumn by lazy { string("hidden_col") }
            }.finalize(emptyMap())
        }
        assertContains(err.message!!, "'hidden'")
        assertContains(err.message!!, "never bound")
    }

    @Test
    fun `an FK column handle held in a val is not treated as a declaration`() {
        // `writer.fk` is an IndexableColumn that exists to be passed to
        // index(...). Separating it from a dropped declaration is done by
        // value, not by type, so this must still validate.
        class Target : EntSchema("fk_targets", clientName = "fkTargets") {
            override fun id() = EntId.long()
        }
        class Post : EntSchema("fk_posts", clientName = "fkPosts") {
            override fun id() = EntId.long()
            val writer by belongsTo<Target>("writer")
            val writerFk: IndexableColumn = writer.fk
            val byWriter = index("idx_fk_posts_writer", writerFk)
        }
        val target = Target()
        val post = Post()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(
            Target::class to target,
            Post::class to post,
        )
        registry.values.forEach { it.finalize(registry) }
        assertEquals("writer", edgeByStorage(post, "writer").declarationName)
    }

    @Test
    fun `a foreign object cannot bind a declaration name`() {
        // The delegate receiver is an unrelated class, so the name comes
        // from outside the schema entirely.
        //
        // What made this land silently before the receiver check: the
        // host's `val title` is ForeignBindingHolder-typed, so the
        // builder-typed property walk skips it, *and* the host happens to
        // declare a property called `title`, so the "declared here" check
        // is satisfied by coincidence. Both checks pass individually
        // while the declaration was never a delegated property on the
        // schema at all.
        val err = assertFailsWith<IllegalStateException> { ForeignFieldHost() }
        assertContains(err.message!!, "'legacy_title'")
        assertContains(err.message!!, "ForeignBindingHolder")
        assertContains(err.message!!, "neither the declaring schema nor a mixin")
    }

    @Test
    fun `a foreign object cannot bind an edge declaration name either`() {
        val err = assertFailsWith<IllegalStateException> { ForeignEdgeHost() }
        assertContains(err.message!!, "'legacy_children'")
        assertContains(err.message!!, "neither the declaring schema nor a mixin")
    }

    @Test
    fun `an unbound registered builder has no declaration name`() {
        // `=` still constructs and registers a builder; it just doesn't
        // name a generated API. Schema finalization rejects this in a
        // later step of the RFC — here we only pin the binding state.
        class Post : EntSchema("posts", clientName = "posts") {
            override fun id() = EntId.long()
            val bound by string("bound_col")
            val unbound = string("unbound_col")
        }
        val schema = Post()
        assertEquals("bound", fieldByColumn(schema, "bound_col").declarationName)
        assertNull(fieldByColumn(schema, "unbound_col").declarationName)
    }

    @Test
    fun `a handle-typed wrapper delegate on the schema is rejected`() {
        // The declared type is the public *handle* interface, not a
        // builder class, so a check that only looked for builder types
        // would skip this property and let the column vanish.
        val err = assertFailsWith<IllegalStateException> {
            object : EntSchema("handle_host", clientName = "handleHosts") {
                override fun id() = EntId.long()
                @Suppress("unused")
                val hidden: FieldHandle<String> by lazy { string("hidden_col") }
            }.finalize(emptyMap())
        }
        assertContains(err.message!!, "'hidden'")
    }

    @Test
    fun `a handle-typed wrapper delegate in a mixin is rejected`() {
        val err = assertFailsWith<IllegalStateException> {
            object : EntSchema("handle_mixin_host", clientName = "handleMixinHosts") {
                override fun id() = EntId.long()
                val m = include(::HandleLazyMixin)
            }
        }
        assertContains(err.message!!, "'hidden'")
    }

    @Test
    fun `a mixin cannot contribute an edge`() {
        // `EntMixin` exposes no edge helpers, but a host can create an
        // edge builder and hand it to a mixin. Binding rejects it there,
        // rather than letting the mixin exemption carry it past the
        // schema's own "declared here" check.
        val err = assertFailsWith<IllegalStateException> {
            EdgeSmugglingHost().finalize(
                mapOf(EdgeSmugglingHost::class to EdgeSmugglingHost() as EntSchema),
            )
        }
        assertContains(err.message!!, "edge")
    }
}

private class BindingTimestamps(scope: EntMixin.Scope) : EntMixin(scope) {
    val createdAt by time("created_at")
    val updatedAt by time("updated_at")
}

private class Article2 : EntSchema("articles2", clientName = "articles2") {
    override fun id() = EntId.long()
    val writer by belongsTo<Profile2>("primary_author")
    val relatedStories by hasMany<Article2>("related_stories")
    val mainProfile by hasOne<Profile2>("main_profile")
    val labels by manyToMany<Profile2>("person_links")
        .throughLink<Junction2>(Junction2::left, Junction2::right)
}

private class Profile2 : EntSchema("profiles2", clientName = "profiles2") {
    override fun id() = EntId.long()
}

private class Junction2 : EntSchema("junction2", clientName = "junction2") {
    override fun id() = EntId.long()
    val left by belongsTo<Article2>("left")
    val right by belongsTo<Profile2>("right")
}

private class LazyMixin(scope: EntMixin.Scope) : EntMixin(scope) {
    @Suppress("unused")
    val lazyField by lazy { time("lazy_col") }
}

private class AssignMixin(scope: EntMixin.Scope) : EntMixin(scope) {
    @Suppress("unused")
    val plain = time("plain_col")
}

private open class BaseMixin(scope: EntMixin.Scope) : EntMixin(scope) {
    val inherited by time("inherited_col")
}

private class DerivedMixin(scope: EntMixin.Scope) : BaseMixin(scope)

private class InnerMixin(scope: EntMixin.Scope) : EntMixin(scope) {
    val note by string("note")
}

private class OuterMixin(scope: EntMixin.Scope) : EntMixin(scope) {
    val createdAt by time("created_at")
    val updatedAt by time("updated_at")
    val inner = include(::InnerMixin)
}

private class HandleLazyMixin(scope: EntMixin.Scope) : EntMixin(scope) {
    @Suppress("unused")
    val hidden: FieldHandle<java.time.Instant> by lazy { time("hidden_col") }
}

private class SmuggleChild : EntSchema("smuggle_children", clientName = "smuggleChildren") {
    override fun id() = EntId.long()
}

private class EdgeSmugglingMixin(
    scope: EntMixin.Scope,
    smuggled: HasManyBuilder<SmuggleChild>,
) : EntMixin(scope) {
    val children by smuggled
}

private class EdgeSmugglingHost : EntSchema("smuggle_hosts", clientName = "smuggleHosts") {
    override fun id() = EntId.long()
    private val raw = hasMany<SmuggleChild>("children")
    val m = include { scope -> EdgeSmugglingMixin(scope, raw) }
}

private class VouchTitleMixin(scope: EntMixin.Scope) : EntMixin(scope) {
    val title by string("mixin_title_col")
}

private class VouchHost : EntSchema("vouch_hosts", clientName = "vouchHosts") {
    override fun id() = EntId.long()
    private val m = include(::VouchTitleMixin)

    @Suppress("unused")
    val title: FieldHandle<String> by lazy { string("host_title_col") }
}

private abstract class LazyBaseSchema(t: String, c: String) : EntSchema(t, c) {
    @Suppress("unused")
    val ghost: FieldHandle<String> by lazy { string("ghost_col") }
}

private class LazyBaseHost : LazyBaseSchema("lazy_base", "lazyBases") {
    override fun id() = EntId.long()
    val keep by string("keep_col")
}

private class ForeignBindingHolder(builder: StringFieldBuilder) {
    @Suppress("unused")
    val title by builder
}

private class ForeignEdgeHolder(builder: HasManyBuilder<ForeignChild>) {
    @Suppress("unused")
    val children by builder
}

private class ForeignChild : EntSchema("foreign_children", clientName = "foreignChildren") {
    override fun id() = EntId.long()
}

private class ForeignFieldHost : EntSchema("foreign_hosts", clientName = "foreignHosts") {
    override fun id() = EntId.long()

    // Deliberately named `title` — the same name the foreign holder binds.
    val title = ForeignBindingHolder(string("legacy_title"))
}

private class ForeignEdgeHost : EntSchema("foreign_edge_hosts", clientName = "foreignEdgeHosts") {
    override fun id() = EntId.long()
    val children = ForeignEdgeHolder(hasMany<ForeignChild>("legacy_children"))
}
