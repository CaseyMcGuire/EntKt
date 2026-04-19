package entkt.codegen

import kotlin.test.Test

class RepoGeneratorTest {

    private val generator = RepoGenerator("com.example.ent")

    @Test
    fun `generates repo class`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("class CarRepo")) { "Should generate CarRepo\n$output" }
    }

    @Test
    fun `repo takes a Driver in its constructor`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("import entkt.runtime.Driver")) { "Should import Driver\n$output" }
        assert(output.contains("driver: Driver")) { "Should take Driver in constructor\n$output" }
    }

    @Test
    fun `repo holds the driver as a private property`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("private val driver: Driver")) {
            "Driver should be a private val\n$output"
        }
    }

    @Test
    fun `repo exposes query, create, and update`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun query(block: CarQuery.() -> Unit = {}): CarQuery")) {
            "Should have query with optional DSL block\n$output"
        }
        assert(output.contains("fun create(block: CarCreate.() -> Unit): CarCreate")) {
            "Should have create taking DSL block\n$output"
        }
        assert(output.contains("fun update(entity: Car, block: CarUpdate.() -> Unit): CarUpdate")) {
            "Should have update(entity, block) — entity comes through the repo, not the instance\n$output"
        }
    }

    @Test
    fun `repo exposes byId taking the schema id type`() {
        val output = generator.generate("User", User).toString()

        // User has UUID id; the byId param type must match.
        assert(output.contains("fun byId(id: UUID): User?")) {
            "byId should return nullable entity and take the schema id type\n$output"
        }
    }

    @Test
    fun `byId delegates to the driver and hydrates via fromRow`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("driver.byId(Car.TABLE, id)")) {
            "byId should call driver.byId with the entity's TABLE constant\n$output"
        }
        assert(output.contains("Car.fromRow(it)")) {
            "byId should hydrate the driver's row via Car.fromRow\n$output"
        }
    }

    @Test
    fun `repo exposes delete taking the entity and deleteById taking the id`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun delete(entity: Car): Boolean")) {
            "Should have delete(entity)\n$output"
        }
        assert(output.contains("fun deleteById(id: Int): Boolean")) {
            "Should have deleteById taking the schema id type\n$output"
        }
    }

    @Test
    fun `delete calls hooks around driver delete`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("for (hook in beforeDeleteHooks) hook(entity)")) {
            "delete should call beforeDelete hooks\n$output"
        }
        assert(output.contains("driver.delete(Car.TABLE, entity.id)")) {
            "delete should call driver.delete with entity.id\n$output"
        }
        assert(output.contains("if (deleted) for (hook in afterDeleteHooks) hook(entity)")) {
            "delete should call afterDelete hooks on success\n$output"
        }
    }

    @Test
    fun `deleteById fetches entity then delegates to delete`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("val entity = byId(id) ?: return false")) {
            "deleteById should fetch entity first\n$output"
        }
        assert(output.contains("return delete(entity)")) {
            "deleteById should delegate to delete(entity)\n$output"
        }
    }

    @Test
    fun `deleteById uses the correct id type for UUID schemas`() {
        val output = generator.generate("User", User).toString()

        assert(output.contains("fun deleteById(id: UUID): Boolean")) {
            "deleteById should use UUID for User's id type\n$output"
        }
    }

    @Test
    fun `create passes client and hook lists to the builder`() {
        val output = generator.generate("Car", Car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("CarCreate(driver, client, beforeSaveHooks, beforeCreateHooks, afterCreateHooks)")) {
            "create should pass client and hook lists to CarCreate\n$output"
        }
    }

    @Test
    fun `update passes client and hook lists to the builder`() {
        val output = generator.generate("Car", Car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("CarUpdate(driver, client, entity, beforeSaveHooks, beforeUpdateHooks, afterUpdateHooks)")) {
            "update should pass client and hook lists to CarUpdate\n$output"
        }
    }

    @Test
    fun `repo has lateinit client property`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("internal lateinit var client: EntClient")) {
            "Should have internal lateinit var client\n$output"
        }
    }

    @Test
    fun `repo registers the entity schema in its init block`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("driver.register(Car.SCHEMA)")) {
            "Repo should register Car.SCHEMA with the driver on construction\n$output"
        }
    }

    @Test
    fun `repo has applyHooks that copies from entity hooks config`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun applyHooks(hooks: CarHooks)")) {
            "Should have applyHooks method taking CarHooks\n$output"
        }
        assert(output.contains("beforeSaveHooks.addAll(hooks.beforeSaveHooks)")) {
            "Should copy beforeSaveHooks from config\n$output"
        }
        assert(output.contains("afterDeleteHooks.addAll(hooks.afterDeleteHooks)")) {
            "Should copy afterDeleteHooks from config\n$output"
        }
    }

    @Test
    fun `repo has copyHooksFrom that copies all hook lists`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun copyHooksFrom(other: CarRepo)")) {
            "Should have copyHooksFrom method\n$output"
        }
        assert(output.contains("beforeSaveHooks.addAll(other.beforeSaveHooks)")) {
            "Should copy beforeSaveHooks\n$output"
        }
        assert(output.contains("afterDeleteHooks.addAll(other.afterDeleteHooks)")) {
            "Should copy afterDeleteHooks\n$output"
        }
    }

    @Test
    fun `repo exposes upsert with vararg conflict columns and block`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun upsert(vararg onConflict: Column<*>, block: CarCreate.() -> Unit): Car")) {
            "Should have upsert method\n$output"
        }
    }

    @Test
    fun `repo upsert passes afterUpdateHooks to the create builder`() {
        val output = generator.generate("Car", Car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("CarCreate(driver, client, beforeSaveHooks, beforeCreateHooks, afterCreateHooks, afterUpdateHooks)")) {
            "upsert should pass afterUpdateHooks to CarCreate\n$output"
        }
    }

    @Test
    fun `repo does not expose hook registration methods`() {
        val output = generator.generate("Car", Car).toString()

        assert(!output.contains("fun onBeforeSave")) {
            "Should not have onBeforeSave — hooks are registered via client config DSL\n$output"
        }
        assert(!output.contains("fun onAfterCreate")) {
            "Should not have onAfterCreate — hooks are registered via client config DSL\n$output"
        }
    }

    @Test
    fun `repo exposes createMany with vararg blocks`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun createMany(vararg blocks: CarCreate.() -> Unit): List<Car>")) {
            "Should have createMany with vararg blocks\n$output"
        }
    }

    @Test
    fun `createMany delegates to create and save for hook support`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("create(it).save()")) {
            "createMany should delegate to create(block).save() so hooks fire\n$output"
        }
    }

    @Test
    fun `repo exposes deleteMany with vararg predicates`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun deleteMany(vararg predicates: Predicate): Int")) {
            "Should have deleteMany with vararg Predicate\n$output"
        }
    }

    @Test
    fun `deleteMany queries then deletes through hook path`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("if (delete(entity)) count++")) {
            "deleteMany should delegate to delete(entity) for hook support\n$output"
        }
    }
}
