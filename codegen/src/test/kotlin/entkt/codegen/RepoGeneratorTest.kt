package entkt.codegen

import entkt.codegen.client.RepoGenerator
import entkt.schema.EntSchema
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
                "buildCreateManyMutationOperation( entity = RepoBytesRecordDescriptor, mutationRuntime = client,",
            ),
        ) {
            "CREATE evaluator construction belongs to the runtime\n$output"
        }
        assert(
            output.contains(
                "privacy = configuredPrivacy, validation = configuredValidation,",
            ),
        ) {
            "Operation factories should receive the resolved rule configuration\n$output"
        }
        assert(!output.contains("DeleteMutationSpec") && !output.contains("deleteSpec")) {
            "DELETE should inject its dependencies without a specification holder\n$output"
        }

        assert(!output.contains("MutationRuleEvaluators") && !output.contains("val rules =") &&
            !output.contains("= run {")) {
            "Runtime factories should return operations without generated assembly blocks or evaluator holders\n$output"
        }

        for (factory in listOf(
            "buildCreateManyMutationOperation",
            "buildUpdateMutationOperation",
            "buildDeleteMutationOperation",
            "buildDeleteManyMutationOperation",
        )) {
            assert(!output.contains("fun $factory(")) {
                "Operation factories belong to runtime, not generated repositories\n$output"
            }
        }

        assert(output.contains("ruleInput = ::RepoBytesRecordDeleteRuleInput")) {
            "DELETE should supply one typed conversion for privacy and validation\n$output"
        }
        assert(!output.contains("CreateRuleInput")) {
            "CREATE must not wrap its candidate\n$output"
        }
        val createBinding = output.substringAfter("protected override val createManyOperation:")
            .substringBefore("protected override val createOperation:")
        assert(!createBinding.contains("freshItem") && !createBinding.contains("candidate ->")) {
            "CREATE evaluators must not require identity converters\n$output"
        }
        assert(output.contains("buildDeleteMutationOperation( entity = RepoBytesRecordDescriptor, converter = RepoBytesRecordDeleteConverter, privacy = configuredPrivacy, validation = configuredValidation,")) {
            "DELETE should delegate policy composition to runtime\n$output"
        }
        assert(!output.contains("mutationValidationEvaluatorForInternalUse") &&
            !output.contains("validationDecisionEvaluatorForInternalUse")) {
            "Validation wiring should use runtime constructors\n$output"
        }
        assert(!output.contains("fun snapshotCreateCandidate")) {
            "rule items should be constructed directly, without callbacks into the repo\n$output"
        }
        val viewerContexts = Regex(
            Regex.escape("PrivacyRuleContext(viewerContext, client.readOnlyClient)"),
        ).findAll(output).count()
        assert(viewerContexts == 0) {
            "Rule contexts belong to the runtime repository; found $viewerContexts\n$output"
        }
        val validationContexts = Regex(
            Regex.escape("val ruleContext = ValidationRuleContext(client.readOnlyClient)"),
        ).findAll(output).count()
        assert(validationContexts == 0) {
            "Mutation validation context construction belongs to runtime phases\n$output"
        }
        assert(output.contains("loadPrivacyRules = configuredPrivacy.loadRules,"))
        assert(!output.contains("freshItem") && !output.contains("LoadPrivacyItem")) {
            "LOAD should pass entities directly without a wrapper or converter\n$output"
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
    fun `repository superclass arguments are consistently indented on separate lines`() {
        val car = Car()
        val session = Session()
        finalize(car, User(), session)

        for ((schema, superclass, idType) in listOf(
            Triple(car, "GeneratedIdRepository", "Int"),
            Triple(session, "ExplicitIdRepository", "String"),
        )) {
            val name = schema::class.simpleName!!
            val output = generator.generate(name, schema).toString()
            val expected = """
                $superclass<$name, $idType, ${name}CreateDraft, ${name}UpdateDraft, ${name}Query, ReadOnlyEntClient>(
                      entity = ${name}Descriptor,
                      driver = driver,
                      mutationExecutor = MutationExecutor(driver, client),
                      loadPrivacyRules = configuredPrivacy.loadRules,
                      defaultUpdateConsistency = client.defaultUpdateConsistency,
                      defaultRelationshipLocking = client.defaultRelationshipLocking,
                    ),
                    ${name}ReadSurface {
            """.trimIndent()

            assert(output.contains(expected)) {
                "Superclass arguments should keep names and values together with consistent indentation\n$output"
            }
        }
    }

    @Test
    fun `generated ID repo inherits the typed runtime entry points`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("GeneratedIdRepository<Car, Int, CarCreateDraft, CarUpdateDraft, CarQuery, ReadOnlyEntClient>")) {
            "The base should retain concrete entity, ID, drafts, query, and rule client types\n$output"
        }
        for (method in listOf("query", "create", "update", "findById", "delete", "deleteById", "createMany", "deleteMany")) {
            assert(!output.contains("fun $method(")) { "$method should be inherited, not generated\n$output" }
        }
        assert(!output.contains("CreateMutationRepository") && !output.contains("UpdateMutationRepository")) {
            "Pending-mutation execution binding belongs to the runtime base\n$output"
        }
    }

    @Test
    fun `repo binds the schema ID type for inherited lookup and deletion`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("GeneratedIdRepository<User, UUID, UserCreateDraft, UserUpdateDraft, UserQuery, ReadOnlyEntClient>")) {
            "Inherited ID parameters should use the schema's UUID type\n$output"
        }
        assert(!output.contains("explainFindById"))
    }

    @Test
    fun `repo supplies a query constructor without generating read execution`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("protected override fun newQuery(): CarQuery = CarQuery(driver, client)")) {
            "The base should construct each query with this repository's driver and client\n$output"
        }
        assert(!output.contains("readRootQuery(") && !output.contains("driver.byId(") &&
            !output.contains("ReadResult.Success") && !output.contains("loadDenialOrNull")) {
            "Read execution, result mapping, and LOAD enforcement belong to runtime\n$output"
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
    fun `repo supplies fresh draft constructors without binding pending mutations`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("protected override fun newCreateDraft(): CarCreateDraft = CarCreateDraft()"))
        assert(output.contains("protected override fun newUpdateDraft(): CarUpdateDraft = CarUpdateDraft()"))
        assert(!output.contains("PendingCreateMutation") && !output.contains("PendingUpdateMutation") &&
            !output.contains("UpdateMutationRequest") && !output.contains(".apply(block)")) {
            "Draft configuration, request construction, and pending handles belong to runtime\n$output"
        }
    }

    @Test
    fun `repo supplies a shared executor defaults and a protected update operation`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(Regex("MutationExecutor\\(driver, client\\)").findAll(output).count() == 1) {
            "The base should receive one shared mutation executor\n$output"
        }
        assert(output.contains("defaultUpdateConsistency = client.defaultUpdateConsistency"))
        assert(output.contains("defaultRelationshipLocking = client.defaultRelationshipLocking"))
        assert(output.contains("protected override val updateOperation: MutationOperation<ReadOnlyEntClient, UpdateMutationInput<CarUpdateDraft>, Car>"))
        assert(Regex("adapter = CarUpdateAdapter\\(driver\\)").findAll(output).count() == 1)
        assert(output.contains("entity = CarDescriptor, mutationRuntime = client,"))
        assert(!output.contains("private val updateAdapter:") && !output.contains("updateAdapter.updateOperation"))
        assert(!output.contains("fun executeUpdate(") && !output.contains("mutationExecutor.execute(")) {
            "Execution forwarding belongs to the base, not the generated repository or adapter\n$output"
        }
    }

    @Test
    fun `update delegates evaluator composition with one typed rule input conversion`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString().replace("\\s+".toRegex(), " ")
        val operation = output.substringAfter("protected override val updateOperation:")
            .substringBefore("private val createConverter:")
        assert(operation.contains("buildUpdateMutationOperation( entity = UserDescriptor, mutationRuntime = client, privacy = configuredPrivacy, validation = configuredValidation,"))
        val ruleInput = "UserUpdateRuleInput( state.before, state.requestedPatch, state.effectivePatch, state.candidate, state.edgeChanges, )"
        assert(Regex(Regex.escape(ruleInput)).findAll(operation).count() == 1) {
            "Both UPDATE evaluators should share one conversion over all prepared rule values\n$output"
        }
        assert(operation.contains("ruleInput = { state: UserUpdateAdapter.PreparedState ->"))
        assert(!operation.contains("candidate =")) {
            "The prepared update should supply its candidate without a generated selector\n$output"
        }
        assert(operation.contains("privacy = configuredPrivacy, validation = configuredValidation,"))
        assert(output.contains("MutationOperation<ReadOnlyEntClient, UpdateMutationInput<UserUpdateDraft>, User>"))
        assert(!output.contains("ruleClientProvider") && !operation.contains("client.readOnlyClient")) {
            "update operation construction must not resolve or capture a read client\n$output"
        }
        assert(!output.contains("mutationPrivacyEvaluatorForInternalUse") &&
            !output.contains("privacyDecisionEvaluatorForInternalUse"))
        assert(!operation.contains("lifecycle") && !operation.contains("unresolvedReason")) {
            "UPDATE privacy and validation diagnostics should belong to runtime\n$output"
        }
        assert(!operation.contains("DerivesFromCreate") && !operation.contains("DecisionEvaluator(")) {
            "Runtime should select and construct primary, fallback, and additional rule evaluators\n$output"
        }
    }

    @Test
    fun `repo supplies one runtime hook lifecycle to the update operation`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "hooks = UpdateMutationHooks( converter = UpdateHookStateConverter(client), beforeSave = configuredHooks.beforeSave, beforeUpdate = configuredHooks.beforeUpdate, afterUpdate = configuredHooks.afterUpdate, ),",
            ),
        ) {
            "The repository should inject one hook lifecycle into the update operation\n$output"
        }
        assert(output.contains("private class UpdateHookStateConverter(")) {
            "The repository should keep its schema-specific hook-state converter private\n$output"
        }
        assert(!output.contains("ValueFactory")) {
            "Generated update wiring should not use callback factories\n$output"
        }
    }

    @Test
    fun `repo supplies after update hooks to the runtime operation`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("afterUpdate = configuredHooks.afterUpdate")) {
            "The repository should inject the afterUpdate hook list into runtime hooks\n$output"
        }
        assert(!output.contains("override fun runAfterUpdate(")) {
            "Generated code should not implement afterUpdate execution\n$output"
        }
        assert(!output.contains("runBatchHooksForInternalUse(listOf(updatedEntity)")) {
            "generated code should not execute afterUpdate hooks itself\n$output"
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
    fun `repo delegates schema registration to the runtime base`() {
        val car = Car()
        val session = Session()
        finalize(car, User(), session)

        for (schema in listOf(car, session)) {
            val name = schema::class.simpleName!!
            val output = generator.generate(name, schema).toString()

            assert(output.contains("entity = ${name}Descriptor,") && output.contains("driver = driver,")) {
                "Both repository bases should receive the descriptor and driver\n$output"
            }
            assert(!output.contains("driver.register(") && !output.contains("init {")) {
                "Schema registration belongs to the runtime base, not generated initialization\n$output"
            }
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
        assert(output.contains("beforeSave = configuredHooks.beforeSave")) {
            "Should use the resolved beforeSave hooks directly\n$output"
        }
        assert(output.contains("afterDelete = configuredHooks.afterDelete")) {
            "Should use the resolved afterDelete hooks directly\n$output"
        }
        assert(!output.contains("private val beforeSaveHooks") && !output.contains("private val afterDeleteHooks"))
        assert(!output.contains("HookRunner")) {
            "Repositories should pass resolved hook lists without constructing runner instances\n$output"
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
    fun `scalar create binds one protected operation for both disclosure modes`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("protected override val createOperation: MutationOperation<ReadOnlyEntClient, CreateMutationInput<CarCreateDraft>, Car>"))
        assert(output.contains("CreateMutationOperation(createManyOperation)"))
        assert(!output.contains("fun saveCreation(") && !output.contains("fun saveAndLoadCreation(") &&
            !output.contains("checkReturnedEntityPrivacy") && !output.contains("mapResult")) {
            "Scalar execution and disclosure selection belong to the runtime repository\n$output"
        }
        assert(!output.contains("it.single()") && !output.contains("requireOne()") && !output.contains("requireMany()"))
    }

    @Test
    fun `repo binds shared scalar and bulk create lifecycle without generating it`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("protected override val createManyOperation: CreateManyMutationOperation<ReadOnlyEntClient, CarCreateDraft, CarWriteCandidate, Car, CarBeforeSaveState, CarBeforeCreateState>"))
        assert(output.contains("CreateMutationOperation(createManyOperation)"))
        assert(!output.contains("CreateManyMutationInput") && !output.contains("CreateManyDisclosure"))
        assert(!output.contains("ArrayList<CarCreateDraft>") && !output.contains("driver.insertMany(Car.TABLE") &&
            !output.contains("val candidates = prepared.map")) {
            "Batch input preparation and lifecycle coordination belong to runtime\n$output"
        }
    }

    @Test
    fun `all bulk terminals share one protected transaction repository binding`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("protected override fun <Result> withTransaction(block: TransactionScope.(GeneratedIdRepository<Car, Int, CarCreateDraft, CarUpdateDraft, CarQuery, ReadOnlyEntClient>) -> Result): TransactionResult<Result>"))
        assert(output.contains("client.withTransaction { tx -> block(tx.cars) }"))
        assert(Regex("client.withTransaction").findAll(output).count() == 1) {
            "CREATE and DELETE bulk operations should share one transaction-bound repo lookup\n$output"
        }
        assert(!output.contains("ownedTransaction =") && !output.contains("completionCapture") &&
            !output.contains("executeInOwnedTransactionForInternalUse") && !output.contains("orRollback()")) {
            "Execution rebinding, completion capture, and rollback belong to the runtime base\n$output"
        }
    }

    @Test
    fun `repo binds typed scalar and bulk delete operations directly`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("protected override val deleteOperation: MutationOperation<ReadOnlyEntClient, DeleteMutationInput, Boolean>") && !output.contains("DeleteOperation")) {
            "the scalar DELETE operation should return a Boolean directly\n$output"
        }
        assert(output.contains("buildDeleteMutationOperation( entity = CarDescriptor, converter = CarDeleteConverter,"))
        assert(output.contains("buildDeleteManyMutationOperation( entity = CarDescriptor, converter = CarDeleteConverter,"))
        assert(!output.contains("DeleteMutationSpec") && !output.contains("deleteSpec"))
        assert(!output.contains("idColumn = Car.SCHEMA.idColumn")) {
            "DELETE should derive its ID column from the injected descriptor\n$output"
        }
        val scalarBinding = output.substringAfter("protected override val deleteOperation:")
            .substringBefore("protected override val deleteManyOperation:")
        val bulkBinding = output.substringAfter("protected override val deleteManyOperation:")
            .substringBefore("protected override fun newQuery(")
        for (binding in listOf(scalarBinding, bulkBinding)) {
            assert(binding.contains("beforeDelete = configuredHooks.beforeDelete"))
            assert(binding.contains("afterDelete = configuredHooks.afterDelete"))
        }
        assert(!scalarBinding.contains("readQueryExecutor")) {
            "Scalar DELETE must not carry an unused query executor\n$output"
        }
        assert(bulkBinding.contains("readQueryExecutor = ReadQueryExecutor(driver, client)")) {
            "Bulk DELETE must receive the query executor bound to this repository's driver and client\n$output"
        }
        assert(!bulkBinding.contains("CarQuery") && !bulkBinding.contains("newQuery")) {
            "DELETE must not depend on a generated query builder or query factory\n$output"
        }
        assert(!output.contains("MutationLifecycle"))
        assert(output.contains("protected override val deleteManyOperation: MutationOperation<ReadOnlyEntClient, DeleteManyMutationInput<Car>, Int>"))
        assert(!output.contains("private fun _executeDeleteManyPhases(")) {
            "deleteMany transaction and failure coordination should live only in runtime\n$output"
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
        assert(output.contains("loadPrivacyRules = configuredPrivacy.loadRules"))
        assert(output.contains("buildCreateManyMutationOperation( entity = CarDescriptor, mutationRuntime = client, converter = createConverter, privacy = configuredPrivacy, validation = configuredValidation,"))
        assert(output.contains("buildDeleteMutationOperation( entity = CarDescriptor, converter = CarDeleteConverter, privacy = configuredPrivacy,"))
        assert(!output.contains("DerivesFromCreate"))
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
    fun `repo inherits fail-closed LOAD enforcement through its read surface`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("CarReadSurface"))
        assert(output.contains("loadPrivacyRules = configuredPrivacy.loadRules,"))
        for (lifecycle in listOf("Load", "Create", "Update", "Delete")) {
            assert(!output.contains("fun has" + lifecycle + "Privacy(")) {
                "Generated repositories should not duplicate constant privacy flags\n$output"
            }
        }
        assert(!output.contains("loadRules.isNotEmpty()"))
    }

    @Test
    fun `repo delegates LOAD evaluator construction and execution to the runtime base`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(!output.contains("fun evaluateLoadPrivacy(")) {
            "The runtime base should satisfy the read surface's correlated LOAD evaluation\n$output"
        }
        assert(output.contains("loadPrivacyRules = configuredPrivacy.loadRules,")) {
            "LOAD should inject rules into the runtime base without resolving a client\n$output"
        }
        assert(!output.contains("LoadPrivacyEvaluator") && !output.contains("val loadPrivacyEvaluator")) {
            "The runtime base should construct and retain its own LOAD evaluator\n$output"
        }
        assert(!output.contains("loadPrivacyEvaluatorForInternalUse"))
        assert(!output.contains("\"Car LOAD privacy\"") && !output.contains("\"no load rule allowed access\"")) {
            "LOAD diagnostics and the default denial reason should be owned by the runtime evaluator\n$output"
        }
        assert(!output.contains("loadPrivacyEvaluator.evaluate(")) {
            "The runtime base should supply the concrete context to its bound LOAD evaluator\n$output"
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
        assert(!output.contains("MutationRuleEvaluators") && !output.contains("private val deleteRules")) {
            "DELETE operations should not expose an intermediate evaluator pair\n$output"
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

        assert(output.contains("loadPrivacyRules = configuredPrivacy.loadRules,")) {
            "LOAD privacy should delegate through the runtime base's evaluator\n$output"
        }
        assert(output.contains("privacy = configuredPrivacy")) {
            "write privacy should delegate decisions through runtime evaluators\n$output"
        }
        assert(!output.contains("mutationPrivacyEvaluatorForInternalUse") &&
            !output.contains("privacyDecisionEvaluatorForInternalUse")) {
            "privacy evaluators should be constructed directly\n$output"
        }
        assert(output.contains("buildCreateManyMutationOperation( entity = CarDescriptor, mutationRuntime = client, converter = createConverter, privacy = configuredPrivacy, validation = configuredValidation,"))
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
    fun `delete delegates create fallback composition to runtime`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("buildDeleteMutationOperation( entity = CarDescriptor, converter = CarDeleteConverter, privacy = configuredPrivacy, validation = configuredValidation, ruleInput = ::CarDeleteRuleInput,")) {
            "DELETE should supply only its configuration and typed input conversion\n$output"
        }
        assert(!output.contains("MutationRuleEvaluators"))
        assert(!output.contains("fallback =") && !output.contains("DerivesFromCreate")) {
            "Fallback selection should not be repeated in generated repositories\n$output"
        }
        assert(!output.contains("lifecycle =") && !output.contains("ruleClientProvider")) {
            "fallback evaluation should use its parent mutation's diagnostics and rule context\n$output"
        }
    }

    @Test
    fun `repo constructs update validation without implementing its lifecycle`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("buildUpdateMutationOperation( entity = CarDescriptor, mutationRuntime = client, privacy = configuredPrivacy, validation = configuredValidation,")) {
            "The repository should delegate UPDATE evaluator construction\n$output"
        }
        assert(!output.contains("evaluateUpdateValidation")) {
            "Repository code should not implement UPDATE validation lifecycle logic\n$output"
        }
    }

    @Test
    fun `delete operation receives its converter query executor and rules directly`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("converter = CarDeleteConverter")) {
            "DELETE should use a schema-specific converter without calling back into the repo\n$output"
        }
        assert(!output.contains("fun buildDeleteCandidate") && !output.contains("::buildDeleteCandidate"))
        assert(output.contains("readQueryExecutor = ReadQueryExecutor(driver, client)")) {
            "DELETE should use the existing runtime query executor\n$output"
        }
        assert(output.contains("buildDeleteMutationOperation( entity = CarDescriptor, converter = CarDeleteConverter, privacy = configuredPrivacy,")) {
            "DELETE should bind its configured privacy rules\n$output"
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
    fun `explicit ID repo selects the ID-required runtime base`() {
        val session = Session()
        finalize(session)
        val output = generator.generate("Session", session).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("ExplicitIdRepository<Session, String, SessionCreateDraft, SessionUpdateDraft, SessionQuery, ReadOnlyEntClient>"))
        assert(!output.contains("GeneratedIdRepository") && !output.contains("fun create("))
        assert(output.contains("client.withTransaction { tx -> block(tx.sessions) }"))
    }

    @Test
    fun `explicit ID draft constructor requires and preserves the ID`() {
        val session = Session()
        finalize(session)
        val output = generator.generate("Session", session).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("protected override fun newCreateDraft(id: String): SessionCreateDraft = SessionCreateDraft(id = id)"))
        assert(!output.contains("fun newCreateDraft()"))
    }

    @Test
    fun `explicit ID repo keeps the batch engine private without exposing batch creation`() {
        val session = Session()
        finalize(session)
        val output = generator.generate("Session", session).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("private val createManyOperation: CreateManyMutationOperation<"))
        assert(output.contains("protected override val createOperation: MutationOperation<ReadOnlyEntClient, CreateMutationInput<SessionCreateDraft>, Session>"))
        assert(output.contains("CreateMutationOperation(createManyOperation)"))
        assert(!output.contains("protected override val createManyOperation") && !output.contains("fun createMany"))
        assert(!output.contains("CreateManyMutationInput") && !output.contains("newDraft ="))
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
        assert(output.contains("buildCreateManyMutationOperation( entity = CarDescriptor, mutationRuntime = client, converter = createConverter, privacy = configuredPrivacy, validation = configuredValidation,"))
        assert(output.contains("buildDeleteMutationOperation( entity = CarDescriptor, converter = CarDeleteConverter, privacy = configuredPrivacy, validation = configuredValidation,"))
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
    fun `repo delegates create and delete validation through runtime evaluators`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(!output.contains("fun evaluateCreateValidation")) {
            "CREATE validation should run in the shared runtime lifecycle\n$output"
        }
        assert(output.contains("validation = configuredValidation")) {
            "createOperation should capture CREATE validation\n$output"
        }
        assert(!output.contains("validationEvaluator =")) {
            "Runtime factories should inject validation evaluators into DELETE operations\n$output"
        }
        assert(!output.contains("evaluateUpdateValidation") && !output.contains("evaluateDeleteValidation")) {
            "repositories should not generate mutation validation engines\n$output"
        }
    }

    @Test
    fun `delete operations are constructed independently by runtime factories`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(Regex("buildDeleteMutationOperation\\(").findAll(output).count() == 1)
        assert(Regex("ruleInput = ::CarDeleteRuleInput").findAll(output).count() == 2)
        assert(Regex("buildDeleteManyMutationOperation\\(").findAll(output).count() == 1)
        assert(!output.contains("deleteRules") && !output.contains("privacyEvaluator ="))
    }

    @Test
    fun `repo resolves its concrete rule client only through a protected getter`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("protected override val ruleClient: ReadOnlyEntClient get() = client.readOnlyClient"))
        assert(Regex("client.readOnlyClient").findAll(output).count() == 1) {
            "The concrete client must be resolved only by the getter, after repository construction\n$output"
        }
        assert(!output.contains("ruleClientProvider") && !output.contains("ruleClient = client.readOnlyClient"))
        assert(!output.contains("asValidationReadClientForInternalUse") &&
            !output.contains("asReadClientForInternalUse") && !output.contains("withFixedViewerContextForInternalUse"))
    }

}
