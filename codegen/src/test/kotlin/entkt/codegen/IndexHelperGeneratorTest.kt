package entkt.codegen

import entkt.codegen.client.RepoGenerator
import entkt.codegen.query.IndexHelperGenerator
import entkt.codegen.query.eligibleResolvedIndexes
import entkt.codegen.query.indexHelperPathsByName
import entkt.postgres.vector.VectorMetric
import entkt.postgres.vector.hnsw
import entkt.postgres.vector.postgresVector
import entkt.postgres.vector.postgresVectorIndex
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

/** Collapse generated whitespace so single-line substring matching works. */
private fun String.flat(): String = replace("\\s+".toRegex(), " ")

/** The body of one nested stage class, from its declaration to the next class declaration. */
private fun classBody(flat: String, name: String): String {
    val start = flat.indexOf("class $name internal")
    require(start >= 0) { "class $name not found in:\n$flat" }
    val next = flat.indexOf("class ", start + "class ".length)
    return if (next >= 0) flat.substring(start, next) else flat.substring(start)
}

private fun countOccurrences(haystack: String, needle: String): Int =
    haystack.split(needle).size - 1

// ── Fixtures ──────────────────────────────────────────────────────────

private class IdxAuthor : EntSchema("authors") {
    override fun id() = EntId.long()
    val name = string("name")
}

private class IdxPost : EntSchema("posts") {
    override fun id() = EntId.long()
    val title = string("title")
    val createdAt = time("created_at")
    val sequence = long("sequence")
    val status = string("status")
    val author = belongsTo<IdxAuthor>("author")

    val byAuthorCreated = index("idx_posts_author_created", author.fk, createdAt)
    val byAuthorCreatedSeq = index("idx_posts_author_created_seq", author.fk, createdAt, sequence)
    val byAuthorStatus = index("idx_posts_author_status", author.fk, status)
}

private fun postOutput(): String {
    val post = IdxPost()
    val author = IdxAuthor()
    finalize(post, author)
    return IndexHelperGenerator("com.example.ent")
        .generate("Post", post, mapOf(post to "Post", author to "Author"))!!
        .toString().flat()
}

private class IdxEmailUser : EntSchema("users") {
    override fun id() = EntId.long()
    val email = string("email").unique()
}

private class IdxNullableUnique : EntSchema("nuniq") {
    override fun id() = EntId.long()
    val nickname = string("nickname").nullable().unique()
}

private class IdxNullable : EntSchema("nul") {
    override fun id() = EntId.long()
    val nickname = string("nickname").nullable()
    val byNick = index("idx_nul_nickname", nickname)
}

private class IdxFriendship : EntSchema("friendships") {
    override fun id() = EntId.long()
    val requesterId = long("requester_id")
    val recipientId = long("recipient_id")
    val byPair = index("idx_friend_pair", requesterId, recipientId).unique()
}

private class IdxFlags : EntSchema("flags") {
    override fun id() = EntId.long()
    val active = bool("active")
    val byActive = index("idx_flags_active", active)
}

private class IdxBytes : EntSchema("blobs") {
    override fun id() = EntId.long()
    val data = bytes("data")
    val byData = index("idx_blob_data", data)
}

private class IdxVec : EntSchema("vecs") {
    override fun id() = EntId.long()
    val embedding = postgresVector("embedding", dimensions = 8).nullable()
    val byEmb = postgresVectorIndex("idx_vec_emb_hnsw", embedding).hnsw(VectorMetric.Cosine)
}

private class IdxPlainVec : EntSchema("pvecs") {
    override fun id() = EntId.long()
    val embedding = postgresVector("embedding", dimensions = 8).nullable()
    val byEmb = index("idx_pvec_emb", embedding)
}

@Serializable
private data class IdxMeta(val k: String)

private class IdxJson : EntSchema("jsons") {
    override fun id() = EntId.long()
    val meta = json("meta", IdxMeta::class)
    val byMeta = index("idx_json_meta", meta)
}

