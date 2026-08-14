package entkt.codegen

import entkt.codegen.client.RepoGenerator
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

        assert(output.contains("import entkt.runtime.driver.Driver")) { "Should import Driver\n$output" }
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
        assert(output.contains("fun update(\n    id: Int,\n    consistency: UpdateConsistency = client.defaultUpdateConsistency,\n    relationshipLocking: RelationshipLocking = client.defaultRelationshipLocking,\n    block: CarUpdate.() -> Unit,\n  ): CarUpdate")) {
            "Should have update(id, consistency, relationshipLocking, block) — update is rooted by id, with per-save UpdateConsistency + RelationshipLocking overrides\n$output"
        }
    }

    @Test
    fun `repo exposes findById and explainFindById taking the schema id type`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        // User has UUID id. The canonical primary-key lookup is the
        // single `findById(id): ReadResult<Entity?>` (absence is a
        // successful null payload) plus its diagnostic explain — the
        // old byId / *OrNull / *OrThrow / *OrError family is gone.
        assert(output.contains("public fun findById(id: UUID): ReadResult<User?>")) {
            "findById should take the schema id type and return ReadResult<User?>\n$output"
        }
        assert(output.contains("public fun explainFindById(id: UUID): QueryPlan")) {
            "explainFindById should take the schema id type and return QueryPlan\n$output"
        }
    }

    @Test
    fun `findById routes BY_ID through the interceptor chain and hydrates via fromRow`() {
        val car = Car()
        finalize(car, User())
        val raw = generator.generate("Car", car).toString()
        val output = raw.replace("\\s+".toRegex(), " ")

        // The by-id read runs the generated query's interceptor chain
        // with operation = BY_ID, carrying the id as an extraStructural
        // leaf, so by-id reads cannot bypass predicate-shaping
        // interceptors (tenant scoping, soft-delete).
        assert(
            output.contains(
                "val spec = q.runReadInterceptors( operation = ReadOperation.BY_ID, extraStructural = listOf(Predicate.Leaf<Car>(\"id\", Op.EQ, id)), )",
            ),
        ) {
            "findById should run interceptors with BY_ID and the id as an extraStructural leaf\n$output"
        }
        // The row read uses driver.query with the post-interceptor
        // predicates (NOT driver.byId — a PK lookup would ignore
        // interceptor-added predicates), limit 1.
        assert(output.contains("driver.query(Car.TABLE, spec.predicates, emptyList(), 1, null).firstOrNull()")) {
            "findById should query with the post-interceptor spec.predicates, limit 1\n$output"
        }
        assert(output.contains("val entity = row?.let { Car.fromRow(it) }")) {
            "findById should hydrate the driver's row via Car.fromRow\n$output"
        }
        // Negative guard on the findById body itself: no raw PK lookup.
        val body = raw.substring(raw.indexOf("fun findById("), raw.indexOf("fun explainFindById("))
        assert(!body.contains("driver.byId(")) {
            "findById must not use driver.byId — interceptor predicates would be silently dropped\n$body"
        }
    }

    @Test
    fun `findById captures every failure through the canonical read capture boundary`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // Expression-body try: presence and absence are both Success
        // (entity is nullable); cancellation rethrows; every other
        // exception is captured as ReadResult.Failed via the guarded
        // factory. No EntError / EntResult wrapper anywhere.
        assert(output.contains("public fun findById(id: Int): ReadResult<Car?> = try {")) {
            "findById should open the canonical expression-body capture boundary\n$output"
        }
        assert(output.contains("ReadResult.Success(entity)")) {
            "findById should return Success(entity) — Success(null) is authoritative absence\n$output"
        }
        assert(
            output.contains(
                "} catch (e: CancellationException) { throw e } catch (e: Exception) { ReadResult.failedForInternalUse(e) }",
            ),
        ) {
            "findById should rethrow cancellation and capture other exceptions as ReadResult.Failed\n$output"
        }
    }

    @Test
    fun `findById enforces load privacy as a typed Root denial`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("val privacy = client.currentPrivacyContext()")) {
            "findById should capture the privacy context\n$output"
        }
        assert(output.contains("val denial = loadDenialOrNull(privacy, entity)")) {
            "findById should evaluate LOAD privacy via the decision-returning loadDenialOrNull\n$output"
        }
        assert(
            output.contains(
                "return ReadResult.failedForInternalUse(EntPrivacyDeniedException(LoadDenialOrigin.Root, listOf(denial)))",
            ),
        ) {
            "a selected-but-denied row should be Failed(EntPrivacyDeniedException(Root, listOf(denial)))\n$output"
        }
    }

    @Test
    fun `explainFindById returns a rejected QueryPlan instead of throwing`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // Explain stays outside the result algebra: interceptor
        // rejection is a diagnostic plan, not an exception, and the
        // plan hardwires the runtime call's limit = 1 / offset = null.
        assert(output.contains("q.buildQueryPlan(spec.copy(limit = 1, offset = null), includeEager = false)")) {
            "explainFindById should build the plan with limit 1 / no offset, no eager\n$output"
        }
        assert(output.contains("} catch (e: EntQueryRejectedException) { QueryPlan.rejected(e) }")) {
            "explainFindById should convert interceptor rejection to QueryPlan.rejected\n$output"
        }
    }

    @Test
    fun `repo exposes the canonical delete family`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // delete(entity) is the idempotent id-handle delete;
        // deleteById(id) preserves the affected-row Boolean; both are
        // MutationResult terminals (the *OrThrow / *OrError variants
        // are gone — getOrThrow() on the result is the projection).
        assert(output.contains("public fun delete(entity: Car): MutationResult<Unit>")) {
            "Should generate delete(entity): MutationResult<Unit>\n$output"
        }
        assert(output.contains("public fun deleteById(id: Int): MutationResult<Boolean>")) {
            "Should generate deleteById(id): MutationResult<Boolean>\n$output"
        }
    }

    @Test
    fun `legacy result-variant surface is removed`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // Compact absence pin for the whole pre-algebra surface. One
        // assertion per removed family, not per removed name.
        assert(!output.contains("fun byId")) {
            "byId / byIdOrNull / byIdOrThrow / byIdOrError should be gone (findById is canonical)\n$output"
        }
        assert(!output.contains("visibleByIdOrNull")) {
            "visibleByIdOrNull should be gone — privacy-as-absence is the visibleOrNull() projection\n$output"
        }
        assert(!output.contains("deleteOrThrow") && !output.contains("deleteOrError") && !output.contains("deleteByIdOrError")) {
            "delete *OrThrow / *OrError variants should be gone (delete/deleteById are canonical)\n$output"
        }
        assert(!output.contains("createManyOrError") && !output.contains("saveOrError")) {
            "createManyOrError / saveOrError should be gone (createMany + executeSaveForInternalUse are canonical)\n$output"
        }
        assert(!output.contains("EntResult") && !output.contains("EntError")) {
            "The EntResult / EntError types should not be referenced anywhere\n$output"
        }
        assert(
            !output.contains("evaluateLoadPrivacy") && !output.contains("evaluateCreatePrivacy") &&
                !output.contains("evaluateUpdatePrivacy") && !output.contains("evaluateDeletePrivacy"),
        ) {
            "Throwing evaluate*Privacy members should be gone (denial evaluators are decision-returning)\n$output"
        }
    }

    @Test
    fun `delete treats the entity as an id handle - reloads and returns Success on absence`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // The passed entity is an id handle only: delete reloads the
        // current row (bypassing LOAD privacy — the delete rule is the
        // authoritative check), maps absence to Success(Unit), and
        // funnels a present row through the shared deleteLoaded
        // pipeline, discarding its Boolean.
        assert(output.contains("client.checkTransactionRequirement(\"Car delete\")")) {
            "delete should run the transaction-requirement preflight\n$output"
        }
        assert(output.contains("driver.byId(Car.TABLE, entity.id)")) {
            "delete should reload the current row via driver.byId (bypassing LOAD privacy)\n$output"
        }
        assert(
            output.contains(
                "if (row == null) { MutationResult.Success(Unit) } else { when (val result = deleteLoaded(Car.fromRow(row))) { is MutationResult.Success -> MutationResult.Success(Unit) is MutationResult.Failed -> result } }",
            ),
        ) {
            "delete should map absence to Success(Unit) and delegate present rows to deleteLoaded\n$output"
        }
    }

    @Test
    fun `deleteLoaded (private) calls hooks around driver delete`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // deleteLoaded is the private workhorse shared by delete,
        // deleteById, and deleteMany. Hooks and the driver call live
        // there; after-delete hooks run only when the row was removed,
        // after the write state advanced past the persist point.
        assert(output.contains("private fun deleteLoaded(entity: Car): MutationResult<Boolean>")) {
            "deleteLoaded should be the private MutationResult<Boolean> pipeline\n$output"
        }
        assert(output.contains("for (hook in beforeDeleteHooks) hook(entity)")) {
            "deleteLoaded should call beforeDelete hooks\n$output"
        }
        assert(output.contains("driver.delete(Car.TABLE, entity.id)")) {
            "deleteLoaded should call driver.delete with entity.id\n$output"
        }
        assert(
            output.contains(
                "if (deleted) { writeState = postWriteState for (hook in afterDeleteHooks) hook(entity) }",
            ),
        ) {
            "deleteLoaded should advance writeState and run afterDelete hooks only when the row was removed\n$output"
        }
        assert(output.contains("return MutationResult.Success(deleted)")) {
            "deleteLoaded should return Success(deleted) — false when the row vanished pre-delete\n$output"
        }
    }

    @Test
    fun `deleteById fetches via driver, returns Success(false) on missing, and delegates to deleteLoaded`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // deleteById runs its own preflight and goes straight to the
        // private deleteLoaded helper after the byId read — no double
        // preflight, and absence is Success(false), never
        // EntTargetAbsentException.
        assert(output.contains("driver.byId(Car.TABLE, id)")) {
            "deleteById should fetch the row via driver (bypassing LOAD privacy)\n$output"
        }
        assert(
            output.contains(
                "if (row == null) { MutationResult.Success(false) } else { deleteLoaded(Car.fromRow(row)) }",
            ),
        ) {
            "deleteById should map absence to Success(false) and delegate to deleteLoaded\n$output"
        }
    }

    @Test
    fun `deleteById uses the correct id type for UUID schemas`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("public fun deleteById(id: UUID): MutationResult<Boolean>")) {
            "deleteById should use UUID for User's id type\n$output"
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
    fun `update passes client, consistency, relationshipLocking, and hook lists to the builder`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("CarUpdate(driver, client, id, consistency, relationshipLocking, beforeSaveHooks, beforeUpdateHooks, afterUpdateHooks)")) {
            "update should pass client, consistency, relationshipLocking, and hook lists to CarUpdate\n$output"
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
    fun `repo exposes canonical createMany with vararg blocks`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // Canonical createMany IS the surface: one MutationResult for
        // the whole atomic batch. The multi-write preflight classifies
        // against the caller's transaction posture; zero blocks are
        // Success(emptyList()) with no transaction work.
        assert(output.contains("public fun createMany(vararg blocks: CarCreate.() -> Unit): MutationResult<List<Car>>")) {
            "Should have createMany with vararg blocks returning MutationResult<List<Car>>\n$output"
        }
        assert(output.contains("client.checkTransactionRequirement(\"Car createMany\", multiWrite = blocks.size > 1)")) {
            "createMany should run the multi-write transaction-requirement preflight\n$output"
        }
        assert(output.contains("if (blocks.isEmpty()) return MutationResult.Success(emptyList())")) {
            "createMany should return Success(emptyList()) for zero blocks\n$output"
        }
    }

    @Test
    fun `createMany shares the per-row create pipeline and short-circuits on the first failure`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // Each block runs the full per-row create pipeline (hooks,
        // privacy, validation, classification) via the internal
        // executeSaveForInternalUse WITHOUT the disclosure step —
        // disclosure runs batched after all writes. The first Failed
        // returns immediately: no later block runs.
        assert(output.contains("for (block in blocks)")) {
            "createMany should iterate blocks explicitly so it can short-circuit\n$output"
        }
        assert(
            output.contains(
                "when (val result = create(block).executeSaveForInternalUse(applyLoadPrivacy = false)) { is MutationResult.Success -> out.add(result.value) is MutationResult.Failed -> { val rowException = result.exception return if (out.isNotEmpty() && rowException.writeState == MutationWriteState.NotPersisted) { val staged = EntUnexpectedMutationException(MutationWriteState.TransactionPending, rowException) client.replaceTransactionMutationFailure(rowException, staged) MutationResult.failedForInternalUse(staged) } else result } }",
            ),
        ) {
            "createMany should delegate rows through create(block).executeSaveForInternalUse(applyLoadPrivacy = false) and return the first Failed\n$output"
        }
    }

    @Test
    fun `createMany self-delegates through withTransaction outside a caller-owned transaction`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // Atomicity is EntKt-owned when no caller transaction exists:
        // the whole batch runs inside one client.withTransaction (a
        // nested withTransaction would throw, hence the
        // driver.inTransaction branch), sharing the same per-row
        // internal execution through the tx-scoped repo.
        assert(output.contains("if (driver.inTransaction) {")) {
            "createMany should branch on driver.inTransaction\n$output"
        }
        assert(output.contains("val txResult = client.withTransaction { tx ->")) {
            "createMany should open an EntKt-owned transaction outside a caller-owned one\n$output"
        }
        assert(
            output.contains(
                "batch.add(tx.cars.create(block).executeSaveForInternalUse(applyLoadPrivacy = false).orRollback())",
            ),
        ) {
            "the EntKt-owned batch should run per-row creates through the tx-scoped repo with orRollback\n$output"
        }
    }

    @Test
    fun `createMany applies LOAD disclosure per returned entity after all writes`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // Return processing runs after every write: a denial inside a
        // caller-owned transaction reports TransactionPending; in the
        // EntKt-owned path the batch still COMMITS and the denial
        // reports Committed. Both use operation = LOAD and identify the
        // one denied entity by key.
        assert(output.contains("val denial = loadDenialOrNull(privacy, entity)")) {
            "createMany should evaluate LOAD disclosure per returned entity\n$output"
        }
        assert(
            output.contains(
                "val exception = EntMutationPrivacyDeniedException(MutationWriteState.TransactionPending, \"Car\", EntOperation.LOAD, EntityKey(\"id\", entity.id), denial.reason)",
            ),
        ) {
            "a caller-owned-tx disclosure denial should be LOAD + TransactionPending with the entity key\n$output"
        }
        assert(
            output.contains(
                "EntMutationPrivacyDeniedException(MutationWriteState.Committed, \"Car\", EntOperation.LOAD, EntityKey(\"id\", disclosureDeniedId!!), reason)",
            ),
        ) {
            "an EntKt-owned-tx disclosure denial should report Committed — the batch is not rolled back\n$output"
        }
    }

    @Test
    fun `repo exposes deleteMany with vararg predicates`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("public fun deleteMany(vararg predicates: Predicate<Car>): MutationResult<Int>")) {
            "Should have deleteMany with vararg typed Predicate<Car> returning MutationResult<Int>\n$output"
        }
        assert(output.contains("client.checkTransactionRequirement(\"Car deleteMany\", multiWrite = true)")) {
            "deleteMany should run the multi-write transaction-requirement preflight\n$output"
        }
    }

    @Test
    fun `deleteMany queries then deletes through deleteLoaded (hook path, no double preflight)`() {
        // Per-entity deletes go through deleteLoaded so they get the
        // hook/privacy/validation path without re-running deleteMany's
        // outer multi-write preflight per row; the first per-row Failed
        // short-circuits (no denied candidate is silently skipped).
        val car = Car()
        finalize(car, User())
        val raw = generator.generate("Car", car).toString()
        val output = raw.replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "when (val result = deleteLoaded(entity)) { is MutationResult.Success -> if (result.value) count++ is MutationResult.Failed -> { val rowException = result.exception return if (count > 0 && rowException.writeState == MutationWriteState.NotPersisted) { val staged = EntUnexpectedMutationException(MutationWriteState.TransactionPending, rowException) client.replaceTransactionMutationFailure(rowException, staged) MutationResult.failedForInternalUse(staged) } else result } }",
            ),
        ) {
            "deleteMany should count deleteLoaded successes and return the first Failed\n$output"
        }
        val body = raw.substring(raw.indexOf("fun deleteMany("), raw.indexOf("fun _classifyDriverFailure("))
        assert(!body.contains("delete(entity)")) {
            "deleteMany must not loop through the public delete(entity) — that would re-run preflight per row\n$body"
        }
    }

    @Test
    fun `deleteMany self-delegates through withTransaction outside a caller-owned transaction`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // Outside a transaction, deleteMany re-enters itself through
        // the canonical client.withTransaction boundary (the nested
        // call takes the caller-owned branch), then converts the
        // TransactionResult: unknown outcome → PersistenceUnknown,
        // recorded EntMutationException passthrough, anything else →
        // NotPersisted after the confirmed rollback.
        assert(output.contains("tx.cars.deleteMany(*predicates).orRollback()")) {
            "deleteMany should self-delegate through the tx-scoped repo with orRollback\n$output"
        }
        assert(
            output.contains(
                "val exception = if (txResult.transactionState == TransactionFailureState.OutcomeUnknown) { EntUnexpectedMutationException(MutationWriteState.PersistenceUnknown, stored) } else if (stored is EntMutationException && stored.writeState == MutationWriteState.NotPersisted) { stored } else { EntUnexpectedMutationException(MutationWriteState.NotPersisted, ((stored as? EntUnexpectedMutationException)?.cause as? Exception) ?: stored) }",
            ),
        ) {
            "deleteMany should convert TransactionResult.Failed with the shared write-state mapping\n$output"
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
    fun `hasPrivacy flags always report true under fail-closed enforcement`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // Privacy is fail-closed: every operation requires an explicit
        // Allow, so enforcement never depends on rule presence and the
        // flags are constant. hasLoadPrivacy overrides the read
        // surface; the write-side flags stay internal.
        assert(output.contains("override fun hasLoadPrivacy(): Boolean = true")) {
            "hasLoadPrivacy should override the read surface and report true\n$output"
        }
        assert(output.contains("internal fun hasCreatePrivacy(): Boolean = true")) {
            "hasCreatePrivacy should report true\n$output"
        }
        assert(output.contains("internal fun hasUpdatePrivacy(): Boolean = true")) {
            "hasUpdatePrivacy should report true\n$output"
        }
        assert(output.contains("internal fun hasDeletePrivacy(): Boolean = true")) {
            "hasDeletePrivacy should report true\n$output"
        }
        assert(!output.contains("loadRules.isNotEmpty()")) {
            "hasLoadPrivacy must not depend on rule presence\n$output"
        }
    }

    @Test
    fun `repo has decision-returning denial evaluators for load, create, update, and delete privacy`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // LOAD returns a keyed PrivacyDenial (aggregatable by strict
        // read terminals); the write side returns the bare denial
        // reason — the terminals construct the typed
        // EntMutationPrivacyDeniedException at their classification
        // point. None of these throw: a rule-THROWN exception escapes
        // to the terminal capture boundary as a foreign failure.
        assert(output.contains("override fun loadDenialOrNull(privacy: PrivacyContext, entity: Car): PrivacyDenial?")) {
            "Should have loadDenialOrNull overriding the read surface\n$output"
        }
        assert(output.contains("internal fun createDenialReasonOrNull(privacy: PrivacyContext, candidate: CarWriteCandidate): String?")) {
            "Should have createDenialReasonOrNull returning String?\n$output"
        }
        assert(
            output.contains(
                "internal fun updateDenialReasonOrNull( privacy: PrivacyContext, before: Car, requestedPatch: CarUpdatePatch, effectivePatch: CarUpdatePatch, candidate: CarWriteCandidate, edgeChanges: CarEdgeChangesView, ): String?",
            ),
        ) {
            "Should have updateDenialReasonOrNull with the full update-context parameter list\n$output"
        }
        assert(
            output.contains(
                "internal fun deleteDenialReasonOrNull( privacy: PrivacyContext, entity: Car, candidate: CarWriteCandidate, ): String?",
            ),
        ) {
            "Should have deleteDenialReasonOrNull taking entity + candidate\n$output"
        }
    }

    @Test
    fun `privacy is fail-closed - default deny reasons when no rule allows`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // With no allowing rule — including no rules at all — the empty
        // loop falls through to the fail-closed default denial. The
        // evaluators RETURN the denial (they never throw), and the
        // PrivacyBypass viewer short-circuits every one of them.
        assert(
            output.contains(
                "return PrivacyDenial(\"Car\", EntityKey(\"id\", entity.id), \"no load rule allowed access\")",
            ),
        ) {
            "loadDenialOrNull should end in the fail-closed keyed denial\n$output"
        }
        assert(output.contains("return \"no create rule allowed access\"")) {
            "createDenialReasonOrNull should end in the fail-closed default reason\n$output"
        }
        assert(output.contains("return \"no update rule allowed access\"")) {
            "updateDenialReasonOrNull should end in the fail-closed default reason\n$output"
        }
        assert(output.contains("return \"no delete rule allowed access\"")) {
            "deleteDenialReasonOrNull should end in the fail-closed default reason\n$output"
        }
        assert(output.contains("is PrivacyDecision.Deny -> return decision.reason")) {
            "write-side rule Deny should RETURN the reason, not throw\n$output"
        }
        val bypasses = Regex(
            Regex.escape("if (privacy.viewer is Viewer.PrivacyBypass) return null")
        ).findAll(output).count()
        assert(bypasses == 4) {
            "All four denial evaluators should short-circuit for PrivacyBypass; found $bypasses\n$output"
        }
    }

    @Test
    fun `delete enforces delete privacy with a typed NotPersisted denial`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("val candidate = buildDeleteCandidate(entity)")) {
            "deleteLoaded should build the delete candidate\n$output"
        }
        assert(output.contains("val denialReason = deleteDenialReasonOrNull(privacy, entity, candidate)")) {
            "deleteLoaded should evaluate delete privacy via deleteDenialReasonOrNull\n$output"
        }
        assert(
            output.contains(
                "val exception = EntMutationPrivacyDeniedException(MutationWriteState.NotPersisted, \"Car\", EntOperation.DELETE, EntityKey(\"id\", entity.id), denialReason)",
            ),
        ) {
            "a returned denial should become EntMutationPrivacyDeniedException(NotPersisted, DELETE) with the entity key\n$output"
        }
    }

    @Test
    fun `deleteMany routes candidate selection through DELETE_CANDIDATES interceptor chain`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // deleteMany candidate selection fires interceptors with
        // operation = DELETE_CANDIDATES so tenant-scoping /
        // soft-delete predicate-shaping interceptors apply uniformly
        // to bulk deletes. The chain takes no entOperation parameter
        // anymore. The generated query's `predicates` backing field is
        // private, so deleteMany seeds it via the public `where()` DSL.
        assert(output.contains("CarQuery(driver, client).apply { for (p in predicates) where(p) }")) {
            "deleteMany should construct a transient CarQuery from caller predicates via the public DSL\n$output"
        }
        assert(output.contains("runReadInterceptors(ReadOperation.DELETE_CANDIDATES)")) {
            "deleteMany should fire interceptors with DELETE_CANDIDATES (no entOperation param)\n$output"
        }
        assert(!output.contains("runReadInterceptors(ReadOperation.DELETE_CANDIDATES, ")) {
            "the dropped entOperation parameter must not reappear\n$output"
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
    fun `every mutation failure construction site records on the transaction coordinator`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // Inside a caller-owned transaction a Failed must mark the
        // scope rollback-only; outside one the record call is a no-op.
        // The invariant is structural: every Failed construction site
        // pairs with a coordinator record call.
        val failed = Regex(Regex.escape("MutationResult.failedForInternalUse(")).findAll(output).count()
        // Batch-level re-reports notify the coordinator through the
        // REPLACING helper (the row failure was already recorded and
        // must be superseded, not duplicated) — both shapes count as
        // the mandatory coordinator notification.
        val recorded = Regex(Regex.escape("client.recordTransactionMutationFailure(")).findAll(output).count() +
            Regex(Regex.escape("client.replaceTransactionMutationFailure(")).findAll(output).count()
        assert(failed > 0) { "Expected MutationResult.Failed construction sites\n$output" }
        assert(failed == recorded) {
            "Every MutationResult.failedForInternalUse site should pair with a coordinator notification; found $failed vs $recorded\n$output"
        }
    }

    @Test
    fun `terminals rethrow cancellation at every capture boundary`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        val catches = Regex(Regex.escape("catch (e: CancellationException)")).findAll(output).count()
        val rethrows = Regex(Regex.escape("catch (e: CancellationException) { throw e }")).findAll(output).count()
        assert(catches > 0) { "Expected cancellation catch clauses\n$output" }
        assert(catches == rethrows) {
            "Every CancellationException catch should immediately rethrow; found $catches catches vs $rethrows rethrows\n$output"
        }
    }

    @Test
    fun `_classifyDriverFailure routes driver exceptions through the driver classification SPI`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // The private helper delegates to driver.classifyException; an
        // unrecognized exception falls back to the caller's
        // phase-tracked write state, and a classification without a
        // state defaults to PersistenceUnknown.
        assert(output.contains("private fun _classifyDriverFailure(e: Exception, fallback: MutationWriteState): EntMutationException")) {
            "Should have the private _classifyDriverFailure helper\n$output"
        }
        assert(output.contains("driver.classifyMutationException(e, \"Car\", EntOperation.DELETE)")) {
            "The helper should call the driver classification SPI with the entity name and operation\n$output"
        }
        assert(output.contains("?: EntUnexpectedMutationException(fallback, e)")) {
            "An unclassified exception should fall back to the phase-tracked write state\n$output"
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

    // ---------- link-table M2M helpers edgeChanges parameter on update evaluator signatures ----------

    @Test
    fun `updateDenialReasonOrNull takes edgeChanges of the per-entity view type`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // Parameter list ends with `edgeChanges: ${Schema}EdgeChangesView`.
        // The context constructor call threads it through verbatim.
        assert(output.contains("edgeChanges: CarEdgeChangesView")) {
            "updateDenialReasonOrNull should accept edgeChanges: CarEdgeChangesView\n$output"
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
        assert(output.contains("EntValidationException(\"Car\", EntOperation.DELETE, violations)")) {
            "violations should become a typed EntValidationException with DELETE\n$output"
        }
        // Privacy runs before validation inside deleteLoaded.
        val privacyAt = output.indexOf("deleteDenialReasonOrNull(privacy, entity, candidate)")
        val validationAt = output.indexOf("evaluateDeleteValidation(entity, candidate)")
        assert(privacyAt in 0 until validationAt) {
            "delete privacy should be evaluated before delete validation\n$output"
        }
    }

    @Test
    fun `validation evaluators hand rules the validation-posture read client`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // The bypass context is fixed inside the adapter, not at the call
        // site — evaluators cannot construct a validation reader under an
        // arbitrary context, so no context literal appears here. All
        // three evaluators (create/update/delete) emit the call
        // independently, so pin the count, not mere presence.
        val mints = Regex(
            Regex.escape("val validationClient = client.asValidationReadClientForInternalUse()")
        ).findAll(output).count()
        assert(mints == 3) {
            "All three validation evaluators should mint the validation-posture read client; found $mints\n$output"
        }
        assert(!output.contains("asReadClientForInternalUse")) {
            "The arbitrary-context adapter must not be called anymore\n$output"
        }
        assert(!output.contains("withFixedPrivacyContextForInternalUse")) {
            "Evaluators must not clone a full write-capable client\n$output"
        }
    }

    @Test
    fun `privacy evaluators hand rules the privacy-posture read client with the caller context`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        // Privacy rule reads are viewer-scoped: the evaluator passes the
        // caller's context into the posture-specific adapter, which
        // freezes it for the reader's lifetime. Each of the four
        // evaluators (load/create/update/delete) emits the call
        // independently — and unlike the nullary validation adapter, this
        // one accepts any context, so a single diverging site would still
        // compile. Pin the count, not mere presence.
        val mints = Regex(
            Regex.escape("val privacyClient = client.asPrivacyReadClientForInternalUse(privacy)")
        ).findAll(output).count()
        assert(mints == 4) {
            "All four privacy evaluators should freeze the caller's context; found $mints\n$output"
        }
    }
}
