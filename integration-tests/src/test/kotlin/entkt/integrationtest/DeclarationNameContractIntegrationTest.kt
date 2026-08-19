package entkt.integrationtest

import entkt.integrationtest.ent.Directory
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.loadedOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * End-to-end Postgres coverage for the declaration-name naming
 * contract, using the deliberately divergent
 * [entkt.integrationtest.schema.Directory] fixture.
 *
 * Every assertion here would still pass if generated names were derived
 * from storage strings *and* the two happened to agree — which is why
 * the fixture makes them disagree on every axis. Kotlin call sites use
 * declaration names; raw JDBC confirms the rows land under the storage
 * names.
 *
 * See `docs/implemented-features/schema/schema-declaration-api-names.md`.
 */
class DeclarationNameContractIntegrationTest : PostgresTestBase() {

    private fun freshClient(): EntClient {
        val driver = resetAndDriver()
        return EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
        }
    }

    @Test
    fun `client property is the declared clientName, not an inflected class name`() {
        val client = freshClient()
        // Compile-time: the repository is `people`. If codegen reverted to
        // inflecting the class name this would have to be `directories`,
        // and this line would not resolve.
        val created = client.people.create { publicLabel = "Reference desk" }
            .saveAndLoad().getOrThrow()
        assertEquals("Reference desk", created.publicLabel)
    }

    @Test
    fun `field API uses the declaration name while storage keeps the column`() {
        val client = freshClient()
        val row = client.people.create { publicLabel = "Archive" }.saveAndLoad().getOrThrow()

        // Kotlin side: `publicLabel`, never `legacyLabelTxt`.
        assertEquals("Archive", row.publicLabel)

        // Storage side: the value is in `legacy_people_tbl.legacy_label_txt`.
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT legacy_label_txt FROM legacy_people_tbl WHERE id = ?",
            ).use { ps ->
                ps.setLong(1, row.id)
                ps.executeQuery().use { rs ->
                    assertEquals(true, rs.next(), "expected a row under the storage table name")
                    assertEquals("Archive", rs.getString("legacy_label_txt"))
                }
            }
        }
    }

    @Test
    fun `predicates over a declaration-named field lower to the storage column`() {
        val client = freshClient()
        client.people.create { publicLabel = "Alpha" }.save().getOrThrow()
        client.people.create { publicLabel = "Beta" }.save().getOrThrow()

        // The companion column ref is `Directory.publicLabel`; the SQL it
        // lowers to must filter on `legacy_label_txt`, or this finds nothing.
        val found = client.people.query { where(Directory.publicLabel eq "Beta") }
            .all().getOrThrow()
        assertEquals(1, found.size)
        assertEquals("Beta", found.single().publicLabel)
    }

    @Test
    fun `implicit FK API is the edge declaration plus Id, over a divergent column`() {
        val client = freshClient()
        val user = client.users.create { name = "Ada"; email = "ada@example.com" }
            .saveAndLoad().getOrThrow()

        // Compile-time: the FK setter is `curatorId` — from the edge
        // declaration `curator`, not from the storage name `legacy_owner`.
        val row = client.people.create {
            publicLabel = "Special collections"
            curatorId = user.id
        }.saveAndLoad().getOrThrow()

        assertEquals(user.id, row.curatorId)

        // Storage side: the FK column is `legacy_owner_id`.
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT legacy_owner_id FROM legacy_people_tbl WHERE id = ?",
            ).use { ps ->
                ps.setLong(1, row.id)
                ps.executeQuery().use { rs ->
                    assertEquals(true, rs.next())
                    assertEquals(user.id, rs.getLong("legacy_owner_id"))
                }
            }
        }
    }

    @Test
    fun `edge traversal and eager loading use the edge declaration name`() {
        val client = freshClient()
        val user = client.users.create { name = "Grace"; email = "grace@example.com" }
            .saveAndLoad().getOrThrow()
        client.people.create { publicLabel = "Rare books"; curatorId = user.id }
            .save().getOrThrow()
        client.people.create { publicLabel = "Unstaffed" }.save().getOrThrow()

        // `queryCurator` traverses Directory → User and is named from the
        // edge declaration `curator`. A storage-derived name would be
        // `queryLegacyOwner`; a target-derived one would be `queryUser`.
        val curators = client.people.query { }
            .queryCurator { }
            .all().getOrThrow()
        assertEquals(listOf("Grace"), curators.map { it.name })

        // The inverse hasMany is declared `directories` over storage edge
        // `legacy_owner`, so traversal back is `queryDirectories`.
        val owned = client.users.query { }
            .queryDirectories { }
            .all().getOrThrow()
        assertEquals(listOf("Rare books"), owned.map { it.publicLabel })

        val loaded = client.people.query { withCurator() }.all().getOrThrow()
        // Both rows were *requested*, so both edges are Loaded; only the
        // value differs. Asserting on the wrapper keeps "loaded with no
        // target" distinct from "never requested".
        val staffedRow = loaded.single { it.publicLabel == "Rare books" }
        assertNotNull(staffedRow.edges.curator.loadedOrNull()?.value)
        val unstaffedRow = loaded.single { it.publicLabel == "Unstaffed" }
        assertNotNull(unstaffedRow.edges.curator.loadedOrNull(), "edge was requested")
        assertNull(unstaffedRow.edges.curator.loadedOrNull()?.value, "no curator row")
    }

    @Test
    fun `M2M traversal over a renamed edge resolves against storage metadata`() {
        // The generated method name is `queryTopics` (declaration), but
        // the bridge predicate it emits must carry `legacy_topic_links`
        // (storage) — the driver resolves that against storage-keyed
        // edge metadata. Emitting the declaration name here compiles
        // fine and fails only when the SQL is built, which is why this
        // has to execute rather than just inspect generated source.
        val client = freshClient()
        val topic = client.topics.create { label = "Cartography" }.saveAndLoad().getOrThrow()
        val other = client.topics.create { label = "Unlinked" }.saveAndLoad().getOrThrow()
        val dir = client.people.create { publicLabel = "Map room" }.saveAndLoad().getOrThrow()
        client.directoryTopics.create { directoryId = dir.id; topicId = topic.id }
            .save().getOrThrow()

        val reached = client.people.query { where(Directory.publicLabel eq "Map room") }
            .queryTopics { }
            .all().getOrThrow()
        assertEquals(listOf("Cartography"), reached.map { it.label })
        assertEquals(false, reached.any { it.id == other.id })
    }
}
