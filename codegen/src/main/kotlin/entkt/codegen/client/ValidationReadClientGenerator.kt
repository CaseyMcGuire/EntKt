package entkt.codegen.client

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.SchemaInput
import entkt.codegen.metadata.toTypeName
import entkt.codegen.pluralize
import entkt.codegen.query.indexHelperTree
import entkt.schema.EntSchema

private val DRIVER = ClassName("entkt.runtime.driver", "Driver")
private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val ENT_INTERCEPTORS_CONFIG = ClassName("entkt.runtime.query", "EntInterceptorsConfig")
private val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")

/**
 * Emits `EntValidationReadClient` plus one `${Entity}ValidationReadRepo`
 * per schema: the read-only, System-scoped client generated validation
 * contexts expose to validators.
 *
 * Each validation read repo exposes the read surface only — the byId
 * family, the full `query { }` DSL (all terminals come with the query
 * class), the explainById* variants, and the generated index helpers.
 * The write surface (create / update / save / delete* / edge mutators /
 * `withTransaction` / config setters) simply does not exist on these
 * types, so a validator calling it is a compile error — the read-only
 * guarantee is structural, not conventional.
 *
 * Queries and index stages constructed through these repos receive the
 * `EntValidationReadClient` itself as their [EntReadRuntime], never an
 * `EntClient` — no validator-reachable object holds a full client, even
 * privately. LOAD-privacy behavior is delegated to the host client's
 * repos (typed as the narrow read surfaces), so `hasLoadPrivacy` /
 * `evaluateLoadPrivacy` behave identically through either client.
 */