private class IdxPartial : EntSchema("part") {
    override fun id() = EntId.long()
    val email = string("email")
    val byEmailLive = index("idx_part_email_live", email).where("deleted_at IS NULL")
}

private class IdxCollisionTarget : EntSchema("ctargets") {
    override fun id() = EntId.long()
    val name = string("name")
}

private class IdxCollision : EntSchema("collide") {
    override fun id() = EntId.long()
    // A field-backed FK whose captured declaration name is `userId`, and a
    // distinct scalar column `user_id` that also camel-cases to `userId`.
    // Two distinct columns → same helper name at the same stage.
    val userId = long("user_fk")
    val owner = belongsTo<IdxCollisionTarget>("owner").field(userId)
    val otherUserId = long("user_id")
    val byFk = index("idx_collide_fk", userId)
    val byScalar = index("idx_collide_scalar", otherUserId)
}

private class IdxFbAuthor : EntSchema("fbauthors") {
    override fun id() = EntId.long()
    val name = string("name")
}

private class IdxFbPost : EntSchema("fbposts") {
    override fun id() = EntId.long()
    val writer = long("author_id")
    val author = belongsTo<IdxFbAuthor>("author").field(writer)
    val byWriter = index("idx_fbposts_writer", writer)
}

private class IdxNoIndex : EntSchema("plain") {
    override fun id() = EntId.long()
    val name = string("name")
}

// Two distinct prefixes whose PascalCase joins both equal "AB...": the
// equality stage for `createdAtRange` and the range terminal for
// `createdAt` both want the nested class "AuthorIdCreatedAtRange".
private class IdxRangeNameCollision : EntSchema("rnc") {
    override fun id() = EntId.long()
    val authorId = long("author_id")
    val createdAt = time("created_at")
    val createdAtRange = time("created_at_range")
    val i1 = index("idx_rnc_created", authorId, createdAt)
    val i2 = index("idx_rnc_created_range", authorId, createdAtRange)
}

// Distinct prefixes (a_b, c) and (a, b_c) both join to "ABC".
private class IdxPrefixCollision : EntSchema("pc") {
    override fun id() = EntId.long()
    val ab = long("a_b")
    val c = long("c")
    val a = long("a")
    val bc = long("b_c")
    val i1 = index("idx_pc_1", ab, c)
    val i2 = index("idx_pc_2", a, bc)
}

// A comparable column named `query` as a non-leading indexed column: its
// range overload `query { }` clashes with the stage's `query { }` terminal.
private class IdxQueryColumn : EntSchema("qc") {
    override fun id() = EntId.long()
    val authorId = long("author_id")
    val q = long("query")
    val i = index("idx_qc", authorId, q)
}

// A column whose property name is the reserved `value`, so the generated
// equality parameter is suffixed to `valueValue`.
private class IdxValueColumn : EntSchema("vc") {
    override fun id() = EntId.long()
    val v = long("value")
    val i = index("idx_vc", v)
}

// Composite index whose prefix column is nullable: helper parameters must
// still be non-null at every stage.
private class IdxNullableComposite : EntSchema("ncomp") {
    override fun id() = EntId.long()
    val tenantId = long("tenant_id").nullable()
    val name = string("name")
    val byTenantName = index("idx_ncomp_tenant_name", tenantId, name)
}

// ── Tests ─────────────────────────────────────────────────────────────

class IndexHelperGeneratorTest {

    private val gen = IndexHelperGenerator("com.example.ent")
    private val repo = RepoGenerator("com.example.ent")

    @Test
    fun `repo with eligible indexes exposes an indexes namespace`() {
        val post = IdxPost(); val author = IdxAuthor()
        finalize(post, author)
        val repoOut = repo.generate("Post", post, mapOf(post to "Post", author to "Author")).toString().flat()
        assertTrue("val indexes: PostIndexes" in repoOut, repoOut)
        assertTrue("PostIndexes(driver, client)" in repoOut, repoOut)
    }

    @Test
    fun `repo without eligible indexes has no indexes namespace and no file`() {
        val s = IdxNoIndex(); finalize(s)
        val repoOut = repo.generate("Plain", s).toString()
        assertTrue("val indexes" !in repoOut, repoOut)
        assertNull(gen.generate("Plain", s), "no eligible index → no Indexes file")
    }

