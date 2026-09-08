package entkt.integrationtest

import entkt.integrationtest.ent.Article
import entkt.integrationtest.ent.ArticlePolicyScope
import entkt.integrationtest.ent.ArticleWriteCandidate
import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserCreatePrivacyRule
import entkt.integrationtest.ent.UserCreateValidationRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.ent.UserWriteCandidate
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.ViewerContext
import entkt.runtime.privacy.allowAll
import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntValidationException
import entkt.runtime.result.MutationResult
import entkt.runtime.result.MutationWriteState
import entkt.runtime.result.TransactionResult
import entkt.runtime.validation.ValidationDecision
import entkt.runtime.validation.matches
import entkt.runtime.validation.maxLength
import entkt.runtime.validation.minLength
import entkt.runtime.validation.positive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FieldValidationRulesIntegrationTest : PostgresTestBase() {
    private val viewerContext = ViewerContext(Viewer.Anonymous)

    private object UserFieldPolicy : EntityPolicy<User, UserPolicyScope> {
        override fun configure(scope: UserPolicyScope) = scope.run {
            privacy {
                load(allowAll)
                create(allowAll)
                update(allowAll)
            }
            validation {
                create(
                    minLength(UserWriteCandidate::name, 2),
                    maxLength(UserWriteCandidate::name, 5),
                    matches(UserWriteCandidate::email, Regex(".+@.+")),
                    maxLength(UserWriteCandidate::apiToken, 4),
                )
                updateDerivesFromCreate()
            }
        }
    }

    @Test
    fun `create aggregates property violations and persists nothing`() {
        val client = EntClient(resetAndDriver()) { policies { users(UserFieldPolicy) } }

        val result = client.users.create {
            name = ""
            email = "invalid"
            apiToken = "too long"
        }.save(viewerContext)

        val failure = validationFailure(result)
        assertEquals(listOf("name", "email", "apiToken"), failure.violations.map { it.field })
        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
        assertEquals(0, client.users.query().all(viewerContext).getOrThrow().size)
    }

    @Test
    fun `derived updates validate candidates and nullable fields remain optional`() {
        val client = EntClient(resetAndDriver()) { policies { users(UserFieldPolicy) } }
        val user = client.users.create {
            name = "Ada"
            email = "ada@example.com"
        }.saveAndLoad(viewerContext).getOrThrow()

        val result = client.users.update(user.id) { name = "X" }.save(viewerContext)
        assertEquals("name", validationFailure(result).violations.single().field)
        assertEquals("Ada", client.users.findById(viewerContext, user.id).getOrThrow()!!.name)

        val updated = client.users.update(user.id) {
            name = "Grace"
            apiToken = null
        }.saveAndLoad(viewerContext).getOrThrow()
        assertEquals("Grace", updated.name)
        assertEquals(null, updated.apiToken)
    }

    @Test
    fun `field rules see the final create and update hook values`() {
        val client = EntClient(resetAndDriver()) {
            policies { users(UserFieldPolicy) }
            hooks {
                users {
                    beforeCreate { state -> state.setName("Ada") }
                    beforeUpdate { state -> state.setName("X") }
                }
            }
        }
        val user = client.users.create {
            name = ""
            email = "ada@example.com"
        }.saveAndLoad(viewerContext).getOrThrow()
        assertEquals("Ada", user.name)

        val result = client.users.update(user.id) { name = "Grace" }.save(viewerContext)
        assertEquals("name", validationFailure(result).violations.single().field)
        assertEquals("Ada", client.users.findById(viewerContext, user.id).getOrThrow()!!.name)
    }

    @Test
    fun `privacy denial precedes field rule evaluation`() {
        var validationRan = false
        val policy = object : EntityPolicy<User, UserPolicyScope> {
            override fun configure(scope: UserPolicyScope) = scope.run {
                privacy {
                    create(UserCreatePrivacyRule { _, _ -> PrivacyDecision.Deny("not allowed") })
                }
                validation {
                    create(
                        minLength(UserWriteCandidate::name, 2),
                        UserCreateValidationRule { _, _ ->
                            validationRan = true
                            ValidationDecision.Valid
                        },
                    )
                }
            }
        }
        val client = EntClient(resetAndDriver()) { policies { users(policy) } }

        val result = client.users.create { name = ""; email = "a@example.com" }.save(viewerContext)

        assertIs<EntMutationPrivacyDeniedException>(assertIs<MutationResult.Failed>(result).exception)
        assertFalse(validationRan)
    }

    @Test
    fun `bulk creates evaluate field rules before any insert`() {
        val client = EntClient(resetAndDriver()) { policies { users(UserFieldPolicy) } }

        val result = client.users.createMany(
            viewerContext,
            { name = "Ada"; email = "ada@example.com" },
            { name = "X"; email = "x@example.com" },
        )

        assertEquals("name", validationFailure(result).violations.single().field)
        assertEquals(0, client.users.query().all(viewerContext).getOrThrow().size)
    }

    @Test
    fun `transaction clients inherit field rules and ignored failures roll back earlier writes`() {
        val client = EntClient(resetAndDriver()) { policies { users(UserFieldPolicy) } }

        val result = client.withTransaction { tx ->
            tx.users.create { name = "Ada"; email = "ada@example.com" }.save(viewerContext).getOrThrow()
            val invalid = tx.users.create { name = "X"; email = "x@example.com" }.save(viewerContext)
            validationFailure(invalid)
            "ignored failure"
        }

        assertIs<TransactionResult.Failed>(result)
        assertEquals(0, client.users.query().all(viewerContext).getOrThrow().size)
    }

    @Test
    fun `field rules are client configured and derived updates check unchanged fields`() {
        val driver = resetAndDriver()
        val unconfigured = EntClient(driver)
        val user = unconfigured.users.create {
            name = "X"
            email = "x@example.com"
        }.saveAndLoad(testBypassContext("seed legacy row")).getOrThrow()
        val configured = EntClient(driver) { policies { users(UserFieldPolicy) } }

        val changed = configured.users.update(user.id) { email = "new@example.com" }.save(viewerContext)
        assertEquals("name", validationFailure(changed).violations.single().field)
        val unchanged = configured.users.update(user.id) {}.save(viewerContext)
        assertEquals("name", validationFailure(unchanged).violations.single().field)
        assertEquals("x@example.com", configured.users.findById(viewerContext, user.id).getOrThrow()!!.email)
    }

    @Test
    fun `numeric helpers validate foreign key properties before storage`() {
        val policy = object : EntityPolicy<Article, ArticlePolicyScope> {
            override fun configure(scope: ArticlePolicyScope) = scope.run {
                privacy { create(allowAll) }
                validation { create(positive(ArticleWriteCandidate::authorId)) }
            }
        }
        val client = EntClient(resetAndDriver()) { policies { articles(policy) } }

        val result = client.articles.create { title = "Title"; authorId = -1L }.save(viewerContext)

        val failure = validationFailure(result)
        assertEquals("authorId", failure.violations.single().field)
        assertEquals(MutationWriteState.NotPersisted, failure.writeState)
    }

    private fun validationFailure(result: MutationResult<*>): EntValidationException =
        assertIs<EntValidationException>(assertIs<MutationResult.Failed>(result).exception)
}
