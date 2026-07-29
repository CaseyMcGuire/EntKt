package entkt.codegen.client

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import entkt.codegen.SchemaInput
import entkt.codegen.pluralize

private val PRIVACY_CONTEXT = ClassName("entkt.runtime.privacy", "PrivacyContext")
private val ENT_INTERCEPTORS_CONFIG = ClassName("entkt.runtime.query", "EntInterceptorsConfig")
private val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")

/**
 * Emits the generated read-runtime contract: `EntReadRuntime` plus one
 * `${Entity}ReadSurface` interface per schema.
 *
 * `EntReadRuntime` names exactly what generated queries need from their
 * host — `currentPrivacyContext()`, `visibleOverfetchLimit`, the
 * `@EntktInternal` interceptor registry, and one accessor per entity
 * typed to that entity's read surface (`hasLoadPrivacy()` /
 * `evaluateLoadPrivacy(...)`, the only repo members query terminals
 * call). Both `EntClient` and `EntReadClient` implement it, so
 * generated query and index-stage constructors can accept the contract
 * instead of the full client.
 *
 * Everything here is `public` + `@EntktInternal`, not Kotlin-`internal`:
 * the public query constructors cannot expose an internal parameter
 * type, the public `EntClient` / `EntReadClient` cannot
 * expose an internal supertype, and `internal` would be no guard anyway
 * — generated code compiles into the consuming application's module,
 * where `internal` stays visible to exactly the application code the
 * contract is meant to keep out. The opt-in marker is the gate;
 * generated files consume it with `@file:OptIn`, which does not
 * propagate to application call sites.
 */
internal class ReadRuntimeGenerator(
    private val packageName: String,
) {

    fun generate(schemas: List<SchemaInput>): FileSpec {
        // Same accessor order as EntClient's repo properties.
        val sorted = topologicalSort(schemas)

        val fileBuilder = FileSpec.builder(packageName, "EntReadRuntime")
            // The declarations below reference each other and are all
            // `@EntktInternal`-marked; the file-level OptIn covers the
            // marked-type usages without re-marking every member.
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%T::class", ENTKT_INTERNAL)
                    .build()
            )

        for (input in schemas) {
            fileBuilder.addType(buildReadSurface(input))
        }
        fileBuilder.addType(buildReadRuntime(sorted))

        return fileBuilder.build()
    }

    private fun buildReadSurface(input: SchemaInput): TypeSpec {
        val entityClass = ClassName(packageName, input.name)
        return TypeSpec.interfaceBuilder("${input.name}ReadSurface")
            .addAnnotation(ENTKT_INTERNAL)
            .addKdoc(
                "Narrow per-entity read surface of `%LRepo`: the only repo members\n" +
                    "generated query terminals call. `%LReadRepo` implements it by\n" +
                    "delegating to the host repo, so LOAD-privacy behavior is identical\n" +
                    "through either client.",
                input.name, input.name,
            )
            .addFunction(
                FunSpec.builder("hasLoadPrivacy")
                    .addModifiers(KModifier.ABSTRACT)
                    .returns(Boolean::class)
                    .build()
            )
            .addFunction(
                FunSpec.builder("evaluateLoadPrivacy")
                    .addModifiers(KModifier.ABSTRACT)
                    .addParameter("privacy", PRIVACY_CONTEXT)
                    .addParameter("entity", entityClass)
                    .build()
            )
            .build()
    }

    private fun buildReadRuntime(sorted: List<SchemaInput>): TypeSpec {
        val builder = TypeSpec.interfaceBuilder("EntReadRuntime")
            .addAnnotation(ENTKT_INTERNAL)
            .addKdoc(
                "The read-runtime contract generated queries and index stages depend\n" +
                    "on, instead of the full `EntClient`. Implemented by `EntClient` and\n" +
                    "`EntReadClient`; a query constructed with either behaves identically\n" +
                    "on the read path (privacy context, LOAD privacy, read interceptors,\n" +
                    "visible-family overfetch cap).",
            )
            .addFunction(
                FunSpec.builder("currentPrivacyContext")
                    .addModifiers(KModifier.ABSTRACT)
                    .returns(PRIVACY_CONTEXT)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("visibleOverfetchLimit", INT)
                    .addModifiers(KModifier.ABSTRACT)
                    .build()
            )
            .addProperty(
                // Keeps the concrete property's `@EntktInternal` guard:
                // interface members can't be `internal`, so the opt-in
                // marker is what stops application code from reaching the
                // raw EntInterceptorsConfig (whose scope-key-keyed
                // `addEntity` permits wrong-entity registration via an
                // unchecked cast). Generated queries read it through
                // their files' `@file:OptIn`.
                PropertySpec.builder("entityInterceptors", ENT_INTERCEPTORS_CONFIG)
                    .addAnnotation(ENTKT_INTERNAL)
                    .addModifiers(KModifier.ABSTRACT)
                    .build()
            )

        for (input in sorted) {
            val propName = pluralize(input.name.replaceFirstChar { it.lowercase() })
            builder.addProperty(
                PropertySpec.builder(propName, ClassName(packageName, "${input.name}ReadSurface"))
                    .addModifiers(KModifier.ABSTRACT)
                    .build()
            )
        }

        return builder.build()
    }
}
