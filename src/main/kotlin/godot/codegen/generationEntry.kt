package godot.codegen

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import godot.codegen.utils.getPackage
import java.io.File

lateinit var tree: Graph<Class>
var isNative: Boolean = false

fun File.generateApiFrom(jsonSource: File, isNat: Boolean) {
    isNative = isNat
    val classes: List<Class> = ObjectMapper().readValue(jsonSource, object : TypeReference<ArrayList<Class>>() {})

    tree = classes.buildTree()
    val icalls = if (isNative) mutableSetOf<ICall>() else null

    classes.forEach {
        it.initClass()
        it.methods.forEach { method -> method.initEngineIndex(it.engineIndexName) }
        it.properties.forEach { property -> property.initEngineIndexNames(it.engineIndexName) }
    }

    if (!isNative) generateEngineIndexesFile(classes).writeTo(this)

    classes.forEach { clazz ->
        clazz.generate(this, icalls)
    }

    val iCallFileSpec = if (isNative) {
        FileSpec.builder("godot.icalls", "__icalls")
                .addFunction(generateICallsVarargsFunction())
                .addImport("kotlinx.cinterop", "set", "get", "pointed")
    } else null

    icalls?.forEach { iCallFileSpec!!.addFunction(it.generated) }

    this.parentFile.mkdirs()

    iCallFileSpec?.build()?.writeTo(this)

    generateEngineTypesRegistration(classes).writeTo(this)
}

private fun generateEngineIndexesFile(classes: List<Class>): FileSpec {
    val fileSpecBuilder = FileSpec.builder("godot", "EngineIndexes")
    var methodIndex = 0
    classes.filter { it.shouldGenerate }.forEachIndexed { classIndex, clazz ->
        fileSpecBuilder.addProperty(
                PropertySpec.builder(clazz.engineIndexName, INT, KModifier.CONST).initializer("%L", classIndex).build()
        )
        clazz.methods.filter { !it.isGetterOrSetter }.forEach { method ->
            fileSpecBuilder.addProperty(
                    PropertySpec.builder(method.engineIndexName, INT, KModifier.CONST)
                            .initializer("%L", methodIndex).build()
            )
            methodIndex++
        }
        clazz.properties.forEach { property ->
            if (property.hasValidGetter) {
                fileSpecBuilder.addProperty(
                        PropertySpec.builder(property.engineGetterIndexName, INT, KModifier.CONST)
                                .initializer("%L", methodIndex).build()
                )
                methodIndex++
            }

            if (property.hasValidSetter) {
                fileSpecBuilder.addProperty(
                        PropertySpec.builder(property.engineSetterIndexName, INT, KModifier.CONST)
                                .initializer("%L", methodIndex).build()
                )
                methodIndex++
            }
        }
    }

    return fileSpecBuilder.build()
}

private fun generateICallsVarargsFunction(): FunSpec {
    return FunSpec
            .builder("_icall_varargs")
            .addModifiers(KModifier.INTERNAL)
            .returns(ClassName("godot.core", "Variant"))
            .addParameter(
                    "mb",
                    ClassName("kotlinx.cinterop", "CPointer")
                            .parameterizedBy(ClassName("godot.gdnative", "godot_method_bind"))
            )
            .addParameter(
                    "inst",
                    ClassName("kotlinx.cinterop", "COpaquePointer")
            )
            .addParameter(
                    "arguments",
                    ClassName("kotlin", "Array").parameterizedBy(STAR)
            )
            .addStatement(
                    """return %M {
                            |    val args = allocArray<%T<%M>>(arguments.size)
                            |    for ((i,arg) in arguments.withIndex()) args[i] = %T.wrap(arg)._handle.ptr
                            |    val result = %T.gdnative.godot_method_bind_call!!.%M(mb, inst, args, arguments.size, null)
                            |    for (i in arguments.indices) %T.gdnative.godot_variant_destroy!!.%M(args[i])
                            |    %T(result)
                            |}
                            |""".trimMargin(),
                    MemberName("godot.internal.utils", "godotScoped"),
                    ClassName("kotlinx.cinterop", "CPointerVar"),
                    MemberName("godot.gdnative", "godot_variant"),
                    ClassName("godot.core", "Variant"),
                    ClassName("godot.core", "Godot"),
                    MemberName("kotlinx.cinterop", "invoke"),
                    ClassName("godot.core", "Godot"),
                    MemberName("kotlinx.cinterop", "invoke"),
                    ClassName("godot.core", "Variant")
            )
            .build()
}

private fun generateEngineTypesRegistration(classes: List<Class>): FileSpec {

    val registerTypesFunBuilder = FunSpec.builder("registerEngineTypes")

    if (isNative) registerTypesFunBuilder.receiver(ClassName("godot.core", "TypeManager"))

    val registerMethodsFunBuilder = FunSpec.builder("registerEngineTypeMethods")

    fun addEngineTypeMethod(classIndexName: String, methodEngineName: String) {
        registerMethodsFunBuilder.addStatement(
                "%T.engineTypeMethod.add(%M to \"${methodEngineName}\")",
                ClassName("godot.core", "TypeManager"),
                MemberName("godot", classIndexName)
        )
    }

    classes.filter { (isNative && !it.isSingleton && it.newName != "Object" && it.shouldGenerate)
            || !isNative && it.shouldGenerate}.forEach {clazz ->
        if (isNative) {
            registerTypesFunBuilder.addStatement(
                    "registerEngineType(%S, ::%T)",
                    clazz.newName,
                    ClassName(clazz.newName.getPackage(), clazz.newName)
            )
        }
        else {
            if (clazz.isSingleton) {
                registerTypesFunBuilder.addStatement(
                        "%T.registerEngineType(%S) { %T }",
                        ClassName("godot.core", "TypeManager"),
                        clazz.newName,
                        ClassName(clazz.newName.getPackage(), clazz.newName)
                )
            } else {
                registerTypesFunBuilder.addStatement(
                        "%T.registerEngineType(%S, ::%T)",
                        ClassName("godot.core", "TypeManager"),
                        clazz.newName,
                        ClassName(clazz.newName.getPackage(), clazz.newName)
                )
            }
            clazz.methods.filter { !it.isGetterOrSetter }.forEach {
                addEngineTypeMethod(clazz.engineIndexName, it.oldName)
            }
            clazz.properties.forEach {
                if (it.hasValidGetter) {
                    addEngineTypeMethod(clazz.engineIndexName, it.validGetter.oldName)
                }
                if (it.hasValidSetter) {
                    addEngineTypeMethod(clazz.engineIndexName, it.validSetter.oldName)
                }
            }
        }
    }
    val registrationFile = FileSpec.builder("godot", "RegisterEngineTypes")
            .addFunction(
                    registerTypesFunBuilder.build()
            )

    if (!isNative) registrationFile.addFunction(registerMethodsFunBuilder.build())

    return registrationFile.build()
}
