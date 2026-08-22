package entkt.codegen

import entkt.codegen.client.RepoGenerator
import entkt.schema.EntSchema
import kotlin.reflect.KClass
import kotlin.test.Test

private fun finalize(vararg schemas: EntSchema) {
    val registry = schemas.associateBy { it::class }
    schemas.forEach { it.finalize(registry) }
}

private class RepoBytesRecord : EntSchema("repo_bytes_records", clientName = "repoBytesRecords") {
    override fun id() = entkt.schema.EntId.long()
    val payload by bytes("payload")
    val thumbnail by bytes("thumbnail").nullable()
}

class RepoGeneratorTest {

    private val generator = RepoGenerator("com.example.ent")

    @Test
    fun `rule evaluators share context and construct a fresh item snapshot for every rule`() {
        val schema = RepoBytesRecord()
        finalize(schema)
        val output = generator.generate("RepoBytesRecord", schema).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "evaluateBatchPrivacyRulesForInternalUse(\"RepoBytesRecord CREATE privacy\", candidateSnapshot, rules, ruleContext) { item -> RepoBytesRecordCreatePrivacyItem",
            ),
        ) {
            "create privacy should delegate through the batch evaluator with a fresh-item factory\n$output"
        }
        assert(
            output.contains(
                "evaluateBatchValidationRulesForInternalUse(\"RepoBytesRecord CREATE validation\", candidateSnapshot, rules, ruleContext) { item -> RepoBytesRecordCreateValidationItem",
            ),
        ) {
            "create validation should delegate through the batch evaluator with a fresh-item factory\n$output"
        }
        assert(
            output.contains(
                "evaluateBatchPrivacyRulesForInternalUse(\"RepoBytesRecord UPDATE privacy\", listOf(candidate), rules, ruleContext) { item -> RepoBytesRecordUpdatePrivacyItem",
            ),
        ) {
            "update privacy should delegate through the batch evaluator with a fresh-item factory\n$output"
        }
        assert(
            output.contains(
                "evaluateBatchValidationRulesForInternalUse(\"RepoBytesRecord UPDATE validation\", listOf(candidate), rules, ruleContext) { item -> RepoBytesRecordUpdateValidationItem",
            ),
        ) {
            "update validation should delegate through the batch evaluator with a fresh-item factory\n$output"
        }
        val privacyContexts = Regex(
            Regex.escape("val ruleContext = PrivacyRuleContext(privacy, privacyClient)"),
        ).findAll(output).count()
        assert(privacyContexts == 4) {
            "Each privacy lifecycle helper should construct shared state exactly once; found $privacyContexts\n$output"
        }
        val validationContexts = Regex(
            Regex.escape("val ruleContext = ValidationRuleContext(validationClient)"),
        ).findAll(output).count()
        assert(validationContexts == 3) {
            "Each validation lifecycle helper should construct shared state exactly once; found $validationContexts\n$output"
        }
        assert(output.contains("item.copy( payload = item.payload.copyOf(), thumbnail = item.thumbnail?.copyOf(), )")) {
            "rule candidates should not alias database-bound byte arrays\n$output"
        }
        assert(output.contains("before.copy( payload = before.payload.copyOf(), thumbnail = before.thumbnail?.copyOf(), )")) {
            "rule before-entities should have isolated byte arrays\n$output"
        }
        assert(output.contains("requestedPatch.copy(")) {
            "requested update patches should copy byte entries\n$output"
        }
        assert(output.contains("effectivePatch.copy(")) {
            "effective update patches should copy byte entries\n$output"
        }
        assert(output.contains("is FieldPatch.Set -> FieldPatch.Set(entry.value.copyOf())")) {
            "required byte patch entries should copy their value\n$output"
        }
        assert(output.contains("is FieldPatch.Set -> FieldPatch.Set(entry.value?.copyOf())")) {
            "nullable byte patch entries should preserve null while copying values\n$output"
        }
        assert(!output.contains("rule.run(")) {
            "generated privacy evaluators should not bypass the shared batch engine\n$output"
        }
        assert(!output.contains("rule.validate(")) {
            "generated validation evaluators should not bypass the shared batch engine\n$output"
        }
    }

    @Test
    fun `generates repo class`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("class CarRepo")) { "Should generate CarRepo\n$output" }
    }

    @Test
    fun `repo takes a DatabaseDriver in its constructor`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("import entkt.runtime.driver.DatabaseDriver")) { "Should import DatabaseDriver\n$output" }
        assert(output.contains("driver: DatabaseDriver")) { "Should take DatabaseDriver in constructor\n$output" }
    }

    @Test
    fun `repo holds the driver as a private property`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("private val driver: DatabaseDriver")) {
            "DatabaseDriver should be a private val\n$output"
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
    fun `repo exposes findById taking the schema id type`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        // User has UUID id. The canonical primary-key lookup is the
        // single `findById(id): ReadResult<Entity?>` (absence is a
        // successful null payload). The old byId / *OrNull /
        // *OrThrow / *OrError family is gone.
        assert(output.contains("public fun findById(id: UUID): ReadResult<User?>")) {
            "findById should take the schema id type and return ReadResult<User?>\n$output"
        }
        assert(!output.contains("explainFindById")) {
            "repositories should not expose a query explain API\n$output"
        }
    }

    @Test
    fun `findById routes BY_ID through the interceptor chain and hydrates via fromRow`() {
        val car = Car()
        finalize(car, User())
        val raw = generator.generate("Car", car).toString()
        val output = raw.replace("\\s+".toRegex(), " ")
        val body = raw.substring(raw.indexOf("fun findById("), raw.indexOf("fun delete("))

        // The repo contributes only the typed id predicate and terminal
        // intent; the runtime root-query pipeline owns preparation,
        // storage loading, decoding, privacy, and failure capture.
        assert(
            output.contains(
                "val query = CarQuery(driver, client) return when (val result = " +
                    "query.readRootQuery( operation = ReadOperation.BY_ID, maximumRows = 1, " +
                    "structuralPredicates = listOf(Predicate.Leaf<Car>(\"id\", Op.EQ, id)), ))",
            ),
        ) {
            "findById should build the id-scoped EntityQuery\n$output"
        }
        assert(output.contains("query.readRootQuery( operation = ReadOperation.BY_ID, maximumRows = 1,")) {
            "findById should delegate BY_ID and its single-row bound to ReadQueryEvaluator\n$output"
        }
        assert(output.contains("ReadResult.Success(result.value.firstOrNull())")) {
            "the repo should adapt the runtime entity list to nullable cardinality\n$output"
        }
        assert(!body.contains("Car.fromRow")) {
            "findById decoding should be runtime-owned\n$body"
        }
        // Negative guard on the findById body itself: no raw PK lookup.
        assert(!body.contains("driver.byId(")) {
            "findById must not use driver.byId — interceptor predicates would be silently dropped\n$body"
        }
    }

    @Test
    fun `findById captures every failure through the canonical read capture boundary`() {
        val car = Car()
        finalize(car, User())
        val raw = generator.generate("Car", car).toString()
        val output = raw.replace("\\s+".toRegex(), " ")

        val body = raw.substring(
            raw.indexOf("public fun findById"),
            raw.indexOf("public fun delete"),
        ).replace("\\s+".toRegex(), " ")
        assert(!body.contains("catch (e:")) {
            "findById should reuse ReadQueryEvaluator's single failure boundary\n$body"
        }
        assert(body.contains("is ReadResult.Failed -> result")) {
            "findById should preserve runtime failures without wrapping them\n$body"
        }
    }

    @Test
    fun `findById enforces load privacy as a typed Root denial`() {
        val car = Car()
        finalize(car, User())
        val raw = generator.generate("Car", car).toString()
        val output = raw.replace("\\s+".toRegex(), " ")

        val body = raw.substring(
            raw.indexOf("public fun findById"),
            raw.indexOf("public fun delete"),
        ).replace("\\s+".toRegex(), " ")
        assert(body.contains("query.readRootQuery( operation = ReadOperation.BY_ID, maximumRows = 1,")) {
            "findById should use the same ReadQueryEvaluator root-privacy lifecycle as entity queries\n$body"
        }
        assert(!body.contains("loadDenialOrNull")) {
            "the generated repo should not duplicate LOAD-privacy evaluation\n$body"
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
        assert(output.contains("runBatchHooksForInternalUse(listOf(entity), beforeDeleteHooks)")) {
            "deleteLoaded should call beforeDelete hooks\n$output"
        }
        assert(output.contains("driver.delete(Car.TABLE, entity.id)")) {
            "deleteLoaded should call driver.delete with entity.id\n$output"
        }
        assert(
            output.contains(
                "if (deleted) { writeState = postWriteState runBatchHooksForInternalUse(listOf(entity), afterDeleteHooks) }",
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
    fun `repo keeps its client backlink private and exposes only the guarded attach method`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("private lateinit var client: EntClient")) {
            "The full client backlink must not escape through a transaction facade's repo\n$output"
        }
        assert(output.contains("internal fun attachClientForInternalUse(client: EntClient)")) {
            "EntClient should retain a guarded generated wiring path\n$output"
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
    fun `createMany runs phase-major lifecycle work before one set-based insert`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("val managedSaveFailures = ArrayList<EntMutationException>()")) {
            "createMany should share one encounter-ordered managed-save tracker\n$output"
        }
        assert(
            output.contains(
                "val builders = ArrayList<CarCreate>(blocks.size) for (block in blocks) { " +
                    "val builder = create { } val configured = try { " +
                    "builder.configureForCreateManyForInternalUse(block, managedSaveFailures)",
            ),
        ) {
            "createMany should instantiate and safely configure every builder in input order\n$output"
        }
        assert(output.contains("runBatchHooksForInternalUse(builders.map { it.beforeSaveHookValueForInternalUse() }, beforeSaveHooks)")) {
            "createMany should run beforeSave once over the full ordered batch\n$output"
        }
        assert(output.contains("runBatchHooksForInternalUse(builders.map { it.beforeCreateHookValueForInternalUse() }, beforeCreateHooks)")) {
            "createMany should run beforeCreate once over the full ordered batch\n$output"
        }
        assert(output.contains("val denialReasons = createDenialReasons(privacy, candidates)")) {
            "CREATE privacy should evaluate the complete candidate list\n$output"
        }
        assert(output.contains("val violationsByCandidate = evaluateCreateValidations(candidates)")) {
            "CREATE validation should evaluate only after batch privacy\n$output"
        }
        assert(output.contains("driver.insertMany(Car.TABLE, prepared.map { it.values })")) {
            "createMany should make one logical set-based driver call\n$output"
        }
        assert(
            output.contains(
                "if (promoteDriverNotPersisted && prepared.size > 1 && " +
                    "classified.writeState == MutationWriteState.NotPersisted)",
            ),
        ) {
            "caller-owned multi-input batches should promote statement-level failures to pending\n$output"
        }
        assert(output.contains("check(rows.size == prepared.size)")) {
            "createMany should reject malformed driver cardinality before hydration\n$output"
        }
        assert(output.contains("val entities = rows.map { row -> Car.fromRow(row) } runBatchHooksForInternalUse(entities, afterCreateHooks)")) {
            "createMany should hydrate the whole result before the afterCreate phase\n$output"
        }
        assert(!output.contains("executeSaveForInternalUse(applyLoadPrivacy = false)")) {
            "createMany must not fall back to the scalar per-row pipeline\n$output"
        }
    }

    @Test
    fun `createMany self-delegates through withTransaction outside a caller-owned transaction`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // Atomicity is EntKt-owned when no caller transaction exists;
        // all phases run through the transaction-scoped repo helper.
        assert(output.contains("if (driver.inTransaction) {")) {
            "createMany should branch on driver.inTransaction\n$output"
        }
        assert(output.contains("val txResult = client.withTransaction { tx ->")) {
            "createMany should open an EntKt-owned transaction outside a caller-owned one\n$output"
        }
        assert(
            output.contains(
                "val completion = tx.cars._executeCreateManyWritePhases(" +
                    "blockSnapshot, promoteDriverNotPersisted = false).orRollback()",
            ),
        ) {
            "the EntKt-owned batch should run the phase-major write helper through the tx-scoped repo\n$output"
        }
    }

    @Test
    fun `createMany batch evaluates returned LOAD with the captured CREATE context`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("loadDenials(completion.privacy, completion.entities).filterNotNull().firstOrNull()")) {
            "caller-owned createMany should batch LOAD disclosure with the captured context\n$output"
        }
        assert(output.contains("tx.cars.loadDenials(completion.privacy, completion.entities).filterNotNull().firstOrNull()")) {
            "owned createMany should batch LOAD disclosure through the transaction repo\n$output"
        }
        assert(
            output.contains(
                "val exception = EntMutationPrivacyDeniedException(MutationWriteState.TransactionPending, \"Car\", EntOperation.LOAD, denial.entityKey, denial.reason)",
            ),
        ) {
            "a caller-owned-tx disclosure denial should be LOAD + TransactionPending with the entity key\n$output"
        }
        assert(
            output.contains(
                "EntMutationPrivacyDeniedException(MutationWriteState.Committed, \"Car\", EntOperation.LOAD, disclosure.denial.entityKey, disclosure.denial.reason)",
            ),
        ) {
            "an EntKt-owned-tx disclosure denial should report Committed — the batch is not rolled back\n$output"
        }
        assert(output.contains("var disclosureFailure: Exception? = null")) { output }
        assert(output.contains("disclosureFailure = e")) { output }
        assert(output.contains("EntUnexpectedMutationException(MutationWriteState.NotPersisted, disclosure)")) {
            "a LOAD exception that aborts the owned transaction should remain the confirmed-rollback cause\n$output"
        }
        assert(output.contains("rolledBack.addSuppressed(stored)")) { output }
        assert(output.contains("unknown.addSuppressed(disclosure)")) {
            "an unknown transaction outcome must remain primary while retaining the LOAD failure\n$output"
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
    fun `deleteMany phases preflight the batch then use one ID-returning write`() {
        val car = Car()
        finalize(car, User())
        val raw = generator.generate("Car", car).toString()
        val output = raw.replace("\\s+".toRegex(), " ")

        assert(output.contains("private fun _executeDeleteManyPhases(")) {
            "deleteMany should isolate its transaction-scoped phase-major pipeline\n$output"
        }
        val privacyAt = output.indexOf("deleteDenialReasons(privacy, entities, candidates)")
        val validationAt = output.indexOf("evaluateDeleteValidations(entities, candidates)")
        val beforeHookAt = output.indexOf("runBatchHooksForInternalUse(entities, beforeDeleteHooks)")
        val writeAt = output.indexOf(
            "driver.deleteManyByIds(Car.TABLE, Car.SCHEMA.idColumn, approvedIds, effectivePredicates)",
        )
        val afterHookAt = output.indexOf("runBatchHooksForInternalUse(deletedEntities, afterDeleteHooks)")
        assert(privacyAt >= 0 && privacyAt < validationAt && validationAt < beforeHookAt && beforeHookAt < writeAt) {
            "deleteMany should finish privacy, validation, and beforeDelete before its one write\n$output"
        }
        assert(writeAt < afterHookAt) {
            "deleteMany should run afterDelete only after the ID-returning write\n$output"
        }
        assert(
            output.contains(
                "check(deletedIdSnapshot.size == deletedIdSet.size && deletedIdSet.all { it in approvedIdSet })",
            ),
        ) {
            "deleteMany should reject duplicate or unapproved returned IDs before afterDelete\n$output"
        }
        assert(output.contains("val deletedEntities = entities.filter { it.id in deletedIdSet }")) {
            "deleteMany should restore returned IDs to candidate encounter order\n$output"
        }
        val phases = raw.substring(
            raw.indexOf("fun _executeDeleteManyPhases("),
            raw.indexOf("fun deleteMany("),
        )
        assert(!phases.contains("deleteLoaded(") && !phases.contains("driver.delete(")) {
            "deleteMany must not fall back to the scalar delete pipeline\n$phases"
        }
    }

    @Test
    fun `deleteMany delegates its private phases through one owned transaction`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "if (driver.inTransaction) { _executeDeleteManyPhases(predicateSnapshot, promoteDriverNotPersisted = true)",
            ),
        ) {
            "caller-owned deleteMany should run the private phases in place\n$output"
        }
        assert(
            output.contains(
                "tx.cars._executeDeleteManyPhases(predicateSnapshot, promoteDriverNotPersisted = false).orRollback()",
            ),
        ) {
            "deleteMany should run the same private phases once in its owned transaction\n$output"
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
    fun `repo has positional plural LOAD evaluator plus singleton and write denial evaluators`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // LOAD evaluates the immutable entity snapshot in one batch and
        // maps decisions back by position. This preserves duplicate IDs
        // and caller order; the singleton API is only a projection of the
        // plural contract.
        assert(output.contains("override fun loadDenials(privacy: PrivacyContext, entities: List<Car>): List<PrivacyDenial?>")) {
            "Should expose the plural LOAD evaluator through the read surface\n$output"
        }
        assert(output.contains("if (entities.isEmpty()) return emptyList() val entitySnapshot = entities.toList()")) {
            "Plural LOAD evaluation should skip empty batches and snapshot non-empty input\n$output"
        }
        assert(
            output.contains(
                "val decisions = evaluateBatchPrivacyRulesForInternalUse(\"Car LOAD privacy\", entitySnapshot, rules, ruleContext) { item -> CarLoadPrivacyItem(item) }",
            ),
        ) {
            "Plural LOAD evaluation should submit the complete ordered snapshot to the batch engine\n$output"
        }
        assert(output.contains("return entitySnapshot.mapIndexed { index, entity -> when (val decision = decisions[index])")) {
            "Plural LOAD decisions should remain positionally aligned with their entities\n$output"
        }
        assert(
            output.contains(
                "is PrivacyDecision.Deny -> PrivacyDenial(\"Car\", EntityKey(\"id\", entity.id), decision.reason)",
            ),
        ) {
            "Each positional LOAD denial should be keyed from the corresponding entity\n$output"
        }
        assert(output.contains("override fun loadDenialOrNull(privacy: PrivacyContext, entity: Car): PrivacyDenial?")) {
            "Should have loadDenialOrNull overriding the read surface\n$output"
        }
        assert(output.contains("loadDenialOrNull(privacy: PrivacyContext, entity: Car): PrivacyDenial? = loadDenials(privacy, listOf(entity)).single()")) {
            "Singleton LOAD evaluation should delegate to the plural evaluator\n$output"
        }

        // The write side returns the bare denial reason — terminals
        // construct EntMutationPrivacyDeniedException at classification.
        // A rule-thrown exception escapes every helper to the terminal
        // capture boundary as an operational failure.
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

        // With no allowing rule — including no rules at all — the
        // evaluators return the fail-closed denial (they never throw).
        // Plural LOAD, CREATE, and DELETE paths preserve positional
        // cardinality for a bypass viewer; singleton UPDATE returns null.
        assert(
            output.contains(
                "is PrivacyDecision.Continue -> PrivacyDenial(\"Car\", EntityKey(\"id\", entity.id), \"no load rule allowed access\")",
            ),
        ) {
            "loadDenials should map a continued decision to the fail-closed keyed denial\n$output"
        }
        assert(output.contains("is PrivacyDecision.Continue -> \"no create rule allowed access\"")) {
            "createDenialReasonOrNull should end in the fail-closed default reason\n$output"
        }
        assert(output.contains("is PrivacyDecision.Continue -> \"no update rule allowed access\"")) {
            "updateDenialReasonOrNull should end in the fail-closed default reason\n$output"
        }
        assert(output.contains("is PrivacyDecision.Continue -> \"no delete rule allowed access\"")) {
            "deleteDenialReasonOrNull should end in the fail-closed default reason\n$output"
        }
        assert(output.contains("is PrivacyDecision.Deny -> decision.reason")) {
            "write-side rule Deny should RETURN the reason, not throw\n$output"
        }
        assert(
            output.contains(
                "if (privacy.viewer is Viewer.PrivacyBypass) return List(entitySnapshot.size) { null }",
            ),
        ) {
            "Plural LOAD bypass should retain one null slot per snapshotted entity\n$output"
        }
        assert(
            output.contains(
                "if (privacy.viewer is Viewer.PrivacyBypass) return List(candidateSnapshot.size) { null }",
            ),
        ) {
            "Plural CREATE bypass should retain one null slot per candidate\n$output"
        }
        val entityBatchBypasses = Regex(
            Regex.escape("if (privacy.viewer is Viewer.PrivacyBypass) return List(entitySnapshot.size) { null }")
        ).findAll(output).count()
        assert(entityBatchBypasses == 2) {
            "LOAD and DELETE batch evaluators should retain positional nulls; found $entityBatchBypasses\n$output"
        }
        val scalarWriteBypasses = Regex(
            Regex.escape("if (privacy.viewer is Viewer.PrivacyBypass) return null")
        ).findAll(output).count()
        assert(scalarWriteBypasses == 1) {
            "The singleton UPDATE evaluator should return null for PrivacyBypass; found $scalarWriteBypasses\n$output"
        }
    }

    @Test
    fun `derived create privacy evaluates only unresolved writes under the enclosing lifecycle`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "if (decision is PrivacyDecision.Continue && privacyConfig.updateDerivesFromCreate) { decision = evaluateBatchPrivacyRulesForInternalUse(\"Car UPDATE privacy\", listOf(candidate), privacyConfig.createRules, ruleContext)",
            ),
        ) {
            "derived CREATE privacy should run only after UPDATE remains Continue and retain the UPDATE lifecycle label\n$output"
        }
        assert(output.contains("val unresolvedIndexes = decisions.indices.filter { decisions[it] is PrivacyDecision.Continue }")) {
            "DELETE derivation should retain the original indexes of only unresolved candidates\n$output"
        }
        assert(
            output.contains(
                "evaluateBatchPrivacyRulesForInternalUse(\"Car DELETE privacy\", unresolvedIndexes, privacyConfig.createRules, ruleContext)",
            ),
        ) {
            "derived CREATE privacy should batch only unresolved DELETE candidates under the DELETE label\n$output"
        }
        assert(
            output.contains(
                "unresolvedIndexes.forEachIndexed { resultIndex, originalIndex -> decisions[originalIndex] = derived[resultIndex] }",
            ),
        ) {
            "derived DELETE decisions should map back to their original encounter indexes\n$output"
        }
    }

    @Test
    fun `derived create validation appends after update invalids under the update lifecycle`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        val updateAt = output.indexOf(
            "val invalids = evaluateBatchValidationRulesForInternalUse(\"Car UPDATE validation\", listOf(candidate), rules, ruleContext)",
        )
        val derivedAt = output.indexOf(
            "invalids += evaluateBatchValidationRulesForInternalUse(\"Car UPDATE validation\", listOf(candidate), validationConfig.createRules, ruleContext)",
        )
        assert(updateAt >= 0 && derivedAt > updateAt) {
            "UPDATE invalids should precede and then combine with derived CREATE invalids under the UPDATE lifecycle label\n$output"
        }
        assert(output.indexOf("return invalids.map { it.toValidationViolation() }", derivedAt) > derivedAt) {
            "combined invalid decisions should map to violations after derivation\n$output"
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
            .replace("\\s+".toRegex(), " ")

        // deleteMany candidate selection fires interceptors with
        // operation = DELETE_CANDIDATES so tenant-scoping /
        // soft-delete predicate-shaping interceptors apply uniformly
        // to bulk deletes. The chain takes no entOperation parameter
        // anymore. The generated query's `predicates` backing field is
        // private, so deleteMany seeds it via the public `where()` DSL.
        assert(output.contains("CarQuery(driver, client).apply { for (predicate in predicates) where(predicate) }")) {
            "deleteMany should construct a transient CarQuery from caller predicates via the public DSL\n$output"
        }
        assert(output.contains("val privacy = client.currentPrivacyContext()")) {
            "deleteMany should capture a privacy context for candidate-selection interceptors\n$output"
        }
        assert(output.contains("compileEntityQuery(ReadOperation.DELETE_CANDIDATES, privacy)")) {
            "deleteMany should delegate DELETE_CANDIDATES preparation to the runtime pipeline\n$output"
        }
        assert(output.contains("val effectivePredicates = spec.predicates.toList()")) {
            "deleteMany should freeze the intercepted predicate list once\n$output"
        }
        assert(output.contains("driver.query(Car.TABLE, effectivePredicates, emptyList(), null, null)")) {
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
    fun `operation-specific driver classifiers use the correct operation`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // The private helper delegates to driver.classifyException; an
        // unrecognized exception falls back to the caller's
        // phase-tracked write state, and a classification without a
        // state defaults to PersistenceUnknown.
        assert(output.contains("private fun _classifyCreateDriverFailure(e: Exception, fallback: MutationWriteState): EntMutationException")) {
            "Should have a CREATE-specific driver classifier\n$output"
        }
        assert(output.contains("driver.classifyMutationException(e, \"Car\", EntOperation.CREATE)")) {
            "The create classifier should use EntOperation.CREATE\n$output"
        }
        assert(output.contains("private fun _classifyDeleteDriverFailure(e: Exception, fallback: MutationWriteState): EntMutationException")) {
            "Should have a DELETE-specific driver classifier\n$output"
        }
        assert(output.contains("driver.classifyMutationException(e, \"Car\", EntOperation.DELETE)")) {
            "The delete classifier should use EntOperation.DELETE\n$output"
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
            "CarUpdatePrivacyItem(before, requestedPatch, effectivePatch, item, edgeChanges)",
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
            "CarUpdateValidationItem(before, requestedPatch, effectivePatch, item, edgeChanges)",
        )) {
            "Validation item constructor call should thread edgeChanges through\n$output"
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
