package godot.codegen

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.kotlinpoet.*

import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import godot.codegen.utils.*


@JsonIgnoreProperties(ignoreUnknown = true)
open class Method @JsonCreator constructor(
    @JsonProperty("name")
    val oldName: String,
    @JsonProperty("return_type")
    var returnType: String,
    @JsonProperty("is_virtual")
    val isVirtual: Boolean,
    @JsonProperty("has_varargs")
    val hasVarargs: Boolean,
    @JsonProperty("arguments")
    val arguments: List<Argument>
) {

    val newName: String

    init {
        newName = if (!isVirtual) oldName.convertToCamelCase() else oldName
        returnType = returnType.convertTypeToKotlin()
    }

    var isGetterOrSetter: Boolean = false

    fun generate(clazz: Class, icalls: MutableSet<ICall>?): FunSpec {
        val modifiers = mutableListOf<KModifier>()

        if (!clazz.isSingleton) {
            modifiers.add(getModifier(clazz))
        }

        val generatedFunBuilder = FunSpec
            .builder(newName)
            .addModifiers(modifiers)

        val shouldReturn = returnType != "Unit"
        if (shouldReturn) {
            val simpleName = returnType.removeEnumPrefix()
            generatedFunBuilder.returns(ClassName(returnType.getPackage(), simpleName).convertIfTypeParameter())
        }

        if (returnType.isEnum()) {
            val type = returnType.removeEnumPrefix()
            if (type.contains('.')) {
                clazz.additionalImports.add(returnType.getPackage() to type.split('.')[0])
            }
        }

        //TODO: move adding arguments to generatedFunBuilder to separate function
        val callArgumentsAsString = buildCallArgumentsString(
            clazz,
            generatedFunBuilder
        ) //cannot be inlined as it also adds the arguments to the generatedFunBuilder

        if (hasVarargs) {
            generatedFunBuilder.addParameter(
                "__var_args",
                ANY.copy(nullable = true),
                KModifier.VARARG
            )
        }

        generatedFunBuilder.generateCodeBlock(clazz, callArgumentsAsString, icalls, shouldReturn)

        return generatedFunBuilder.build()
    }

    private fun FunSpec.Builder.generateCodeBlock(clazz: Class,
                                                  callArgumentsAsString: String,
                                                  icalls: MutableSet<ICall>? = null,
                                                  shouldReturn: Boolean
    ) {
        if (!isVirtual) {
            if (isNative) {
                checkNotNull(icalls)
                addStatement("val mb = %M(\"${clazz.oldName}\",\"${oldName}\")", MemberName("godot.internal.utils", "getMethodBind"))
                val constructedICall = constructICall(callArgumentsAsString, icalls)
                addStatement(
                        "%L%L%M%L%L",
                        if (shouldReturn) "return " else "",
                        when {
                            returnType == "enum.Error" -> {
                                "${returnType.removeEnumPrefix()}.byValue( "
                            }
                            returnType.isEnum() -> {
                                "${returnType.removeEnumPrefix()}.from( "
                            }
                            hasVarargs && returnType != "Variant" && returnType != "Unit" -> {
                                "$returnType from "
                            }
                            else -> {
                                ""
                            }
                        },
                        MemberName("godot.icalls", constructedICall.first),
                        constructedICall.second,
                        when {
                            returnType == "enum.Error" -> ".toUInt())"
                            returnType.isEnum() -> ")"
                            else -> ""
                        }
                )
            } else {
                val ktVariantClassName = ClassName("godot.core", "KtVariant")
                val transferContextClassName = ClassName("godot.core", "TransferContext")
                if (arguments.isNotEmpty() || shouldReturn) {
                    addStatement(
                            "val refresh = %T.writeArguments($callArgumentsAsString)",
                            transferContextClassName,
                            *arguments.map { ktVariantClassName }.toTypedArray()
                    )
                    val returnTypeCase = if (returnType.isEnum()) "Long" else returnType
                    addStatement(
                            "%T.callMethod(rawPtr, \"${clazz.oldName}\", \"$oldName\", " +
                                    "%T.Type.${returnTypeCase.jvmVariantTypeValue}, refresh)",
                            transferContextClassName,
                            ClassName("godot.core", "KtVariant")
                    )
                    if (shouldReturn) {
                        if (returnType.isEnum()) {
                            addStatement(
                                    "return ${returnType.removeEnumPrefix()}.from(%T.readReturnValue().asLong())",
                                    transferContextClassName
                            )
                        } else {
                            addStatement(
                                    "return %T.readReturnValue().as%L()",
                                    transferContextClassName,
                                    returnType
                            )
                        }
                    } else {
                        addStatement(
                                "%T.readReturnValue()",
                                transferContextClassName
                        )
                    }
                } else {
                    addStatement(
                            "%T.callMethod(rawPtr, \"${clazz.oldName}\", \"$oldName\", TODO(), false)",
                            transferContextClassName
                    )
                }
            }
        } else {
            if (shouldReturn) {
                addStatement(
                        "%L %T(%S)",
                        "throw",
                        NotImplementedError::class,
                        "$oldName is not implemented for ${clazz.newName}"
                )
            }
        }
    }

    private fun buildCallArgumentsString(cl: Class, generatedFunBuilder: FunSpec.Builder): String {
        return buildString {
            arguments.withIndex().forEach {
                val index = it.index
                val argument = it.value

                val shouldAddComa = if (isNative) index != 0 || !hasVarargs else index != 0

                if (shouldAddComa) append(", ")

                val sanitisedName = tree.getSanitisedArgumentName(this@Method, index, cl)
                if (isNative) {
                    append(sanitisedName)
                    if (argument.type.isEnum()) append(".id")
                } else {
                    append("%T($sanitisedName")
                    if (argument.type.isEnum()) append(".id")
                    append(")")
                }

                if (argument.type.isEnum()) append(".id")

                val baseClassName = ClassName(
                    argument.type.getPackage(),
                    argument.type.removeEnumPrefix()
                )
                val parameterBuilder = ParameterSpec.builder(
                    argument.name,
                    baseClassName.convertIfTypeParameter().copy(nullable = argument.nullable)
                )

                if (argument.applyDefault != null) parameterBuilder.defaultValue(argument.applyDefault)

                generatedFunBuilder.addParameter(parameterBuilder.build())
            }
            if (hasVarargs && !isEmpty()) append(", ")
        }
    }

    private fun getModifier(cl: Class) =
        if (tree.doAncestorsHaveMethod(cl, this)) KModifier.OVERRIDE else KModifier.OPEN

    private fun constructICall(methodArguments: String, icalls: MutableSet<ICall>): Pair<String, String> {
        if (hasVarargs) {
            return "_icall_varargs" to
                "( mb, this.ptr, " +
                if (methodArguments.isNotEmpty()) "arrayOf($methodArguments*__var_args))" else "__var_args)"
        }

        val icall = ICall(returnType, arguments)
        icalls.add(icall)
        return icall.name to "( mb, this.ptr$methodArguments)"
    }
}
