package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.OnDelete

/** M2M target for [Directory.topics]. */
class Topic : EntSchema("topics", clientName = "topics") {
    override fun id() = EntId.long()
    val label by string("label")
}

/**
 * Junction for [Directory.topics]. Mirrors [PostTag]'s helper-eligible
 * shape so the M2M is a realistic link table rather than a degenerate one.
 */
class DirectoryTopic : EntSchema("directory_topics", clientName = "directoryTopics") {
    override fun id() = EntId.long()

    val directory by belongsTo<Directory>("directory").onDelete(OnDelete.CASCADE)
    val topic by belongsTo<Topic>("topic").onDelete(OnDelete.CASCADE)

    val pair = index("idx_dir_topics_dir_topic", directory.fk, topic.fk).unique()
    val byTopic = index("idx_dir_topics_topic_dir", topic.fk, directory.fk)
}
