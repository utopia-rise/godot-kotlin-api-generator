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
        if (hasVarargs) {
            addStatement(
                "%T.writeArguments($argumentsString *__var_args.map { %T to it }.toTypedArray())",
                transferContextClassName,
                *ktVariantClassNames,
                ClassName("godot.core.VariantType", "ANY")
            )
        } else {
            addStatement(
                "%T.writeArguments($argumentsString)",
                transferContextClassName,
                *ktVariantClassNames
            )
        }
    }

    val returnTypeVariantTypeClass = if (returnType.isEnum()) {
        ClassName("godot.core.VariantType", "LONG")
    } else {
        ClassName("godot.core.VariantType", returnType.jvmVariantTypeValue)
    }

    addStatement(
        "%T.callMethod(rawPtr, %M, %T)",
        transferContextClassName,
        MemberName("godot", engineIndexName),
        returnTypeVariantTypeClass
    )

    if (shouldReturn) {
        if (returnType.isEnum()) {
            addStatement(
                "return ${returnType.removeEnumPrefix()}.from(%T.readReturnValue().asLong())",
                transferContextClassName
            )
        } else {
            val isNullableReturn = returnType.convertTypeForICalls() == "Object" || returnType.convertTypeForICalls() == "Any"
            addStatement(
                "return %T.readReturnValue(%T, %L) as %T",
                transferContextClassName,
                returnTypeVariantTypeClass,
                isNullableReturn,
                ClassName(returnType.getPackage(), returnType.convertTypeToKotlin()).copy(nullable = isNullableReturn)
            )
        }
    }
    return this
}
