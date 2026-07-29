package entkt.codegen

import entkt.codegen.client.ClientGenerator
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
            SchemaInput("Car", car),
            SchemaInput("User", user),
        )
    }

    @Test
    fun `generates EntClient class`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("class EntClient")) { "Should generate EntClient\n$output" }
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
        val driverProp = output.indexOf("registerAll(SCHEMAS)")
        val firstRepo = output.indexOf("override val cars:")
        val initBlock = output.indexOf("init {")
        assert(driverProp in 0 until firstRepo) {
            "registerAll must be wired before the first repo property\n$output"
        }
        assert(firstRepo in 0 until initBlock) {
            "repo properties precede the init block — an init-block call would be too late\n$output"
        }
    }

    @Test
    fun `EntClient takes a Driver and optional config in its constructor`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("import entkt.runtime.driver.Driver")) { "Should import Driver\n$output" }
        assert(output.contains("driver: Driver")) { "Should take Driver in constructor\n$output" }
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
        // Config copy + the four context-propagation sites thread it through.
        assert(output.contains("defaultRelationshipLocking = cfg.defaultRelationshipLocking")) {
            "Config copy must thread defaultRelationshipLocking\n$output"
        }
        assert(output.contains("tx.defaultRelationshipLocking = this.defaultRelationshipLocking")) {
            "withTransaction must propagate defaultRelationshipLocking\n$output"
        }
        assert(output.contains("scoped.defaultRelationshipLocking = this.defaultRelationshipLocking")) {
            "withPrivacyContext must propagate defaultRelationshipLocking\n$output"
        }
        assert(output.contains("fixed.defaultRelationshipLocking = this.defaultRelationshipLocking")) {
            "fixed-context must propagate defaultRelationshipLocking\n$output"
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
    fun `EntClient init block sets client on repos and applies hooks`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("cars.client = this")) {
            "Should set client on cars repo\n$output"
        }
        assert(output.contains("users.client = this")) {
            "Should set client on users repo\n$output"
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
    fun `EntClient emits withTransaction that creates a transactional client`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("fun <T> withTransaction(block: (EntClient) -> T): T")) {
            "Should emit withTransaction method\n$output"
        }
        assert(output.contains("driver.withTransaction")) {
            "Should delegate to driver.withTransaction\n$output"
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

        assert(output.contains("fun privacyContext(provider: () -> PrivacyContext)")) {
            "Should have privacyContext method on EntClientConfig\n$output"
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
    fun `EntClient has internal withFixedPrivacyContextForInternalUse method`() {
        val schemas = buildSchemas()
        val output = generator.generate(schemas).toString()

        assert(output.contains("internal fun withFixedPrivacyContextForInternalUse(context: PrivacyContext): EntClient")) {
            "Should have withFixedPrivacyContextForInternalUse method\n$output"
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
    fun `pluralize handles the cases the example schemas exercise`() {
        assertEquals("users", pluralize("user"))
        assertEquals("posts", pluralize("post"))
        assertEquals("tags", pluralize("tag"))
        assertEquals("categories", pluralize("category"))
        assertEquals("boxes", pluralize("box"))
        assertEquals("dishes", pluralize("dish"))
    }
}
