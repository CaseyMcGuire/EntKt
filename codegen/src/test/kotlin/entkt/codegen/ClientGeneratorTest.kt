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
            scope.contains("fun currentPrivacyContext(): PrivacyContext")) {
            "EntClientScope should expose repositories and the current privacy context only\n$output"
        }
        assert(output.contains("class EntTransactionClient") && output.contains(": EntClientScope")) {
            "EntTransactionClient should implement EntClientScope\n$output"
        }
        assert(output.contains("internal val hookClientScopeForInternalUse: EntClientScope = _EntHookClientScope(this)")) {
            "EntClient should cache a narrow hook facade\n$output"
        }
        assert(output.contains("private class _EntHookClientScope") && output.contains(": EntClientScope")) {
            "Hook contexts should receive a private facade rather than the full client\n$output"
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
        // `driver` property (declared first), not in the init block that
        // follows the repo properties.
        val clientStart = output.indexOf("public class EntClient(")
        val driverProp = output.indexOf("registerAll(SCHEMAS)", clientStart)
        val firstRepo = output.indexOf("override val cars:", clientStart)
        val initBlock = output.indexOf("init {", clientStart)
        assert(driverProp in 0 until firstRepo) {
            "registerAll must be wired before the first repo property\n$output"
        }
        assert(firstRepo in 0 until initBlock) {
            "repo properties precede the init block — an init-block call would be too late\n$output"
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

        // Both the client and the config expose the configurable default,
        // initialized to OwnerOnly (symmetric link-table writes). Two declarations total.
        val decls = Regex("defaultRelationshipLocking: RelationshipLocking = RelationshipLocking\\.OwnerOnly")
            .findAll(output).count()
        assert(decls == 2) {
            "Expected the client + config to both declare defaultRelationshipLocking = OwnerOnly; found $decls\n$output"
        }
        // Config copy + the clone-propagation sites thread it through.
        // (The former fixed-context clone is gone — read-side evaluators
        // use asValidationReadClientForInternalUse /
        // asPrivacyReadClientForInternalUse, which carry no write-side
        // defaults by design.)
        assert(output.contains("defaultRelationshipLocking = cfg.defaultRelationshipLocking")) {
            "Config copy must thread defaultRelationshipLocking\n$output"
        }
        assert(output.contains("tx.defaultRelationshipLocking = this.defaultRelationshipLocking")) {
            "withTransaction must propagate defaultRelationshipLocking\n$output"
        }
        assert(output.contains("scoped.defaultRelationshipLocking = this.defaultRelationshipLocking")) {
            "withPrivacyContext must propagate defaultRelationshipLocking\n$output"
        }
    }

    @Test
    fun `EntClient exposes a repo property per schema`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("val cars: CarRepo = CarRepo(driver)")) {
            "Should expose cars: CarRepo\n$output"
        }
        assert(output.contains("val users: UserRepo = UserRepo(driver)")) {
            "Should expose users: UserRepo\n$output"
        }
    }

    @Test
    fun `EntClient init block attaches client to repos and applies hooks`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("cars.attachClientForInternalUse(this)")) {
            "Should attach client to cars repo without exposing a backlink\n$output"
        }
        assert(output.contains("users.attachClientForInternalUse(this)")) {
            "Should attach client to users repo without exposing a backlink\n$output"
        }
        assert(output.contains("val cfg = EntClientConfig().apply(config)")) {
            "Should create config and apply lambda\n$output"
        }
        assert(output.contains("cars.applyHooks(cfg.hooksConfig.cars)")) {
            "Should apply car hooks from config\n$output"
        }
        assert(output.contains("users.applyHooks(cfg.hooksConfig.users)")) {
            "Should apply user hooks from config\n$output"
        }
    }

    @Test
    fun `EntClient is emitted in the configured package`() {
        val schemas = buildSchemas()
        val file = generator.generate(schemas)

        assertEquals("com.example.ent", file.packageName)
        assertEquals("EntClient", file.name)
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
        assert(output.contains("val tx = EntClient(txDriver)")) {
            "makeTxClient should build the transactional client on the tx driver\n$output"
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
        // every Failed construction site; privacy-context clones propagate
        // the coordinator so scoped mutations still mark the transaction.
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
        assert(output.contains("scoped.transactionCoordinator = this.transactionCoordinator")) {
            "withPrivacyContext should propagate the coordinator to the scoped clone\n$output"
        }
        assert(output.contains("scoped.transactionExecutionToken = this.transactionExecutionToken")) {
            "withPrivacyContext should preserve transaction execution authorization\n$output"
        }
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
    fun `EntTransactionClient exposes repos and scoped privacy but no nested transaction entry point`() {
        val output = generator.generate(buildSchemas()).toString().replace("\\s+".toRegex(), " ")
        val start = output.indexOf("class EntTransactionClient")
        val end = output.indexOf("class EntClient(", start)
        assert(start >= 0 && end > start) { "Could not isolate EntTransactionClient\n$output" }
        val transactionClient = output.substring(start, end)

        assert(transactionClient.contains("val cars: CarRepo get() = delegate.cars")) {
            "Transaction facade should expose full repositories\n$transactionClient"
        }
        assert(transactionClient.contains("fun <T> withPrivacyContext(")) {
            "Transaction facade should preserve privacy re-scoping\n$transactionClient"
        }
        assert(transactionClient.contains("fun <T> bypassPrivacy_DANGEROUS(")) {
            "Transaction facade should preserve the explicit privacy bypass\n$transactionClient"
        }
        assert(!transactionClient.contains("fun <T> withTransaction(")) {
            "Nested transactions must be absent from the transaction facade\n$transactionClient"
        }
        assert(transactionClient.contains("private val `delegate`: EntClient")) {
            "The full transaction-bound EntClient must not be exposed\n$transactionClient"
        }
    }

    @Test
    fun `withTransaction copies hooks from original repos to transactional repos`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("tx.cars.copyHooksFrom(this.cars)")) {
            "Should copy hooks for cars repo\n$output"
        }
        assert(output.contains("tx.users.copyHooksFrom(this.users)")) {
            "Should copy hooks for users repo\n$output"
        }
    }

    @Test
    fun `generates per-entity hooks DSL class`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("class CarHooks")) { "Should generate CarHooks\n$output" }
        assert(output.contains("class UserHooks")) { "Should generate UserHooks\n$output" }
    }

    @Test
    fun `entity hooks class has DSL methods for each lifecycle phase`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("fun beforeSave(hook: (CarMutation) -> Unit)")) {
            "Should have beforeSave\n$output"
        }
        assert(output.contains("fun beforeCreate(hook: (CarCreateHookContext) -> Unit)")) {
            "beforeCreate hook should be typed against CarCreateHookContext (restricted view + client)\n$output"
        }
        assert(output.contains("fun afterCreate(hook: (Car) -> Unit)")) {
            "Should have afterCreate\n$output"
        }
        assert(output.contains("fun beforeUpdate(hook: (CarUpdateHookContext) -> Unit)")) {
            "beforeUpdate now takes a CarUpdateHookContext\n$output"
        }
        assert(output.contains("fun afterUpdate(hook: (Car) -> Unit)")) {
            "Should have afterUpdate\n$output"
        }
        assert(output.contains("fun beforeDelete(hook: (Car) -> Unit)")) {
            "Should have beforeDelete\n$output"
        }
        assert(output.contains("fun afterDelete(hook: (Car) -> Unit)")) {
            "Should have afterDelete\n$output"
        }
        assert(output.contains("val beforeCreateHooks: MutableList<BatchHook<CarCreateHookContext>>")) {
            "Hook registries should store scalar and batch hooks in one ordered list\n$output"
        }
        assert(output.contains("beforeCreateHooks.add(Hook(hook))")) {
            "Scalar hook lambdas should be adapted to Hook\n$output"
        }
        assert(output.contains("fun beforeCreate(hook: BatchHook<CarCreateHookContext>)")) {
            "Should accept an explicitly batch-aware hook under the same lifecycle name\n$output"
        }
        assert(output.contains("@JvmName(\"beforeCreateBatchHook\")")) {
            "The batch overload should not capture Java scalar lambdas\n$output"
        }
    }

    @Test
    fun `generates EntClientHooks with per-entity methods`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("class EntClientHooks")) {
            "Should generate EntClientHooks\n$output"
        }
        assert(output.contains("fun cars(block: CarHooks.() -> Unit)")) {
            "Should have cars method on EntClientHooks\n$output"
        }
        assert(output.contains("fun users(block: UserHooks.() -> Unit)")) {
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

        // Check that @EntktDsl appears before each hooks class
        val carHooksPos = output.indexOf("class CarHooks")
        val entktDslBeforeCar = output.lastIndexOf("@EntktDsl", carHooksPos)
        assert(entktDslBeforeCar != -1 && entktDslBeforeCar < carHooksPos) {
            "CarHooks should be annotated with @EntktDsl\n$output"
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
    fun `EntClientConfig has privacyContext method`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("fun privacyContext(provider: PrivacyContextProvider)")) {
            "Should have privacyContext method on EntClientConfig\n$output"
        }
        assert(output.contains("internal var privacyContextProviderConfig: PrivacyContextProvider? = null")) {
            "The config should store the callback through the named provider contract\n$output"
        }
        assert(output.contains("privacyContextProviderConfig = provider")) {
            "The config should retain the named provider directly\n$output"
        }
    }

    @Test
    fun `EntClient has withPrivacyContext method`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("fun <T> withPrivacyContext(context: PrivacyContext, block: (EntClient) -> T): T")) {
            "Should have withPrivacyContext method\n$output"
        }
    }

    @Test
    fun `EntClient has the guarded internal posture adapters and no fixed-context clone`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("internal fun asValidationReadClientForInternalUse(): EntValidationReadClient")) {
            "Should have the asValidationReadClientForInternalUse adapter\n$output"
        }
        assert(output.contains("internal fun asPrivacyReadClientForInternalUse(privacy: PrivacyContext): EntPrivacyReadClient")) {
            "Should have the asPrivacyReadClientForInternalUse adapter\n$output"
        }
        // The validation adapter fixes the bypass context itself, so
        // evaluators cannot construct a validation reader under an
        // arbitrary context.
        assert(
            output.contains(
                "EntValidationReadClient(readClientImpl(PrivacyContext(Viewer.PrivacyBypass(\"validation read\"))))"
            )
        ) {
            "Validation adapter should fix the PrivacyBypass(\"validation read\") context\n$output"
        }
        // The privacy adapter freezes the supplied caller context.
        assert(output.contains("EntPrivacyReadClient(readClientImpl(privacy))")) {
            "Privacy adapter should freeze the caller's context\n$output"
        }
        // Both adapters share one private impl builder — the wrappers
        // cannot drift structurally.
        assert(output.contains("private fun readClientImpl(context: PrivacyContext): EntReadClientImpl")) {
            "Both adapters should call one private EntReadClientImpl builder\n$output"
        }
        // The opt-in marker is the gate that keeps same-module application
        // code from minting fixed-context readers.
        assert(output.contains("@EntktInternal\n  internal fun asValidationReadClientForInternalUse")) {
            "Validation adapter should carry the @EntktInternal guard\n$output"
        }
        assert(output.contains("@EntktInternal\n  internal fun asPrivacyReadClientForInternalUse")) {
            "Privacy adapter should carry the @EntktInternal guard\n$output"
        }
        // The arbitrary-context adapter is replaced by the posture pair —
        // removed, not kept around.
        assert(!output.contains("asReadClientForInternalUse")) {
            "The arbitrary-context asReadClientForInternalUse adapter should be removed\n$output"
        }
        // The full-client fixed-context clone is dead once evaluators use
        // the adapters — removed, not kept around.
        assert(!output.contains("withFixedPrivacyContextForInternalUse")) {
            "The fixed-context full-client clone should be removed\n$output"
        }
    }

    @Test
    fun `withTransaction copies privacy context, privacy config, and validation config`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("tx.privacyContextProvider = this.privacyContextProvider")) {
            "withTransaction should copy privacy context provider\n$output"
        }
        assert(output.contains("tx.cars.copyPrivacyFrom(this.cars)")) {
            "withTransaction should copy privacy for cars repo\n$output"
        }
        assert(output.contains("tx.users.copyPrivacyFrom(this.users)")) {
            "withTransaction should copy privacy for users repo\n$output"
        }
        assert(output.contains("tx.cars.copyValidationFrom(this.cars)")) {
            "withTransaction should copy validation for cars repo\n$output"
        }
        assert(output.contains("tx.users.copyValidationFrom(this.users)")) {
            "withTransaction should copy validation for users repo\n$output"
        }
    }

    @Test
    fun `init block applies policies from config`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("cars.applyPrivacy(cfg.policiesConfig.carsPrivacyConfig)")) {
            "Should apply car privacy from policies config\n$output"
        }
        assert(output.contains("users.applyPrivacy(cfg.policiesConfig.usersPrivacyConfig)")) {
            "Should apply user privacy from policies config\n$output"
        }
        assert(output.contains("cars.applyValidation(cfg.policiesConfig.carsValidationConfig)")) {
            "Should apply car validation from policies config\n$output"
        }
        assert(output.contains("users.applyValidation(cfg.policiesConfig.usersValidationConfig)")) {
            "Should apply user validation from policies config\n$output"
        }
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
