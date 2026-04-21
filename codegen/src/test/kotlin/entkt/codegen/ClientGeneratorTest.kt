package entkt.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientGeneratorTest {

    private val generator = ClientGenerator("com.example.ent")

    private val schemas = listOf(
        SchemaInput("Car", Car),
        SchemaInput("User", User),
    )

    @Test
    fun `generates EntClient class`() {
        val output = generator.generate(schemas).toString()

        assert(output.contains("class EntClient")) { "Should generate EntClient\n$output" }
    }

    @Test
    fun `EntClient takes a Driver and optional config in its constructor`() {
        val output = generator.generate(schemas).toString()

        assert(output.contains("import entkt.runtime.Driver")) { "Should import Driver\n$output" }
        assert(output.contains("driver: Driver")) { "Should take Driver in constructor\n$output" }
        assert(output.contains("config: EntClientConfig.() -> Unit = {}")) {
            "Should take optional config lambda\n$output"
        }
    }

    @Test
    fun `EntClient exposes a repo property per schema`() {
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
        val file = generator.generate(schemas)

        assertEquals("com.example.ent", file.packageName)
        assertEquals("EntClient", file.name)
    }

    @Test
    fun `EntClient emits withTransaction that creates a transactional client`() {
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
        val output = generator.generate(schemas).toString()

        assert(output.contains("class CarHooks")) { "Should generate CarHooks\n$output" }
        assert(output.contains("class UserHooks")) { "Should generate UserHooks\n$output" }
    }

    @Test
    fun `entity hooks class has DSL methods for each lifecycle phase`() {
        val output = generator.generate(schemas).toString()

        assert(output.contains("fun beforeSave(hook: (CarMutation) -> Unit)")) {
            "Should have beforeSave\n$output"
        }
        assert(output.contains("fun beforeCreate(hook: (CarCreate) -> Unit)")) {
            "Should have beforeCreate\n$output"
        }
        assert(output.contains("fun afterCreate(hook: (Car) -> Unit)")) {
            "Should have afterCreate\n$output"
        }
        assert(output.contains("fun beforeUpdate(hook: (CarUpdate) -> Unit)")) {
            "Should have beforeUpdate\n$output"
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
        val output = generator.generate(schemas).toString()

        assert(output.contains("fun policies(block: EntClientPolicies.() -> Unit)")) {
            "Should have policies method on EntClientConfig\n$output"
        }
    }

    @Test
    fun `EntClientConfig has privacyContext method`() {
        val output = generator.generate(schemas).toString()

        assert(output.contains("fun privacyContext(provider: () -> PrivacyContext)")) {
            "Should have privacyContext method on EntClientConfig\n$output"
        }
    }

    @Test
    fun `EntClient has withPrivacyContext method`() {
        val output = generator.generate(schemas).toString()

        assert(output.contains("fun <T> withPrivacyContext(context: PrivacyContext, block: (EntClient) -> T): T")) {
            "Should have withPrivacyContext method\n$output"
        }
    }

    @Test
    fun `EntClient has internal withFixedPrivacyContextForInternalUse method`() {
        val output = generator.generate(schemas).toString()

        assert(output.contains("internal fun withFixedPrivacyContextForInternalUse(context: PrivacyContext): EntClient")) {
            "Should have withFixedPrivacyContextForInternalUse method\n$output"
        }
    }

    @Test
    fun `withTransaction copies privacy context and privacy config`() {
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
    }

    @Test
    fun `init block applies policies from config`() {
        val output = generator.generate(schemas).toString()

        assert(output.contains("cars.applyPrivacy(cfg.policiesConfig.carsConfig)")) {
            "Should apply car privacy from policies config\n$output"
        }
        assert(output.contains("users.applyPrivacy(cfg.policiesConfig.usersConfig)")) {
            "Should apply user privacy from policies config\n$output"
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
