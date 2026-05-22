package entkt.codegen

import entkt.schema.EntSchema
import kotlin.reflect.KClass
import kotlin.test.Test

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

class RepoGeneratorTest {

    private val generator = RepoGenerator("com.example.ent")

    @Test
    fun `generates repo class`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("class CarRepo")) { "Should generate CarRepo\n$output" }
    }

    @Test
    fun `repo takes a Driver in its constructor`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("import entkt.runtime.Driver")) { "Should import Driver\n$output" }
        assert(output.contains("driver: Driver")) { "Should take Driver in constructor\n$output" }
    }

    @Test
    fun `repo holds the driver as a private property`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("private val driver: Driver")) {
            "Driver should be a private val\n$output"
        }
    }

    @Test
    fun `repo exposes query, create, and update`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("fun query(block: CarQuery.() -> Unit = {}): CarQuery")) {
            "Should have query with optional DSL block\n$output"
        }
        assert(output.contains("fun create(block: CarCreate.() -> Unit): CarCreate")) {
            "Should have create taking DSL block\n$output"
        }
        assert(output.contains("fun update(\n    id: Int,\n    consistency: UpdateConsistency = client.defaultUpdateConsistency,\n    block: CarUpdate.() -> Unit,\n  ): CarUpdate")) {
            "Should have update(id, consistency, block) — update is rooted by id, with a per-save UpdateConsistency override\n$output"
        }
    }

    @Test
    fun `repo exposes the byId family taking the schema id type`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        // User has UUID id; every byId variant's param type matches.
        // The repo-level `byId(id)` is removed per the Result Variants
        // RFC — callers use the explicit *OrNull / *OrThrow /
        // visibleOr* / *OrError names instead.
        assert(!output.contains("public fun byId(id:")) {
            "Repo-level byId(id) should be removed in favor of the *Or* family\n$output"
        }
        assert(output.contains("public fun byIdOrNull(id: UUID): User?")) {
            "byIdOrNull should return nullable entity and take the schema id type\n$output"
        }
        assert(output.contains("public fun byIdOrThrow(id: UUID): User")) {
            "byIdOrThrow should return non-null entity\n$output"
        }
        assert(output.contains("public fun visibleByIdOrNull(id: UUID): User?")) {
            "visibleByIdOrNull should return nullable entity (absence OR invisibility → null)\n$output"
        }
        assert(output.contains("public fun byIdOrError(id: UUID): EntResult<User>")) {
            "byIdOrError should return EntResult<User>\n$output"
        }
    }

    @Test
    fun `byIdOrNull delegates to the driver and hydrates via fromRow`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("driver.byId(Car.TABLE, id)")) {
            "byIdOrNull should call driver.byId with the entity's TABLE constant\n$output"
        }
        assert(output.contains("Car.fromRow(it)")) {
            "byIdOrNull should hydrate the driver's row via Car.fromRow\n$output"
        }
    }

    @Test
    fun `byIdOrThrow wraps byIdOrError and throws via getOrThrow`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("public fun byIdOrThrow(id: Int): Car = byIdOrError(id).getOrThrow()")) {
            "byIdOrThrow should delegate to byIdOrError(id).getOrThrow()\n$output"
        }
    }

    @Test
    fun `visibleByIdOrNull collapses PrivacyDeniedException to null`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("byIdOrNull(id)")) {
            "visibleByIdOrNull should call byIdOrNull\n$output"
        }
        assert(output.contains("catch (_: PrivacyDeniedException)")) {
            "visibleByIdOrNull should catch PrivacyDeniedException\n$output"
        }
    }

    @Test
    fun `byIdOrError maps every failure surface into a structured EntError variant`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // Absence → Err(NotFound) inline (no PrivacyDeniedException
        // for missing rows because byIdOrNull bails before the
        // privacy check).
        assert(
            output.contains(
                "byIdOrNull(id)?.let { EntResult.Ok(it) } ?: EntResult.Err(EntError.NotFound(\"Car\", EntOperation.LOAD, id))",
            ),
        ) {
            "byIdOrError should map non-null to Ok and null to Err(NotFound) inline\n$output"
        }
        // PrivacyDeniedException → Err(PrivacyDenied) with the
        // PrivacyOperation valueOf-mapped to EntOperation.
        assert(
            output.contains(
                "catch (e: PrivacyDeniedException) { EntResult.Err(EntError.PrivacyDenied(e.entity, EntOperation.valueOf(e.operation.name), e.reason)) }",
            ),
        ) {
            "byIdOrError should map PrivacyDeniedException to Err(PrivacyDenied)\n$output"
        }
        // Other Exception → routed through classifyDriverError with
        // EntOperation.LOAD.
        assert(
            output.contains(
                "catch (e: Exception) { EntResult.Err(classifyDriverError(driver, e, \"Car\", EntOperation.LOAD)) }",
            ),
        ) {
            "byIdOrError should route uncaught Exception through classifyDriverError with LOAD operation\n$output"
        }
    }

    @Test
    fun `repo exposes the delete family — removes delete(entity) and deleteById(id)`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // Per the Result Variants RFC the legacy Boolean-returning
        // public delete(entity) and deleteById(id) are removed in
        // favor of the structured *OrThrow / *OrError variants.
        assert(!output.contains("public fun delete(entity:")) {
            "Boolean-returning delete(entity) should be removed in favor of deleteOrThrow / deleteOrError\n$output"
        }
        assert(!output.contains("public fun deleteById(id:")) {
            "Boolean-returning deleteById(id) should be removed in favor of deleteByIdOrError\n$output"
        }
        // KotlinPoet omits the explicit `: Unit` return type in
        // generated output, so match on the parameter shape only.
        assert(output.contains("public fun deleteOrThrow(entity: Car)")) {
            "Should generate deleteOrThrow(entity: Car)\n$output"
        }
        assert(output.contains("public fun deleteOrError(entity: Car): EntResult<Unit>")) {
            "Should generate deleteOrError(entity): EntResult<Unit>\n$output"
        }
        assert(output.contains("public fun deleteByIdOrError(id: Int): EntResult<Boolean>")) {
            "Should generate deleteByIdOrError(id): EntResult<Boolean>\n$output"
        }
    }

    @Test
    fun `deleteLoaded (private) calls hooks around driver delete`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // deleteLoaded is the private workhorse used by deleteOrError
        // (and the bulk deleteMany). The hooks and driver call still
        // live there.
        assert(output.contains("for (hook in beforeDeleteHooks) hook(entity)")) {
            "deleteLoaded should call beforeDelete hooks\n$output"
        }
        assert(output.contains("driver.delete(Car.TABLE, entity.id)")) {
            "deleteLoaded should call driver.delete with entity.id\n$output"
        }
        assert(output.contains("if (deleted) for (hook in afterDeleteHooks) hook(entity)")) {
            "deleteLoaded should call afterDelete hooks on success\n$output"
        }
    }

    @Test
    fun `deleteByIdOrError fetches via driver, returns Ok(false) on missing, and delegates to deleteLoaded`() {
        // The public `delete(entity)` has been removed — deleteByIdOrError
        // runs its own preflight and goes straight to the private
        // `deleteLoaded` helper after the byId read, avoiding any
        // double preflight that calling a hypothetical public
        // delete(entity) would introduce.
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("driver.byId(Car.TABLE, id)")) {
            "deleteByIdOrError should fetch entity via driver (bypassing LOAD privacy)\n$output"
        }
        assert(output.contains("deleteLoaded(Car.fromRow(row))") || output.contains("deleteLoaded(entity)")) {
            "deleteByIdOrError should delegate to deleteLoaded\n$output"
        }
        // KotlinPoet may wrap "EntResult.Ok(false)" across lines; check that the
        // missing-id Ok(false) branch is emitted at all.
        assert(output.contains("EntResult.Ok(false)")) {
            "deleteByIdOrError should return Ok(false) when the row is missing\n$output"
        }
    }

    @Test
    fun `deleteByIdOrError uses the correct id type for UUID schemas`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("public fun deleteByIdOrError(id: UUID): EntResult<Boolean>")) {
            "deleteByIdOrError should use UUID for User's id type\n$output"
        }
    }

    @Test
    fun `create passes client and hook lists to the builder`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("CarCreate(driver, client, beforeSaveHooks, beforeCreateHooks, afterCreateHooks)")) {
            "create should pass client and hook lists to CarCreate\n$output"
        }
    }

    @Test
    fun `update passes client, consistency, and hook lists to the builder`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("CarUpdate(driver, client, id, consistency, beforeSaveHooks, beforeUpdateHooks, afterUpdateHooks)")) {
            "update should pass client, consistency, and hook lists to CarUpdate\n$output"
        }
    }

    @Test
    fun `repo has lateinit client property`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("internal lateinit var client: EntClient")) {
            "Should have internal lateinit var client\n$output"
        }
    }

    @Test
    fun `repo registers the entity schema in its init block`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("driver.register(Car.SCHEMA)")) {
            "Repo should register Car.SCHEMA with the driver on construction\n$output"
        }
    }

    @Test
    fun `repo has applyHooks that copies from entity hooks config`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

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
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

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
    fun `repo does not expose hook registration methods`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(!output.contains("fun onBeforeSave")) {
            "Should not have onBeforeSave — hooks are registered via client config DSL\n$output"
        }
        assert(!output.contains("fun onAfterCreate")) {
            "Should not have onAfterCreate — hooks are registered via client config DSL\n$output"
        }
    }

    @Test
    fun `repo exposes createManyOrError with vararg blocks — legacy createMany is removed`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // The legacy throwing `createMany(*blocks): List<Car>` is
        // removed in favor of the structured-result variant per the
        // Result Variants RFC. createManyOrError is transaction-
        // required (preflight throws TransactionRequiredException
        // outside a tx, regardless of the client's TransactionRequirement).
        assert(!output.contains("public fun createMany(")) {
            "Boolean-returning createMany should be removed\n$output"
        }
        assert(output.contains("fun createManyOrError(vararg blocks: CarCreate.() -> Unit): EntResult<List<Car>>")) {
            "Should have createManyOrError with vararg blocks returning EntResult<List<Car>>\n$output"
        }
    }

    @Test
    fun `createManyOrError delegates to create and saveOrError for hook support`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // Each per-block create() goes through the normal saveOrError
        // pipeline (hooks, privacy, validation, classification). The
        // short-circuit loop wraps each `create(block).saveOrError()`
        // so the pattern lands as `create(block).saveOrError()` inside
        // a `for (block in blocks)` body.
        assert(output.contains("create(block).saveOrError()")) {
            "createManyOrError should delegate to create(block).saveOrError() so hooks fire\n$output"
        }
        // Short-circuit on first Err: verifies the implementation
        // doesn't run subsequent blocks after a failure (the prior
        // blocks.map { ... } shape ran every block before checking).
        assert(output.contains("for (block in blocks)") || output.contains("for(block in blocks)")) {
            "createManyOrError should iterate blocks explicitly so it can break on first Err\n$output"
        }
        assert(output.contains("firstErr = r; break") || output.contains("firstErr = r\n        break")) {
            "createManyOrError should break out of the loop on the first Err\n$output"
        }
    }

    @Test
    fun `repo exposes deleteMany with vararg predicates`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("fun deleteMany(vararg predicates: Predicate<Car>): Int")) {
            "Should have deleteMany with vararg typed Predicate<Car>\n$output"
        }
    }

    @Test
    fun `deleteMany queries then deletes through deleteLoaded (hook path, no double preflight)`() {
        // Per-entity deletes go through `deleteLoaded` so they get the
        // hook/privacy/validation path without re-running deleteMany's
        // outer multi-write preflight per row.
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("if (deleteLoaded(entity)) count++")) {
            "deleteMany should delegate to deleteLoaded(entity), not public delete (avoids per-row preflight)\n$output"
        }
        assert(!output.contains("if (delete(entity)) count++")) {
            "deleteMany should not loop through public delete(entity) — that would re-run preflight per row\n$output"
        }
    }

    @Test
    fun `repo has privacy config property`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("internal val privacyConfig: CarPrivacyConfig")) {
            "Should have internal privacyConfig property\n$output"
        }
    }

    @Test
    fun `repo has applyPrivacy and copyPrivacyFrom`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("fun applyPrivacy(config: CarPrivacyConfig)")) {
            "Should have applyPrivacy method\n$output"
        }
        assert(output.contains("fun copyPrivacyFrom(other: CarRepo)")) {
            "Should have copyPrivacyFrom method\n$output"
        }
    }

    @Test
    fun `repo has hasPrivacy methods for all operations`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

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
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

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
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("val privacy = client.currentPrivacyContext()")) {
            "byId should capture privacy context\n$output"
        }
        assert(output.contains("evaluateLoadPrivacy(privacy, entity)")) {
            "byId should call evaluateLoadPrivacy\n$output"
        }
    }

    @Test
    fun `delete enforces delete privacy`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("val candidate = buildDeleteCandidate(entity)")) {
            "delete should build delete candidate\n$output"
        }
        assert(output.contains("evaluateDeletePrivacy(privacy, entity, candidate)")) {
            "delete should call evaluateDeletePrivacy\n$output"
        }
    }

    @Test
    fun `deleteMany routes candidate selection through DELETE_CANDIDATES interceptor chain`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // Per the read-path-interceptors + soft-delete RFCs:
        // deleteMany candidate selection now fires interceptors
        // with operation = DELETE_CANDIDATES (entOperation = DELETE)
        // so tenant-scoping / soft-delete predicate-shaping
        // interceptors apply uniformly to bulk deletes. The driver
        // call uses the post-interceptor spec.predicates rather
        // than raw `predicates.toList()`.
        assert(output.contains("CarQuery(driver, client).also { it.predicates = predicates.toList() }")) {
            "deleteMany should construct a transient CarQuery from caller predicates\n$output"
        }
        assert(output.contains("runReadInterceptors(ReadOperation.DELETE_CANDIDATES, EntOperation.DELETE)")) {
            "deleteMany should fire interceptors with DELETE_CANDIDATES / DELETE\n$output"
        }
        assert(output.contains("driver.query(Car.TABLE, spec.predicates, emptyList(), null, null)")) {
            "deleteMany should pass the post-interceptor spec.predicates to driver.query\n$output"
        }
        // Negative guard: the pre-fix raw shape must not reappear.
        assert(!output.contains("driver.query(Car.TABLE, predicates.toList()")) {
            "deleteMany must NOT pass raw caller predicates to driver.query (skips interceptors)\n$output"
        }
    }

    @Test
    fun `explicit id create takes id as first parameter`() {
        val session = Session()
        finalize(session)
        val output = generator.generate("Session", session).toString()

        assert(output.contains("fun create(id: String, block: SessionCreate.() -> Unit): SessionCreate")) {
            "create() should take id as first parameter for EXPLICIT strategy\n$output"
        }
    }

    @Test
    fun `explicit id create passes id to constructor`() {
        val session = Session()
        finalize(session)
        val output = generator.generate("Session", session).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("SessionCreate(driver, client, beforeSaveHooks, beforeCreateHooks, afterCreateHooks, id = id)")) {
            "create() should pass id to SessionCreate constructor\n$output"
        }
    }

    @Test
    fun `explicit id repo does not generate createMany`() {
        val session = Session()
        finalize(session)
        val output = generator.generate("Session", session).toString()

        assert(!output.contains("fun createMany")) {
            "Should not generate createMany for EXPLICIT id strategy\n$output"
        }
    }

    @Test
    fun `repo has validation config property`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("internal val validationConfig: CarValidationConfig")) {
            "Should have internal validationConfig property\n$output"
        }
    }

    @Test
    fun `repo has applyValidation and copyValidationFrom`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("fun applyValidation(config: CarValidationConfig)")) {
            "Should have applyValidation method\n$output"
        }
        assert(output.contains("fun copyValidationFrom(other: CarRepo)")) {
            "Should have copyValidationFrom method\n$output"
        }
    }

    @Test
    fun `repo has evaluate methods for create, update, and delete validation`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

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

    // ---------- RFC #5 Phase 5: edgeChanges parameter on evaluateUpdate signatures ----------

    @Test
    fun `evaluateUpdatePrivacy takes edgeChanges of the per-entity view type`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // Parameter list ends with `edgeChanges: ${Schema}EdgeChangesView`.
        // The context constructor call threads it through verbatim.
        assert(output.contains("edgeChanges: CarEdgeChangesView")) {
            "evaluateUpdatePrivacy should accept edgeChanges: CarEdgeChangesView\n$output"
        }
        assert(output.contains(
            "CarUpdatePrivacyContext(privacy, privacyClient, before, requestedPatch, effectivePatch, candidate, edgeChanges)",
        )) {
            "Constructor call should thread edgeChanges through as the final positional arg\n$output"
        }
    }

    @Test
    fun `evaluateUpdateValidation takes edgeChanges of the per-entity view type`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("edgeChanges: CarEdgeChangesView")) {
            "evaluateUpdateValidation should accept edgeChanges: CarEdgeChangesView\n$output"
        }
        assert(output.contains(
            "CarUpdateValidationContext(validationClient, before, requestedPatch, effectivePatch, candidate, edgeChanges)",
        )) {
            "Validation context constructor call should thread edgeChanges through\n$output"
        }
    }

    @Test
    fun `delete enforces delete validation after privacy`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("evaluateDeleteValidation(entity, candidate)")) {
            "delete should call evaluateDeleteValidation\n$output"
        }
    }

    @Test
    fun `evaluateCreateValidation uses system-scoped client`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("client.withFixedPrivacyContextForInternalUse(PrivacyContext(Viewer.System))")) {
            "Validation evaluator should use system-scoped client\n$output"
        }
    }
}
