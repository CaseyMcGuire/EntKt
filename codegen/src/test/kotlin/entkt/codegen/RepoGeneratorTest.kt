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
    fun `repo specifications construct fresh create and delete rule items`() {
        val schema = RepoBytesRecord()
        finalize(schema)
        val output = generator.generate("RepoBytesRecord", schema).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "privacy = mutationPrivacyPhaseForInternalUse(\"RepoBytesRecord CREATE privacy\", privacyConfig.createRules) { candidate -> RepoBytesRecordCreatePrivacyItem(snapshotCreateCandidate(candidate)) }",
            ),
        ) {
            "the privacy phase should provide a fresh detached item for each rule\n$output"
        }
        assert(
            output.contains(
                "validation = mutationValidationPhaseForInternalUse(\"RepoBytesRecord CREATE validation\", validationConfig.createRules) { candidate -> RepoBytesRecordCreateValidationItem(snapshotCreateCandidate(candidate)) }",
            ),
        ) {
            "the validation phase should provide a fresh detached item for each rule\n$output"
        }
        assert(output.contains("private val deleteSpec: DeleteMutationSpec<RepoBytesRecord, RepoBytesRecordWriteCandidate, ReadOnlyEntClient>")) {
            "DELETE should expose one typed runtime specification\n$output"
        }
        assert(output.contains("freshItem = { item: DeleteRuleCandidate<RepoBytesRecord, RepoBytesRecordWriteCandidate> -> RepoBytesRecordDeletePrivacyItem")) {
            "DELETE privacy should adapt a fresh typed item\n$output"
        }
        assert(output.contains("RepoBytesRecordDeleteValidationItem")) {
            "DELETE validation should adapt a fresh typed item\n$output"
        }
        assert(output.contains("private fun snapshotCreateCandidate(candidate: RepoBytesRecordWriteCandidate): RepoBytesRecordWriteCandidate")) {
            "mutable create candidates should be detached by one generated mapping helper\n$output"
        }
        val viewerContexts = Regex(
            Regex.escape("val ruleContext = PrivacyRuleContext(viewerContext, client.readOnlyClient)"),
        ).findAll(output).count()
        assert(viewerContexts == 1) {
            "Only the generated LOAD evaluator should construct rule context directly; found $viewerContexts\n$output"
        }
        val validationContexts = Regex(
            Regex.escape("val ruleContext = ValidationRuleContext(client.readOnlyClient)"),
        ).findAll(output).count()
        assert(validationContexts == 0) {
            "Mutation validation context construction belongs to runtime phases\n$output"
        }
        assert(output.contains("item.copy( payload = item.payload.copyOf(), thumbnail = item.thumbnail?.copyOf(), )")) {
            "rule candidates should not alias database-bound byte arrays\n$output"
        }
        assert(output.contains("item.entity.copy( payload = item.entity.payload.copyOf(), thumbnail = item.entity.thumbnail?.copyOf(), )")) {
            "delete entities should not alias mutable byte arrays\n$output"
        }
        assert(output.contains("item.candidate.copy( payload = item.candidate.payload.copyOf(), thumbnail = item.candidate.thumbnail?.copyOf(), )")) {
            "delete candidates should not alias mutable byte arrays\n$output"
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
        assert(output.contains("fun create(block: CarCreateDraft.() -> Unit):") &&
            output.contains("CreateMutation<CarCreateDraft, Car>")) {
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
        assert(output.contains("public fun findById(viewerContext: ViewerContext, id: UUID): ReadResult<User?>")) {
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
                    "query.readRootQuery( viewerContext = viewerContext, operation = ReadOperation.BY_ID, maximumRows = 1, " +
                    "structuralPredicates = listOf(Predicate.Leaf<Car>(\"id\", Op.EQ, id)), ))",
            ),
        ) {
            "findById should build the id-scoped EntityQuery\n$output"
        }
        assert(output.contains("query.readRootQuery( viewerContext = viewerContext, operation = ReadOperation.BY_ID, maximumRows = 1,")) {
            "findById should delegate BY_ID and its single-row bound to ReadQueryExecutor\n$output"
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
            "findById should reuse ReadQueryExecutor's single failure boundary\n$body"
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
        assert(body.contains("query.readRootQuery( viewerContext = viewerContext, operation = ReadOperation.BY_ID, maximumRows = 1,")) {
            "findById should use the same ReadQueryExecutor root-privacy lifecycle as entity queries\n$body"
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
        assert(output.contains("public fun delete(viewerContext: ViewerContext, entity: Car): MutationResult<Unit>")) {
            "Should generate delete(entity): MutationResult<Unit>\n$output"
        }
        assert(output.contains("public fun deleteById(viewerContext: ViewerContext, id: Int): MutationResult<Boolean>")) {
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
            !output.contains("evaluateLoadPrivacy") &&
                !output.contains("evaluateUpdatePrivacy") && !output.contains("evaluateDeletePrivacy"),
        ) {
            "Legacy throwing privacy evaluators should be gone\n$output"
        }
    }

    @Test
    fun `delete delegates entity handles to DeleteMutationExecutor`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("client.deleteMutations.delete(viewerContext, entity, deleteSpec)")) {
            "delete should pass the handle and typed spec to the runtime executor\n$output"
        }
    }

    @Test
    fun `repo does not generate a scalar delete lifecycle engine`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(!output.contains("deleteLoaded") && !output.contains("driver.delete(Car.TABLE")) {
            "scalar delete lifecycle and storage coordination belong to DeleteMutationExecutor\n$output"
        }
    }

    @Test
    fun `deleteById delegates idempotent semantics to DeleteMutationExecutor`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("client.deleteMutations.deleteById(viewerContext, id, deleteSpec)")) {
            "deleteById should pass the id and typed spec to the runtime executor\n$output"
        }
    }

    @Test
    fun `deleteById uses the correct id type for UUID schemas`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()

        assert(output.contains("public fun deleteById(viewerContext: ViewerContext, id: UUID): MutationResult<Boolean>")) {
            "deleteById should use UUID for User's id type\n$output"
        }
    }

    @Test
    fun `create constructs a draft and generic mutation wrapper`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("val draft = CarCreateDraft().apply(block)")) {
            "create should configure a fresh draft\n$output"
        }
        assert(output.contains("return CreateMutation(draft, this)")) {
            "the generic mutation wrapper should retain the draft and repo\n$output"
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
    fun `repo receives its private client backlink in the constructor`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("private val client: EntClient")) {
            "The full client backlink must not escape through a transaction facade's repo\n$output"
        }
        assert(!output.contains("attachClientForInternalUse")) {
            "A fully constructed repo should not need a later attach phase\n$output"
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
    fun `repo snapshots configured hooks during construction`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("configuredHooks: CarHooks")) {
            "The constructor should receive the entity hook configuration\n$output"
        }
        assert(output.contains("configuredHooks.beforeSaveHooks.toList()")) {
            "Should snapshot beforeSaveHooks from config\n$output"
        }
        assert(output.contains("configuredHooks.afterDeleteHooks.toList()")) {
            "Should snapshot afterDeleteHooks from config\n$output"
        }
        assert(!output.contains("fun applyHooks"))
    }

    @Test
    fun `repo has no post-construction hook copy path`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(!output.contains("copyHooksFrom")) { "Hook configuration is constructor-injected\n$output" }
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
        assert(
            Regex(
                "public fun createMany\\(\\s*viewerContext: ViewerContext,\\s*" +
                    "vararg blocks: CarCreateDraft\\.\\(\\) -> Unit,?\\s*\\):\\s*MutationResult<List<Car>>",
            ).containsMatchIn(output),
        ) {
            "Should have createMany with vararg blocks returning MutationResult<List<Car>>\n$output"
        }
        assert(output.contains("client.createMutations.checkCreateManyTransactionRequirement(\"Car\", blocks.size)")) {
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

        assert(
            output.contains(
                "val drafts = ArrayList<CarCreateDraft>(blocks.size) for (block in blocks) { " +
                    "drafts += CarCreateDraft().apply(block)",
            ),
        ) {
            "createMany should instantiate and configure every draft in input order\n$output"
        }
        assert(
            output.contains(
                "return client.createMutations.createMany( viewerContext = viewerContext, drafts = drafts, " +
                    "spec = createSpec, " +
                    "promoteDriverNotPersisted = promoteDriverNotPersisted, )",
            ),
        ) {
            "createMany should delegate its shared lifecycle after configuring builders\n$output"
        }
        assert(!output.contains("driver.insertMany(Car.TABLE") && !output.contains("val candidates = prepared.map")) {
            "The generated repo should not duplicate the runtime create lifecycle\n$output"
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
                    "viewerContext, blockSnapshot, promoteDriverNotPersisted = false).orRollback()",
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

        assert(output.contains("loadDenials(viewerContext, completion).filterNotNull().firstOrNull()")) {
            "caller-owned createMany should batch LOAD disclosure with the terminal context\n$output"
        }
        assert(output.contains("tx.cars.loadDenials(viewerContext, completion).filterNotNull().firstOrNull()")) {
            "owned createMany should batch LOAD disclosure through the transaction repo with the terminal context\n$output"
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

        assert(
            Regex(
                "public fun deleteMany\\(\\s*viewerContext: ViewerContext,\\s*" +
                    "vararg predicates: Predicate<Car>,?\\s*\\):\\s*MutationResult<Int>",
            ).containsMatchIn(output),
        ) {
            "Should have deleteMany with vararg typed Predicate<Car> returning MutationResult<Int>\n$output"
        }
        assert(output.contains("client.deleteMutations.checkDeleteManyTransactionRequirement(\"Car\")")) {
            "deleteMany should delegate its multi-write transaction preflight\n$output"
        }
    }

    @Test
    fun `deleteMany private phases delegate to the runtime executor`() {
        val car = Car()
        finalize(car, User())
        val raw = generator.generate("Car", car).toString()
        val output = raw.replace("\\s+".toRegex(), " ")

        assert(output.contains("private fun _executeDeleteManyPhases(")) {
            "deleteMany should isolate its transaction-scoped phase-major pipeline\n$output"
        }
        assert(output.contains("client.deleteMutations.deleteMany(")) {
            "the transaction-scoped adapter should call DeleteMutationExecutor\n$output"
        }
        assert(output.contains("viewerContext = viewerContext") && output.contains("spec = deleteSpec")) {
            "the exact operation context and immutable spec should cross the adapter\n$output"
        }
        val phases = raw.substring(
            raw.indexOf("fun _executeDeleteManyPhases("),
            raw.indexOf("fun deleteMany("),
        )
        assert(!phases.contains("driver.") && !phases.contains("runBatchHooksForInternalUse")) {
            "generated deleteMany phases should contain no lifecycle or storage algorithm\n$phases"
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
                "if (driver.inTransaction) { _executeDeleteManyPhases(viewerContext, predicateSnapshot, promoteDriverNotPersisted = true)",
            ),
        ) {
            "caller-owned deleteMany should run the private phases in place\n$output"
        }
        assert(
            output.contains(
                "tx.cars._executeDeleteManyPhases(viewerContext, predicateSnapshot, promoteDriverNotPersisted = false).orRollback()",
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
    fun `repo receives privacy configuration in its constructor`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("configuredPrivacy: CarPrivacyConfig")) {
            "Should accept privacy configuration in the constructor\n$output"
        }
        assert(output.contains("internal val privacyConfig: CarPrivacyConfig = configuredPrivacy"))
        assert(!output.contains("applyPrivacy") && !output.contains("copyPrivacyFrom"))
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
    fun `repo has positional LOAD evaluators and mutation specifications`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // LOAD evaluates the immutable entity snapshot in one batch and
        // maps decisions back by position. This preserves duplicate IDs
        // and caller order; the singleton API is only a projection of the
        // plural contract.
        assert(output.contains("override fun loadDenials(viewerContext: ViewerContext, entities: List<Car>): List<PrivacyDenial?>")) {
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
        assert(output.contains("override fun loadDenialOrNull(viewerContext: ViewerContext, entity: Car): PrivacyDenial?")) {
            "Should have loadDenialOrNull overriding the read surface\n$output"
        }
        assert(output.contains("loadDenialOrNull(viewerContext: ViewerContext, entity: Car): PrivacyDenial? = loadDenials(viewerContext, listOf(entity)).single()")) {
            "Singleton LOAD evaluation should delegate to the plural evaluator\n$output"
        }

        assert(!output.contains("fun evaluateCreatePrivacy")) {
            "CREATE privacy should run in CreateMutationExecutor from createSpec\n$output"
        }
        assert(output.contains("private val deleteSpec: DeleteMutationSpec<Car, CarWriteCandidate, ReadOnlyEntClient>")) {
            "DELETE rule adapters should live in one typed specification\n$output"
        }
        assert(!output.contains("updateDenialReasonOrNull") && !output.contains("deleteDenialReasonOrNull")) {
            "repositories should not generate write lifecycle evaluators\n$output"
        }
    }

    @Test
    fun `generated LOAD is fail-closed while write phases delegate fail-closed decisions`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "is PrivacyDecision.Continue -> PrivacyDenial(\"Car\", EntityKey(\"id\", entity.id), \"no load rule allowed access\")",
            ),
        ) {
            "loadDenials should map a continued decision to the fail-closed keyed denial\n$output"
        }
        assert(output.contains("privacy = mutationPrivacyPhaseForInternalUse(")) {
            "write privacy should delegate decisions through runtime phases\n$output"
        }
        assert(
            output.contains(
                "if (viewerContext.viewer is Viewer.PrivacyBypass) return List(entitySnapshot.size) { null }",
            ),
        ) {
            "Plural LOAD bypass should retain one null slot per snapshotted entity\n$output"
        }
        val entityBatchBypasses = Regex(
            Regex.escape("if (viewerContext.viewer is Viewer.PrivacyBypass) return List(entitySnapshot.size) { null }")
        ).findAll(output).count()
        assert(entityBatchBypasses == 1) {
            "Only LOAD remains a generated positional evaluator; found $entityBatchBypasses\n$output"
        }
    }

    @Test
    fun `delete privacy composes create fallback under the delete lifecycle`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("withPrivacyFallbackForInternalUse(")) {
            "DELETE should use the reusable unresolved-candidate fallback combinator\n$output"
        }
        assert(output.contains("if (privacyConfig.deleteDerivesFromCreate)") &&
            output.contains("rules = privacyConfig.createRules")) {
            "DELETE-derived CREATE rules should remain explicitly configured under deleteSpec\n$output"
        }
    }

    @Test
    fun `repo leaves update validation composition to the generated update adapter`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(!output.contains("evaluateUpdateValidation") && !output.contains("UPDATE validation")) {
            "repository code should not duplicate UPDATE validation lifecycle logic\n$output"
        }
    }

    @Test
    fun `delete specification captures privacy candidates and rules`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("candidate = ::buildDeleteCandidate")) {
            "deleteSpec should provide normalized candidates\n$output"
        }
        assert(output.contains("rules = privacyConfig.deleteRules")) {
            "deleteSpec should capture DELETE privacy rules\n$output"
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
        assert(!output.contains("currentViewerContext")) {
            "deleteMany should not read ambient viewer state\n$output"
        }
        assert(output.contains("compileEntityQuery(viewerContext, ReadOperation.DELETE_CANDIDATES)")) {
            "deleteMany should delegate DELETE_CANDIDATES preparation to the runtime pipeline\n$output"
        }
        assert(output.contains("val effectivePredicates = querySpec.predicates.toList()")) {
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
        val recorded = Regex(Regex.escape("client.recordTransactionMutationFailure(")).findAll(output).count()
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
    fun `repo generates no operation-specific driver classifiers`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(!output.contains("_classifyCreateDriverFailure") &&
            !output.contains("_classifyUpdateDriverFailure") &&
            !output.contains("_classifyDeleteDriverFailure")) {
            "driver failure classification belongs to the three runtime executors\n$output"
        }
    }

    @Test
    fun `explicit id create takes id as first parameter`() {
        val session = Session()
        finalize(session)
        val output = generator.generate("Session", session).toString()

        assert(output.contains("fun create(id: String, block: SessionCreateDraft.() -> Unit):") &&
            output.contains("CreateMutation<SessionCreateDraft, Session>")) {
            "create() should take id as first parameter for EXPLICIT strategy\n$output"
        }
    }

    @Test
    fun `explicit id create passes id to constructor`() {
        val session = Session()
        finalize(session)
        val output = generator.generate("Session", session).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("SessionCreateDraft(id = id).apply(block)")) {
            "create() should pass id to SessionCreateDraft constructor\n$output"
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
    fun `repo receives validation configuration in its constructor`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("configuredValidation: CarValidationConfig")) {
            "Should accept validation configuration in the constructor\n$output"
        }
        assert(output.contains("internal val validationConfig: CarValidationConfig = configuredValidation"))
        assert(!output.contains("applyValidation") && !output.contains("copyValidationFrom"))
    }

    @Test
    fun `repo delegates create and delete validation through runtime specifications`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(!output.contains("fun evaluateCreateValidation")) {
            "CREATE validation should run in CreateMutationExecutor from createSpec\n$output"
        }
        assert(output.contains("validation = mutationValidationPhaseForInternalUse(\"Car CREATE validation\"")) {
            "createSpec should capture CREATE validation\n$output"
        }
        assert(output.contains("lifecycle = \"Car DELETE validation\"") &&
            output.contains("rules = validationConfig.deleteRules")) {
            "deleteSpec should capture DELETE validation\n$output"
        }
        assert(!output.contains("evaluateUpdateValidation") && !output.contains("evaluateDeleteValidation")) {
            "repositories should not generate mutation validation engines\n$output"
        }
    }

    @Test
    fun `delete spec places privacy before validation`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        val privacyAt = output.indexOf("privacy = mutationPrivacyPhaseForInternalUse(")
        val validationAt = output.indexOf("validation = mutationValidationPhaseForInternalUse(", privacyAt)
        assert(privacyAt in 0 until validationAt) {
            "deleteSpec should wire privacy before validation for the runtime lifecycle\n$output"
        }
    }

    @Test
    fun `mutation specs are typed to the stable read-only client`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("CreateMutationSpec<CarCreateDraft, CarWriteCandidate, Car, ReadOnlyEntClient>")) {
            "createSpec should use ReadOnlyEntClient\n$output"
        }
        assert(output.contains("DeleteMutationSpec<Car, CarWriteCandidate, ReadOnlyEntClient>")) {
            "deleteSpec should use ReadOnlyEntClient\n$output"
        }
        assert(!output.contains("asValidationReadClientForInternalUse"))
        assert(!output.contains("asReadClientForInternalUse")) {
            "The arbitrary-context adapter must not be called anymore\n$output"
        }
        assert(!output.contains("withFixedViewerContextForInternalUse")) {
            "Evaluators must not clone a full write-capable client\n$output"
        }
    }

    @Test
    fun `generated LOAD evaluator reuses the stable read-only client with the caller context`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        val uses = Regex(
            Regex.escape("PrivacyRuleContext(viewerContext, client.readOnlyClient)")
        ).findAll(output).count()
        assert(uses == 1) {
            "only LOAD remains generated and should reuse the stable client; found $uses\n$output"
        }
        assert(!output.contains("asPrivacyReadClientForInternalUse"))
    }
}
