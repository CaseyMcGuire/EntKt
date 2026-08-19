package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

/**
 * Fixture for the declaration-name naming contract. Every name here is
 * deliberately divergent, so nothing about the generated Kotlin API can
 * be reconstructed from storage strings — or from the class name:
 *
 * | Surface | Value | Source |
 * |---|---|---|
 * | Entity types | `Directory`, `DirectoryQuery`, … | the class name |
 * | Client property | `client.people` | `clientName` |
 * | Table | `legacy_people_tbl` | `tableName` |
 * | Field API | `Directory.publicLabel` | the `val` |
 * | Field column | `legacy_label_txt` | `string(...)` |
 * | Edge API | `curator`, implicit FK `curatorId` | the `val` |
 * | FK column | `legacy_owner_id` | `belongsTo(...)` |
 *
 * Note the client property is `people` — neither `directories` (what an
 * English pluralizer would produce from the class) nor anything derived
 * from the table. [Note] covers the field-backed FK form; this schema
 * covers the client name, a plain scalar, and an implicit FK.
 *
 * See `docs/implemented-features/schema/schema-declaration-api-names.md`.
 */
class Directory : EntSchema("legacy_people_tbl", clientName = "people") {
    override fun id() = EntId.long()

    /** Kotlin API `publicLabel`; storage column `legacy_label_txt`. */
    val publicLabel by string("legacy_label_txt")

    /** Kotlin API `curator` (+ implicit FK `curatorId`); FK column `legacy_owner_id`. */
    val curator by belongsTo<User>("legacy_owner").nullable().inverse(User::directories)

    /**
     * Kotlin API `topics`; storage edge name `legacy_topic_links`.
     *
     * The M2M traversal bridge resolves its edge against storage-keyed
     * driver metadata, while the generated method name and the paths
     * callers see come from this declaration — so a divergent M2M is
     * the case that separates the two.
     */
    val topics by manyToMany<Topic>("legacy_topic_links")
        .throughLink<DirectoryTopic>(DirectoryTopic::directory, DirectoryTopic::topic)
}
