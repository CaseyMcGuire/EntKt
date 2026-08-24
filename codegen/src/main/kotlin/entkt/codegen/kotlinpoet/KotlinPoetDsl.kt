package entkt.codegen.kotlinpoet

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

/** Build a Kotlin file while keeping KotlinPoet's builder available as the DSL receiver. */
internal inline fun kotlinFile(
    packageName: String,
    fileName: String,
    build: FileSpec.Builder.() -> Unit,
): FileSpec = FileSpec.builder(packageName, fileName).apply(build).build()

/** Build a class declaration. */
internal inline fun classType(
    name: String,
    build: TypeSpec.Builder.() -> Unit,
): TypeSpec = TypeSpec.classBuilder(name).apply(build).build()

/** Build a class declaration whose name is already represented by KotlinPoet. */
internal inline fun classType(
    name: ClassName,
    build: TypeSpec.Builder.() -> Unit,
): TypeSpec = TypeSpec.classBuilder(name).apply(build).build()

/** Build an interface declaration. */
internal inline fun interfaceType(
    name: String,
    build: TypeSpec.Builder.() -> Unit,
): TypeSpec = TypeSpec.interfaceBuilder(name).apply(build).build()

/** Build an interface declaration whose name is already represented by KotlinPoet. */
internal inline fun interfaceType(
    name: ClassName,
    build: TypeSpec.Builder.() -> Unit,
): TypeSpec = TypeSpec.interfaceBuilder(name).apply(build).build()

/** Build a named object declaration. */
internal inline fun objectType(
    name: String,
    build: TypeSpec.Builder.() -> Unit,
): TypeSpec = TypeSpec.objectBuilder(name).apply(build).build()

/** Build a named object declaration whose name is already represented by KotlinPoet. */
internal inline fun objectType(
    name: ClassName,
    build: TypeSpec.Builder.() -> Unit,
): TypeSpec = TypeSpec.objectBuilder(name).apply(build).build()

/** Build a companion object declaration. */
internal inline fun companionObject(
    name: String? = null,
    build: TypeSpec.Builder.() -> Unit,
): TypeSpec = TypeSpec.companionObjectBuilder(name).apply(build).build()

/** Build an anonymous object declaration. */
internal inline fun anonymousType(
    build: TypeSpec.Builder.() -> Unit,
): TypeSpec = TypeSpec.anonymousClassBuilder().apply(build).build()

/** Build a function declaration. */
internal inline fun function(
    name: String,
    returnType: TypeName? = null,
    configure: FunSpec.Builder.() -> Unit = {},
): FunSpec = FunSpec.builder(name)
    .apply {
        returnType?.let(::returns)
        configure()
    }
    .build()

/** Build a constructor declaration. */
internal inline fun constructor(
    build: FunSpec.Builder.() -> Unit,
): FunSpec = FunSpec.constructorBuilder().apply(build).build()

/** Build a property getter. */
internal inline fun getter(
    build: FunSpec.Builder.() -> Unit,
): FunSpec = FunSpec.getterBuilder().apply(build).build()

/** Build a property setter. */
internal inline fun setter(
    build: FunSpec.Builder.() -> Unit,
): FunSpec = FunSpec.setterBuilder().apply(build).build()

/** Build a property declaration. */
internal inline fun property(
    name: String,
    type: TypeName,
    build: PropertySpec.Builder.() -> Unit = {},
): PropertySpec = PropertySpec.builder(name, type).apply(build).build()

/** Build a function or constructor parameter. */
internal inline fun parameter(
    name: String,
    type: TypeName,
    build: ParameterSpec.Builder.() -> Unit = {},
): ParameterSpec = ParameterSpec.builder(name, type).apply(build).build()

/** Build an annotation. */
internal inline fun annotation(
    type: ClassName,
    build: AnnotationSpec.Builder.() -> Unit = {},
): AnnotationSpec = AnnotationSpec.builder(type).apply(build).build()

/** Build a reusable fragment of generated Kotlin code. */
internal inline fun codeBlock(
    build: CodeBlock.Builder.() -> Unit,
): CodeBlock = CodeBlock.builder().apply(build).build()

