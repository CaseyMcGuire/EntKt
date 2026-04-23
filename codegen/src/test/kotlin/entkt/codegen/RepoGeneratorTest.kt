package entkt.codegen

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.fields
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

        assert(output.contains("driver.byId(Car.TABLE, id)")) {
            "deleteById should fetch entity via driver (bypassing LOAD privacy)\n$output"
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
        assert(!output.contains("fun upsert(vararg onConflict: Column<*>, block: CarCreate.() -> Unit): Car?")) {
            "upsert() should return non-nullable Car\n$output"
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

    @Test
    fun `repo has privacy config property`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("internal val privacyConfig: CarPrivacyConfig")) {
            "Should have internal privacyConfig property\n$output"
        }
    }

    @Test
    fun `repo has applyPrivacy and copyPrivacyFrom`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun applyPrivacy(config: CarPrivacyConfig)")) {
            "Should have applyPrivacy method\n$output"
        }
        assert(output.contains("fun copyPrivacyFrom(other: CarRepo)")) {
            "Should have copyPrivacyFrom method\n$output"
        }
    }

    @Test
    fun `repo has hasPrivacy methods for all operations`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun hasLoadPrivacy(): Boolean")) {
            "Should have hasLoadPrivacy\n$output"
        }
        assert(output.contains("fun hasCreatePrivacy(): Boolean")) {
            "Should have hasCreatePrivacy\n$output"
        }
        assert(output.contains("fun hasUpdatePrivacy(): Boolean")) {
            "Should have hasUpdatePrivacy\n$output"
        }
        assert(output.contains("fun hasDeletePrivacy(): Boolean")) {
            "Should have hasDeletePrivacy\n$output"
        }
    }

    @Test
    fun `repo has evaluate methods for load, create, update, and delete privacy`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun evaluateLoadPrivacy(privacy: PrivacyContext, entity: Car)")) {
            "Should have evaluateLoadPrivacy\n$output"
        }
        assert(output.contains("fun evaluateCreatePrivacy(privacy: PrivacyContext, candidate: CarWriteCandidate)")) {
            "Should have evaluateCreatePrivacy\n$output"
        }
        assert(output.contains("evaluateUpdatePrivacy")) {
            "Should have evaluateUpdatePrivacy\n$output"
        }
        assert(output.contains("evaluateDeletePrivacy")) {
            "Should have evaluateDeletePrivacy\n$output"
        }
    }

    @Test
    fun `byId enforces load privacy`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("val privacy = client.currentPrivacyContext()")) {
            "byId should capture privacy context\n$output"
        }
        assert(output.contains("evaluateLoadPrivacy(privacy, entity)")) {
            "byId should call evaluateLoadPrivacy\n$output"
        }
    }

    @Test
    fun `delete enforces delete privacy`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("val candidate = buildDeleteCandidate(entity)")) {
            "delete should build delete candidate\n$output"
        }
        assert(output.contains("evaluateDeletePrivacy(privacy, entity, candidate)")) {
            "delete should call evaluateDeletePrivacy\n$output"
        }
    }

    @Test
    fun `deleteMany queries driver directly to bypass load privacy`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("driver.query(Car.TABLE, predicates.toList()")) {
            "deleteMany should query driver directly to bypass LOAD privacy\n$output"
        }
    }

    @Test
    fun `explicit id create takes id as first parameter`() {
        val output = generator.generate("Session", Session).toString()

        assert(output.contains("fun create(id: String, block: SessionCreate.() -> Unit): SessionCreate")) {
            "create() should take id as first parameter for EXPLICIT strategy\n$output"
        }
    }

    @Test
    fun `explicit id create passes id to constructor`() {
        val output = generator.generate("Session", Session).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("SessionCreate(driver, client, beforeSaveHooks, beforeCreateHooks, afterCreateHooks, id = id)")) {
            "create() should pass id to SessionCreate constructor\n$output"
        }
    }

    @Test
    fun `explicit id upsert takes id as first parameter`() {
        val output = generator.generate("Session", Session).toString()

        assert(output.contains("fun upsert(")) {
            "Should have upsert method\n$output"
        }
        assert(output.contains("id: String") && output.contains("vararg onConflict: Column<*>")) {
            "upsert() should take id and onConflict for EXPLICIT strategy\n$output"
        }
        assert(!output.contains("TODO")) {
            "upsert should not contain TODO\n$output"
        }
    }

    @Test
    fun `explicit id repo does not generate createMany`() {
        val output = generator.generate("Session", Session).toString()

        assert(!output.contains("fun createMany")) {
            "Should not generate createMany for EXPLICIT id strategy\n$output"
        }
    }

    @Test
    fun `repo has validation config property`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("internal val validationConfig: CarValidationConfig")) {
            "Should have internal validationConfig property\n$output"
        }
    }

    @Test
    fun `repo has applyValidation and copyValidationFrom`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun applyValidation(config: CarValidationConfig)")) {
            "Should have applyValidation method\n$output"
        }
        assert(output.contains("fun copyValidationFrom(other: CarRepo)")) {
            "Should have copyValidationFrom method\n$output"
        }
    }

    @Test
    fun `repo has evaluate methods for create, update, and delete validation`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("fun evaluateCreateValidation(candidate: CarWriteCandidate)")) {
            "Should have evaluateCreateValidation\n$output"
        }
        assert(output.contains("evaluateUpdateValidation")) {
            "Should have evaluateUpdateValidation\n$output"
        }
        assert(output.contains("evaluateDeleteValidation")) {
            "Should have evaluateDeleteValidation\n$output"
        }
    }

    @Test
    fun `delete enforces delete validation after privacy`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("evaluateDeleteValidation(entity, candidate)")) {
            "delete should call evaluateDeleteValidation\n$output"
        }
    }

    @Test
    fun `evaluateCreateValidation uses system-scoped client`() {
        val output = generator.generate("Car", Car).toString()

        assert(output.contains("client.withFixedPrivacyContextForInternalUse(PrivacyContext(Viewer.System))")) {
            "Validation evaluator should use system-scoped client\n$output"
        }
    }
}
