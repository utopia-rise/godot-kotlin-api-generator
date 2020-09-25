package godot.gradle

import godot.codegen.generateApiFrom
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create

open class ApiGeneratorPluginExtension(objects: ObjectFactory) {
    var outputDir = objects.directoryProperty()
    var sourceJson = objects.fileProperty()
    var isNative = false
}

open class GenerateAPI : DefaultTask() {
    @OutputDirectory
    val outputDir = project.objects.directoryProperty()

    @InputFile
    val sourceJson = project.objects.fileProperty()

    var isNative = false

    @TaskAction
    fun execute() {
        val output = outputDir.get().asFile
        output.deleteRecursively()
        output.generateApiFrom(sourceJson.get().asFile, isNative)
    }
}

class ApiGeneratorPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create<ApiGeneratorPluginExtension>("ApiGenerator")
        project.tasks.register("generateAPI", GenerateAPI::class.java) {
            outputDir.set(extension.outputDir)
            sourceJson.set(extension.sourceJson)
            isNative = extension.isNative
        }
    }
}