/** Add a nested class to this file. */
internal inline fun FileSpec.Builder.classType(
    name: String,
    build: TypeSpec.Builder.() -> Unit,
) {
    addType(entkt.codegen.kotlinpoet.classType(name, build))
}

/** Add a nested class to this type. */
internal inline fun TypeSpec.Builder.classType(
    name: String,
    build: TypeSpec.Builder.() -> Unit,
) {
    addType(entkt.codegen.kotlinpoet.classType(name, build))
}

/** Add a nested interface to this file. */
internal inline fun FileSpec.Builder.interfaceType(
    name: String,
    build: TypeSpec.Builder.() -> Unit,
) {
    addType(entkt.codegen.kotlinpoet.interfaceType(name, build))
}

/** Add a nested interface to this type. */
internal inline fun TypeSpec.Builder.interfaceType(
    name: String,
    build: TypeSpec.Builder.() -> Unit,
) {
    addType(entkt.codegen.kotlinpoet.interfaceType(name, build))
}

/** Add a named object to this file. */
internal inline fun FileSpec.Builder.objectType(
    name: String,
    build: TypeSpec.Builder.() -> Unit,
) {
    addType(entkt.codegen.kotlinpoet.objectType(name, build))
}

/** Add a named object to this type. */
internal inline fun TypeSpec.Builder.objectType(
    name: String,
    build: TypeSpec.Builder.() -> Unit,
) {
    addType(entkt.codegen.kotlinpoet.objectType(name, build))
}

/** Add a companion object to this type. */
internal inline fun TypeSpec.Builder.companionObject(
    name: String? = null,
    build: TypeSpec.Builder.() -> Unit,
) {
    addType(entkt.codegen.kotlinpoet.companionObject(name, build))
}

/** Set this type's primary constructor. */
internal inline fun TypeSpec.Builder.primaryConstructor(
    build: FunSpec.Builder.() -> Unit,
) {
    primaryConstructor(constructor(build))
}

/** Add a function to this type. */
internal inline fun TypeSpec.Builder.function(
    name: String,
    returnType: TypeName? = null,
    configure: FunSpec.Builder.() -> Unit = {},
) {
    addFunction(entkt.codegen.kotlinpoet.function(name, returnType, configure))
}

/** Add a function to this file. */
internal inline fun FileSpec.Builder.function(
    name: String,
    returnType: TypeName? = null,
    configure: FunSpec.Builder.() -> Unit = {},
) {
    addFunction(entkt.codegen.kotlinpoet.function(name, returnType, configure))
}

/** Add a property to this type. */
internal inline fun TypeSpec.Builder.property(
    name: String,
    type: TypeName,
    build: PropertySpec.Builder.() -> Unit = {},
) {
    addProperty(entkt.codegen.kotlinpoet.property(name, type, build))
}

/** Add a property to this file. */
internal inline fun FileSpec.Builder.property(
    name: String,
    type: TypeName,
    build: PropertySpec.Builder.() -> Unit = {},
) {
    addProperty(entkt.codegen.kotlinpoet.property(name, type, build))
}

/** Add a parameter to this function or constructor. */
internal inline fun FunSpec.Builder.parameter(
    name: String,
    type: TypeName,
    build: ParameterSpec.Builder.() -> Unit = {},
) {
    addParameter(entkt.codegen.kotlinpoet.parameter(name, type, build))
}

/** Set this property's getter. */
internal inline fun PropertySpec.Builder.getter(
    build: FunSpec.Builder.() -> Unit,
) {
    getter(entkt.codegen.kotlinpoet.getter(build))
}

/** Set this property's setter. */
internal inline fun PropertySpec.Builder.setter(
    build: FunSpec.Builder.() -> Unit,
) {
    setter(entkt.codegen.kotlinpoet.setter(build))
}

/** Add a code body assembled with KotlinPoet's code-block builder. */
internal inline fun FunSpec.Builder.body(
    build: CodeBlock.Builder.() -> Unit,
) {
    addCode(codeBlock(build))
}

/** Add one generated Kotlin statement. */
internal fun FunSpec.Builder.statement(format: String, vararg arguments: Any) {
    addStatement(format, *arguments)
}

/** Add one generated Kotlin statement to a reusable code block. */
internal fun CodeBlock.Builder.statement(format: String, vararg arguments: Any) {
    addStatement(format, *arguments)
}