internal class ValidationReadClientGenerator(
    private val packageName: String,
) {

    fun generate(
        schemas: List<SchemaInput>,
        schemaNames: Map<EntSchema, String>,
    ): FileSpec {
        // Same accessor/parameter order as EntClient's repo properties and
        // asValidationReadClient()'s positional host arguments.
        val sorted = topologicalSort(schemas)

        val fileBuilder = FileSpec.builder(packageName, "EntValidationReadClient")
            // The client implements the `@EntktInternal`-guarded
            // EntReadRuntime and the repos implement the guarded read
            // surfaces; the file-level OptIn consumes the requirement
            // here without propagating it to validator code.
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%T::class", ENTKT_INTERNAL)
                    .build()
            )

        for (input in schemas) {
            fileBuilder.addType(buildValidationReadRepo(input, schemaNames))
        }
        fileBuilder.addType(buildClient(sorted))

        return fileBuilder.build()
    }

    private fun buildValidationReadRepo(
        input: SchemaInput,
        schemaNames: Map<EntSchema, String>,
    ): TypeSpec {
        val schemaName = input.name
        val entityClass = ClassName(packageName, schemaName)
        val queryClass = ClassName(packageName, "${schemaName}Query")
        val indexesClass = ClassName(packageName, "${schemaName}Indexes")
        val readSurfaceClass = ClassName(packageName, "${schemaName}ReadSurface")
        val idType = input.schema.id().type.toTypeName()

        return TypeSpec.classBuilder("${schemaName}ValidationReadRepo")
            .addKdoc(
                "Read-only `%L` repository handed to validators via\n" +
                    "`EntValidationReadClient`. Reads behave exactly like the full repo's\n" +
                    "(same query machinery, read interceptors, LOAD-privacy delegation);\n" +
                    "the write surface does not exist here, so validator writes fail to\n" +
                    "compile.",
                schemaName,
            )
            .addSuperinterface(readSurfaceClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("driver", DRIVER)
                    .addParameter("host", readSurfaceClass)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("driver", DRIVER)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("driver")
                    .build()
            )
            .addProperty(
                // The host client's repo, narrowed to the read surface: the
                // LOAD-privacy delegation target. The narrow type keeps the
                // no-writes guarantee structural — no member anywhere in the
                // validation read client's object graph is typed EntClient
                // or ${schemaName}Repo.
                PropertySpec.builder("host", readSurfaceClass)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("host")
                    .build()
            )
            .addProperty(
                // Set by EntValidationReadClient's init block (the client
                // and its repos reference each other, mirroring the
                // EntClient/repo wiring).
                PropertySpec.builder("runtime", ClassName(packageName, "EntReadRuntime"))
                    .addModifiers(KModifier.INTERNAL, KModifier.LATEINIT)
                    .mutable(true)
                    .build()
            )
            .addFunction(
                FunSpec.builder("hasLoadPrivacy")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(Boolean::class)
                    .addStatement("return host.hasLoadPrivacy()")
                    .build()
            )
            .addFunction(
                FunSpec.builder("evaluateLoadPrivacy")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("privacy", PRIVACY_CONTEXT)
                    .addParameter("entity", entityClass)
                    .addStatement("host.evaluateLoadPrivacy(privacy, entity)")
                    .build()
            )
            .addFunction(buildQueryEntry(queryClass, clientRef = "runtime"))
            // Index-helper namespace: the same `${schemaName}Indexes`
            // stages the full repo exposes, constructed with the read
            // runtime. Emitted under the same eligibility condition.
            .also { builder ->
                if (indexHelperTree(input.schema, schemaNames) != null) {
                    builder.addProperty(buildIndexesProperty(indexesClass, clientRef = "runtime"))
                }
            }
            .addFunction(buildByIdOrNull(schemaName, entityClass, idType, clientRef = "runtime"))
            .addFunction(buildByIdOrThrow(entityClass, idType))
            .addFunction(buildVisibleByIdOrNull(entityClass, idType))
            .addFunction(buildByIdOrError(schemaName, entityClass, idType))
            .addFunctions(buildByIdExplainMethods(schemaName, entityClass, idType, clientRef = "runtime"))
            .build()
    }

    private fun buildClient(sorted: List<SchemaInput>): TypeSpec {
        val builder = TypeSpec.classBuilder("EntValidationReadClient")
            .addKdoc(
                "Read-only, System-scoped client exposed in generated validation\n" +
                    "contexts. Constructed by `EntClient.asValidationReadClient()` from the\n" +
                    "operation's current client: same driver instance (a\n" +
                    "transaction-scoped client yields a transaction-scoped read client),\n" +
                    "same read interceptors, same per-repo LOAD-privacy behavior, and a\n" +
                    "fixed `Viewer.PrivacyBypass(\"validation read\")` context so invariant\n" +
                    "checks are not blocked by LOAD privacy. Write-side state\n" +
                    "(`transactionRequirement`, hooks, validation config) is deliberately\n" +
                    "absent — its absence is part of the no-writes guarantee.",
            )
            .addSuperinterface(ClassName(packageName, "EntReadRuntime"))

        val ctor = FunSpec.constructorBuilder()
            .addModifiers(KModifier.INTERNAL)
            .addParameter("driver", DRIVER)
            .addParameter("privacyContext", PRIVACY_CONTEXT)
            .addParameter("entityInterceptors", ENT_INTERCEPTORS_CONFIG)
            .addParameter("visibleOverfetchLimit", INT)
        for (input in sorted) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            ctor.addParameter("${propName}Host", ClassName(packageName, "${input.name}ReadSurface"))
        }
        builder.primaryConstructor(ctor.build())

        builder.addProperty(
            PropertySpec.builder("privacyContext", PRIVACY_CONTEXT)
                .addModifiers(KModifier.PRIVATE)
                .initializer("privacyContext")
                .build()
        )
        builder.addProperty(
            // Same instance as the host client's registry, same
            // `@EntktInternal` guard on the override. KotlinPoet merges
            // this same-name-initialized property into the constructor, so
            // the marker needs the `property:` use-site target — opt-in
            // markers can't annotate a parameter.
            PropertySpec.builder("entityInterceptors", ENT_INTERCEPTORS_CONFIG)
                .addAnnotation(
                    AnnotationSpec.builder(ENTKT_INTERNAL)
                        .useSiteTarget(AnnotationSpec.UseSiteTarget.PROPERTY)
                        .build()
                )
                .addModifiers(KModifier.OVERRIDE)
                .initializer("entityInterceptors")
                .build()
        )
        builder.addProperty(
            PropertySpec.builder("visibleOverfetchLimit", INT)
                .addModifiers(KModifier.OVERRIDE)
                .initializer("visibleOverfetchLimit")
                .build()
        )

        for (input in sorted) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            val repoClass = ClassName(packageName, "${input.name}ValidationReadRepo")
            builder.addProperty(
                // Covariant override of EntReadRuntime's read-surface
                // accessor, narrowed to the validator-facing repo.
                PropertySpec.builder(propName, repoClass)
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer("%T(driver, %LHost)", repoClass, propName)
                    .build()
            )
        }

        val init = CodeBlock.builder()
        for (input in sorted) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            init.addStatement("%L.runtime = this", propName)
        }
        builder.addInitializerBlock(init.build())

        builder.addFunction(
            FunSpec.builder("currentPrivacyContext")
                .addModifiers(KModifier.OVERRIDE)
                .returns(PRIVACY_CONTEXT)
                .addStatement("return privacyContext")
                .build()
        )

        return builder.build()
    }
}
