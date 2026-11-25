package entkt.codegen.client

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName

internal val ENTITY_HOOKS = ClassName("entkt.runtime.hook", "EntityHooks")
private val RESOLVED_ENTITY_HOOKS = ClassName("entkt.runtime.hook", "ResolvedEntityHooks")

internal fun entityHooksType(packageName: String, schemaName: String): TypeName =
    ENTITY_HOOKS.parameterizedBy(
        ClassName(packageName, "${schemaName}BeforeSaveState"),
        ClassName(packageName, "${schemaName}BeforeCreateState"),
        ClassName(packageName, "${schemaName}BeforeUpdateState"),
        ClassName(packageName, schemaName),
    )

internal fun resolvedEntityHooksType(packageName: String, schemaName: String): TypeName =
    RESOLVED_ENTITY_HOOKS.parameterizedBy(
        ClassName(packageName, "${schemaName}BeforeSaveState"),
        ClassName(packageName, "${schemaName}BeforeCreateState"),
        ClassName(packageName, "${schemaName}BeforeUpdateState"),
        ClassName(packageName, schemaName),
    )
