package godot.codegen.utils

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

fun ClassName.convertIfTypeParameter() = when(this.simpleName) {
    "GodotArray" -> this.parameterizedBy(ANY.copy(nullable = true))
    "Dictionary" -> this.parameterizedBy(ANY.copy(nullable = true), ANY.copy(nullable = true))
    else -> this
}

fun FunSpec.Builder.generateJvmMethodCall(
        method: String,
        clazz: String,
        engineIndexName: String,
        returnType: String,
        argumentsString: String,
        argumentsTypes: List<String>,
        hasVarargs: Boolean
): FunSpec.Builder {
    val ktVariantClassNames = argumentsTypes.map {
        ClassName("godot.core.VariantType", it.jvmVariantTypeValue)
    }.toTypedArray()

    val transferContextClassName = ClassName("godot.core", "TransferContext")

    val shouldReturn = returnType != "Unit"
    if (ktVariantClassNames.isNotEmpty() || shouldReturn) {
        addStatement(
            "%T.writeArguments($argumentsString" +
                "${if (hasVarargs) "*__var_args" else ""})",
            transferContextClassName,
            *ktVariantClassNames
        )
        val returnTypeCase = if (returnType.isEnum()) "Long" else returnType
        addStatement(
            "%T.callMethod(rawPtr, %M, %T)",
            transferContextClassName,
            MemberName("godot", engineIndexName),
            ClassName("godot.core.VariantType", clazz.jvmVariantTypeValue)
        )
        if (shouldReturn) {
            if (returnType.isEnum()) {
                addStatement(
                    "return ${returnType.removeEnumPrefix()}.from(%T.readReturnValue().asLong())",
                    transferContextClassName
                )
            } else {
                val icallReturnType = returnType.convertTypeForICalls()
                if (icallReturnType == "Object") {
                    addStatement(
                        "return %T.readReturnValue().as%L(::%L)",
                        transferContextClassName,
                        icallReturnType,
                        returnType
                    )
                } else {
                    addStatement(
                        "return %T.readReturnValue().as%L()",
                        transferContextClassName,
                        icallReturnType
                    )
                }
            }
        }
    } else {
        addStatement(
            "%T.callMethod(rawPtr, \"${clazz}\", \"$method\", TODO(), false)",
            transferContextClassName
        )
    }
    return this
}
