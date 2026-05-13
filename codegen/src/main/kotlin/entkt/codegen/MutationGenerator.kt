package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import entkt.schema.EntSchema

/**
 * Generates a `${SchemaName}Mutation` interface per entity. Both the
 * generated Create and Update builders implement this interface, which
 * exposes all **mutable** field properties (immutable fields are excluded
 * since they can't be changed on update). Edge FK properties are included.
 *
 * This lets users register `onBeforeSave` hooks that fire on both create
 * and update — validation, timestamp injection, etc. — without
 * duplicating the logic.
 *
 * **Conceptually write-only.** The `Mutation` interface is intentionally
 * the *shared writable surface* for `beforeSave` hooks. It has no
 * patch-model operations (`unset{Field}()`, reading pending-state)
 * because those are update-specific — creates don't have a `dirtyFields`
 * patch to unset from. Reading property getters is allowed by the
 * Kotlin type system (declared `var`) but the read semantics differ
 * between Create and Update implementations:
 *
 * - On Create, `m.title` returns the staged value (or `null` if nothing
 *   has been assigned).
 * - On Update, `m.title` **throws** when the field is not in
 *   `dirtyFields`, because a default-null getter would conflate
 *   `Unset` and explicit `Set(null)` (see the id-based update roots
 *   RFC). Hooks that need to inspect pending update state should use
 *   `beforeUpdate` and read `ctx.patch.title`, which has explicit
 *   `FieldPatch.Unset` / `Set` / `Set(null)` semantics.
 *
 * In short: use `beforeSave` when you only need to **write**
 * field/FK values that apply uniformly to create and update. Use
 * `beforeCreate` / `beforeUpdate` for phase-specific reads, patch
 * inspection, or `unset{Field}()`.
 *
 * Also generates a `${SchemaName}UpdateMutationView` interface that
 * extends the shared `Mutation` interface with `unset{Field}()` methods.
 * This is the restricted, hook-facing view used inside
 * `${SchemaName}UpdateHookContext.mutation` — narrowing to writable
 * field/FK setters plus unset semantics, hiding `save()`, the loaded
 * `entity` lateinit, the owner `id`, and the private patch helpers
 * that live on the full update builder. `unset` lives only on this
 * view (not on `Mutation`) because the patch model it removes from is
 * update-specific.
 */
internal class MutationGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val interfaceName = "${schemaName}Mutation"
        val viewName = "${schemaName}UpdateMutationView"
        val fields = schema.fields()
        val mutableFields = fields.filter { !it.immutable }
        val edgeFks = computeEdgeFks(schema, schemaNames)

        val mutationInterface = TypeSpec.interfaceBuilder(interfaceName)

        for (field in mutableFields) {
            val typeName = field.resolvedTypeName().copy(nullable = true)
            val prop = PropertySpec.builder(toCamelCase(field.name), typeName)
                .mutable(true)
            val comment = field.comment
            if (comment != null) prop.addKdoc("%L", comment)
            mutationInterface.addProperty(prop.build())
        }

        for (fk in edgeFks) {
            val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
            mutationInterface.addProperty(
                PropertySpec.builder(fk.propertyName, typeName)
                    .mutable(true)
                    .build(),
            )
        }

        // The restricted hook-facing view extends Mutation and adds
        // unset semantics. Hooks see only this interface through
        // `ctx.mutation`, which prevents calling `save()`, touching the
        // `entity` lateinit, or otherwise reentering the save pipeline.
        val viewInterface = TypeSpec.interfaceBuilder(viewName)
            .addSuperinterface(ClassName(packageName, interfaceName))
        for (field in mutableFields) {
            viewInterface.addFunction(unsetSpec(toCamelCase(field.name)))
        }
        for (fk in edgeFks) {
            viewInterface.addFunction(unsetSpec(fk.propertyName))
        }

        return FileSpec.builder(packageName, interfaceName)
            .addType(mutationInterface.build())
            .addType(viewInterface.build())
            .build()
    }

    private fun unsetSpec(prop: String): FunSpec {
        val name = "unset${prop.replaceFirstChar { it.uppercaseChar() }}"
        return FunSpec.builder(name)
            .addModifiers(com.squareup.kotlinpoet.KModifier.ABSTRACT)
            .build()
    }
}
