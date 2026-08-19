package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlinx.serialization.Serializable

/** A typed JSON document stored on `articles.metadata`. */
@Serializable
data class ArticleMeta(
    val source: String?,
    val tags: List<String>,
)

/** Element type of the generic `articles.rects` JSON column. */
@Serializable
data class HighlightRect(
    val page: Int,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
)

class Article : EntSchema("articles", clientName = "articles") {
    override fun id() = EntId.long()

    val title by string("title")
    val notes by string("notes").nullable()
    val published by bool("published").default(false)
    val payload by bytes("payload").nullable()
    val metadata by json("metadata", ArticleMeta::class).nullable()
    // Generic JSON shape: the full List<HighlightRect> type is captured, so
    // the generated property is typed and elements round-trip through the
    // element serializer (no wrapper class).
    val rects by json<List<HighlightRect>>("rects").nullable()

    val author by belongsTo<User>("author").inverse(User::articles)

    // Exercises indexed query helpers: a composite over the implicit FK
    // (author_id → authorId) plus a comparable text column (title → range
    // blocks). `User.email.unique()` covers the unique-terminal path.
    val byAuthorTitle = index("idx_articles_author_title", author.fk, title)
}
