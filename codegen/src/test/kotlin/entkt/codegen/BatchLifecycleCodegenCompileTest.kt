@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compile-time coverage for mixed scalar and batch lifecycle registration. */
class BatchLifecycleCodegenCompileTest {

    private class HookSchema : EntSchema("hooks", clientName = "hookSchemas") {
        override fun id() = EntId.long()
    }

    private class BatchHookSchema : EntSchema("batch_hooks", clientName = "batchHookSchemas") {
        override fun id() = EntId.long()
    }

    private class Widget : EntSchema("widgets", clientName = "widgets") {
        override fun id() = EntId.int()
    }

    private fun generatedSources(): List<SourceFile> {
        val car = Car()
        val user = User()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(
            car::class to car,
            user::class to user,
        )
        car.finalize(registry)
        user.finalize(registry)
        return EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(car), SchemaInput(user)))
            .toCompileTestSources()
    }

    private fun compile(snippet: SourceFile): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = generatedSources() + snippet
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    private fun compileGenerated(sources: List<SourceFile>): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    @Test
    fun `generated lifecycle DSL accepts scalar spread and explicit batch callbacks together`() {
        val result = compile(
            SourceFile.kotlin(
                "BatchLifecycleRegistrationSnippet.kt",
                """
                package com.example.app

                import com.example.ent.Car
                import com.example.ent.CarCreateHookContext
                import com.example.ent.CarCreateValidationItem
                import com.example.ent.CarCreateValidationRule
                import com.example.ent.CarCreateBatchValidationRule
                import com.example.ent.CarLoadPrivacyItem
                import com.example.ent.CarLoadPrivacyRule
                import com.example.ent.CarLoadBatchPrivacyRule
                import com.example.ent.CarPolicyScope
                import com.example.ent.EntClient
                import com.example.ent.EntPrivacyReadClient
                import com.example.ent.EntValidationReadClient
                import entkt.runtime.driver.Driver
                import entkt.runtime.hook.Hook
                import entkt.runtime.hook.batchHook
                import entkt.runtime.privacy.EntityPolicy
                import entkt.runtime.privacy.PrivacyDecision
                import entkt.runtime.privacy.allowAll
                import entkt.runtime.privacy.batchPrivacyRule
                import entkt.runtime.validation.ValidationDecision
                import entkt.runtime.validation.batchValidationRule

                private val scalarLoad = CarLoadPrivacyRule { _, _ -> PrivacyDecision.Continue }
                private val scalarLoads = arrayOf(scalarLoad)
                private val batchLoad: CarLoadBatchPrivacyRule =
                    batchPrivacyRule<EntPrivacyReadClient, CarLoadPrivacyItem> { _, batch ->
                        batch.decideEach { PrivacyDecision.Allow }
                    }

                private val scalarCreate = CarCreateValidationRule { _, _ -> ValidationDecision.Valid }
                private val scalarCreates = arrayOf(scalarCreate)
                private val batchCreate: CarCreateBatchValidationRule =
                    batchValidationRule<EntValidationReadClient, CarCreateValidationItem> { _, batch ->
                        batch.decideEach { ValidationDecision.Valid }
                    }

                private object MixedPolicy : EntityPolicy<Car, CarPolicyScope> {
                    override fun configure(scope: CarPolicyScope) = scope.run {
                        privacy {
                            load(allowAll)
                            load(CarLoadPrivacyRule { _, _ -> PrivacyDecision.Continue })
                            load(*scalarLoads)
                            load(batchPrivacyRule { _, batch ->
                                batch.decideEach { PrivacyDecision.Allow }
                            })
                            load(batchLoad)
                            load(scalarLoad)
                        }
                        validation {
                            create(CarCreateValidationRule { _, _ -> ValidationDecision.Valid })
                            create(*scalarCreates)
                            create(batchValidationRule { _, batch ->
                                batch.decideEach { ValidationDecision.Valid }
                            })
                            create(batchCreate)
                            create(scalarCreate)
                        }
                    }
                }

                fun configuredClient(driver: Driver): EntClient = EntClient(driver) {
                    policies { cars(MixedPolicy) }
                    hooks {
                        cars {
                            beforeCreate { _ -> }
                            beforeCreate(Hook<CarCreateHookContext> { })
                            beforeCreate(batchHook<CarCreateHookContext> { })
                            beforeCreate { _ -> }
                        }
                    }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected mixed scalar and batch lifecycle registrations to compile, got:\n${result.messages}",
        )
    }

    @Test
    fun `entity names Hook and BatchHook do not collide with runtime hook contracts`() {
        val hook = HookSchema()
        val batchHook = BatchHookSchema()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(
            hook::class to hook,
            batchHook::class to batchHook,
        )
        hook.finalize(registry)
        batchHook.finalize(registry)
        val generated = EntGenerator("com.example.ent")
            .generate(
                listOf(
                    SchemaInput(hook),
                    SchemaInput(batchHook),
                ),
            )
            .toCompileTestSources()

        val result = compileGenerated(generated)

        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected runtime hook contract imports to avoid entity-name collisions, got:\n${result.messages}",
        )
    }

    @Test
    fun `entity named List does not shadow generated collection constructors`() {
        val widget = Widget()
        widget.finalize(mapOf(widget::class to widget))
        val generated = EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(widget)))
            .toCompileTestSources() + SourceFile.kotlin(
            "List.kt",
            """
            package com.example.ent

            class List
            """.trimIndent(),
            )

        val result = compileGenerated(generated)

        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected collection constructors to avoid an entity named List, got:\n${result.messages}",
        )
    }

    @Test
    fun `generated all batches LOAD privacy in row order and captures malformed cardinality`() {
        val widget = Widget()
        val generated = EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(widget)))
            .toCompileTestSources()
        val probe = SourceFile.kotlin(
            "BatchLifecycleRuntimeProbe.kt",
            """
            package com.example.app

            import com.example.ent.EntClient
            import com.example.ent.EntPrivacyReadClient
            import com.example.ent.Widget
            import com.example.ent.WidgetLoadPrivacyItem
            import com.example.ent.WidgetLoadPrivacyRule
            import com.example.ent.WidgetPolicyScope
            import entkt.query.OrderField
            import entkt.query.Predicate
            import entkt.runtime.driver.Driver
            import entkt.runtime.driver.NoopDriver
            import entkt.runtime.privacy.EntityPolicy
            import entkt.runtime.privacy.PrivacyDecision
            import entkt.runtime.privacy.PrivacyContext
            import entkt.runtime.privacy.PrivacyRuleContext
            import entkt.runtime.privacy.Viewer
            import entkt.runtime.privacy.batchPrivacyRule
            import entkt.runtime.rule.RuleBatch
            import entkt.runtime.rule.RuleDecisions
            import entkt.runtime.result.EntBatchRuleContractException
            import entkt.runtime.result.EntPrivacyDeniedException
            import entkt.runtime.result.LoadDenialOrigin
            import entkt.runtime.result.ReadResult

            object BatchLifecycleRuntimeProbe {
                private fun rowDriver(vararg ids: Int): Driver = object : Driver by NoopDriver {
                    override fun query(
                        table: String,
                        predicates: List<Predicate<*>>,
                        orderBy: List<OrderField<*>>,
                        limit: Int?,
                        offset: Int?,
                    ): List<Map<String, Any?>> = ids.map { mapOf("id" to it) }
                }

                private fun loadPolicy(
                    block: (
                        PrivacyRuleContext<EntPrivacyReadClient>,
                        RuleBatch<WidgetLoadPrivacyItem>,
                    ) -> RuleDecisions<PrivacyDecision>,
                ): EntityPolicy<Widget, WidgetPolicyScope> =
                    object : EntityPolicy<Widget, WidgetPolicyScope> {
                        override fun configure(scope: WidgetPolicyScope) = scope.run {
                            privacy { load(batchPrivacyRule(block)) }
                        }
                    }

                @JvmStatic
                fun run(): String {
                    val batchIds = mutableListOf<List<Int>>()
                    val batchPolicy = object : EntityPolicy<Widget, WidgetPolicyScope> {
                        override fun configure(scope: WidgetPolicyScope) = scope.run {
                            privacy {
                                load(batchPrivacyRule<EntPrivacyReadClient, WidgetLoadPrivacyItem> { _, batch ->
                                    batchIds += batch.map { it.entity.id }
                                    batch.decideEach { PrivacyDecision.Allow }
                                })
                            }
                        }
                    }
                    val batchOrder = (100 downTo 1).toList()
                    val loadedIds = EntClient(rowDriver(*batchOrder.toIntArray())) {
                        policies { widgets(batchPolicy) }
                    }.widgets.query().all().getOrThrow().map { it.id }
                    check(loadedIds == batchOrder)
                    check(batchIds == listOf(batchOrder))

                    val mixedResult = EntClient(rowDriver(30, 10, 40, 20)) {
                        policies {
                            widgets(loadPolicy { _, batch ->
                                batch.decideEach { item ->
                                    when (item.entity.id) {
                                        10 -> PrivacyDecision.Deny("deny-10")
                                        20 -> PrivacyDecision.Deny("deny-20")
                                        else -> PrivacyDecision.Allow
                                    }
                                }
                            })
                        }
                    }.widgets.query().all()
                    val mixedFailure = mixedResult as? ReadResult.Failed
                        ?: error("expected mixed ReadResult.Failed, got ${'$'}mixedResult")
                    val mixedException = mixedFailure.exception as? EntPrivacyDeniedException
                        ?: error("expected EntPrivacyDeniedException, got ${'$'}{mixedFailure.exception}")
                    check(mixedException.origin == LoadDenialOrigin.Root)
                    val mixedDenials = mixedException.denials
                    check(mixedDenials.map { it.entityType } == listOf("Widget", "Widget"))
                    check(
                        mixedDenials.map { Triple(it.entityKey.field, it.entityKey.value, it.reason) } ==
                            listOf(
                                Triple<String, Any, String>("id", 10, "deny-10"),
                                Triple<String, Any, String>("id", 20, "deny-20"),
                            )
                    )

                    val scalarIds = mutableListOf<Int>()
                    val scalarPolicy = object : EntityPolicy<Widget, WidgetPolicyScope> {
                        override fun configure(scope: WidgetPolicyScope) = scope.run {
                            privacy {
                                load(WidgetLoadPrivacyRule { _, item ->
                                    scalarIds += item.entity.id
                                    PrivacyDecision.Allow
                                })
                            }
                        }
                    }
                    EntClient(rowDriver(8, 5, 6)) {
                        policies { widgets(scalarPolicy) }
                    }.widgets.query().all().getOrThrow()
                    check(scalarIds == listOf(8, 5, 6))

                    var emptyBatchCalls = 0
                    val emptyPolicy = object : EntityPolicy<Widget, WidgetPolicyScope> {
                        override fun configure(scope: WidgetPolicyScope) = scope.run {
                            privacy {
                                load(batchPrivacyRule<EntPrivacyReadClient, WidgetLoadPrivacyItem> { _, batch ->
                                    emptyBatchCalls += 1
                                    batch.decideEach { PrivacyDecision.Allow }
                                })
                            }
                        }
                    }
                    val empty = EntClient(rowDriver()) {
                        policies { widgets(emptyPolicy) }
                    }.widgets.query().all().getOrThrow()
                    check(empty.isEmpty())
                    check(emptyBatchCalls == 0)

                    var bypassBatchCalls = 0
                    val bypassRoot = EntClient(rowDriver(71, 72)) {
                        policies {
                            widgets(loadPolicy { _, _ ->
                                bypassBatchCalls += 1
                                error("PrivacyBypass must not invoke LOAD rules")
                            })
                        }
                    }
                    val bypassIds = bypassRoot.withPrivacyContext(
                        PrivacyContext(Viewer.PrivacyBypass("generated-probe")),
                    ) { scoped ->
                        scoped.widgets.query().all().getOrThrow().map { it.id }
                    }
                    check(bypassIds == listOf(71, 72))
                    check(bypassBatchCalls == 0)

                    val singletonBatches = mutableListOf<List<Int>>()
                    val singletonPolicy = loadPolicy { _, batch ->
                        singletonBatches += batch.map { it.entity.id }
                        batch.decideEach { PrivacyDecision.Allow }
                    }
                    val found = EntClient(rowDriver(41)) {
                        policies { widgets(singletonPolicy) }
                    }.widgets.findById(41).getOrThrow()
                    check(found?.id == 41)
                    val first = EntClient(rowDriver(42)) {
                        policies { widgets(singletonPolicy) }
                    }.widgets.query().firstOrNull().getOrThrow()
                    check(first?.id == 42)
                    check(singletonBatches == listOf(listOf(41), listOf(42)))

                    val presentCallbackCount = singletonBatches.size
                    val missing = EntClient(rowDriver()) {
                        policies { widgets(singletonPolicy) }
                    }.widgets.findById(404).getOrThrow()
                    check(missing == null)
                    val noFirst = EntClient(rowDriver()) {
                        policies { widgets(singletonPolicy) }
                    }.widgets.query().firstOrNull().getOrThrow()
                    check(noFirst == null)
                    check(singletonBatches.size == presentCallbackCount)

                    var priorDecisions: RuleDecisions<PrivacyDecision>? = null
                    val foreignDecisionsPolicy = object : EntityPolicy<Widget, WidgetPolicyScope> {
                        override fun configure(scope: WidgetPolicyScope) = scope.run {
                            privacy {
                                load(batchPrivacyRule<EntPrivacyReadClient, WidgetLoadPrivacyItem> { _, batch ->
                                    priorDecisions ?: batch.decideEach { PrivacyDecision.Allow }
                                        .also { priorDecisions = it }
                                })
                            }
                        }
                    }
                    val foreignClient = EntClient(rowDriver(4, 7, 9)) {
                        policies { widgets(foreignDecisionsPolicy) }
                    }
                    foreignClient.widgets.query().all().getOrThrow()
                    val foreign = foreignClient.widgets.query().all()
                    val failure = foreign as? ReadResult.Failed
                        ?: error("expected ReadResult.Failed, got ${'$'}foreign")
                    val contract = failure.exception as? EntBatchRuleContractException
                        ?: error("expected EntBatchRuleContractException, got ${'$'}{failure.exception}")
                    check(contract.foreignBatchResult)

                    return "${'$'}{batchIds.size}:${'$'}{batchIds.single().size}:" +
                        "${'$'}{batchIds.single().first()}:${'$'}{batchIds.single().last()}|" +
                        "${'$'}{scalarIds.joinToString(",")}|${'$'}emptyBatchCalls|" +
                        "${'$'}{contract.expectedSize}:${'$'}{contract.actualSize}:${'$'}{contract.foreignBatchResult}|" +
                        "${'$'}{mixedDenials.size}:${'$'}{mixedDenials.first().entityKey.value}:" +
                        "${'$'}{mixedDenials.last().entityKey.value}|${'$'}bypassBatchCalls:" +
                        "${'$'}{bypassIds.size}|${'$'}{singletonBatches.joinToString(";") { it.single().toString() }}"
                }
            }
            """.trimIndent(),
        )

        val result = compileGenerated(generated + probe)
        assertEquals(
            KotlinCompilation.ExitCode.OK,
            result.exitCode,
            "Expected generated plural LOAD batch-runtime probe to compile, got:\n${result.messages}",
        )

        val probeClass = result.classLoader.loadClass("com.example.app.BatchLifecycleRuntimeProbe")
        assertEquals(
            "1:100:100:1|8,5,6|0|3:3:true|2:10:20|0:2|41;42",
            probeClass.getMethod("run").invoke(null),
        )
    }
}
