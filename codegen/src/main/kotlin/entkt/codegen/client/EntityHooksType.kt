package entkt.codegen.client

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName

internal val ENTITY_HOOKS = ClassName("entkt.runtime.hook", "EntityHooks")

/** The runtime hook registrations with one entity's hook state types bound. */
internal fun entityHooksType(packageName: String, schemaName: String): TypeName =
    ENTITY_HOOKS.parameterizedBy(
        ClassName(packageName, "${schemaName}BeforeSaveState"),
        ClassName(packageName, "${schemaName}BeforeCreateState"),
        ClassName(packageName, "${schemaName}BeforeUpdateState"),
        ClassName(packageName, schemaName),
    )
