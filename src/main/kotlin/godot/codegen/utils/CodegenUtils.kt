package godot.codegen.utils

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import godot.codegen.isNative

fun ClassName.convertIfTypeParameter(): TypeName {
    val arrayClassName = if (isNative) "GodotArray" else "VariantArray"
    return when(this.simpleName) {
        arrayClassName -> this.parameterizedBy(ANY.copy(nullable = true))
        "Dictionary" -> this.parameterizedBy(ANY.copy(nullable = true), ANY.copy(nullable = true))
        else -> this
    }
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
    if (ktVariantClassNames.isNotEmpty()) {
        addStatement(
            "%T.writeArguments($argumentsString" +
                    "${if (hasVarargs) "*__var_args" else ""})",
            transferContextClassName,
            *ktVariantClassNames
        )
    }

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
            val isNullableReturn = returnType.convertTypeForICalls() == "Object"
            val nullableString = if (isNullableReturn) "?" else ""
            addStatement(
                "return %T.readReturnValue(%T, %L) as %T$nullableString",
                transferContextClassName,
                ClassName("godot.core.VariantType", returnType.jvmVariantTypeValue),
                isNullableReturn,
                ClassName(returnType.getPackage(), returnType.convertTypeToKotlin())
            )
        }
    }
    return this
}
