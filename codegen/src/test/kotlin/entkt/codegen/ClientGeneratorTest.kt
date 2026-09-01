package entkt.codegen

import entkt.codegen.client.ClientGenerator
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class ClientGeneratorTest {

    private val generator = ClientGenerator("com.example.ent")

    private fun buildSchemas(): List<SchemaInput> {
        val car = Car()
        val user = User()
        finalize(car, user)
        return listOf(
            SchemaInput(car),
            SchemaInput(user),
        )
    }

    @Test
    fun `generates EntClient class`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("class EntClient")) { "Should generate EntClient\n$output" }
        assert(output.contains("class EntTransactionClient")) {
            "Should generate the transaction-scoped facade\n$output"
        }
        assert(output.contains("interface EntClientScope")) {
            "Should generate the shared repository capability\n$output"
        }
    }

    @Test
    fun `root transaction and hook facades share the narrow client scope`() {
        val output = generator.generate(buildSchemas()).toString().replace("\\s+".toRegex(), " ")

        val scopeStart = output.indexOf("interface EntClientScope")
        val scopeEnd = output.indexOf("private class _EntHookClientScope", scopeStart)
        val scope = output.substring(scopeStart, scopeEnd)
        assert(scope.contains("val cars: CarRepo") && scope.contains("val users: UserRepo") &&
            !scope.contains("ViewerContext")) {
            "EntClientScope should expose repositories without ambient viewer state\n$output"
        }
        assert(output.contains("class EntTransactionClient") && output.contains(": EntClientScope by client")) {
            "EntTransactionClient should delegate EntClientScope\n$output"
        }
        assert(output.contains("internal val hookClientScopeForInternalUse: EntClientScope = newEntHookClientScopeForInternalUse(this)")) {
            "EntClient should cache a narrow hook facade\n$output"
        }
        assert(output.contains("private class _EntHookClientScope") &&
            Regex(": EntClientScope by client").findAll(output).count() == 2) {
            "Both narrow facades should delegate EntClientScope\n$output"
        }
    }

    @Test
    fun `EntClient batch-registers the whole schema set before building repos`() {
        val output = generator.generate(buildSchemas()).toString()

        // Drivers that materialize storage need the complete set at
        // once: foreign keys between mutually-referencing entities have
        // no valid one-schema-at-a-time creation order.
        assert(output.contains("driver.also { it.registerAll(SCHEMAS) }")) {
            "driver property should batch-register the schema set\n$output"
        }

        // Ordering is the load-bearing part. Each repo registers its own
        // schema from its property initializer, and property initializers
        // run in declaration order — so the batch call has to live on the
        // `driver` property declared before every repository.
        val clientStart = output.indexOf("public class EntClient private constructor(")
        val driverProp = output.indexOf("registerAll(SCHEMAS)", clientStart)
        val firstRepo = output.indexOf("override val cars:", clientStart)
        assert(driverProp in 0 until firstRepo) {
            "registerAll must be wired before the first repo property\n$output"
        }
    }

    @Test
    fun `EntClient takes a DatabaseDriver and optional config in its constructor`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("import entkt.runtime.driver.DatabaseDriver")) { "Should import DatabaseDriver\n$output" }
        assert(output.contains("driver: DatabaseDriver")) { "Should take DatabaseDriver in constructor\n$output" }
        assert(output.contains("config: EntClientConfig.() -> Unit = {}")) {
            "Should take optional config lambda\n$output"
        }
    }

    @Test
    fun `EntClient and config carry defaultRelationshipLocking defaulting to OwnerOnly`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString().replace("\\s+".toRegex(), " ")

        // The public config owns the default; the client reads its resolved value.
        val decls = Regex("defaultRelationshipLocking: RelationshipLocking = RelationshipLocking\\.OwnerOnly")
            .findAll(output).count()
        assert(decls == 1) {
            "Expected the config to declare defaultRelationshipLocking = OwnerOnly; found $decls\n$output"
        }
        assert(
            output.contains(
                "defaultRelationshipLocking: RelationshipLocking = configuration.defaultRelationshipLocking",
            ),
        ) {
            "The client must initialize the resolved default from configuration\n$output"
        }
        // Transaction clients reuse the immutable resolved value.
        assert(output.contains("val tx = EntClient(txDriver, configuration)")) {
            "withTransaction must reuse the resolved configuration\n$output"
        }
        assert(!output.contains("tx.defaultRelationshipLocking ="))
        assert(!output.contains("scoped.defaultRelationshipLocking"))
    }

    @Test
    fun `EntClient exposes a repo property per schema`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("val cars: CarRepo = CarRepo( driver = driver, client = this,")) {
            "Should expose cars: CarRepo\n$output"
        }
        assert(output.contains("val users: UserRepo = UserRepo( driver = driver, client = this,")) {
            "Should expose users: UserRepo\n$output"
        }
    }

    @Test
    fun `EntClient resolves configuration before constructing complete repos`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("EntClientConfig().apply(config).resolveForInternalUse()")) {
            "The public constructor should resolve its configuration once\n$output"
        }
        assert(output.contains("configuredHooks = configuration.hooks.cars")) {
            "The cars repo should receive configured hooks in its constructor\n$output"
        }
        assert(output.contains("configuredPrivacy = configuration.policies.usersPrivacyConfig")) {
            "The users repo should receive privacy configuration in its constructor\n$output"
        }
        assert(output.contains("configuredValidation = configuration.policies.usersValidationConfig")) {
            "The users repo should receive validation configuration in its constructor\n$output"
        }
        assert(!output.contains("attachClientForInternalUse") && !output.contains(".applyHooks("))
    }

    @Test
    fun `EntClient exposes one stable read-only rule client for entity-local evaluators`() {
        val output = generator.generate(buildSchemas()).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains(": EntReadRuntime, MutationRuntime, EntClientScope"))
        assert(output.contains("internal val readOnlyClient: ReadOnlyEntClient by lazy"))
        assert(output.contains("ReadOnlyEntClientImpl("))
        assert(!output.contains("CreateMutationExecutor"))
        assert(!output.contains("DeleteMutationExecutor"))
        assert(!output.contains("UpdateMutationExecutor"))
        assert(!output.contains("createMutations"))
        assert(!output.contains("deleteMutations"))
        assert(!output.contains("updateMutations"))
        assert(!output.contains("privacyReadClient") && !output.contains("validationReadClient"))
        assert(!output.contains("privacyRuleClient(") && !output.contains("validationRuleClient("))
        assert(!output.contains("evaluateCreatePrivacy") && !output.contains("evaluateCreateValidation")) {
            "The mutation executors should consume typed rule phases directly\n$output"
        }
    }

    @Test
    fun `EntClientConfig resolves every mutable configuration registry`() {
        val output = generator.generate(buildSchemas()).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("hooks = ResolvedEntClientHooks(hooksConfig)"))
        assert(output.contains("source.cars.resolveForInternalUse(\"Car\")"))
        assert(output.contains("policies = ResolvedEntClientPolicies(policiesConfig)"))
        assert(output.contains("source.usersPrivacyConfig.resolveForInternalUse()"))
        assert(output.contains("source.usersValidationConfig.resolveForInternalUse()"))
        assert(output.contains("interceptors = interceptorsConfig.config.resolveForInternalUse()"))
        assert(!output.contains("snapshotForInternalUse") && !output.contains("copyFromForInternalUse"))
    }

    @Test
    fun `EntClient is emitted in the configured package`() {
        val schemas = buildSchemas()
        val file = generator.generate(schemas).single { it.name == "EntClient" }

        assertEquals("com.example.ent", file.packageName)
        assertEquals("EntClient", file.name)
    }

    @Test
    fun `emits every top-level client class in a separate same-named file`() {
        val files = generator.generate(buildSchemas())

        assertEquals(
            setOf(
                "EntClientHooks",
                "ResolvedEntClientHooks",
                "EntClientPolicies",
                "ResolvedEntClientPolicies",
                "EntClientInterceptors",
                "EntClientConfig",
                "ResolvedEntClientConfig",
                "EntClientScope",
                "_EntHookClientScope",
                "EntTransactionClient",
                "EntClient",
            ),
            files.map { it.name }.toSet(),
        )
        assert(!files.single { it.name == "EntClient" }.toString().contains("class CarHooks"))
        assert(
            files.single { it.name == "_EntHookClientScope" }
                .toString()
                .contains("private class _EntHookClientScope"),
        )
    }

    @Test
    fun `EntClient emits withTransaction returning TransactionResult via runEntTransaction`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString().replace("\\s+".toRegex(), " ")

        // The canonical transaction entry point enters the root-client
        // execution guard, then delegates to runEntTransaction. The
        // makeTxClient lambda carries the matching capability into the
        // transaction-scoped client.
        assert(
            output.contains(
                "public fun <T> withTransaction(block: TransactionScope.(EntTransactionClient) -> T): " +
                    "TransactionResult<T> {"
            )
        ) {
            "withTransaction should return TransactionResult<T>\n$output"
        }
        assert(output.contains("val executionToken = transactionExecutionGuard.enterTransaction()")) {
            "withTransaction should enter the root-client execution guard\n$output"
        }
        assert(output.contains("runEntTransaction(driver, { txDriver, coordinator ->")) {
            "withTransaction should delegate to runEntTransaction\n$output"
        }
        assert(output.contains("val tx = EntClient(txDriver, configuration)")) {
            "makeTxClient should build the transactional client with the resolved configuration\n$output"
        }
        assert(output.contains("tx.transactionCoordinator = coordinator")) {
            "makeTxClient should wire the coordinator into the tx client\n$output"
        }
        assert(output.contains("tx.transactionExecutionToken = executionToken")) {
            "makeTxClient should authorize the transaction-scoped client\n$output"
        }
        assert(output.contains("transactionExecutionGuard.exitTransaction(executionToken)")) {
            "withTransaction should clear the execution guard in finally\n$output"
        }
        assert(output.contains("EntTransactionClient(tx)")) {
            "makeTxClient should expose only the transaction-scoped facade\n$output"
        }

        // The client owns the coordinator slot and the hook repos call at
        // every Failed construction site.
        assert(output.contains("internal var transactionCoordinator: TransactionCoordinator? = null")) {
            "EntClient should carry an internal transactionCoordinator\n$output"
        }
        assert(
            output.contains(
                "override fun recordTransactionMutationFailure(exception: EntMutationException) { " +
                    "transactionCoordinator?.recordFailure(exception) }"
            )
        ) {
            "EntClient should expose recordTransactionMutationFailure delegating to the coordinator\n$output"
        }
        assert(!output.contains("scoped.transactionCoordinator"))
        assert(!output.contains("scoped.transactionExecutionToken"))
        assert(
            output.contains(
                "transactionExecutionGuard.checkClientOperation(transactionExecutionToken)"
            )
        ) {
            "read and mutation preflights should reject captured-root operations\n$output"
        }

        // No throwing/OrError transaction variant survives, and the
        // visible-scan overfetch cap is gone with the visible* family.
        assert(!output.contains("withTransactionOrError")) {
            "withTransactionOrError should be removed — getOrThrow() is the only throwing projection\n$output"
        }
        assert(!output.contains("visibleOverfetchLimit")) {
            "visibleOverfetchLimit should be gone from the client and config\n$output"
        }
    }

    @Test
    fun `EntTransactionClient exposes repos but no context scoping or nested transaction entry point`() {
        val output = generator.generate(buildSchemas()).toString().replace("\\s+".toRegex(), " ")
        val start = output.indexOf("class EntTransactionClient")
        val end = output.indexOf("class EntClient private constructor(", start)
        assert(start >= 0 && end > start) { "Could not isolate EntTransactionClient\n$output" }
        val transactionClient = output.substring(start, end)

        assert(transactionClient.contains(": EntClientScope by client")) {
            "Transaction facade should delegate the repository capability\n$transactionClient"
        }
        assert(!transactionClient.contains("override val cars:"))
        assert(!transactionClient.contains("withViewerContext"))
        assert(!transactionClient.contains("bypassPrivacy_DANGEROUS"))
        assert(!transactionClient.contains("fun <T> withTransaction(")) {
            "Nested transactions must be absent from the transaction facade\n$transactionClient"
        }
        assert(transactionClient.contains("client: EntClient") &&
            !transactionClient.contains("val client: EntClient")) {
            "The delegated EntClient must remain a constructor parameter, not an exposed property\n$transactionClient"
        }
    }

    @Test
    fun `withTransaction reuses resolved repository configuration`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("val tx = EntClient(txDriver, configuration)")) {
            "Transactional repositories should be constructed from the resolved configuration\n$output"
        }
        assert(!output.contains("copyHooksFrom"))
    }

    @Test
    fun `uses the generic runtime entity hooks holder`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("EntityHooks<CarBeforeSaveState, CarBeforeCreateState, CarBeforeUpdateState, Car>"))
        assert(output.contains("EntityHooks<UserBeforeSaveState, UserBeforeCreateState, UserBeforeUpdateState, User>"))
        assert(!output.contains("class CarHooks") && !output.contains("class UserHooks"))
    }

    @Test
    fun `resolved entity hooks preserve all schema-specific hook types`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("ResolvedEntityHooks<CarBeforeSaveState, CarBeforeCreateState, CarBeforeUpdateState, Car>"))
        assert(!output.contains("beforeCreateHooks") && !output.contains("beforeCreateBatchHook"))
    }

    @Test
    fun `generates EntClientHooks with per-entity methods`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("class EntClientHooks")) {
            "Should generate EntClientHooks\n$output"
        }
        assert(output.contains("fun cars(block: EntityHooks<CarBeforeSaveState, CarBeforeCreateState, CarBeforeUpdateState, Car>.() -> Unit)")) {
            "Should have cars method on EntClientHooks\n$output"
        }
        assert(output.contains("fun users(block: EntityHooks<UserBeforeSaveState, UserBeforeCreateState, UserBeforeUpdateState, User>.() -> Unit)")) {
            "Should have users method on EntClientHooks\n$output"
        }
    }

    @Test
    fun `generates EntClientConfig with hooks method`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("class EntClientConfig")) {
            "Should generate EntClientConfig\n$output"
        }
        assert(output.contains("fun hooks(block: EntClientHooks.() -> Unit)")) {
            "Should have hooks method on EntClientConfig\n$output"
        }
    }

    @Test
    fun `hooks DSL classes are annotated with EntktDsl`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        val hooksPos = output.indexOf("class EntClientHooks")
        val entktDslBeforeHooks = output.lastIndexOf("@EntktDsl", hooksPos)
        assert(entktDslBeforeHooks != -1 && entktDslBeforeHooks < hooksPos) {
            "EntClientHooks should be annotated with @EntktDsl\n$output"
        }
    }

    @Test
    fun `generates EntClientPolicies with per-entity registration`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("class EntClientPolicies")) {
            "Should generate EntClientPolicies\n$output"
        }
        assert(output.contains("fun cars(policy: EntityPolicy<Car, CarPolicyScope>)")) {
            "Should have cars policy registration method\n$output"
        }
        assert(output.contains("fun users(policy: EntityPolicy<User, UserPolicyScope>)")) {
            "Should have users policy registration method\n$output"
        }
    }

    @Test
    fun `EntClientConfig has policies method`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("fun policies(block: EntClientPolicies.() -> Unit)")) {
            "Should have policies method on EntClientConfig\n$output"
        }
    }

    @Test
    fun `EntClientConfig has no ambient viewer context method`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(!output.contains("ViewerContextProvider"))
        assert(!output.contains("viewerContextProvider"))
        assert(!output.contains("fun viewerContext("))
    }

    @Test
    fun `EntClient has no context-scoping methods`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(!output.contains("withViewerContext"))
        assert(!output.contains("bypassPrivacy_DANGEROUS"))
        assert(!output.contains("currentViewerContext"))
    }

    @Test
    fun `EntClient has one stable contextless read-only client`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("internal val readOnlyClient: ReadOnlyEntClient by lazy"))
        assert(output.contains("lazy { ReadOnlyEntClientImpl("))
        assert(!output.contains("EntPrivacyReadClient"))
        assert(!output.contains("EntValidationReadClient"))
        assert(!output.contains("asValidationReadClientForInternalUse"))
        assert(!output.contains("asPrivacyReadClientForInternalUse"))
        assert(!output.contains("asReadClientForInternalUse")) {
            "The arbitrary-context asReadClientForInternalUse adapter should be removed\n$output"
        }
        // The full-client fixed-context clone is dead once evaluators use
        // the adapters — removed, not kept around.
        assert(!output.contains("withFixedViewerContextForInternalUse")) {
            "The fixed-context full-client clone should be removed\n$output"
        }
    }

    @Test
    fun `withTransaction reuses policy configuration without copying viewer state`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(!output.contains("viewerContextProvider"))
        assert(output.contains("val tx = EntClient(txDriver, configuration)")) {
            "withTransaction should rebuild repos from the resolved policy configuration\n$output"
        }
        assert(!output.contains("copyPrivacyFrom") && !output.contains("copyValidationFrom"))
    }

    @Test
    fun `repo constructors receive policies from config`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("configuredPrivacy = configuration.policies.carsPrivacyConfig"))
        assert(output.contains("configuredPrivacy = configuration.policies.usersPrivacyConfig"))
        assert(output.contains("configuredValidation = configuration.policies.carsValidationConfig"))
        assert(output.contains("configuredValidation = configuration.policies.usersValidationConfig"))
        assert(!output.contains("applyPrivacy") && !output.contains("applyValidation"))
    }

    @Test
    fun `client properties are the declared clientName, verbatim`() {
        // No inflection step exists: whatever the schema declares is what
        // the client exposes, including irregular and uncountable terms
        // that no pluralizer would produce.
        class Person : EntSchema("people", clientName = "people") {
            override fun id() = EntId.long()
            val name by string("name")
        }
        class NewsItem : EntSchema("news_items", clientName = "news") {
            override fun id() = EntId.long()
            val headline by string("headline")
        }
        class Audit : EntSchema("audit_log", clientName = "audit") {
            override fun id() = EntId.long()
            val action by string("action")
        }
        val schemas = listOf(Person(), NewsItem(), Audit())
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }

        val output = ClientGenerator("com.example.ent")
            .generate(schemas.map { SchemaInput(it) })
            .toString()

        for (prop in listOf("people", "news", "audit")) {
            assert(output.contains("val $prop:")) { "expected client property '$prop'\n$output" }
        }
        // The pluralizer's output for these class names must not appear.
        for (absent in listOf("persons", "newsItems", "audits")) {
            assert(!output.contains("val $absent:")) { "unexpected inflected property '$absent'\n$output" }
        }
    }
}