    @Test
    fun `generates the indexes namespace class and delegates to the query builder`() {
        val out = postOutput()
        assertTrue("public class PostIndexes internal constructor(" in out, out)
        // Delegates through PostQuery, seeding the prefix via where(); never
        // touches the driver directly.
        assertTrue("val q = PostQuery(driver, client)" in out, out)
        assertTrue("for (p in predicates) q.where(p)" in out, out)
        assertTrue("driver.query" !in out, out)
        assertTrue("driver.count" !in out, out)
    }

    @Test
    fun `native non-btree (pgvector hnsw) indexes generate no helpers`() {
        val s = IdxVec(); finalize(s)
        assertTrue(eligibleResolvedIndexes(s, emptyMap()).isEmpty())
        assertNull(gen.generate("Vec", s))
    }

    @Test
    fun `plain index over a native pgvector column generates no helpers`() {
        val s = IdxPlainVec(); finalize(s)
        assertTrue(eligibleResolvedIndexes(s, emptyMap()).isEmpty())
        assertNull(gen.generate("Pvec", s))
    }

    @Test
    fun `bytes column generates no equality helper stage`() {
        val s = IdxBytes(); finalize(s)
        assertTrue(eligibleResolvedIndexes(s, emptyMap()).isEmpty())
        assertNull(gen.generate("Blob", s))
    }

    @Test
    fun `json column cannot be indexed (schema rejects it)`() {
        // Typed JSON is rejected by the schema layer, so it never reaches
        // helper generation — a JSON column produces no equality helper.
        assertFailsWith<IllegalStateException> {
            val s = IdxJson(); finalize(s); gen.generate("Json", s)
        }
    }

    @Test
    fun `partial raw-SQL indexes are skipped`() {
        val s = IdxPartial(); finalize(s)
        assertTrue(eligibleResolvedIndexes(s, emptyMap()).isEmpty())
        assertNull(gen.generate("Part", s))
    }

    @Test
    fun `unique single-column index generates unique terminals`() {
        val s = IdxEmailUser(); finalize(s)
        val out = gen.generate("User", s)!!.toString().flat()
        // Non-null param even though the helper reads by value.
        assertTrue("fun email(email: String)" in out, out)
        val email = classBody(out, "Email")
        assertTrue("fun orNull(): User?" in email, email)
        assertTrue("fun visibleOrNull(): User?" in email, email)
        assertTrue("fun orError(): EntResult<User>" in email, email)
        assertTrue("fun orThrow(): User" in email, email)
        assertTrue("fun query(" in email, email)
    }

    @Test
    fun `unique composite generates terminals only at the full unique stage`() {
        val s = IdxFriendship(); finalize(s)
        val out = gen.generate("Friendship", s)!!.toString().flat()
        // Exactly one stage (the full pair) exposes the unique terminals.
        assertEquals(1, countOccurrences(out, "fun orNull()"), out)
        // The prefix stage exists, exposes query(), but no terminals.
        val prefix = classBody(out, "RequesterId")
        assertTrue("fun recipientId(recipientId: Long)" in prefix, prefix)
        assertTrue("fun query(" in prefix, prefix)
        assertTrue("orNull" !in prefix, prefix)
        // The full stage exposes the terminals.
        val full = classBody(out, "RequesterIdRecipientId")
        assertTrue("fun orNull(): Friendship?" in full, full)
        assertTrue("fun orError(): EntResult<Friendship>" in full, full)
    }

    @Test
    fun `range-bound stages never expose unique terminals`() {
        // requesterId/recipientId are comparable, so a range overload exists,
        // but its terminal stage offers only query().
        val s = IdxFriendship(); finalize(s)
        val out = gen.generate("Friendship", s)!!.toString().flat()
        val rangeStage = classBody(out, "RequesterIdRecipientIdRange")
        assertTrue("fun query(" in rangeStage, rangeStage)
        assertTrue("orNull" !in rangeStage, rangeStage)
    }

