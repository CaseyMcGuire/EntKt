package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserQueryScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.integrationtest.support.RecordingDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ImmutableQueryIntegrationTest : PostgresTestBase() {
    @Test
    fun `fluent pages and block refinements execute independently of the original query`() {
        val recording = RecordingDriver(resetAndDriver())
        val client = EntClient(recording)
        for (value in listOf("A", "B", "C")) {
            client.users.create { name = value; email = "$value@example.com" }
                .save(testViewerContext).getOrThrow()
        }
        recording.reset()

        val names = mutableListOf("A", "B", "C")
        var scope: UserQueryScope? = null
        val base = client.users.query {
            scope = this
            where(User.name `in` names)
            orderBy(User.name.asc())
        }
        val first = base.limit(1)
        val second = first.offset(1)
        val onlyC = base.configure { where(User.name eq "C") }
        names.clear()
        assertNotNull(scope).limit(0)
        assertEquals(0, recording.callCount(), "construction and refinement perform no reads")

        assertEquals(listOf("A"), first.all(testViewerContext).getOrThrow().map { it.name })
        assertEquals(listOf("B"), second.all(testViewerContext).getOrThrow().map { it.name })
        assertEquals(listOf("C"), onlyC.all(testViewerContext).getOrThrow().map { it.name })
        assertEquals(listOf("A", "B", "C"), base.all(testViewerContext).getOrThrow().map { it.name })
    }

    @Test
    fun `repeated terminals perform fresh SQL without changing the query's window`() {
        val recording = RecordingDriver(resetAndDriver())
        val client = EntClient(recording)
        client.users.create { name = "B"; email = "b@example.com" }.save(testViewerContext).getOrThrow()
        val query = client.users.query { orderBy(User.name.asc()) }

        assertEquals("B", query.firstOrNull(testViewerContext).getOrThrow()?.name)
        client.users.create { name = "A"; email = "a@example.com" }.save(testViewerContext).getOrThrow()
        recording.reset()

        assertEquals("A", query.firstOrNull(testViewerContext).getOrThrow()?.name)
        assertEquals(listOf("A", "B"), query.all(testViewerContext).getOrThrow().map { it.name })
        assertEquals(2, recording.callCount("query:users"))
    }
}
