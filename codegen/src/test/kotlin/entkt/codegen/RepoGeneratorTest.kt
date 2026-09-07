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
    fun `repo evaluators share create candidates and delete values without defensive copies`() {
        val schema = RepoBytesRecord()
        finalize(schema)
        val output = generator.generate("RepoBytesRecord", schema).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "privacyEvaluator = MutationPrivacyEvaluator( entity = RepoBytesRecordDescriptor, operation = PrivacyOperation.CREATE, rules = configuredPrivacy.createRules, )",
            ),
        ) {
            "CREATE privacy should receive the prepared candidate directly\n$output"
        }
        assert(
            output.contains(
                "validationEvaluator = mutationValidationEvaluatorForInternalUse( lifecycle = \"RepoBytesRecord CREATE validation\", rules = configuredValidation.createRules, )",
            ),
        ) {
            "CREATE validation should receive the prepared candidate directly\n$output"
        }
        assert(output.contains("private val deleteSpec: DeleteMutationSpec<RepoBytesRecord>")) {
            "DELETE should expose one typed runtime specification\n$output"
        }
        val deleteInput = "freshItem = { item: DeleteRuleCandidate<RepoBytesRecord, RepoBytesRecordWriteCandidate> -> RepoBytesRecordDeleteRuleInput(item.entity, item.candidate) }"
        assert(Regex(Regex.escape(deleteInput)).findAll(output).count() == 2) {
            "DELETE privacy and validation should share the entity and candidate\n$output"
        }
        assert(output.contains("freshItem = { item: DeleteRuleCandidate<RepoBytesRecord, RepoBytesRecordWriteCandidate> -> item.candidate }")) {
            "Derived CREATE privacy should receive the DELETE candidate directly\n$output"
        }
        assert(!output.contains("CreateRuleInput")) {
            "CREATE must not wrap its candidate\n$output"
        }
        val createBinding = output.substringAfter("private val createManyMutationOperation:")
            .substringBefore("private val createMutationOperation:")
        assert(!createBinding.contains("freshItem") && !createBinding.contains("candidate ->")) {
            "CREATE evaluators must not require identity converters\n$output"
        }
        assert(!output.contains("fun snapshotCreateCandidate")) {
            "rule items should be constructed directly, without callbacks into the repo\n$output"
        }
        val viewerContexts = Regex(
            Regex.escape("PrivacyRuleContext(viewerContext, client.readOnlyClient)"),
        ).findAll(output).count()
        assert(viewerContexts == 1) {
            "Only LOAD should receive its concrete rule context from the repository; found $viewerContexts\n$output"
        }
        val validationContexts = Regex(
            Regex.escape("val ruleContext = ValidationRuleContext(client.readOnlyClient)"),
        ).findAll(output).count()
        assert(validationContexts == 0) {
            "Mutation validation context construction belongs to runtime phases\n$output"
        }
        val loadBinding = output.substringAfter("private val loadPrivacyEvaluator:")
            .substringBefore("private val createConverter:")
        assert(!loadBinding.contains("freshItem") && !loadBinding.contains("LoadPrivacyItem")) {
            "LOAD should pass entities directly without a wrapper or converter\n$output"
        }
        assert(!loadBinding.contains("copyOf") && !loadBinding.contains(".copy(")) {
            "LOAD must not generate defensive entity copies\n$output"
        }
        assert(!output.contains("copyOf") && !output.contains(".copy(") && !output.contains("copyJsonValue")) {
            "Rule wiring must not generate defensive copies\n$output"
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
            output.contains("PendingCreateMutation<CarCreateDraft, Car>")) {
            "Should have create taking DSL block\n$output"
        }
        assert(output.contains("fun update(\n    id: Int,\n    consistency: UpdateConsistency = client.defaultUpdateConsistency,\n    relationshipLocking: RelationshipLocking = client.defaultRelationshipLocking,\n    block: CarUpdateDraft.() -> Unit,\n  ): PendingUpdateMutation<CarUpdateDraft, Car>")) {
            "Should return a generic PendingUpdateMutation configured through CarUpdateDraft\n$output"
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
            "createManyOrError / saveOrError should be gone (createMany and save/saveAndLoad are canonical)\n$output"
        }
        assert(!output.contains("EntResult") && !output.contains("EntError")) {
            "The EntResult / EntError types should not be referenced anywhere\n$output"
        }
        assert(
            !output.contains("evaluateUpdatePrivacy") && !output.contains("evaluateDeletePrivacy"),
        ) {
            "Legacy throwing privacy evaluators should be gone\n$output"
        }
    }

    @Test
    fun `delete delegates entity handles to the bound delete operation`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("mutationExecutor.execute( operation = deleteMutationOperation.mapResult { Unit }, ruleClient = client.readOnlyClient, input = DeleteMutationInput(viewerContext, entity.id), )")) {
            "delete should pass the handle to the bound runtime operation\n$output"
        }
    }

    @Test
    fun `repo does not generate a scalar delete lifecycle engine`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(!output.contains("deleteLoaded") && !output.contains("driver.delete(Car.TABLE")) {
            "scalar delete lifecycle and storage coordination belong to DeleteMutationOperation\n$output"
        }
    }

    @Test
    fun `deleteById delegates idempotent semantics to the bound delete operation`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("mutationExecutor.execute( operation = deleteMutationOperation, ruleClient = client.readOnlyClient, input = DeleteMutationInput(viewerContext, id), )")) {
            "deleteById should pass the id to the bound runtime operation\n$output"
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
        assert(output.contains("return PendingCreateMutation(draft, this)")) {
            "the generic mutation wrapper should retain the draft and repo\n$output"
        }
    }

    @Test
    fun `update captures a draft request and submits its operation directly to the executor`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("private val mutationExecutor: MutationExecutor = MutationExecutor(driver, client)")) {
            "repo should construct one shared mutation executor\n$output"
        }
        assert(Regex("private val mutationExecutor:").findAll(output).count() == 1)
        assert(!output.contains("createOperation") && !output.contains("deleteOperation") &&
            !output.contains("updateAdapter.execute(")) {
            "No operation or schema adapter should wrap the shared executor\n$output"
        }
        assert(output.contains("private val updateAdapter: CarUpdateAdapter = CarUpdateAdapter(driver, client, configuredPrivacy, configuredValidation, configuredHooks.beforeSave, configuredHooks.beforeUpdate, configuredHooks.afterUpdate)")) {
            "repo should construct one stable schema-specific update adapter\n$output"
        }
        assert(output.contains("val draft = CarUpdateDraft().apply(block)") &&
            output.contains("val request = UpdateMutationRequest(id, draft, consistency, relationshipLocking)") &&
            output.contains("return PendingUpdateMutation(request, this)")) {
            "update should capture only per-operation state in the draft and request\n$output"
        }
        assert(output.contains("override fun executeUpdate(") &&
            output.contains("mutationExecutor.execute( operation = updateAdapter.updateOperation, ruleClient = client.readOnlyClient, input = UpdateMutationInput( viewerContext = viewerContext, request = request, applyLoadPrivacy = applyLoadPrivacy,")) {
            "repository execution should pass the complete request directly to the shared executor\n$output"
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
    fun `repo uses resolved hooks directly during construction`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("configuredHooks: ResolvedEntityHooks<CarBeforeSaveState, CarBeforeCreateState, CarBeforeUpdateState, Car>")) {
            "The constructor should receive resolved entity hooks\n$output"
        }
        assert(output.contains("beforeSaveHookRunner = configuredHooks.beforeSave")) {
            "Should use the resolved beforeSave hooks directly\n$output"
        }
        assert(output.contains("afterDelete = configuredHooks.afterDelete")) {
            "Should use the resolved afterDelete hooks directly\n$output"
        }
        assert(!output.contains("private val beforeSaveHooks") && !output.contains("private val afterDeleteHooks"))
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
    fun `scalar create terminals delegate to the same bound operation`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("private val createMutationOperation: MutationOperation<ReadOnlyEntClient, CreateMutationInput<") && !output.contains("CreateOperation")) {
            "the repo should bind a scalar operation returning the entity directly\n$output"
        }
        assert(
            output.contains(
                "mutationExecutor.execute( operation = createMutationOperation.mapResult { Unit }, " +
                    "ruleClient = client.readOnlyClient, " +
                    "input = CreateMutationInput(viewerContext, draft, checkReturnedEntityPrivacy = false), )",
            ),
        ) {
            "save should use the bound operation without returned LOAD disclosure\n$output"
        }
        assert(
            output.contains(
                "mutationExecutor.execute( operation = createMutationOperation, ruleClient = client.readOnlyClient, input = CreateMutationInput(viewerContext, draft, checkReturnedEntityPrivacy = true), )",
            ),
        ) {
            "saveAndLoad should use the same bound operation with returned LOAD disclosure\n$output"
        }
        assert(!output.contains("client.createMutations.create(")) {
            "generated scalar terminals should not bypass the bound operation\n$output"
        }
        assert(!output.contains("it.single()") && !output.contains("requireOne()") && !output.contains("requireMany()")) {
            "operation result types should not require runtime scalar-bulk checks in generated code\n$output"
        }
    }

    @Test
    fun `repo exposes canonical createMany with vararg blocks`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // Canonical createMany IS the surface: one MutationResult for
        // the whole atomic batch. The shared runtime executor owns its
        // transaction preflight; the operation owns empty-input behavior.
        assert(
            Regex(
                "public fun createMany\\(\\s*viewerContext: ViewerContext,\\s*" +
                    "vararg blocks: CarCreateDraft\\.\\(\\) -> Unit,?\\s*\\):\\s*MutationResult<List<Car>>",
            ).containsMatchIn(output),
        ) {
            "Should have createMany with vararg blocks returning MutationResult<List<Car>>\n$output"
        }
        assert(output.contains("mutationExecutor.execute(") && output.contains("input = CreateManyMutationInput(viewerContext, blocks.asList(), newDraft = { CarCreateDraft() }),")) {
            "createMany should delegate transaction and empty-input behavior to the bound operation\n$output"
        }
    }

    @Test
    fun `createMany runs phase-major lifecycle work before one set-based insert`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("private val createManyMutationOperation: CreateManyMutationOperation<ReadOnlyEntClient, CarCreateDraft, CarWriteCandidate, Car, CarBeforeSaveState, CarBeforeCreateState>")) {
            "the repo should bind one typed create operation\n$output"
        }
        assert(
            output.contains(
                "CreateMutationOperation(createManyMutationOperation)",
            ),
        ) {
            "scalar create should reuse the canonical batch operation\n$output"
        }
        assert(Regex("newDraft =").findAll(output).count() == 1) {
            "the bulk request should capture its schema-specific draft constructor\n$output"
        }
        assert(!output.contains("mutationExecutor.create") && !output.contains("CreateManyDisclosure")) {
            "the shared executor should receive only typed operation inputs and generic completions\n$output"
        }
        assert(
            !output.contains("ArrayList<CarCreateDraft>") &&
                !output.contains("client.createMutations.createMany(") &&
                !output.contains("driver.insertMany(Car.TABLE") &&
                !output.contains("val candidates = prepared.map"),
        ) {
            "The generated repo should not duplicate the runtime create lifecycle\n$output"
        }
    }

    @Test
    fun `createMany self-delegates through withTransaction outside a caller-owned transaction`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        // Runtime owns the posture branch; generated wiring only selects the
        // corresponding transaction-bound operation.
        assert(output.contains("ownedTransaction = { input, completionCapture -> client.withTransaction { tx ->")) {
            "the bound operation should be given an EntKt-owned transaction adapter\n$output"
        }
        assert(
            output.contains(
                "tx.cars.mutationExecutor.executeInOwnedTransactionForInternalUse( " +
                    "operation = tx.cars.createManyMutationOperation, ruleClient = tx.cars.client.readOnlyClient, input = input, " +
                    "completionCapture = completionCapture, ).orRollback()",
            ),
        ) {
            "the EntKt-owned batch should use the transaction repo's bound operation\n$output"
        }
        assert(
            !output.contains("_executeCreateManyWritePhases") &&
                !output.contains("checkCreateManyTransactionRequirement"),
        ) {
            "the generated createMany surface should not duplicate runtime transaction coordination\n$output"
        }
    }

    @Test
    fun `createMany batch evaluates returned LOAD with the captured CREATE context`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("mutationExecutor.execute(") && output.contains("input = CreateManyMutationInput(viewerContext, blocks.asList(), newDraft = { CarCreateDraft() }),")) {
            "the terminal should pass its exact ViewerContext to the runtime operation\n$output"
        }
        assert(output.contains("input = input, completionCapture = completionCapture")) {
            "the transaction wiring should preserve the original input and generic completion capture\n$output"
        }
        assert(
            !output.contains("loadDenials(viewerContext, completion)") &&
                !output.contains("disclosureFailure") &&
                !output.contains("disclosureDenial"),
        ) {
            "returned LOAD disclosure and failure precedence should live only in runtime\n$output"
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
        assert(output.contains("mutationExecutor.execute(") && output.contains("input = DeleteManyMutationInput(viewerContext, predicates.asList()),")) {
            "deleteMany should delegate its typed predicates to the bound runtime operation\n$output"
        }
    }

    @Test
    fun `repo binds typed scalar and bulk delete operations directly`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("private val deleteMutationOperation: MutationOperation<ReadOnlyEntClient, DeleteMutationInput, Boolean>") && !output.contains("DeleteOperation")) {
            "the scalar DELETE operation should return a Boolean directly\n$output"
        }
        assert(output.contains("DeleteMutationOperation(spec = deleteSpec, converter = CarDeleteConverter,"))
        assert(output.contains("DeleteManyMutationOperation(spec = deleteSpec, converter = CarDeleteConverter,"))
        assert(!output.contains("MutationLifecycle"))
        assert(output.contains("private val deleteManyMutationOperation: MutationOperation<ReadOnlyEntClient, DeleteManyMutationInput<Car>, Int>"))
        assert(
            output.contains(
                "mutationExecutor.execute( operation = deleteManyMutationOperation, " +
                    "ruleClient = client.readOnlyClient, " +
                    "input = DeleteManyMutationInput(viewerContext, predicates.asList()), ownedTransaction =",
            ),
        ) {
            "the executor should receive the repository's typed delete operation\n$output"
        }
        assert(!output.contains("private fun _executeDeleteManyPhases(")) {
            "deleteMany transaction and failure coordination should live only in runtime\n$output"
        }
    }

    @Test
    fun `delete operation delegates owned work through the transaction repository`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "ownedTransaction = { input, completionCapture -> client.withTransaction { tx ->",
            ),
        ) {
            "the bound operation should receive an EntKt-owned transaction adapter\n$output"
        }
        assert(
            output.contains(
                "tx.cars.mutationExecutor.executeInOwnedTransactionForInternalUse( operation = tx.cars.deleteManyMutationOperation, ruleClient = tx.cars.client.readOnlyClient, input = input, completionCapture = completionCapture, ).orRollback()",
            ),
        ) {
            "owned deleteMany work should use the transaction repository's bound operation\n$output"
        }
        assert(
            !output.contains("txResult.transactionState") &&
                !output.contains("promoteDriverNotPersisted"),
        ) {
            "deleteMany transaction posture and failure conversion should not be generated\n$output"
        }
    }

    @Test
    fun `repo uses privacy constructor input without retaining a configuration property`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(!output.contains("val privacyConfig") && !output.contains("val configuredPrivacy")) {
            "Privacy configuration should only be a constructor input\n$output"
        }
        assert(!output.contains("privacyConfig."))
        assert(output.contains("rules = configuredPrivacy.loadRules"))
        assert(output.contains("rules = configuredPrivacy.createRules"))
        assert(output.contains("rules = configuredPrivacy.deleteRules"))
        assert(output.contains("if (configuredPrivacy.deleteDerivesFromCreate)"))
    }

    @Test
    fun `repo receives privacy configuration in its constructor`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        val type = "ResolvedEntityPrivacyConfig<CarLoadBatchPrivacyRule, CarCreateBatchPrivacyRule, CarUpdateBatchPrivacyRule, CarDeleteBatchPrivacyRule>"
        assert(output.contains("configuredPrivacy: $type")) {
            "Should accept privacy configuration in the constructor\n$output"
        }
        assert(!output.contains("privacyConfig:"))
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
    fun `repo binds correlated LOAD evaluation and mutation specifications`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("override fun evaluateLoadPrivacy(viewerContext: ViewerContext, entities: List<Car>): PrivacyEvaluation<Car>")) {
            "Should expose the correlated LOAD evaluator through the read surface\n$output"
        }
        assert(
            output.contains(
                "private val loadPrivacyEvaluator: LoadPrivacyEvaluator<ReadOnlyEntClient, Car> = LoadPrivacyEvaluator( " +
                    "entity = CarDescriptor, rules = configuredPrivacy.loadRules, )",
            ),
        ) {
            "LOAD should inject only its descriptor and rules without resolving a client\n$output"
        }
        assert(!output.contains("loadPrivacyEvaluatorForInternalUse"))
        assert(!output.contains("\"Car LOAD privacy\"") && !output.contains("\"no load rule allowed access\"")) {
            "LOAD diagnostics and the default denial reason should be owned by the runtime evaluator\n$output"
        }
        assert(output.contains("loadPrivacyEvaluator.evaluate( context = PrivacyRuleContext(viewerContext, client.readOnlyClient), entities = entities, )")) {
            "The read-surface method should supply the concrete context to its bound runtime evaluator\n$output"
        }
        assert(
            !output.contains("evaluateBatchPrivacyRulesForInternalUse") &&
                !output.contains("entitySnapshot.mapIndexed") &&
                !output.contains("PrivacyDecision.Deny"),
        ) {
            "LOAD batch evaluation and denial correlation should not be generated\n$output"
        }
        assert(!output.contains("fun evaluateCreatePrivacy")) {
            "CREATE privacy should run in the shared runtime lifecycle\n$output"
        }
        assert(output.contains("private val deleteSpec: DeleteMutationSpec<Car>")) {
            "DELETE rule adapters should live in one typed specification\n$output"
        }
        assert(!output.contains("updateDenialReasonOrNull") && !output.contains("deleteDenialReasonOrNull")) {
            "repositories should not generate write lifecycle evaluators\n$output"
        }
    }

    @Test
    fun `generated privacy delegates fail-closed evaluation to runtime phases`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("= LoadPrivacyEvaluator(")) {
            "LOAD privacy should delegate through its runtime evaluator\n$output"
        }
        assert(output.contains("privacyEvaluator = MutationPrivacyEvaluator(")) {
            "write privacy should delegate decisions through runtime evaluators\n$output"
        }
        assert(!output.contains("mutationPrivacyEvaluatorForInternalUse") &&
            !output.contains("privacyDecisionEvaluatorForInternalUse")) {
            "privacy evaluators should be constructed directly\n$output"
        }
        assert(output.contains("entity = CarDescriptor, operation = PrivacyOperation.CREATE"))
        assert(!output.contains("\"Car CREATE privacy\"") &&
            !output.contains("\"Car DELETE privacy\"") && !output.contains("unresolvedReason")) {
            "mutation privacy diagnostics should be owned by the runtime evaluator\n$output"
        }
        assert(
            !output.contains("Viewer.PrivacyBypass") &&
                !output.contains("PrivacyDecision.Continue"),
        ) {
            "LOAD bypass and fail-closed decision mapping should live only in runtime\n$output"
        }
    }

    @Test
    fun `delete privacy composes create fallback under the delete lifecycle`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("fallback = if (configuredPrivacy.deleteDerivesFromCreate)")) {
            "DELETE should configure the runtime's unresolved-candidate fallback\n$output"
        }
        assert(output.contains("MutationPrivacyEvaluator<ReadOnlyEntClient, DeleteRuleCandidate<Car, CarWriteCandidate>>"))
        assert(output.contains("entity = CarDescriptor, operation = PrivacyOperation.DELETE"))
        assert(output.contains("primary = PrivacyDecisionEvaluator( rules = configuredPrivacy.deleteRules,"))
        val fallback = output.substringAfter("fallback = if (configuredPrivacy.deleteDerivesFromCreate) {")
            .substringBefore("} else {")
        assert(fallback.contains("PrivacyDecisionEvaluator( rules = configuredPrivacy.createRules,")) {
            "DELETE-derived CREATE rules should be bound to a concrete runtime evaluator\n$output"
        }
        assert(!fallback.contains("lifecycle") && !fallback.contains("ruleClientProvider")) {
            "fallback evaluation should use its parent mutation's diagnostics and rule context\n$output"
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

        assert(output.contains("converter = CarDeleteConverter")) {
            "DELETE should use a schema-specific converter without calling back into the repo\n$output"
        }
        assert(!output.contains("fun buildDeleteCandidate") && !output.contains("::buildDeleteCandidate"))
        assert(output.contains("newQuery = { CarQuery(driver, client) }")) {
            "deleteSpec should provide only the schema-specific candidate query factory\n$output"
        }
        assert(output.contains("rules = configuredPrivacy.deleteRules")) {
            "deleteSpec should capture DELETE privacy rules\n$output"
        }
    }

    @Test
    fun `deleteMany emits no candidate-selection algorithm`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            !output.contains("selectDeleteCandidates") &&
                !output.contains("ReadOperation.DELETE_CANDIDATES") &&
                !output.contains("driver.query(Car.TABLE"),
        ) {
            "candidate compilation, raw loading, and predicate freezing belong to runtime\n$output"
        }
    }

    @Test
    fun `repo emits no mutation failure coordination`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(
            !output.contains("MutationResult.failedForInternalUse(") &&
                !output.contains("client.recordTransactionMutationFailure("),
        ) {
            "mutation failure construction and coordinator recording belong to runtime operations\n$output"
        }
    }

    @Test
    fun `repo emits no mutation capture boundaries`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(!output.contains("catch (e: CancellationException)")) {
            "mutation capture and cancellation handling belong to runtime operations\n$output"
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
            output.contains("PendingCreateMutation<SessionCreateDraft, Session>")) {
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
        val normalized = output.replace("\\s+".toRegex(), " ")

        assert(!output.contains("fun createMany")) {
            "Should not generate createMany for EXPLICIT id strategy\n$output"
        }
        assert(
            normalized.contains(
                "mutationExecutor.execute( operation = createMutationOperation,",
            ),
        ) {
            "explicit-id repositories should bind only the scalar create capability\n$output"
        }
        assert(!normalized.contains("CreateManyMutationInput") && !normalized.contains("newDraft =")) {
            "explicit-id repositories should not emit unreachable createMany wiring\n$output"
        }
    }

    @Test
    fun `repo uses validation constructor input without retaining a configuration property`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(!output.contains("val validationConfig") && !output.contains("val configuredValidation")) {
            "Validation configuration should only be a constructor input\n$output"
        }
        assert(!output.contains("validationConfig."))
        assert(output.contains("rules = configuredValidation.createRules"))
        assert(output.contains("rules = configuredValidation.deleteRules"))
    }

    @Test
    fun `repo receives validation configuration in its constructor`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        val type = "ResolvedEntityValidationConfig<CarCreateBatchValidationRule, CarUpdateBatchValidationRule, CarDeleteBatchValidationRule>"
        assert(output.contains("configuredValidation: $type")) {
            "Should accept validation configuration in the constructor\n$output"
        }
        assert(!output.contains("validationConfig:"))
        assert(!output.contains("applyValidation") && !output.contains("copyValidationFrom"))
    }

    @Test
    fun `repo delegates create and delete validation through runtime specifications`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(!output.contains("fun evaluateCreateValidation")) {
            "CREATE validation should run in the shared runtime lifecycle\n$output"
        }
        assert(output.contains("validationEvaluator = mutationValidationEvaluatorForInternalUse( lifecycle = \"Car CREATE validation\"")) {
            "createMutationOperation should capture CREATE validation\n$output"
        }
        assert(output.contains("lifecycle = \"Car DELETE validation\"") &&
            output.contains("rules = configuredValidation.deleteRules")) {
            "deleteSpec should capture DELETE validation\n$output"
        }
        assert(!output.contains("evaluateUpdateValidation") && !output.contains("evaluateDeleteValidation")) {
            "repositories should not generate mutation validation engines\n$output"
        }
    }

    @Test
    fun `delete operations reuse bound privacy and validation evaluators`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(Regex("rules = configuredPrivacy.deleteRules").findAll(output).count() == 1)
        assert(Regex("rules = configuredValidation.deleteRules").findAll(output).count() == 1)
        assert(Regex("privacyEvaluator = deletePrivacyEvaluator").findAll(output).count() == 2)
        assert(Regex("validationEvaluator = deleteValidationEvaluator").findAll(output).count() == 2)
    }

    @Test
    fun `mutation operations receive the typed read-only client only at execution time`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()

        assert(output.contains("CreateManyMutationOperation<ReadOnlyEntClient, CarCreateDraft, CarWriteCandidate, Car, CarBeforeSaveState, CarBeforeCreateState>")) {
            "the create operation should require the concrete read-only client\n$output"
        }
        assert(output.contains("DeleteMutationSpec<Car>")) {
            "deleteSpec should not carry a rule-client type\n$output"
        }
        assert(!output.contains("ruleClientProvider")) {
            "evaluators must not capture a rule-client provider\n$output"
        }
        val construction = output.substringBefore("\n  init {")
        assert(!construction.contains("client.readOnlyClient")) {
            "repository construction must not resolve a read client\n$output"
        }
        assert(output.contains("ruleClient = client.readOnlyClient"))
        assert(output.contains("ruleClient = tx.cars.client.readOnlyClient"))
        assert(!output.contains("asValidationReadClientForInternalUse"))
        assert(!output.contains("asReadClientForInternalUse")) {
            "The arbitrary-context adapter must not be called anymore\n$output"
        }
        assert(!output.contains("withFixedViewerContextForInternalUse")) {
            "Evaluators must not clone a full write-capable client\n$output"
        }
    }

    @Test
    fun `generated LOAD phase supplies its concrete context only at evaluation time`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        val loadBinding = output.substringAfter("private val loadPrivacyEvaluator:")
            .substringBefore("private val createConverter:")
        assert(!loadBinding.contains("ruleClientProvider") && !loadBinding.contains("client.readOnlyClient")) {
            "The LOAD evaluator must not depend on a client during repository construction\n$output"
        }
        assert(
            output.contains(
                "override fun evaluateLoadPrivacy(viewerContext: ViewerContext, entities: List<Car>): PrivacyEvaluation<Car> = " +
                    "loadPrivacyEvaluator.evaluate( context = PrivacyRuleContext(viewerContext, client.readOnlyClient), entities = entities, )",
            ),
        ) {
            "The read-surface method should preserve its API and construct the context when called\n$output"
        }
        assert(!output.contains("asPrivacyReadClientForInternalUse"))
    }
}
