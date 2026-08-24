package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.EntClientInterceptors
import entkt.integrationtest.ent.User
import entkt.integrationtest.ent.UserCreateValidationRule
import entkt.integrationtest.ent.UserHooks
import entkt.integrationtest.ent.UserLoadPrivacyRule
import entkt.integrationtest.ent.UserPolicyScope
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.Viewer
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.ReadResult
import entkt.runtime.validation.ValidationDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClientConfigurationSnapshotIntegrationTest : PostgresTestBase() {

    @Test
    fun `retained configuration scopes cannot mutate an existing client or its derived clients`() {
        lateinit var retainedHooks: UserHooks
        lateinit var retainedPolicy: UserPolicyScope
        lateinit var retainedInterceptors: EntClientInterceptors

        val client = EntClient(resetAndDriver()) {
            hooks {
                users { retainedHooks = this }
            }
            policies {
                users(object : EntityPolicy<User, UserPolicyScope> {
                    override fun configure(scope: UserPolicyScope) {
                        retainedPolicy = scope
                    }
                })
            }
            interceptors {
                retainedInterceptors = this
            }
        }

        retainedHooks.beforeCreate { error("late hook must not run") }
        retainedPolicy.privacy {
            load(UserLoadPrivacyRule { _, _ -> PrivacyDecision.Allow })
        }
        retainedPolicy.validation {
            create(UserCreateValidationRule { _, _ ->
                ValidationDecision.Invalid("late validation must not run")
            })
        }
        retainedInterceptors.users(
            QueryInterceptor { scope, _ -> scope.reject("late interceptor must not run") },
            name = "late-interceptor",
        )

        val created = client.withPrivacyContext(
            PrivacyContext(Viewer.PrivacyBypass("seed")),
        ) { scoped ->
            scoped.users.create {
                name = "Ada"
                email = "ada@example.com"
            }.saveAndLoad().getOrThrow()
        }
        assertEquals("Ada", created.name)

        val rootRead = assertIs<ReadResult.Failed>(client.users.query().all())
        assertIs<EntPrivacyDeniedException>(rootRead.exception)

        val transactionRead = client.withTransaction { transaction ->
            transaction.withPrivacyContext(PrivacyContext(Viewer.Anonymous)) { scoped ->
                scoped.users.query().all()
            }
        }.getOrThrow()
        val transactionFailure = assertIs<ReadResult.Failed>(transactionRead)
        assertIs<EntPrivacyDeniedException>(transactionFailure.exception)
    }
}
