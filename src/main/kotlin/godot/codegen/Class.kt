package godot.codegen

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import godot.codegen.utils.escapeUnderscore
import godot.codegen.utils.isCoreType
import godot.codegen.utils.isCoreTypeAdaptedForKotlin
import java.io.File


@JsonIgnoreProperties(ignoreUnknown = true)
class Class @JsonCreator constructor(
    @JsonProperty("name")
    val oldName: String,
    @JsonProperty("base_class")
    var baseClass: String,
    @JsonProperty("singleton")
    val isSingleton: Boolean,
    @JsonProperty("singleton_name")
    val singletonName: String,
    @JsonProperty("instanciable")
    val isInstanciable: Boolean,
    @JsonProperty("constants")
    val constants: Map<String, Int>,
    @JsonProperty("properties")
    val properties: List<Property>,
    @JsonProperty("signals")
    val signals: List<Signal>,
    @JsonProperty("methods")
    val methods: List<Method>,
    @JsonProperty("enums")
    val enums: List<Enum>
) {

    val newName: String = oldName.escapeUnderscore()
    val engineClassDBIndexName = "ENGINECLASS_${oldName.toUpperCase()}"
    val engineSingletonIndexName: String? = if (isSingleton) "ENGINESINGLETON_${newName.toUpperCase()}" else null
    var shouldGenerate: Boolean = true
    val additionalImports = mutableListOf<Pair<String, String>>()

    init {
        baseClass = baseClass.escapeUnderscore()
    }

    fun initClass() {
        shouldGenerate = newName != "GlobalConstants" && tree.getBaseClass(this)?.isSingleton == false
                || isInstanciable || isSingleton
        applyGettersAndSettersForProperties()
    }

    fun generate(outputDir: File, icalls: MutableSet<ICall>?) {
        if (!shouldGenerate) return

        val className = ClassName("godot", newName)

        val classTypeBuilder = createTypeBuilder(className)
        if (!isNative) {
            classTypeBuilder.addAnnotation(ClassName("godot.annotation", "GodotBaseType"))
        }

        if (newName == "Object") {
            if (isNative) {
                generatePointerVariable(classTypeBuilder)
                generateInitAndDestroy(classTypeBuilder)
                generateToVariantMethod(classTypeBuilder)
            } else {
                classTypeBuilder.superclass(ClassName("godot.core", "KtObject"))
            }
            generateSignalExtensions(classTypeBuilder)
        }
        if (!isNative && newName == "Reference") {
            classTypeBuilder.addFunction(
                FunSpec.builder("____DO_NOT_TOUCH_THIS_isRef____")
                    .addModifiers(KModifier.OVERRIDE)
                    .addStatement("return true")
                    .build()
            )
        }

        generateConstructors(classTypeBuilder)

        if (!isNative && isSingleton) {
            classTypeBuilder.addFunction(
                FunSpec.builder("____DO_NOT_TOUCH_THIS_isSingleton____")
                    .addModifiers(KModifier.OVERRIDE)
                    .addStatement("return true")
                    .build()
            )
        }

        generateEnums(classTypeBuilder)

        val baseCompanion = if (!isSingleton && constants.isNotEmpty()) TypeSpec.companionObjectBuilder() else null

        generateConstants(baseCompanion ?: classTypeBuilder)

        baseCompanion?.build()?.let { classTypeBuilder.addType(it) }

        generateSignals(classTypeBuilder)
        generateProperties(icalls, classTypeBuilder)
        generateMethods(classTypeBuilder, icalls)


        //Build Type and create file
        val fileBuilder = FileSpec
            .builder("godot", className.simpleName)
            .addType(classTypeBuilder.build())

        additionalImports.forEach {
            fileBuilder.addImport(it.first, it.second)
        }

        generateSuppressWarnings(fileBuilder)

        fileBuilder
            .addComment(GENERATED_COMMENT)
            .build()
            .writeTo(outputDir)
    }

    private fun applyGettersAndSettersForProperties() {
        properties.forEach { property ->
            methods.forEach { method ->
                property applyGetterOrSetter method
            }
        }
    }

    private fun createTypeBuilder(className: ClassName): TypeSpec.Builder {
        val typeSpec = if (isSingleton) TypeSpec.objectBuilder(className)
        else TypeSpec.classBuilder(className).addModifiers(KModifier.OPEN)

        if (isSingleton) typeSpec.superclass(ClassName("godot", "Object"))
        else if (baseClass.isNotEmpty()) typeSpec.superclass(ClassName("godot", baseClass))

        return typeSpec
    }

    private fun generatePointerVariable(typeBuilder: TypeSpec.Builder) {
        typeBuilder.addProperty(
            PropertySpec.builder(
                "ptr",
                ClassName("kotlinx.cinterop", "COpaquePointer")
            )
                .addModifiers(KModifier.INTERNAL, KModifier.LATEINIT)
                .mutable(true)
                .build()
        )
    }

    private fun generateInitAndDestroy(typeBuilder: TypeSpec.Builder) {
        typeBuilder.addFunctions(
            listOf(
                FunSpec.builder("_onInit").addModifiers(KModifier.OPEN).build(),
                FunSpec.builder("_onDestroy").addModifiers(KModifier.OPEN).build()
            )
        )
    }

    private fun generateSignalExtensions(typeBuilder: TypeSpec.Builder) {

        fun List<TypeVariableName>.toParameterTypes() = this.map {
            ParameterSpec.builder(it.name.toLowerCase(), it).build()
        }

        val typeVariablesNames = mutableListOf<TypeVariableName>()
        for (i in 0..10) {
            if (i != 0) typeVariablesNames.add(TypeVariableName.invoke("A${i - 1}"))

            val signalType = if (isNative) {
                ClassName("godot.core", "Signal$i")
            } else {
                ClassName("godot.signals", "Signal$i")
            }

            val emitFunBuilder = FunSpec.builder("emit")

            val signalParameterizedType = if (typeVariablesNames.isNotEmpty()) {
                val parameterSpecs = typeVariablesNames.toParameterTypes()
                emitFunBuilder.addTypeVariables(typeVariablesNames)
                emitFunBuilder.addParameters(parameterSpecs)
                emitFunBuilder.addStatement(
                    "%L(this@Object, ${
                    parameterSpecs
                        .map { it.name }
                        .reduce{ acc, string -> "$acc, $string" }
                    })",
                "emit"
                )
                signalType.parameterizedBy(typeVariablesNames)
            }
            else {
                emitFunBuilder.addStatement(
                    "%L(this@Object)",
                    "emit"
                )
                signalType
            }

            emitFunBuilder.receiver(signalParameterizedType)

            typeBuilder.addFunction(emitFunBuilder.build())

            val kTypeVariable = TypeVariableName.invoke(
                "K",
                bounds = *arrayOf(
                    LambdaTypeName.get(
                        returnType = UNIT,
                        parameters = *typeVariablesNames.toTypedArray()
                    )
                )
            ).copy(reified = true)
            val connectTypeVariableNames = listOf(
                *typeVariablesNames.toTypedArray(),
                kTypeVariable
            )

            val objectType = ClassName("godot", "Object")
            val arrayType = if (isNative) {
                ClassName("godot.core", "GodotArray")
            } else {
                ClassName("godot.core", "VariantArray")
            }
            val connectFun = FunSpec.builder("connect")
                .receiver(signalParameterizedType)
                .addTypeVariables(connectTypeVariableNames)
                .addModifiers(KModifier.INLINE)
                .addParameters(
                    listOf(
                        ParameterSpec.builder("target", objectType)
                            .build(),
                        ParameterSpec.builder("method", kTypeVariable)
                            .build(),
                        ParameterSpec.builder(
                            "binds", arrayType
                                .parameterizedBy(ANY.copy(nullable = true))
                                .copy(nullable = true)
                        )
                            .defaultValue("null")
                            .build(),
                        ParameterSpec.builder("flags", Long::class)
                            .defaultValue("0")
                            .build()
                    )
                )
            if (isNative) {
                connectFun.addCode("""
                            |val methodName = (method as %T<%T>).name
                            |connect(this@%T, target, methodName, binds, flags)
                            |""".trimMargin(),
                    ClassName("kotlin.reflect", "KCallable"),
                    UNIT,
                    objectType
                )
            } else {
                connectFun.addCode("""
                            |val methodName = (method as %T<*>).name.%M()
                            |connect(target, methodName, binds, flags)
                            |""".trimMargin(),
                    ClassName("kotlin.reflect", "KCallable"),
                    MemberName("godot.util", "camelToSnakeCase")
                )
            }
            typeBuilder.addFunction(connectFun.build())
        }
    }

    private fun generateConstructors(typeBuilder: TypeSpec.Builder) {
        if (isNative) {
            if (isSingleton) {
                typeBuilder.addInitializerBlock(
                        CodeBlock.of("""
                            |%M {
                            |    val ptr = %M(%T.gdnative.godot_global_get_singleton).%M("$singletonName".%M.ptr)
                            |    %M(ptr) { "No instance found for singleton $singletonName" }
                            |    this@$newName.ptr = ptr
                            |}
                            |""".trimMargin(),
                                MemberName("godot.internal.utils", "godotScoped"),
                                MemberName("godot.internal.type", "nullSafe"),
                                ClassName("godot.core", "Godot"),
                                MemberName("kotlinx.cinterop", "invoke"),
                                MemberName("kotlinx.cinterop", "cstr"),
                                MemberName("kotlin", "requireNotNull")
                        )
                )
            } else {

                val noArgConstructor = if (!isInstanciable) {
                    FunSpec.constructorBuilder()
                            .addModifiers(KModifier.INTERNAL)
                            .callThisConstructor("null")
                } else {
                    FunSpec.constructorBuilder()
                            .callThisConstructor("null")
                            .addStatement(
                                    """if (%M()) {
                   |    this.ptr = %M("$newName", "$oldName")
                   |}
                   |""".trimMargin(),
                                    MemberName(ClassName("godot.core", "Godot"), "shouldInitPtr"),
                                    MemberName("godot.internal.utils", "getConstructor")
                            )
                }

                typeBuilder.addFunction(noArgConstructor.build())

                typeBuilder.primaryConstructor(
                        FunSpec.constructorBuilder()
                                .addParameter(ParameterSpec("_ignore", ANY.copy(nullable = true)))
                                .callSuperConstructor()
                                .addModifiers(KModifier.INTERNAL)
                                .build()
                ).addSuperclassConstructorParameter("_ignore")
            }
        } else {
            if (isSingleton) {
                typeBuilder.addFunction(
                    FunSpec.builder("__new")
                        .addModifiers(KModifier.OVERRIDE)
                        .returns(ClassName("godot.util", "VoidPtr"))
                        .addStatement(
                            "return %T.getSingleton(%M)",
                            ClassName("godot.core", "TransferContext"),
                            MemberName("godot", engineSingletonIndexName!!)
                        )
                        .build()
                )
            } else {
                typeBuilder.addFunction(
                    FunSpec.builder("__new")
                        .addModifiers(KModifier.OVERRIDE)
                        .returns(ClassName("godot.util", "VoidPtr"))
                        .addStatement(
                            "return %T.invokeConstructor(%M)",
                            ClassName("godot.core", "TransferContext"),
                            MemberName("godot", engineClassDBIndexName)
                        )
                        .build()
                )
            }
        }
    }

    private fun generateEnums(typeBuilder: TypeSpec.Builder) {
        enums.forEach {
            typeBuilder.addType(it.generated)
        }
    }

    private fun generateSignals(typeBuilder: TypeSpec.Builder) {
        signals.forEach {
            if (properties.map { p -> p.newName }.contains(it.name)) it.name = "signal${it.name.capitalize()}"
            typeBuilder.addProperty(it.generated)
        }
    }

    private fun generateConstants(baseCompanion: TypeSpec.Builder) {
        constants.forEach { (key, value) ->
            baseCompanion.addProperty(
                PropertySpec
                    .builder(key, Long::class)
                    .addModifiers(KModifier.CONST, KModifier.FINAL)
                    .initializer("%L", value)
                    .build()
            )
        }
    }

    private fun generateProperties(
        icalls: MutableSet<ICall>?,
        propertiesReceiverType: TypeSpec.Builder
    ) {
        properties.forEach { property ->
            val propertySpec = property.generate(this, icalls)
            if (propertySpec != null) {
                propertiesReceiverType.addProperty(propertySpec)

                val parameterType = property.type
                val parameterTypeName =
                    ClassName(if (parameterType.isCoreType()) "godot.core" else "godot", parameterType)

                if (property.hasValidSetter && parameterType.isCoreTypeAdaptedForKotlin()) {
                    val parameterName = property.newName
                    val propertyFunSpec = FunSpec.builder(parameterName)

                    if (!isSingleton) {
                        if (tree.doAncestorsHaveProperty(this, property)) {
                            propertyFunSpec.addModifiers(KModifier.OVERRIDE)
                        } else {
                            propertyFunSpec.addModifiers(KModifier.OPEN)
                        }
                    }

                    propertyFunSpec
                        .addParameter(
                            ParameterSpec.builder(
                                "schedule",
                                LambdaTypeName.get(
                                    receiver = parameterTypeName,
                                    returnType = ClassName("kotlin", "Unit")
                                )
                            ).build()
                        )
                        .returns(parameterTypeName)
                        .addStatement(
                            """return $parameterName.apply{
                                                |    schedule(this)
                                                |    $parameterName = this
                                                |}
                                                |""".trimMargin()
                        )

                    propertiesReceiverType.addFunction(propertyFunSpec.build())
                }
            }
        }
    }

    private fun generateMethods(
        propertiesReceiverType: TypeSpec.Builder,
        icalls: MutableSet<ICall>?
    ) {
        (if (isNative) methods else methods.filter { !it.isGetterOrSetter })
            .forEach { method ->
                // FIX API for JVM
                val shouldGenerate = (!jvmMethodToNotGenerate.contains(method.engineIndexName) && !isNative) || isNative
                if (shouldGenerate) {
                    propertiesReceiverType.addFunction(method.generate(this, icalls))
                }
            }
    }

    private fun generateToVariantMethod(propertiesReceiverType: TypeSpec.Builder) {
        val variantType = ClassName("godot.core", "Variant")
        propertiesReceiverType.addFunction(
            FunSpec.builder("toVariant")
                .returns(variantType)
                .addStatement("return %T(this)", variantType)
                .build()
        )
    }

    private fun generateSuppressWarnings(fileBuilder: FileSpec.Builder) {
        fileBuilder.addAnnotation(
            AnnotationSpec.builder(ClassName("kotlin", "Suppress"))
                .addMember("\"PackageDirectoryMismatch\", \"unused\", \"FunctionName\", \"RedundantModalityModifier\", " +
                        "\"UNCHECKED_CAST\", \"JoinDeclarationAndAssignment\", \"USELESS_CAST\", \"RemoveRedundantQualifierName\"")
                .build()
        )
    }
}
