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
    fun `repo wires typed create and delete rule values without generated copies`() {
        val schema = RepoBytesRecord()
        finalize(schema)
        val output = generator.generate("RepoBytesRecord", schema).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "buildCreateOperations( entity = RepoBytesRecordDescriptor, mutationRuntime = client, converter = RepoBytesRecordCreateConverter(driver, client.hookClientScopeForInternalUse),",
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
        assert(output.contains("ruleInput = ::RepoBytesRecordDeleteRuleInput")) {
            "DELETE should supply one typed conversion for privacy and validation\n$output"
        }
        assert(!output.contains("CreateRuleInput")) {
            "CREATE must not wrap its candidate\n$output"
        }
        assert(output.contains("buildDeleteMutationOperation( entity = RepoBytesRecordDescriptor, converter = RepoBytesRecordDeleteConverter, privacy = configuredPrivacy, validation = configuredValidation,")) {
            "DELETE should delegate policy composition to runtime\n$output"
        }
        assert(!output.contains("copyOf") && !output.contains(".copy(") && !output.contains("copyJsonValue")) {
            "Rule wiring must not generate defensive copies\n$output"
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
                      mutationRuntime = client,
                      loadPrivacyRules = configuredPrivacy.loadRules,
                      createOperations = buildCreateOperations(
            """.trimIndent()

            assert(output.contains(expected)) {
                "Superclass arguments should keep names and values together with consistent indentation\n$output"
            }
            for ((argument, factory) in listOf(
                "createOperations" to "buildCreateOperations",
                "updateOperation" to "buildUpdateOperation",
                "deleteOperation" to "buildDeleteMutationOperation",
                "deleteManyOperation" to "buildDeleteManyMutationOperation",
            )) {
                assert(output.contains("      $argument = $factory(\n        entity = ${name}Descriptor,")) {
                    "Factory arguments should be indented inside the superclass argument\n$output"
                }
            }
            assert(output.contains(
                "      defaultUpdateConsistency = client.defaultUpdateConsistency,\n" +
                    "      defaultRelationshipLocking = client.defaultRelationshipLocking,\n" +
                    "    ),\n    ${name}ReadSurface {",
            ))
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
    fun `repo constructor injects completed operations and runtime dependencies`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("driver = driver, mutationRuntime = client, loadPrivacyRules = configuredPrivacy.loadRules,")) {
            "The base should receive its execution dependencies and LOAD rules\n$output"
        }
        val constructor = output.substringBefore("CarReadSurface {")
        for (operation in listOf("createOperations", "updateOperation", "deleteOperation", "deleteManyOperation")) {
            assert(constructor.contains("$operation = build")) {
                "Operations should be injected into the superclass constructor\n$output"
            }
            assert(!output.contains("val $operation")) {
                "Repositories must not override or retain operation properties\n$output"
            }
        }
        assert(Regex(Regex.escape("CarCreateConverter(")).findAll(output).count() == 1)
        assert(!output.contains("private val createConverter"))
        assert(output.contains("defaultUpdateConsistency = client.defaultUpdateConsistency"))
        assert(output.contains("defaultRelationshipLocking = client.defaultRelationshipLocking"))
        assert(output.contains("updateOperation = buildUpdateOperation("))
        assert(Regex(Regex.escape("CarUpdateAdapter(driver, client.hookClientScopeForInternalUse)")).findAll(output).count() == 1)
        assert(output.contains("buildUpdateOperation( entity = CarDescriptor, mutationRuntime = client, privacy = configuredPrivacy, validation = configuredValidation,"))
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
        val operation = output.substringAfter("updateOperation =")
            .substringBefore("deleteOperation =")
        assert(operation.contains("buildUpdateOperation( entity = UserDescriptor, mutationRuntime = client, privacy = configuredPrivacy, validation = configuredValidation,"))
        assert(operation.contains("entity = UserDescriptor") && operation.contains("mutationRuntime = client")) {
            "The UPDATE factory should receive scoped runtime dependencies\n$output"
        }
        val ruleInput = "UserUpdateRuleInput( state.before, state.requestedPatch, state.effectivePatch, state.candidate, state.edgeChanges, )"
        assert(Regex(Regex.escape(ruleInput)).findAll(operation).count() == 1) {
            "Both UPDATE evaluators should share one conversion over all prepared rule values\n$output"
        }
        assert(operation.contains("ruleInput = { state: UserUpdateAdapter.PreparedState ->"))
        assert(!operation.contains("candidate =")) {
            "The prepared update should supply its candidate without a generated selector\n$output"
        }
        assert(operation.contains("privacy = configuredPrivacy, validation = configuredValidation,"))
        assert(output.contains("updateOperation = buildUpdateOperation("))
        assert(!output.contains("ruleClientProvider") && !operation.contains("client.readOnlyClient")) {
            "update operation construction must not resolve or capture a read client\n$output"
        }
        assert(!operation.contains("lifecycle") && !operation.contains("unresolvedReason")) {
            "UPDATE privacy and validation diagnostics should belong to runtime\n$output"
        }
        assert(!operation.contains("DerivesFromCreate") && !operation.contains("DecisionEvaluator(")) {
            "Runtime should select and construct primary, fallback, and additional rule evaluators\n$output"
        }
    }

    @Test
    fun `repo delegates update hook assembly to the runtime factory`() {
        val user = User()
        finalize(user, Car())
        val output = generator.generate("User", user).toString()
            .replace("\\s+".toRegex(), " ")

        assert(
            output.contains(
                "adapter = UserUpdateAdapter(driver, client.hookClientScopeForInternalUse), beforeSave = configuredHooks.beforeSave, beforeUpdate = configuredHooks.beforeUpdate, afterUpdate = configuredHooks.afterUpdate,",
            ),
        ) {
            "The repository should supply one combined adapter and hook lists to the runtime factory\n$output"
        }
        assert(!output.contains("UpdateMutationHooks(") && !output.contains("buildUpdateMutationOperation(")) {
            "The runtime factory should construct the hooks and UPDATE operation\n$output"
        }
        assert(!output.contains("fun toBeforeSaveState(") && !output.contains("fun toBeforeUpdateState(")) {
            "Hook-state construction should belong to the schema adapter, not the repository\n$output"
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
    fun `repo binds shared scalar and bulk create lifecycle without generating it`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("createOperations = buildCreateOperations("))
        assert(Regex("buildCreateOperations\\(").findAll(output).count() == 1) {
            "The repository should supply CREATE dependencies once for both operations\n$output"
        }
        assert(!output.contains("CreateManyMutationOperation") && !output.contains("CreateMutationOperation("))
        val binding = output.substringAfter("createOperations =")
            .substringBefore("updateOperation =")
        assert(binding.contains("entity = CarDescriptor") && binding.contains("mutationRuntime = client")) {
            "The CREATE factory should receive scoped runtime dependencies\n$output"
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
    }

    @Test
    fun `repo binds typed scalar and bulk delete operations directly`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("deleteOperation = buildDeleteMutationOperation(") && !output.contains("DeleteOperation")) {
            "the scalar DELETE operation should return a Boolean directly\n$output"
        }
        assert(output.contains("buildDeleteMutationOperation( entity = CarDescriptor, converter = CarDeleteConverter,"))
        assert(output.contains("buildDeleteManyMutationOperation( entity = CarDescriptor, converter = CarDeleteConverter,"))
        assert(!output.contains("idColumn = Car.SCHEMA.idColumn")) {
            "DELETE should derive its ID column from the injected descriptor\n$output"
        }
        val scalarBinding = output.substringAfter("deleteOperation =")
            .substringBefore("deleteManyOperation =")
        val bulkBinding = output.substringAfter("deleteManyOperation =")
            .substringBefore("defaultUpdateConsistency =")
        for (binding in listOf(scalarBinding, bulkBinding)) {
            assert(binding.contains("beforeDelete = configuredHooks.beforeDelete"))
            assert(binding.contains("afterDelete = configuredHooks.afterDelete"))
        }
        assert(!scalarBinding.contains("readQueryExecutor")) {
            "Scalar DELETE must not carry an unused query executor\n$output"
        }
        assert(bulkBinding.contains("driver = driver, readExecutionHost = client")) {
            "The bulk DELETE factory must receive the scoped driver and read host\n$output"
        }
        assert(!bulkBinding.contains("CarQuery") && !bulkBinding.contains("newQuery")) {
            "DELETE must not depend on a generated query builder or query factory\n$output"
        }
        assert(!output.contains("MutationLifecycle"))
        assert(output.contains("deleteManyOperation = buildDeleteManyMutationOperation("))
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
        assert(output.contains("buildCreateOperations( entity = CarDescriptor, mutationRuntime = client, converter = CarCreateConverter(driver, client.hookClientScopeForInternalUse), privacy = configuredPrivacy, validation = configuredValidation,"))
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
    fun `repositories leave storage rule evaluation and failure handling to runtime`() {
        val car = Car()
        val session = Session()
        finalize(car, User(), session)

        for ((schemaName, schema) in listOf("Car" to car, "Session" to session)) {
            val output = generator.generate(schemaName, schema).toString()
            for (runtimeOwned in listOf(
                "driver.byId(",
                "driver.query(",
                "driver.insert(",
                "driver.insertMany(",
                "driver.update(",
                "driver.delete(",
                "driver.deleteMany(",
                "classifyMutationException(",
                "MutationExecutor",
                "ReadQueryExecutor",
                "LoadPrivacyEvaluator",
                "MutationPrivacyEvaluator",
                "MutationValidationEvaluator",
                "PrivacyRuleContext(",
                "ValidationRuleContext(",
                "PrivacyDecision.",
                "ValidationDecision.",
                "rule.run(",
                "rule.validate(",
                "fun evaluateLoadPrivacy(",
                "MutationResult.failedForInternalUse",
                "recordTransactionMutationFailure",
                "catch (",
            )) {
                assert(!output.contains(runtimeOwned)) {
                    "Runtime-owned operation '$runtimeOwned' must not be generated in $schemaName repository\n$output"
                }
            }
            for (factory in listOf(
                "buildCreateOperations",
                "buildUpdateOperation",
                "buildDeleteMutationOperation",
                "buildDeleteManyMutationOperation",
            )) {
                assert(!output.contains("fun $factory(")) {
                    "Operation factories belong to runtime, not generated repositories\n$output"
                }
            }
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
        assert(!output.contains("fallback =") && !output.contains("DerivesFromCreate")) {
            "Fallback selection should not be repeated in generated repositories\n$output"
        }
        assert(!output.contains("lifecycle =") && !output.contains("ruleClientProvider")) {
            "fallback evaluation should use its parent mutation's diagnostics and rule context\n$output"
        }
    }

    @Test
    fun `delete factory receives its converter execution dependencies and rules directly`() {
        val car = Car()
        finalize(car, User())
        val output = generator.generate("Car", car).toString()
            .replace("\\s+".toRegex(), " ")

        assert(output.contains("converter = CarDeleteConverter")) {
            "DELETE should use a schema-specific converter without calling back into the repo\n$output"
        }
        assert(output.contains("driver = driver, readExecutionHost = client")) {
            "The DELETE factory should construct its query executor from scoped dependencies\n$output"
        }
        assert(output.contains("buildDeleteMutationOperation( entity = CarDescriptor, converter = CarDeleteConverter, privacy = configuredPrivacy,")) {
            "DELETE should bind its configured privacy rules\n$output"
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
    fun `explicit ID repo binds the CREATE pair without exposing batch creation`() {
        val session = Session()
        finalize(session)
        val output = generator.generate("Session", session).toString().replace("\\s+".toRegex(), " ")

        assert(output.contains("createOperations = buildCreateOperations("))
        assert(output.contains("buildCreateOperations( entity = SessionDescriptor, mutationRuntime = client, converter = SessionCreateConverter(driver, client.hookClientScopeForInternalUse),"))
        assert(!output.contains("val createManyOperation:") && !output.contains("fun createMany"))
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
        assert(output.contains("buildCreateOperations( entity = CarDescriptor, mutationRuntime = client, converter = CarCreateConverter(driver, client.hookClientScopeForInternalUse), privacy = configuredPrivacy, validation = configuredValidation,"))
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
    }

}