    @Test
    fun `composite indexes generate left-prefix staged helpers only`() {
        val out = postOutput()
        assertTrue("class AuthorId internal" in out, out)
        assertTrue("class AuthorIdCreatedAt internal" in out, out)
        assertTrue("class AuthorIdCreatedAtSequence internal" in out, out)
        assertTrue("class AuthorIdStatus internal" in out, out)
        // No stage skips the leading author_id column.
        assertTrue("class CreatedAt internal" !in out, out)
        assertTrue("class Sequence internal" !in out, out)
        assertTrue("class Status internal" !in out, out)
    }

    @Test
    fun `shared prefixes from multiple indexes merge into one staged prefix`() {
        val out = postOutput()
        // One shared first stage, even though three indexes lead with author_id.
        assertEquals(1, countOccurrences(out, "class AuthorId internal"), out)
        val authorId = classBody(out, "AuthorId")
        assertTrue("fun createdAt(createdAt: Instant)" in authorId, authorId)
        assertTrue("fun status(status: String)" in authorId, authorId)
    }

    @Test
    fun `comparable next indexed columns generate range-block stages`() {
        val out = postOutput()
        assertTrue("fun createdAt(block: IndexRangeBuilder<Post, Instant>" in out, out)
        assertTrue("fun sequence(block: IndexRangeBuilder<Post, Long>" in out, out)
        assertTrue("fun status(block: IndexRangeBuilder<Post, String>" in out, out)
        // The empty-prefix lead column is comparable, so the root has a range too.
        assertTrue("fun authorId(block: IndexRangeBuilder<Post, Long>" in out, out)
    }

    @Test
    fun `equality and range overloads for the same column coexist`() {
        val out = postOutput()
        assertTrue("fun createdAt(createdAt: Instant)" in out, out)
        assertTrue("fun createdAt(block: IndexRangeBuilder<Post, Instant>" in out, out)
    }

    @Test
    fun `non-comparable indexed columns do not generate range-block stages`() {
        val s = IdxFlags(); finalize(s)
        val out = gen.generate("Flag", s)!!.toString().flat()
        assertTrue("fun active(active: Boolean)" in out, out)
        assertTrue("fun active(block:" !in out, out)
    }

    @Test
    fun `range-block stage exposes query but no later indexed columns or terminals`() {
        val out = postOutput()
        val rangeStage = classBody(out, "AuthorIdCreatedAtRange")
        assertTrue("fun query(" in rangeStage, rangeStage)
        assertTrue("fun sequence(" !in rangeStage, rangeStage)
        assertTrue("orNull" !in rangeStage, rangeStage)
    }

    @Test
    fun `helper names use generated column-ref property names not storage names`() {
        val out = postOutput()
        assertTrue("fun authorId(" in out, out)
        assertTrue("fun createdAt(" in out, out)
        // Physical column names never leak into the generated helper surface.
        assertTrue("author_id" !in out, out)
        assertTrue("created_at" !in out, out)
    }

    @Test
    fun `field-backed FK uses the captured declaration name`() {
        val post = IdxFbPost(); val author = IdxFbAuthor()
        finalize(post, author)
        val out = gen.generate("FbPost", post, mapOf(post to "FbPost", author to "FbAuthor"))!!.toString().flat()
        assertTrue("fun writer(writer: Long)" in out, out)
        assertTrue("authorId" !in out, out)
    }

    @Test
    fun `nullable non-unique index generates a non-null helper parameter`() {
        val s = IdxNullable(); finalize(s)
        val out = gen.generate("Nul", s)!!.toString().flat()
        assertTrue("fun nickname(nickname: String)" in out, out)
        assertTrue("nickname: String?" !in out, out)
    }

    @Test
    fun `nullable composite prefix generates non-null helper parameters`() {
        val s = IdxNullableComposite(); finalize(s)
        val out = gen.generate("Ncomp", s)!!.toString().flat()
        // The nullable prefix column and the deeper column are both non-null params.
        assertTrue("fun tenantId(tenantId: Long)" in out, out)
        assertTrue("fun name(name: String)" in out, out)
        assertTrue("Long?" !in out, out)
        assertTrue("String?" !in out, out)
    }

