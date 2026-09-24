package entkt.integrationtest

import entkt.integrationtest.required.ent.EntClient
import entkt.integrationtest.required.ent.Profile
import entkt.integrationtest.required.ent.ProfileLoadPrivacyRule
import entkt.integrationtest.required.ent.ProfilePolicyScope
import entkt.integrationtest.required.ent.User
import entkt.integrationtest.required.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.allowAll
import entkt.runtime.query.requireLoaded
import entkt.runtime.result.EntConstraintViolationException
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.LoadDenialOrigin
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.ReadResult
import entkt.runtime.result.TransactionFailureState
import entkt.runtime.result.TransactionResult
import org.postgresql.util.PSQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Uses its own generated client; the shared fixtures keep their optional hasOne. */
class HasOneRequiredIntegrationTest : PostgresTestBase() {
    private fun requiredDriver(): PostgresDriver {
        val driver = PostgresDriver(dataSource, autoDdl = true)
        driver.registerAll(EntClient.SCHEMAS)
        val tables = EntClient.SCHEMAS.joinToString(", ") { "\"${it.table}\"" }
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("TRUNCATE TABLE $tables RESTART IDENTITY CASCADE") }
        }
        return driver
    }

    private fun createPair(client: EntClient, name: String = "User"): Pair<User, Profile> =
        client.withTransaction { tx ->
            val user = tx.users.create { this.name = name }.saveAndLoad(testViewerContext).orRollback()
            val profile = tx.profiles.create { ownerId = user.id; bio = "Original" }
                .saveAndLoad(testViewerContext).orRollback()
            user to profile
        }.getOrThrow()

    private fun assertCommitRejected(result: TransactionResult<*>) {
        val failure = assertIs<TransactionResult.Failed>(result)
        assertEquals(TransactionFailureState.NotCommitted, failure.transactionState)
        val exception = assertIs<PSQLException>(failure.exception)
        assertEquals("23503", exception.sqlState)
        assertSame(exception, assertFailsWith<PSQLException> { result.getOrThrow() })
    }

    @Test
    fun `generated creates permit a temporary gap and load the completed graph after commit`() {
        val client = EntClient(requiredDriver())
        val user = client.withTransaction { tx ->
            val user = tx.users.create { name = "Created together" }
                .saveAndLoad(testViewerContext).orRollback()
            val pending = tx.users.query {
                where(User.id eq user.id)
                loadProfile()
            }.firstOrNull(testViewerContext).orRollback()
            assertNull(assertNotNull(pending).edges.profile.requireLoaded())

            tx.profiles.create { ownerId = user.id; bio = "Present at commit" }
                .save(testViewerContext).orRollback()
            user
        }.getOrThrow()

        val loaded = client.users.query {
            where(User.id eq user.id)
            loadProfile()
        }.firstOrNull(testViewerContext).getOrThrow()
        val profile: Profile? = assertNotNull(loaded).edges.profile.requireLoaded()
        assertEquals(user.id, assertNotNull(profile).ownerId)
        assertEquals("Present at commit", profile.bio)
    }

    @Test
    fun `successful inner create does not hide a missing-profile commit failure`() {
        val hookCalls = mutableListOf<String>()
        val client = EntClient(requiredDriver()) {
            hooks { users { afterCreate { hookCalls += it.name } } }
        }
        var inner: MutationResult<User>? = null
        val result = client.withTransaction { tx ->
            tx.users.create { name = "No profile" }.saveAndLoad(testViewerContext).also { inner = it }
        }

        assertIs<MutationResult.Success<User>>(inner)
        assertCommitRejected(result)
        assertEquals(listOf("No profile"), hookCalls, "commit checks must not replay lifecycle hooks")
        assertTrue(client.users.query().all(testViewerContext).getOrThrow().isEmpty())
        // A rejected commit must also release the generated client's execution guard.
        createPair(client)
    }

    @Test
    fun `autocommit create reports an unpersisted constraint violation`() {
        val client = EntClient(requiredDriver())
        val result = client.users.create { name = "Missing profile" }.save(testViewerContext)

        val failure = assertIs<MutationResult.Failed>(result)
        assertIs<EntConstraintViolationException>(failure.exception)
        assertEquals(MutationWriteState.NotPersisted, failure.exception.writeState)
        assertTrue(client.users.query().all(testViewerContext).getOrThrow().isEmpty())
    }

    @Test
    fun `deleting only the required profile rejects the outer transaction`() {
        val client = EntClient(requiredDriver())
        val (_, profile) = createPair(client)
        val result = client.withTransaction { tx ->
            assertEquals(true, tx.profiles.deleteById(testViewerContext, profile.id).orRollback())
        }

        assertCommitRejected(result)
        assertEquals(profile, client.profiles.findById(testViewerContext, profile.id).getOrThrow())
    }

    @Test
    fun `generated delete and create can replace the required profile`() {
        val client = EntClient(requiredDriver())
        val (user, original) = createPair(client)
        val replacement = client.withTransaction { tx ->
            tx.profiles.deleteById(testViewerContext, original.id).orRollback()
            tx.profiles.create { ownerId = user.id; bio = "Replacement" }
                .saveAndLoad(testViewerContext).orRollback()
        }.getOrThrow()

        assertNull(client.profiles.findById(testViewerContext, original.id).getOrThrow())
        assertEquals(replacement, client.profiles.findById(testViewerContext, replacement.id).getOrThrow())
        assertEquals(user.id, replacement.ownerId)
    }

    @Test
    fun `generated FK update cannot leave the previous owner without a profile`() {
        val client = EntClient(requiredDriver())
        val (firstUser, firstProfile) = createPair(client, "First")
        val (_, secondProfile) = createPair(client, "Second")
        val result = client.withTransaction { tx ->
            tx.profiles.deleteById(testViewerContext, firstProfile.id).orRollback()
            tx.profiles.update(secondProfile.id) { ownerId = firstUser.id }
                .save(testViewerContext).orRollback()
        }

        assertCommitRejected(result)
        assertEquals(firstProfile, client.profiles.findById(testViewerContext, firstProfile.id).getOrThrow())
        assertEquals(secondProfile, client.profiles.findById(testViewerContext, secondProfile.id).getOrThrow())
    }

    @Test
    fun `deleting both rows in child-first order is allowed`() {
        val client = EntClient(requiredDriver())
        val (user, profile) = createPair(client)
        client.withTransaction { tx ->
            tx.profiles.deleteById(testViewerContext, profile.id).orRollback()
            tx.users.deleteById(testViewerContext, user.id).orRollback()
        }.getOrThrow()

        assertNull(client.users.findById(testViewerContext, user.id).getOrThrow())
        assertNull(client.profiles.findById(testViewerContext, profile.id).getOrThrow())
    }

    @Test
    fun `createMany in a caller transaction can be followed by the required children`() {
        val client = EntClient(requiredDriver())
        val users = client.withTransaction { tx ->
            val users = tx.users.createMany(testViewerContext, { name = "First" }, { name = "Second" })
                .orRollback()
            for (user in users) {
                tx.profiles.create { ownerId = user.id; bio = user.name }.save(testViewerContext).orRollback()
            }
            users
        }.getOrThrow()

        assertEquals(users, client.users.query { orderBy(User.id.asc()) }.all(testViewerContext).getOrThrow())
        assertEquals(2, client.profiles.query().all(testViewerContext).getOrThrow().size)
    }

    @Test
    fun `owned createMany rejects commit without children and reports no persisted writes`() {
        val client = EntClient(requiredDriver())
        val result = client.users.createMany(testViewerContext, { name = "First" }, { name = "Second" })

        val failure = assertIs<MutationResult.Failed>(result)
        assertEquals(MutationWriteState.NotPersisted, failure.exception.writeState)
        assertTrue(client.users.query().all(testViewerContext).getOrThrow().isEmpty())
    }

    @Test
    fun `owned deleteMany cannot commit removal of required profiles`() {
        val client = EntClient(requiredDriver())
        val (_, profile) = createPair(client)
        val failure = assertIs<MutationResult.Failed>(client.profiles.deleteMany(testViewerContext))

        assertEquals(MutationWriteState.NotPersisted, failure.exception.writeState)
        assertEquals(profile, client.profiles.findById(testViewerContext, profile.id).getOrThrow())
    }

    @Test
    fun `required existence neither bypasses read privacy nor changes nullable edge projections`() {
        val client = EntClient(requiredDriver()) {
            policies {
                users(object : EntityPolicy<User, UserPolicyScope> {
                    override fun configure(scope: UserPolicyScope) = scope.run { privacy { load(allowAll) } }
                })
                profiles(object : EntityPolicy<Profile, ProfilePolicyScope> {
                    override fun configure(scope: ProfilePolicyScope) = scope.run {
                        privacy { load(ProfileLoadPrivacyRule { _, _ -> PrivacyDecision.Deny("Profile is hidden") }) }
                    }
                })
            }
        }
        val (user, profile) = createPair(client)
        val viewerContext = ViewerContext(Viewer.User(7L))
        assertEquals(user, client.users.findById(viewerContext, user.id).getOrThrow())

        val strict = client.users.query { loadProfile() }.firstOrNull(viewerContext)
        val denial = assertIs<EntPrivacyDeniedException>(assertIs<ReadResult.Failed>(strict).exception)
        assertIs<LoadDenialOrigin.SelectedEdgePath>(denial.origin)

        val visible = client.users.query { loadProfile().filterVisible() }.firstOrNull(viewerContext).getOrThrow()
        val hiddenProfile: Profile? = assertNotNull(visible).edges.profile.requireLoaded()
        assertNull(hiddenProfile)

        val filtered = client.users.query {
            loadProfile { where(Profile.bio eq "Does not match") }
        }.firstOrNull(testViewerContext).getOrThrow()
        assertNull(assertNotNull(filtered).edges.profile.requireLoaded())
        assertEquals(profile, client.profiles.findById(testViewerContext, profile.id).getOrThrow())
    }
}
