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

class Article : EntSchema("articles") {
    override fun id() = EntId.long()

    val title = string("title")
    val notes = string("notes").nullable()
    val published = bool("published").default(false)
    val metadata = json("metadata", ArticleMeta::class).nullable()

    val author = belongsTo<User>("author").inverse(User::articles)
}