    @Test
    fun `nullable unique index does not generate unique terminals in V1`() {
        val s = IdxNullableUnique(); finalize(s)
        val out = gen.generate("Nuniq", s)!!.toString().flat()
        assertTrue("fun nickname(nickname: String)" in out, out)
        assertEquals(0, countOccurrences(out, "fun orNull()"), out)
        assertTrue("fun query(" in out, out)
    }

    @Test
    fun `stage method name collisions are detected with a clear error`() {
        val s = IdxCollision(); val target = IdxCollisionTarget(); finalize(s, target)
        val ex = assertFailsWith<IllegalStateException> {
            gen.generate("Collide", s, mapOf(s to "Collide", target to "CollisionTarget"))
        }
        assertTrue(ex.message?.contains("collision") == true, ex.message ?: "")
    }

    @Test
    fun `equality stage and range terminal name collision is detected`() {
        val s = IdxRangeNameCollision(); finalize(s)
        val ex = assertFailsWith<IllegalStateException> { gen.generate("Rnc", s) }
        assertTrue(ex.message?.contains("collision") == true, ex.message ?: "")
    }

    @Test
    fun `cross-prefix stage class name collision is detected`() {
        val s = IdxPrefixCollision(); finalize(s)
        val ex = assertFailsWith<IllegalStateException> { gen.generate("Pc", s) }
        assertTrue(ex.message?.contains("collision") == true, ex.message ?: "")
    }

    @Test
    fun `comparable column named query collides with the query entrypoint`() {
        val s = IdxQueryColumn(); finalize(s)
        val ex = assertFailsWith<IllegalStateException> { gen.generate("Qc", s) }
        assertTrue(ex.message?.contains("builder entrypoint") == true, ex.message ?: "")
    }

    @Test
    fun `reserved-name column uses a suffixed value parameter in code and printed paths`() {
        val s = IdxValueColumn(); finalize(s)
        val out = gen.generate("Vc", s)!!.toString().flat()
        // `value` is a soft keyword, so the method name is backtick-escaped,
        // but the value parameter is suffixed rather than escaped.
        assertTrue("fun `value`(valueValue: Long)" in out, out)
        // Schema-printer path matches the actual generated parameter name.
        assertEquals(
            listOf("indexes.value(valueValue).query()"),
            indexHelperPathsByName(s, emptyMap()).getValue("idx_vc"),
        )
    }

    @Test
    fun `schema validation reports index-helper collisions (explain stays consistent with codegen)`() {
        // SchemaInspector.validate (and therefore explain, which validates
        // first) must surface the same collision codegen rejects, rather
        // than advertising helper paths for an invalid surface.
        val result = SchemaInspector.validate(listOf(SchemaInput("Rnc", IdxRangeNameCollision())))
        assertTrue(!result.valid, "expected validation to fail")
        assertTrue(result.errors.any { it.contains("collision") }, result.errors.toString())

        assertFailsWith<IllegalStateException> {
            SchemaInspector.explain(listOf(SchemaInput("Rnc", IdxRangeNameCollision())))
        }
    }

    @Test
    fun `query and query block delegate the seeded prefix to the query builder`() {
        val out = postOutput()
        val authorId = classBody(out, "AuthorId")
        assertTrue("fun query(block: PostQuery.() -> Unit" in authorId, authorId)
        assertTrue(".apply(block)" in authorId, authorId)
    }

    @Test
    fun `schema printer advertises generated helper paths`() {
        val post = IdxPost(); val author = IdxAuthor()
        finalize(post, author)
        val paths = indexHelperPathsByName(post, mapOf(post to "Post", author to "Author"))
        val created = paths.getValue("idx_posts_author_created")
        assertEquals(
            listOf(
                "indexes.authorId(authorId).query()",
                "indexes.authorId(authorId).createdAt(createdAt).query()",
            ),
            created,
        )
    }
}
